#include "sleep_engine.h"
#include "smart_alarm.h"

//! First of consecutive keys holding the session (alarm uses 100)
#define PERSIST_KEY_SENSORS 102
#define PERSIST_KEY_SESSION 200
#define MIN(a, b) ((a) < (b) ? (a) : (b))

// Thresholds for actigraphy scoring
// Movement is Pebble Health's minute activity count. Measured over a real night it is exactly 0 while
// still and a few hundred to a few thousand for a turn in bed (daytime medians are 400-1400), so a
// single busy minute is not waking: judge the average of the last 5 minutes, and let only a very
// large burst count on its own.
#define VMC_THRESHOLD_DEEP 30
#define VMC_MEAN_AWAKE 400
#define VMC_BURST_AWAKE 2500
#define VMC_WINDOW 5
#define SOUND_THRESHOLD_DISTURBANCE 70
#define SOUND_THRESHOLD_AWAKE 85

static SleepSession s_session;
static SensorSettings s_sensors = { .light = true, .mic = true, .heart_rate = true };
static SleepEngineUpdateCallback s_update_cb = NULL;
static SleepEngineMinuteCallback s_minute_cb = NULL;
static uint32_t s_minute_accel_acc = 0;
static uint16_t s_minute_accel_samples = 0;
static bool s_accel_subscribed = false;

//! Session exceeds PERSIST_DATA_MAX_LENGTH, so store it as consecutive chunks
//! Sample heart rate every minute while tracking (0 restores the watch default)
static void prv_apply_hr_sampling(void) {
#if defined(PBL_HEALTH)
  health_service_set_heart_rate_sample_period(
      (s_session.is_tracking && s_sensors.heart_rate) ? 60 : 0);
#endif
}

static void prv_save_session(void) {
  const uint8_t *bytes = (const uint8_t *)&s_session;
  for (size_t off = 0, key = PERSIST_KEY_SESSION; off < sizeof(s_session);
       off += PERSIST_DATA_MAX_LENGTH, key++) {
    size_t len = MIN(PERSIST_DATA_MAX_LENGTH, sizeof(s_session) - off);
    persist_write_data(key, bytes + off, len);
  }
}

static bool prv_read_session(void) {
  uint8_t *bytes = (uint8_t *)&s_session;
  for (size_t off = 0, key = PERSIST_KEY_SESSION; off < sizeof(s_session);
       off += PERSIST_DATA_MAX_LENGTH, key++) {
    size_t len = MIN(PERSIST_DATA_MAX_LENGTH, sizeof(s_session) - off);
    if (persist_get_size(key) != (int)len ||
        persist_read_data(key, bytes + off, len) != (int)len) {
      return false;
    }
  }
  return true;
}

static void prv_load_session(void) {
  if (!prv_read_session()) {
    memset(&s_session, 0, sizeof(s_session));
    s_session.current_stage = SLEEP_STAGE_AWAKE;
    s_session.current_light = APP_LIGHT_UNKNOWN;
  }
}

#if defined(PBL_HEALTH)
static AppLightLevel prv_convert_health_light(uint8_t raw_light) {
  switch (raw_light) {
    case 1: return APP_LIGHT_VERY_DARK;
    case 2: return APP_LIGHT_DARK;
    case 3: return APP_LIGHT_LIGHT;
    case 4: return APP_LIGHT_VERY_LIGHT;
    default: return APP_LIGHT_UNKNOWN;
  }
}
#endif

//! Classify a 1-minute epoch using sensor fusion:
//! VMC (movement), Ambient Light, Sound level, and Ultradian cycle position (~90 min)
static SleepStage prv_classify_epoch(uint16_t vmc, uint32_t mean_vmc, AppLightLevel light, uint8_t sound,
                                     uint32_t elapsed_sec) {
  // 1. Check for Awakening:
  // Bright lights turned on + moderate movement
  if ((light >= APP_LIGHT_LIGHT) && (vmc > 50)) {
    return SLEEP_STAGE_AWAKE;
  }
  // Loud noise + movement
  if ((sound >= SOUND_THRESHOLD_AWAKE) && (vmc > 45)) {
    return SLEEP_STAGE_AWAKE;
  }
  // Sustained movement, or one very large burst
  if (mean_vmc >= VMC_MEAN_AWAKE || vmc >= VMC_BURST_AWAKE) {
    return SLEEP_STAGE_AWAKE;
  }

  // 2. Room is illuminated: prevents deep restorative sleep, maximum allowed is Light
  if (light >= APP_LIGHT_LIGHT) {
    return SLEEP_STAGE_LIGHT;
  }

  // 3. Moderate movement or sound disturbance: Light Sleep (N1 / N2)
  // (a turn in bed stays Light for the next few minutes through the average)
  if (vmc > VMC_THRESHOLD_DEEP || mean_vmc > VMC_THRESHOLD_DEEP || sound >= SOUND_THRESHOLD_DISTURBANCE) {
    return SLEEP_STAGE_LIGHT;
  }

  // 4. Low movement in dark environment: Distinguish between Deep Sleep and REM
  // Standard ultradian sleep cycle is ~90 minutes (5400 sec)
  uint32_t cycle_min = (elapsed_sec % 5400) / 60;

  // SWS (Slow-Wave Deep Sleep) predominates during the early-to-mid phase of a cycle (min 15-55)
  // REM sleep recurs at the end of the 90-min cycle (min 65-88)
  if (elapsed_sec > 1800 && cycle_min >= 65 && cycle_min <= 88) {
    return SLEEP_STAGE_REM;
  }

  // Sustained stillness early in cycle = Deep Restful Sleep (N3)
  return SLEEP_STAGE_DEEP;
}

//! Record one minute. `live` epochs also persist, evaluate the alarm and notify the UI;
//! backfilled epochs (minutes missed while the app was closed) skip all three.
//! Average movement of this minute and the (up to) four before it
static uint32_t prv_recent_mean_vmc(uint16_t current) {
  uint32_t sum = current;
  uint32_t count = 1;
  for (uint16_t i = 1; i < VMC_WINDOW && i <= s_session.epoch_count; i++) {
    uint16_t idx = (s_session.epoch_head + SLEEP_EPOCH_HISTORY_MAX - i) % SLEEP_EPOCH_HISTORY_MAX;
    sum += s_session.epochs[idx].vmc;
    count++;
  }
  return sum / count;
}

static void prv_record_epoch(time_t now, uint16_t vmc, AppLightLevel light, uint8_t sound,
                             uint8_t orientation, uint8_t heart_rate, bool live) {
  uint32_t elapsed_sec = (s_session.session_start > 0) ? (now - s_session.session_start) : 0;

  SleepStage stage = prv_classify_epoch(vmc, prv_recent_mean_vmc(vmc), light, sound, elapsed_sec);

  s_session.current_stage = stage;
  s_session.current_light = light;
  s_session.current_sound = sound;
  s_session.current_vmc = vmc;
  s_session.current_hr = heart_rate;

  // Insert into circular history buffer
  SleepEpoch epoch = {
    .timestamp = now,
    .vmc = vmc,
    .light_level = (uint8_t)light,
    .sound_level = sound,
    .orientation = orientation,
    .heart_rate = heart_rate,
    .stage = stage,
  };

  s_session.epochs[s_session.epoch_head] = epoch;
  s_session.epoch_head = (s_session.epoch_head + 1) % SLEEP_EPOCH_HISTORY_MAX;
  if (s_session.epoch_count < SLEEP_EPOCH_HISTORY_MAX) {
    s_session.epoch_count++;
  }

  // Accumulate durations (each epoch is 60 seconds)
  switch (stage) {
    case SLEEP_STAGE_DEEP:
      s_session.deep_sleep_sec += 60;
      s_session.total_sleep_sec += 60;
      break;
    case SLEEP_STAGE_LIGHT:
      s_session.light_sleep_sec += 60;
      s_session.total_sleep_sec += 60;
      break;
    case SLEEP_STAGE_REM:
      s_session.rem_sleep_sec += 60;
      s_session.total_sleep_sec += 60;
      break;
    case SLEEP_STAGE_AWAKE:
      s_session.awake_sec += 60;
      break;
  }

  // Cycle count (each cycle is ~90 minutes)
  s_session.cycle_count = s_session.total_sleep_sec / 5400;

  // Sleep efficiency score: percentage of sleep vs total time in bed, scaled 0-100
  uint32_t total_time = s_session.total_sleep_sec + s_session.awake_sec;
  if (total_time > 0) {
    s_session.sleep_score = (s_session.total_sleep_sec * 100) / total_time;
  }

  if (!live) {
    return;
  }

  prv_save_session();

  // Evaluate smart alarm against newly classified stage
  smart_alarm_evaluate(stage);

  if (s_update_cb) {
    s_update_cb(&s_session);
  }
}

//! Integer square root; libm sqrt faults on watch hardware
static uint32_t prv_isqrt(uint32_t n) {
  uint32_t root = 0;
  uint32_t bit = 1u << 30;
  while (bit > n) bit >>= 2;
  while (bit) {
    if (n >= root + bit) {
      n -= root + bit;
      root = (root >> 1) + bit;
    } else {
      root >>= 1;
    }
    bit >>= 2;
  }
  return root;
}

//! If the app is closed while tracking (the watch can drop back to the watch face overnight),
//! a wakeup relaunches it. Every live minute pushes the wakeup out, so it only fires once
//! the app has stopped ticking.
#define WAKEUP_DELAY_SEC (5 * 60)
#define WAKEUP_COOKIE 1

static void prv_schedule_wakeup(void) {
  wakeup_cancel_all();
  if (s_session.is_tracking) {
    wakeup_schedule(time(NULL) + WAKEUP_DELAY_SEC, WAKEUP_COOKIE, false);
  }
}

#if defined(PBL_HEALTH)
//! Fill the minutes missed while the app was closed from Pebble Health's history.
//! Noise is unknown for those minutes; the alarm is not evaluated for them.
static void prv_backfill_gap(void) {
  time_t now = time(NULL);
  time_t last = s_session.epoch_count
      ? s_session.epochs[(s_session.epoch_head + SLEEP_EPOCH_HISTORY_MAX - 1) % SLEEP_EPOCH_HISTORY_MAX].timestamp
      : s_session.session_start;
  const uint32_t chunk = 60;
  HealthMinuteData records[chunk];

  time_t from = last + 60;
  while (from < now - 60) {
    time_t time_start = from;
    time_t time_end = from + chunk * 60;
    uint32_t count = health_service_get_minute_history(records, chunk, &time_start, &time_end);
    for (uint32_t i = 0; i < count; i++) {
      if (records[i].is_invalid) {
        continue;
      }
      time_t when = time_start + (time_t)(i * 60);
      if (when <= last || when >= now - 60) {
        continue;
      }
      AppLightLevel light = s_sensors.light ? prv_convert_health_light(records[i].light) : APP_LIGHT_UNKNOWN;
      uint8_t heart_rate = s_sensors.heart_rate ? records[i].heart_rate_bpm : 0;
      prv_record_epoch(when, records[i].vmc, light, 0, records[i].orientation, heart_rate, false);
    }
    from += chunk * 60;
  }
  prv_save_session();
}
#endif

static void prv_accel_handler(AccelData *data, uint32_t num_samples) {
  for (uint32_t i = 0; i < num_samples; i++) {
    int32_t x = data[i].x;
    int32_t y = data[i].y;
    int32_t z = data[i].z;
    // Calculate 3D magnitude (1G = 1000)
    int32_t mag = (int32_t)prv_isqrt((uint32_t)(x * x + y * y + z * z));
    int32_t delta = mag - 1000;
    if (delta < 0) delta = -delta;
    s_minute_accel_acc += (uint32_t)delta;
    s_minute_accel_samples++;
  }
}

static void prv_process_minute(void) {
  if (!s_session.is_tracking) {
    return;
  }

  uint16_t vmc = 0;
  AppLightLevel light = s_session.current_light;
  uint8_t orientation = 0;
  uint8_t heart_rate = 0;

#if defined(PBL_HEALTH)
  time_t now = time(NULL);
  time_t time_start = now - 60;
  time_t time_end = now;

  HealthMinuteData records[2];
  uint32_t count = health_service_get_minute_history(records, 2, &time_start, &time_end);
  if (count > 0 && !records[0].is_invalid) {
    vmc = records[0].vmc;
    light = prv_convert_health_light(records[0].light);
    orientation = records[0].orientation;
    heart_rate = records[0].heart_rate_bpm;
  } else
#endif
  {
    // Fallback using raw accelerometer accumulator
    if (s_minute_accel_samples > 0) {
      // Raw accelerometer fallback: milli-g, which runs about 10x smaller than Health's count
      uint32_t scaled = (s_minute_accel_acc / s_minute_accel_samples) * 10;
      vmc = (uint16_t)(scaled > 65535 ? 65535 : scaled);
    }
  }

  if (!s_sensors.light) light = APP_LIGHT_UNKNOWN;
  if (!s_sensors.heart_rate) heart_rate = 0;

  // Reset minute accel accumulator
  s_minute_accel_acc = 0;
  s_minute_accel_samples = 0;

  prv_record_epoch(time(NULL), vmc, light, s_session.current_sound, orientation, heart_rate, true);
  prv_schedule_wakeup();
}

static void prv_minute_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  prv_process_minute();
  if (s_minute_cb) {
    s_minute_cb();
  }
}

void sleep_engine_init(void) {
  if (persist_exists(PERSIST_KEY_SENSORS)) {
    persist_read_data(PERSIST_KEY_SENSORS, &s_sensors, sizeof(s_sensors));
  }
  prv_load_session();
  tick_timer_service_subscribe(MINUTE_UNIT, prv_minute_tick_handler);

  // If session was left tracking previously, resume accelerometer
  if (s_session.is_tracking && !s_accel_subscribed) {
    accel_service_set_sampling_rate(ACCEL_SAMPLING_10HZ);
    accel_data_service_subscribe(10, prv_accel_handler);
    s_accel_subscribed = true;
    prv_apply_hr_sampling();
#if defined(PBL_HEALTH)
    prv_backfill_gap();
#endif
    prv_schedule_wakeup();
  }
}

void sleep_engine_deinit(void) {
  tick_timer_service_unsubscribe();
#if defined(PBL_HEALTH)
  health_service_set_heart_rate_sample_period(0);
#endif
  if (s_accel_subscribed) {
    accel_data_service_unsubscribe();
    s_accel_subscribed = false;
  }
  prv_save_session();
}

void sleep_engine_start_session(void) {
  time_t now = time(NULL);
  s_session.is_tracking = true;
  s_session.session_start = now;
  s_session.session_end = 0;
  s_session.total_sleep_sec = 0;
  s_session.deep_sleep_sec = 0;
  s_session.light_sleep_sec = 0;
  s_session.rem_sleep_sec = 0;
  s_session.awake_sec = 0;
  s_session.cycle_count = 0;
  s_session.snooze_count = 0;
  s_session.snooze_sec = 0;
  s_session.sleep_score = 100;
  s_session.current_stage = SLEEP_STAGE_AWAKE;
  s_session.epoch_count = 0;
  s_session.epoch_head = 0;
  s_session.current_hr = 0;
  s_minute_accel_acc = 0;
  s_minute_accel_samples = 0;

  if (!s_accel_subscribed) {
    accel_service_set_sampling_rate(ACCEL_SAMPLING_10HZ);
    accel_data_service_subscribe(10, prv_accel_handler);
    s_accel_subscribed = true;
  }
  prv_apply_hr_sampling();
  prv_schedule_wakeup();

  prv_save_session();
  if (s_update_cb) {
    s_update_cb(&s_session);
  }
}

void sleep_engine_stop_session(void) {
  s_session.is_tracking = false;
  s_session.session_end = time(NULL);
  s_session.current_hr = 0;
  prv_apply_hr_sampling();
  prv_schedule_wakeup(); // not tracking any more: just cancels
  smart_alarm_cancel_snooze();

  if (s_accel_subscribed) {
    accel_data_service_unsubscribe();
    s_accel_subscribed = false;
  }

  prv_save_session();
  if (s_update_cb) {
    s_update_cb(&s_session);
  }
}

void sleep_engine_toggle_session(void) {
  if (s_session.is_tracking) {
    sleep_engine_stop_session();
  } else {
    sleep_engine_start_session();
  }
}

bool sleep_engine_is_tracking(void) {
  return s_session.is_tracking;
}

const SleepSession *sleep_engine_get_session(void) {
  return &s_session;
}

void sleep_engine_update_sound(uint8_t sound_level) {
  if (!s_sensors.mic) {
    return;
  }
  s_session.current_sound = sound_level;
  if (s_update_cb) {
    s_update_cb(&s_session);
  }
}

void sleep_engine_set_minute_callback(SleepEngineMinuteCallback callback) {
  s_minute_cb = callback;
}

void sleep_engine_set_update_callback(SleepEngineUpdateCallback callback) {
  s_update_cb = callback;
}

void sleep_engine_add_snooze(uint32_t seconds) {
  s_session.snooze_count++;
  s_session.snooze_sec += seconds;
  prv_save_session();
  if (s_update_cb) {
    s_update_cb(&s_session);
  }
}

void sleep_engine_set_sensors(bool light, bool mic, bool heart_rate) {
  s_sensors.light = light;
  s_sensors.mic = mic;
  s_sensors.heart_rate = heart_rate;
  persist_write_data(PERSIST_KEY_SENSORS, &s_sensors, sizeof(s_sensors));

  // Clear readings from sensors that were just turned off
  if (!light) s_session.current_light = APP_LIGHT_UNKNOWN;
  if (!mic) s_session.current_sound = 0;
  if (!heart_rate) s_session.current_hr = 0;
  prv_apply_hr_sampling();

  if (s_update_cb) {
    s_update_cb(&s_session);
  }
}

const SensorSettings *sleep_engine_get_sensors(void) {
  return &s_sensors;
}

const char *sleep_engine_stage_name(SleepStage stage) {
  switch (stage) {
    case SLEEP_STAGE_DEEP:  return "DEEP";
    case SLEEP_STAGE_LIGHT: return "LIGHT";
    case SLEEP_STAGE_REM:   return "REM";
    case SLEEP_STAGE_AWAKE: return "AWAKE";
    default:                return "UNKNOWN";
  }
}

const char *sleep_engine_light_name(AppLightLevel light) {
  switch (light) {
    case APP_LIGHT_VERY_DARK: return "V.Dark";
    case APP_LIGHT_DARK:      return "Dark";
    case APP_LIGHT_LIGHT:     return "Light";
    case APP_LIGHT_VERY_LIGHT:return "Bright";
    default:                  return "Auto";
  }
}

GColor sleep_engine_stage_color(SleepStage stage) {
#if defined(PBL_COLOR)
  switch (stage) {
    case SLEEP_STAGE_DEEP:  return GColorOxfordBlue;
    case SLEEP_STAGE_LIGHT: return GColorTiffanyBlue;
    case SLEEP_STAGE_REM:   return GColorPurpureus;
    case SLEEP_STAGE_AWAKE: return GColorOrange;
    default:                return GColorBlack;
  }
#else
  // Monochrome (aplite / diorite)
  switch (stage) {
    case SLEEP_STAGE_DEEP:  return GColorBlack;
    case SLEEP_STAGE_LIGHT: return GColorWhite;
    case SLEEP_STAGE_REM:   return GColorBlack;
    case SLEEP_STAGE_AWAKE: return GColorWhite;
    default:                return GColorBlack;
  }
#endif
}
