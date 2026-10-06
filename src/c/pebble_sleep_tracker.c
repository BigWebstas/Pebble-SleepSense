#include <pebble.h>
#include "model/sleep_types.h"
#include "engine/sleep_engine.h"
#include "engine/smart_alarm.h"
#include "comm/comm.h"
#include "ui/ui_main.h"

static void prv_engine_update_handler(const SleepSession *session) {
  ui_main_update(session);
  comm_send_session_update(session);
}

static void prv_alarm_trigger_handler(bool is_smart_wake) {
  ui_main_alarm_trigger(is_smart_wake);
  comm_send_session_update(sleep_engine_get_session());
}

static void prv_init(void) {
  sleep_engine_init();
  smart_alarm_init();
  comm_init();
  ui_main_init();

  sleep_engine_set_update_callback(prv_engine_update_handler);
  sleep_engine_set_minute_callback(ui_main_refresh_clock);
  smart_alarm_set_trigger_callback(prv_alarm_trigger_handler);
}

static void prv_deinit(void) {
  ui_main_deinit();
  comm_deinit();
  smart_alarm_deinit();
  sleep_engine_deinit();
}

int main(void) {
  prv_init();
  APP_LOG(APP_LOG_LEVEL_INFO, "SleepSense Initialized");
  app_event_loop();
  prv_deinit();
}
