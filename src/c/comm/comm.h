#pragma once
#include <pebble.h>
#include "../model/sleep_types.h"

//! Initialize AppMessage communication with phone companion
void comm_init(void);

//! Tear down communication
void comm_deinit(void);

//! Broadcast current sleep session stats to phone companion
void comm_send_session_update(const SleepSession *session);

//! Start a voice dream journal note using Pebble microphone (if supported)
void comm_start_voice_journal(void);

//! Asks the phone to sync now (alarm, history, status) instead of at its next scheduled check
void comm_request_refresh(void);
