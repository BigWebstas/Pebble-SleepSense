#pragma once
#include <pebble.h>
#include "../model/sleep_types.h"

typedef void (*SmartAlarmTriggerCallback)(bool is_smart_wake);
typedef void (*SmartAlarmDismissCallback)(void);

//! Initialize smart alarm module and read saved preferences
void smart_alarm_init(void);

//! Tear down smart alarm module
void smart_alarm_deinit(void);

//! Makes sure the watch will launch the app for the next alarm even if it is closed by then.
//! Cheap to call often; only touches the wakeup when the time it should fire has changed.
void smart_alarm_sync_wakeup(void);

//! Get reference to alarm settings
SmartAlarmSettings *smart_alarm_get_settings(void);

//! Set target alarm time (hour: 0-23, min: 0-59)
void smart_alarm_set_target(uint8_t hour, uint8_t min);

//! Toggle smart alarm enabled/disabled
void smart_alarm_toggle(void);

//! Cycle smart wake window duration (15m -> 30m -> 45m)
void smart_alarm_cycle_window(void);

//! Evaluate current time and sleep stage for alarm triggering
void smart_alarm_evaluate(SleepStage current_stage);

//! Dismiss an active ringing alarm
void smart_alarm_dismiss(void);

//! Silence a ringing alarm and ring again after the snooze length.
//! Returns false (alarm untouched) if snooze is disabled or nothing is ringing.
bool smart_alarm_snooze(void);

//! Forget a pending snooze (tracking stopped or alarm turned off)
void smart_alarm_cancel_snooze(void);

//! Set snooze length in minutes (0 disables snooze)
void smart_alarm_set_snooze_minutes(uint8_t minutes);

//! Returns true if the alarm is currently ringing
bool smart_alarm_is_active(void);

//! Set trigger callback to update UI when alarm goes off
void smart_alarm_set_trigger_callback(SmartAlarmTriggerCallback callback);

//! Set dismiss callback to update UI when alarm is dismissed outside tracking
void smart_alarm_set_dismiss_callback(SmartAlarmDismissCallback callback);
