#pragma once
#include <pebble.h>

//! Maximum number of history epochs to keep in watch memory
//! 120 epochs = 2 hours of minute-by-minute sleep timeline
#define SLEEP_EPOCH_HISTORY_MAX 120

//! Supported sleep stages
typedef enum {
  SLEEP_STAGE_AWAKE = 0,
  SLEEP_STAGE_LIGHT = 1,
  SLEEP_STAGE_DEEP  = 2,
  SLEEP_STAGE_REM   = 3,
} SleepStage;

//! Normalized ambient light levels
typedef enum {
  APP_LIGHT_UNKNOWN = 0,
  APP_LIGHT_VERY_DARK = 1,
  APP_LIGHT_DARK = 2,
  APP_LIGHT_LIGHT = 3,
  APP_LIGHT_VERY_LIGHT = 4,
} AppLightLevel;

//! Minute-by-minute sleep epoch record
typedef struct {
  time_t timestamp;
  uint16_t vmc;            //!< Vector Magnitude Counts (movement intensity)
  uint8_t light_level;     //!< AppLightLevel
  uint8_t sound_level;     //!< Phone mic sound level (0 - 100)
  uint8_t orientation;     //!< Pitch/yaw orientation
  uint8_t heart_rate;      //!< Beats per minute, 0 if unavailable
  SleepStage stage;        //!< Staged classification
} SleepEpoch;

//! Overall sleep tracking session state and metrics
typedef struct {
  bool is_tracking;
  time_t session_start;
  time_t session_end;
  
  uint32_t total_sleep_sec;
  uint32_t deep_sleep_sec;
  uint32_t light_sleep_sec;
  uint32_t rem_sleep_sec;
  uint32_t awake_sec;

  uint8_t cycle_count;     //!< Number of ~90 minute sleep cycles completed
  uint8_t sleep_score;     //!< Sleep efficiency score (0 - 100)
  
  SleepStage current_stage;
  AppLightLevel current_light;
  uint8_t current_sound;   //!< Latest mic sound level
  uint16_t current_vmc;    //!< Latest movement VMC
  uint8_t current_hr;      //!< Latest heart rate (bpm), 0 if unavailable

  //! Circular buffer of minute epochs
  SleepEpoch epochs[SLEEP_EPOCH_HISTORY_MAX];
  uint16_t epoch_count;
  uint16_t epoch_head;     //!< Next insertion index
} SleepSession;

//! Smart alarm configuration
typedef struct {
  bool enabled;
  uint8_t target_hour;     //!< 0 - 23
  uint8_t target_min;      //!< 0 - 59
  uint8_t window_minutes;  //!< e.g. 15, 30, 45 min
  bool triggered;
  time_t trigger_time;
} SmartAlarmSettings;
