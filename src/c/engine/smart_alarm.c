#include "smart_alarm.h"
#include "sleep_engine.h"

#define PERSIST_KEY_ALARM 100
#define ALARM_REPEAT_TIMER_MS 2500
#define ALARM_SOUND_DELAY_SEC 30      // the speaker stays silent for this long (the vibration wakes first)...
#define ALARM_SOUND_RAMP_SEC (5 * 60) // ...then goes from 0% to 100% over this
#ifdef _PBL_API_EXISTS_speaker_play_notes
#define HAS_SPEAKER 1
#define ALARM_TIMEOUT_SEC (ALARM_SOUND_DELAY_SEC + ALARM_SOUND_RAMP_SEC + 60) // a minute at full volume, then give up
#else
#define ALARM_TIMEOUT_SEC 180
#endif
#define DEFAULT_SNOOZE_MIN 9
#define ALARM_WAKEUP_COOKIE 2
#define ALARM_WAKEUP_LEAD_SEC 60 // launches the app a minute early, so it is up for the minute tick on the alarm time
#define ALARM_CATCH_UP_SEC 180   // an alarm missed by up to this long still rings when the app comes back
#define ALARM_RELAUNCH_SEC 20    // ...which is when the app is relaunched, if something pushed it aside at the alarm time

static SmartAlarmSettings s_settings;
static AppTimer *s_vibe_timer = NULL;
static bool s_is_ringing = false;
static time_t s_ringing_start = 0;
static SmartAlarmTriggerCallback s_trigger_cb = NULL;
static SmartAlarmDismissCallback s_dismiss_cb = NULL;
static WakeupId s_wakeup_id = -1;
static time_t s_wakeup_for = 0; // the ring time the wakeup was set up for

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
    int read = persist_read_data(PERSIST_KEY_ALARM, &s_settings, sizeof(s_settings));
    if (read < (int)sizeof(s_settings)) {
      // Saved by a version without snooze
      s_settings.snooze_minutes = DEFAULT_SNOOZE_MIN;
      s_settings.snooze_until = 0;
    }
  } else {
    // Defaults: 07:00 AM, 30m window, enabled
    s_settings.enabled = true;
    s_settings.target_hour = 7;
    s_settings.target_min = 0;
    s_settings.window_minutes = 30;
    s_settings.triggered = false;
    s_settings.trigger_time = 0;
    s_settings.snooze_minutes = DEFAULT_SNOOZE_MIN;
    s_settings.snooze_until = 0;
  }
}

#ifdef HAS_SPEAKER
#define ALARM_SOUND_REPEAT_MS 2500

//! Three short beeps; after the silent start each repeat is a little louder than the last
static const SpeakerNote s_chime[] = {
  { .midi_note = 84, .waveform = SpeakerWaveformSine, .duration_ms = 150 },
  { .midi_note = 0, .duration_ms = 100 },
  { .midi_note = 84, .waveform = SpeakerWaveformSine, .duration_ms = 150 },
  { .midi_note = 0, .duration_ms = 100 },
  { .midi_note = 84, .waveform = SpeakerWaveformSine, .duration_ms = 150 },
};
static AppTimer *s_sound_timer = NULL;

static void prv_sound_timer_handler(void *context) {
  s_sound_timer = NULL;
  if (!s_is_ringing) {
    return;
  }
  s_sound_timer = app_timer_register(ALARM_SOUND_REPEAT_MS, prv_sound_timer_handler, NULL);
  if (speaker_is_muted()) {
    return;
  }
  time_t elapsed = time(NULL) - s_ringing_start - ALARM_SOUND_DELAY_SEC;
  if (elapsed < 0) {
    return;
  }
  if (elapsed > ALARM_SOUND_RAMP_SEC) elapsed = ALARM_SOUND_RAMP_SEC;
  uint8_t volume = (100 * elapsed) / ALARM_SOUND_RAMP_SEC;
  if (volume > 0) { // 0% is silent: nothing to play yet
    speaker_play_notes(s_chime, ARRAY_LENGTH(s_chime), volume);
  }
}
#endif

static void prv_start_alarm_sound(void) {
#ifdef HAS_SPEAKER
  if (s_sound_timer) {
    app_timer_cancel(s_sound_timer);
  }
  // Half a period after the vibration, so the two take turns instead of overlapping (the vibration
  // takes 1.1 s of every 2.5 s, the chime 0.65 s)
  s_sound_timer = app_timer_register(ALARM_SOUND_REPEAT_MS / 2, prv_sound_timer_handler, NULL);
#endif
}

static void prv_stop_alarm_sound(void) {
#ifdef HAS_SPEAKER
  if (s_sound_timer) {
    app_timer_cancel(s_sound_timer);
    s_sound_timer = NULL;
  }
  speaker_stop();
#endif
}

static void prv_stop_ringing(void) {
  if (s_is_ringing) {
    s_is_ringing = false;
    if (s_vibe_timer) {
      app_timer_cancel(s_vibe_timer);
      s_vibe_timer = NULL;
    }
    vibes_cancel();
    prv_stop_alarm_sound();
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
  prv_start_alarm_sound();

  if (s_trigger_cb) {
    s_trigger_cb(is_smart_wake);
  }

  if (s_vibe_timer) {
    app_timer_cancel(s_vibe_timer);
  }
  s_vibe_timer = app_timer_register(ALARM_REPEAT_TIMER_MS, prv_vibe_timer_handler, NULL);
}

//! The alarm time today as a timestamp (it may be earlier than now), or 0 if the time can't be read
static time_t prv_alarm_time_today(time_t now) {
  struct tm *t = localtime(&now);
  if (!t) {
    return 0;
  }
  t->tm_hour = s_settings.target_hour;
  t->tm_min = s_settings.target_min;
  t->tm_sec = 0;
  return mktime(t);
}

//! The alarm hasn't rung for this alarm time yet
static bool prv_not_rung_since(time_t at) {
  return !(s_settings.triggered && s_settings.trigger_time >= at);
}

//! When the app has to be running to ring the alarm: its time, or the end of a snooze (0 = never).
//! A time just passed still counts if the app was waiting for it and it hasn't rung: something
//! else (another app's alarm at the same moment) can push the app aside right at the alarm time.
static time_t prv_next_ring_time(time_t now) {
  if (!s_settings.enabled) {
    return 0;
  }
  if (s_settings.snooze_until) {
    return s_settings.snooze_until;
  }
  time_t at = prv_alarm_time_today(now);
  if (!at) {
    return 0;
  }
  if (at > now) {
    return at;
  }
  if (at == s_wakeup_for && now - at <= ALARM_CATCH_UP_SEC && prv_not_rung_since(at)) {
    return at;
  }
  return at + 24 * 3600;
}

void smart_alarm_sync_wakeup(void) {
  time_t now = time(NULL);
  time_t ring = s_is_ringing ? 0 : prv_next_ring_time(now);
  bool missed = ring && ring <= now; // the app is about to be left without having rung
  if (ring == s_wakeup_for && !missed) {
    return;
  }
  if (s_wakeup_id >= 0) {
    wakeup_cancel(s_wakeup_id);
    s_wakeup_id = -1;
  }
  s_wakeup_for = ring;
  if (!ring) {
    return;
  }
  if (missed) {
    // Soon, and a minute later each time one is refused, while it can still be caught up
    for (int wait = ALARM_RELAUNCH_SEC; ring + ALARM_CATCH_UP_SEC > now + wait; wait += ALARM_WAKEUP_LEAD_SEC) {
      s_wakeup_id = wakeup_schedule(now + wait, ALARM_WAKEUP_COOKIE, false);
      APP_LOG(APP_LOG_LEVEL_INFO, "Alarm missed, relaunch wakeup in %d s: %d", wait, (int)s_wakeup_id);
      if (s_wakeup_id >= 0) {
        return;
      }
    }
    return;
  }
  // Wakeups can't sit within a minute of each other (another app's alarm may be at this very
  // time), so when one is refused try again a minute earlier. Too close to now: the app is open.
  for (int lead = ALARM_WAKEUP_LEAD_SEC; lead <= 5 * ALARM_WAKEUP_LEAD_SEC && ring - lead > now + 5;
       lead += ALARM_WAKEUP_LEAD_SEC) {
    s_wakeup_id = wakeup_schedule(ring - lead, ALARM_WAKEUP_COOKIE, false);
    APP_LOG(APP_LOG_LEVEL_INFO, "Alarm wakeup %d s before the alarm: %d", lead, (int)s_wakeup_id);
    if (s_wakeup_id >= 0) {
      return;
    }
  }
}

//! Launched by a wakeup: if the alarm time (or the end of a snooze) has just passed without the
//! alarm ringing, because the app was pushed aside, ring now.
static void prv_catch_up(time_t now) {
  if (!s_settings.enabled) {
    return;
  }
  if (s_settings.snooze_until) {
    if (now >= s_settings.snooze_until && now - s_settings.snooze_until <= ALARM_CATCH_UP_SEC) {
      s_settings.snooze_until = 0;
      APP_LOG(APP_LOG_LEVEL_INFO, "Snooze ended while away, ringing now");
      prv_start_ringing(false);
    }
    return;
  }
  time_t at = prv_alarm_time_today(now);
  if (at && at <= now && now - at <= ALARM_CATCH_UP_SEC && prv_not_rung_since(at)) {
    APP_LOG(APP_LOG_LEVEL_INFO, "Alarm time passed while away, ringing now");
    prv_start_ringing(false);
  }
}

void smart_alarm_init(void) {
  prv_load_settings();
  if (launch_reason() == APP_LAUNCH_WAKEUP) {
    prv_catch_up(time(NULL));
  }
  smart_alarm_sync_wakeup();
}

void smart_alarm_deinit(void) {
  prv_stop_ringing();
  prv_save_settings();
  smart_alarm_sync_wakeup(); // the app is closing: make sure the watch brings it back for the alarm
}

SmartAlarmSettings *smart_alarm_get_settings(void) {
  return &s_settings;
}

void smart_alarm_set_target(uint8_t hour, uint8_t min) {
  s_settings.target_hour = hour % 24;
  s_settings.target_min = min % 60;
  s_settings.triggered = false;
  prv_save_settings();
  smart_alarm_sync_wakeup();
}

void smart_alarm_toggle(void) {
  s_settings.enabled = !s_settings.enabled;
  if (!s_settings.enabled) {
    s_settings.snooze_until = 0;
  }
  if (s_settings.enabled) {
    s_settings.triggered = false; // Reset trigger state when re-enabling
  }
  prv_save_settings();
  smart_alarm_sync_wakeup();
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

  // A snoozed alarm rings again once its time is up (checked each minute, so it survives
  // the app being relaunched); nothing else is evaluated while snoozing.
  if (s_settings.snooze_until) {
    if (now >= s_settings.snooze_until) {
      s_settings.snooze_until = 0;
      APP_LOG(APP_LOG_LEVEL_INFO, "Snooze over, ringing again");
      prv_start_ringing(false);
    }
    return;
  }

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

  // 1. Inside smart wake window: trigger early if user is in Light Sleep or Awake (needs tracking
  //    to know the stage; without it the alarm rings at its time)
  if (sleep_engine_is_tracking() && diff > 0 && diff <= s_settings.window_minutes) {
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
    prv_stop_ringing();
    APP_LOG(APP_LOG_LEVEL_INFO, "Alarm dismissed by user");
    smart_alarm_sync_wakeup();
    if (sleep_engine_is_tracking()) {
      sleep_engine_stop_session();
    } else if (s_dismiss_cb) {
      s_dismiss_cb();
    }
  }
}

bool smart_alarm_snooze(void) {
  if (!s_is_ringing || s_settings.snooze_minutes == 0) {
    return false;
  }
  uint32_t seconds = s_settings.snooze_minutes * 60;
  s_settings.snooze_until = time(NULL) + seconds;
  prv_stop_ringing();
  prv_save_settings();
  smart_alarm_sync_wakeup();
  sleep_engine_add_snooze(seconds); // notifies, now reporting "snoozed"
  APP_LOG(APP_LOG_LEVEL_INFO, "Alarm snoozed for %d min", s_settings.snooze_minutes);
  return true;
}

void smart_alarm_cancel_snooze(void) {
  if (s_settings.snooze_until) {
    s_settings.snooze_until = 0;
    prv_save_settings();
  }
}

void smart_alarm_set_snooze_minutes(uint8_t minutes) {
  s_settings.snooze_minutes = minutes;
  prv_save_settings();
}

bool smart_alarm_is_active(void) {
  return s_is_ringing;
}

void smart_alarm_set_trigger_callback(SmartAlarmTriggerCallback callback) {
  s_trigger_cb = callback;
}

void smart_alarm_set_dismiss_callback(SmartAlarmDismissCallback callback) {
  s_dismiss_cb = callback;
}
