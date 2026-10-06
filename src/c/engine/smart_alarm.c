#include "smart_alarm.h"

#define PERSIST_KEY_ALARM 100
#define ALARM_REPEAT_TIMER_MS 2500
#define ALARM_TIMEOUT_SEC 180

static SmartAlarmSettings s_settings;
static AppTimer *s_vibe_timer = NULL;
static bool s_is_ringing = false;
static time_t s_ringing_start = 0;
static SmartAlarmTriggerCallback s_trigger_cb = NULL;

//! Gentle progressive vibration pattern for smart wake
static const uint32_t s_gentle_vibe_pattern[] = { 120, 200, 150, 400, 250 };
static const VibePattern s_gentle_vibes = {
  .durations = s_gentle_vibe_pattern,
  .num_segments = ARRAY_LENGTH(s_gentle_vibe_pattern),
};

//! Standard persistent pattern for hard deadline
static const uint32_t s_hard_vibe_pattern[] = { 350, 200, 350, 200, 500 };
static const VibePattern s_hard_vibes = {
  .durations = s_hard_vibe_pattern,
  .num_segments = ARRAY_LENGTH(s_hard_vibe_pattern),
};

static void prv_save_settings(void) {
  persist_write_data(PERSIST_KEY_ALARM, &s_settings, sizeof(s_settings));
}

static void prv_load_settings(void) {
  if (persist_exists(PERSIST_KEY_ALARM)) {
    persist_read_data(PERSIST_KEY_ALARM, &s_settings, sizeof(s_settings));
  } else {
    // Defaults: 07:00 AM, 30m window, enabled
    s_settings.enabled = true;
    s_settings.target_hour = 7;
    s_settings.target_min = 0;
    s_settings.window_minutes = 30;
    s_settings.triggered = false;
    s_settings.trigger_time = 0;
  }
}

static void prv_vibe_timer_handler(void *context) {
  s_vibe_timer = NULL;
  if (!s_is_ringing) {
    return;
  }

  // Check timeout
  time_t now = time(NULL);
  if (now - s_ringing_start >= ALARM_TIMEOUT_SEC) {
    smart_alarm_dismiss();
    return;
  }

  // Issue pulse
  vibes_enqueue_custom_pattern(s_gentle_vibes);

  // Schedule next pulse
  s_vibe_timer = app_timer_register(ALARM_REPEAT_TIMER_MS, prv_vibe_timer_handler, NULL);
}

static void prv_start_ringing(bool is_smart_wake) {
  s_is_ringing = true;
  s_ringing_start = time(NULL);
  s_settings.triggered = true;
  s_settings.trigger_time = s_ringing_start;
  prv_save_settings();

  if (is_smart_wake) {
    vibes_enqueue_custom_pattern(s_gentle_vibes);
  } else {
    vibes_enqueue_custom_pattern(s_hard_vibes);
  }

  if (s_trigger_cb) {
    s_trigger_cb(is_smart_wake);
  }

  if (s_vibe_timer) {
    app_timer_cancel(s_vibe_timer);
  }
  s_vibe_timer = app_timer_register(ALARM_REPEAT_TIMER_MS, prv_vibe_timer_handler, NULL);
}

void smart_alarm_init(void) {
  prv_load_settings();
}

void smart_alarm_deinit(void) {
  if (s_vibe_timer) {
    app_timer_cancel(s_vibe_timer);
    s_vibe_timer = NULL;
  }
  s_is_ringing = false;
  prv_save_settings();
}

SmartAlarmSettings *smart_alarm_get_settings(void) {
  return &s_settings;
}

void smart_alarm_set_target(uint8_t hour, uint8_t min) {
  s_settings.target_hour = hour % 24;
  s_settings.target_min = min % 60;
  s_settings.triggered = false;
  prv_save_settings();
}

void smart_alarm_toggle(void) {
  s_settings.enabled = !s_settings.enabled;
  if (s_settings.enabled) {
    s_settings.triggered = false; // Reset trigger state when re-enabling
  }
  prv_save_settings();
}

void smart_alarm_cycle_window(void) {
  if (s_settings.window_minutes == 15) {
    s_settings.window_minutes = 30;
  } else if (s_settings.window_minutes == 30) {
    s_settings.window_minutes = 45;
  } else {
    s_settings.window_minutes = 15;
  }
  prv_save_settings();
}

void smart_alarm_evaluate(SleepStage current_stage) {
  if (!s_settings.enabled || s_is_ringing) {
    return;
  }

  time_t now = time(NULL);
  struct tm *t = localtime(&now);
  if (!t) return;

  int curr_min = t->tm_hour * 60 + t->tm_min;
  int target_min = s_settings.target_hour * 60 + s_settings.target_min;

  // Minutes until target alarm
  int diff = target_min - curr_min;
  if (diff < 0) {
    diff += 1440; // Next day
  }

  // If already triggered within the last 12 hours, ignore
  if (s_settings.triggered && (now - s_settings.trigger_time < 12 * 3600)) {
    return;
  } else if (s_settings.triggered && (now - s_settings.trigger_time >= 12 * 3600)) {
    // Reset for next day
    s_settings.triggered = false;
  }

  // 1. Inside smart wake window: trigger early if user is in Light Sleep or Awake
  if (diff > 0 && diff <= s_settings.window_minutes) {
    if (current_stage == SLEEP_STAGE_LIGHT || current_stage == SLEEP_STAGE_AWAKE) {
      APP_LOG(APP_LOG_LEVEL_INFO, "Smart Wake triggered! Diff: %d min, Stage: %d", diff, current_stage);
      prv_start_ringing(true);
      return;
    }
  }

  // 2. Exact deadline reached: hard wake up regardless of stage
  if (diff == 0) {
    APP_LOG(APP_LOG_LEVEL_INFO, "Hard alarm deadline reached!");
    prv_start_ringing(false);
  }
}

void smart_alarm_dismiss(void) {
  if (s_is_ringing) {
    s_is_ringing = false;
    if (s_vibe_timer) {
      app_timer_cancel(s_vibe_timer);
      s_vibe_timer = NULL;
    }
    vibes_cancel();
    APP_LOG(APP_LOG_LEVEL_INFO, "Alarm dismissed by user");
  }
}

bool smart_alarm_is_active(void) {
  return s_is_ringing;
}

void smart_alarm_set_trigger_callback(SmartAlarmTriggerCallback callback) {
  s_trigger_cb = callback;
}
