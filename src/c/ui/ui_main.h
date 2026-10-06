#pragma once
#include <pebble.h>
#include "../model/sleep_types.h"

//! Initialize the main UI window and layers
void ui_main_init(void);

//! Tear down UI resources
void ui_main_deinit(void);

//! Update the UI with the latest session data
void ui_main_update(const SleepSession *session);

//! Notify UI of smart alarm trigger state
void ui_main_alarm_trigger(bool is_smart_wake);

//! Refresh the on-screen date and time (Pebble Time 2 only; no-op elsewhere)
void ui_main_refresh_clock(void);
