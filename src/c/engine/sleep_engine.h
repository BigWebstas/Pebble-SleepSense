#pragma once
#include <pebble.h>
#include "../model/sleep_types.h"

typedef void (*SleepEngineUpdateCallback)(const SleepSession *session);

//! Initialize the sleep engine and restore persistent settings
void sleep_engine_init(void);

//! Tear down the sleep engine and save state
void sleep_engine_deinit(void);

//! Start an active sleep tracking session
void sleep_engine_start_session(void);

//! Stop the active sleep tracking session
void sleep_engine_stop_session(void);

//! Toggle sleep tracking session on/off
void sleep_engine_toggle_session(void);

//! Returns true if tracking session is currently active
bool sleep_engine_is_tracking(void);

//! Get current sleep session pointer
const SleepSession *sleep_engine_get_session(void);

//! Provide latest sound level from phone mic (0 - 100)
void sleep_engine_update_sound(uint8_t sound_level);

//! Register a callback to be called whenever an epoch or state updates
void sleep_engine_set_update_callback(SleepEngineUpdateCallback callback);

//! Get human readable string for a sleep stage
const char *sleep_engine_stage_name(SleepStage stage);

//! Called every minute (tracking or not), e.g. to refresh a clock on screen
typedef void (*SleepEngineMinuteCallback)(void);
void sleep_engine_set_minute_callback(SleepEngineMinuteCallback callback);

//! Count a snooze of the given length against the current session
void sleep_engine_add_snooze(uint32_t seconds);

//! Enable or disable each sensor; persisted and applied immediately
void sleep_engine_set_sensors(bool light, bool mic, bool heart_rate);

//! Current sensor settings
const SensorSettings *sleep_engine_get_sensors(void);

//! Get human readable string for light level
const char *sleep_engine_light_name(AppLightLevel light);

//! Get color associated with a sleep stage
GColor sleep_engine_stage_color(SleepStage stage);
