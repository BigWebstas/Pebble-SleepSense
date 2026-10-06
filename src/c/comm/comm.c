#include "comm.h"
#include "../engine/sleep_engine.h"
#include "../engine/smart_alarm.h"
#include <message_keys.auto.h>

#if defined(PBL_MICROPHONE)
static DictationSession *s_dictation_session = NULL;

static void prv_dictation_callback(DictationSession *session, DictationSessionStatus status,
                                   char *transcription, void *context) {
  if (status == DictationSessionStatusSuccess && transcription) {
    APP_LOG(APP_LOG_LEVEL_INFO, "Voice Journal: %s", transcription);
    DictionaryIterator *out_iter;
    AppMessageResult result = app_message_outbox_begin(&out_iter);
    if (result == APP_MSG_OK && out_iter) {
      dict_write_cstring(out_iter, MESSAGE_KEY_VOICE_NOTE_TRANSCRIPT, transcription);
      app_message_outbox_send();
    }
  } else {
    APP_LOG(APP_LOG_LEVEL_INFO, "Dictation ended or aborted with status: %d", status);
  }

  if (s_dictation_session) {
    dictation_session_destroy(s_dictation_session);
    s_dictation_session = NULL;
  }
}
#endif

static void prv_inbox_received(DictionaryIterator *iter, void *context) {
  // 1. Phone mic ambient sound sample
  Tuple *sound_tuple = dict_find(iter, MESSAGE_KEY_PHONE_SOUND_SAMPLE);
  if (sound_tuple) {
    uint8_t sound = sound_tuple->value->uint8;
    sleep_engine_update_sound(sound);
  }

  // Start tracking on request of the phone (the Android widget)
  Tuple *start_cmd = dict_find(iter, MESSAGE_KEY_COMMAND_START_TRACKING);
  if (start_cmd && start_cmd->value->uint8 != 0 && !sleep_engine_is_tracking()) {
    sleep_engine_start_session();
  }

  // 2. Sensor toggles (always sent together)
  Tuple *light_en = dict_find(iter, MESSAGE_KEY_SENSOR_LIGHT_ENABLED);
  Tuple *mic_en = dict_find(iter, MESSAGE_KEY_SENSOR_MIC_ENABLED);
  Tuple *hr_en = dict_find(iter, MESSAGE_KEY_SENSOR_HR_ENABLED);
  if (light_en && mic_en && hr_en) {
    sleep_engine_set_sensors(light_en->value->uint8 != 0, mic_en->value->uint8 != 0,
                             hr_en->value->uint8 != 0);
  }

  // 3. Alarm configuration
  Tuple *target_h = dict_find(iter, MESSAGE_KEY_ALARM_TARGET_HOUR);
  Tuple *target_m = dict_find(iter, MESSAGE_KEY_ALARM_TARGET_MIN);
  if (target_h && target_m) {
    smart_alarm_set_target(target_h->value->uint8, target_m->value->uint8);
  }

  Tuple *smart_win = dict_find(iter, MESSAGE_KEY_SMART_WINDOW_MIN);
  if (smart_win) {
    SmartAlarmSettings *settings = smart_alarm_get_settings();
    settings->window_minutes = smart_win->value->uint8;
  }

  Tuple *snooze_min = dict_find(iter, MESSAGE_KEY_SNOOZE_MINUTES);
  if (snooze_min) {
    smart_alarm_set_snooze_minutes(snooze_min->value->uint8);
  }

  Tuple *smart_en = dict_find(iter, MESSAGE_KEY_SMART_ALARM_ENABLED);
  if (smart_en) {
    SmartAlarmSettings *settings = smart_alarm_get_settings();
    settings->enabled = (smart_en->value->uint8 != 0);
  }
}

static void prv_inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "AppMessage inbox dropped: %d", reason);
}

static void prv_outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "AppMessage outbox failed: %d", reason);
}

void comm_init(void) {
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_inbox_dropped(prv_inbox_dropped);
  app_message_register_outbox_failed(prv_outbox_failed);

  const uint32_t inbox_size = 256;
  const uint32_t outbox_size = 256;
  app_message_open(inbox_size, outbox_size);
}

void comm_deinit(void) {
  app_message_deregister_callbacks();
#if defined(PBL_MICROPHONE)
  if (s_dictation_session) {
    dictation_session_destroy(s_dictation_session);
    s_dictation_session = NULL;
  }
#endif
}

void comm_send_session_update(const SleepSession *session) {
  if (!session) return;

  DictionaryIterator *out_iter;
  AppMessageResult result = app_message_outbox_begin(&out_iter);
  if (result != APP_MSG_OK || !out_iter) {
    return;
  }

  dict_write_uint8(out_iter, MESSAGE_KEY_STATUS_STATE, (uint8_t)session->current_stage);
  dict_write_uint32(out_iter, MESSAGE_KEY_STATUS_DURATION, session->total_sleep_sec / 60);
  dict_write_uint32(out_iter, MESSAGE_KEY_STATUS_DEEP_DURATION, session->deep_sleep_sec / 60);
  dict_write_uint8(out_iter, MESSAGE_KEY_STATUS_LIGHT_LEVEL, (uint8_t)session->current_light);
  dict_write_uint8(out_iter, MESSAGE_KEY_STATUS_SOUND_LEVEL, session->current_sound);
  dict_write_uint8(out_iter, MESSAGE_KEY_STATUS_CYCLE_COUNT, session->cycle_count);
  dict_write_uint8(out_iter, MESSAGE_KEY_STATUS_SLEEP_SCORE, session->sleep_score);
  dict_write_uint8(out_iter, MESSAGE_KEY_STATUS_HEART_RATE, session->current_hr);
  dict_write_uint8(out_iter, MESSAGE_KEY_STATUS_SNOOZE_COUNT, session->snooze_count);
  dict_write_uint32(out_iter, MESSAGE_KEY_STATUS_SNOOZE_SEC, session->snooze_sec);
  // 0 = quiet, 1 = ringing, 2 = snoozed (the phone turns changes into timeline events)
  uint8_t alarm_state = smart_alarm_is_active() ? 1 : (smart_alarm_get_settings()->snooze_until ? 2 : 0);
  dict_write_uint8(out_iter, MESSAGE_KEY_STATUS_ALARM_STATE, alarm_state);
  // The watch owns the alarm settings; report them so the phone's copy (and any companion app)
  // always matches. These reuse the keys the phone uses to set them.
  const SmartAlarmSettings *alarm = smart_alarm_get_settings();
  dict_write_uint8(out_iter, MESSAGE_KEY_ALARM_TARGET_HOUR, alarm->target_hour);
  dict_write_uint8(out_iter, MESSAGE_KEY_ALARM_TARGET_MIN, alarm->target_min);
  dict_write_uint8(out_iter, MESSAGE_KEY_SMART_WINDOW_MIN, alarm->window_minutes);
  dict_write_uint8(out_iter, MESSAGE_KEY_SMART_ALARM_ENABLED, alarm->enabled ? 1 : 0);
  dict_write_uint8(out_iter, MESSAGE_KEY_SNOOZE_MINUTES, alarm->snooze_minutes);
  dict_write_uint8(out_iter, MESSAGE_KEY_TRACKING_ACTIVE, session->is_tracking ? 1 : 0);

  app_message_outbox_send();
}

void comm_start_voice_journal(void) {
#if defined(PBL_MICROPHONE)
  if (s_dictation_session) {
    dictation_session_destroy(s_dictation_session);
  }
  s_dictation_session = dictation_session_create(512, prv_dictation_callback, NULL);
  if (s_dictation_session) {
    dictation_session_enable_confirmation(s_dictation_session, true);
    dictation_session_start(s_dictation_session);
  } else {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Failed to create DictationSession");
  }
#else
  APP_LOG(APP_LOG_LEVEL_INFO, "Microphone not available on this platform");
#endif
}
