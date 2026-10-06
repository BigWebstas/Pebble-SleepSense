#include "sleep_engine.h"
#include "smart_alarm.h"

//! First of consecutive keys holding the session (alarm uses 100)
#define PERSIST_KEY_SESSION 200
#define MIN(a, b) ((a) < (b) ? (a) : (b))

// Thresholds for actigraphy scoring
#define VMC_THRESHOLD_DEEP 30
#define VMC_THRESHOLD_AWAKE 140
#define SOUND_THRESHOLD_DISTURBANCE 70
#define SOUND_THRESHOLD_AWAKE 85

static SleepSession s_session;
static SleepEngineUpdateCallback s_update_cb = NULL;
static uint32_t s_minute_accel_acc = 0;
static uint16_t s_minute_accel_samples = 0;
static bool s_accel_subscribed = false;

//! Session exceeds PERSIST_DATA_MAX_LENGTH, so store it as consecutive chunks
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
static SleepStage prv_classify_epoch(uint16_t vmc, AppLightLevel light, uint8_t sound, uint32_t elapsed_sec) {
  // 1. Check for Awakening:
  // Bright lights turned on + moderate movement
  if ((light >= APP_LIGHT_LIGHT) && (vmc > 50)) {
    return SLEEP_STAGE_AWAKE;
  }
  // Loud noise + movement
  if ((sound >= SOUND_THRESHOLD_AWAKE) && (vmc > 45)) {
    return SLEEP_STAGE_AWAKE;
  }
  // High physical actigraphy count
  if (vmc >= VMC_THRESHOLD_AWAKE) {
    return SLEEP_STAGE_AWAKE;
  }

  // 2. Room is illuminated: prevents deep restorative sleep, maximum allowed is Light
  if (light >= APP_LIGHT_LIGHT) {
    return SLEEP_STAGE_LIGHT;
  }

  // 3. Moderate movement or sound disturbance: Light Sleep (N1 / N2)
  if (vmc > VMC_THRESHOLD_DEEP || sound >= SOUND_THRESHOLD_DISTURBANCE) {
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

static void prv_record_epoch(uint16_t vmc, AppLightLevel light, uint8_t sound, uint8_t orientation) {
  time_t now = time(NULL);
  uint32_t elapsed_sec = (s_session.session_start > 0) ? (now - s_session.session_start) : 0;

  SleepStage stage = prv_classify_epoch(vmc, light, sound, elapsed_sec);

  s_session.current_stage = stage;
  s_session.current_light = light;
  s_session.current_sound = sound;
  s_session.current_vmc = vmc;

  // Insert into circular history buffer
  SleepEpoch epoch = {
    .timestamp = now,
    .vmc = vmc,
    .light_level = (uint8_t)light,
    .sound_level = sound,
    .orientation = orientation,
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
  } else
#endif
  {
    // Fallback using raw accelerometer accumulator
    if (s_minute_accel_samples > 0) {
      vmc = (uint16_t)(s_minute_accel_acc / s_minute_accel_samples);
    }
  }

  // Reset minute accel accumulator
  s_minute_accel_acc = 0;
  s_minute_accel_samples = 0;

  prv_record_epoch(vmc, light, s_session.current_sound, orientation);
}

static void prv_minute_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  prv_process_minute();
}

void sleep_engine_init(void) {
  prv_load_session();
  tick_timer_service_subscribe(MINUTE_UNIT, prv_minute_tick_handler);

  // If session was left tracking previously, resume accelerometer
  if (s_session.is_tracking && !s_accel_subscribed) {
    accel_service_set_sampling_rate(ACCEL_SAMPLING_10HZ);
    accel_data_service_subscribe(10, prv_accel_handler);
    s_accel_subscribed = true;
  }
}

void sleep_engine_deinit(void) {
  tick_timer_service_unsubscribe();
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
  s_session.sleep_score = 100;
  s_session.current_stage = SLEEP_STAGE_AWAKE;
  s_session.epoch_count = 0;
  s_session.epoch_head = 0;
  s_minute_accel_acc = 0;
  s_minute_accel_samples = 0;

  if (!s_accel_subscribed) {
    accel_service_set_sampling_rate(ACCEL_SAMPLING_10HZ);
    accel_data_service_subscribe(10, prv_accel_handler);
    s_accel_subscribed = true;
  }

  prv_save_session();
  if (s_update_cb) {
    s_update_cb(&s_session);
  }
}

void sleep_engine_stop_session(void) {
  s_session.is_tracking = false;
  s_session.session_end = time(NULL);

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
  s_session.current_sound = sound_level;
  if (s_update_cb) {
    s_update_cb(&s_session);
  }
}

void sleep_engine_set_update_callback(SleepEngineUpdateCallback callback) {
  s_update_cb = callback;
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
