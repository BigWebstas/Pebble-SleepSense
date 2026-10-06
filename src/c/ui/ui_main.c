#include "ui_main.h"
#include "../engine/sleep_engine.h"
#include "../engine/smart_alarm.h"
#include "../comm/comm.h"

static Window *s_main_window;
static TextLayer *s_header_layer;
static TextLayer *s_stage_layer;
static TextLayer *s_duration_layer;
static TextLayer *s_sensors_layer;
static TextLayer *s_alarm_layer;
#if defined(PBL_PLATFORM_EMERY)
// Only the Time 2 screen has room under the alarm row
static TextLayer *s_time_layer;
static TextLayer *s_date_layer;
static char s_time_buf[16];
static char s_date_buf[24];
#endif
static Layer *s_hypnogram_layer;

static char s_header_buf[32];
static char s_stage_buf[32];
static char s_duration_buf[48];
static char s_sensors_buf[48];
static char s_alarm_buf[48];

//! Custom hypnogram rendering procedure
static void prv_hypnogram_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  const SleepSession *session = sleep_engine_get_session();

  // Draw background box
  graphics_context_set_fill_color(ctx, GColorDarkGray);
  graphics_fill_rect(ctx, bounds, 2, GCornersAll);

  if (!session || session->epoch_count == 0) {
    // Empty state guideline
    graphics_context_set_stroke_color(ctx, GColorLightGray);
    graphics_draw_line(ctx, GPoint(bounds.origin.x + 2, bounds.origin.y + bounds.size.h / 2),
                            GPoint(bounds.origin.x + bounds.size.w - 3, bounds.origin.y + bounds.size.h / 2));
    return;
  }

  uint16_t count = session->epoch_count;
  int16_t avail_w = bounds.size.w - 4;
  int16_t step_w = (count > 0 && avail_w > count) ? (avail_w / count) : 1;
  if (step_w < 1) step_w = 1;

  // Render epochs in chronological order
  uint16_t start_idx = (session->epoch_head + SLEEP_EPOCH_HISTORY_MAX - count) % SLEEP_EPOCH_HISTORY_MAX;

  for (uint16_t i = 0; i < count; i++) {
    uint16_t idx = (start_idx + i) % SLEEP_EPOCH_HISTORY_MAX;
    const SleepEpoch *epoch = &session->epochs[idx];

    int16_t x = bounds.origin.x + 2 + (i * avail_w) / count;
    int16_t w = step_w;
    if (x + w > bounds.origin.x + bounds.size.w - 2) {
      w = (bounds.origin.x + bounds.size.w - 2) - x;
    }
    if (w <= 0) break;

    // Y position and height by stage
    // Top = Awake, Upper-Mid = REM, Lower-Mid = Light, Bottom = Deep
    int16_t h = bounds.size.h;
    int16_t bar_y = bounds.origin.y;
    int16_t bar_h = h / 4;

    switch (epoch->stage) {
      case SLEEP_STAGE_AWAKE:
        bar_y = bounds.origin.y + 1;
        bar_h = h / 4;
        break;
      case SLEEP_STAGE_REM:
        bar_y = bounds.origin.y + h / 4;
        bar_h = h / 4;
        break;
      case SLEEP_STAGE_LIGHT:
        bar_y = bounds.origin.y + (h * 2) / 4;
        bar_h = h / 4;
        break;
      case SLEEP_STAGE_DEEP:
        bar_y = bounds.origin.y + (h * 3) / 4;
        bar_h = h / 4 - 1;
        break;
    }

    graphics_context_set_fill_color(ctx, sleep_engine_stage_color(epoch->stage));
    graphics_fill_rect(ctx, GRect(x, bar_y, w, bar_h), 0, GCornerNone);
  }
}

void ui_main_refresh_clock(void) {
#if defined(PBL_PLATFORM_EMERY)
  if (!s_time_layer) return;
  time_t now = time(NULL);
  struct tm *t = localtime(&now);
  // Big digits only; AM/PM (12h clocks) goes on the date line
  strftime(s_time_buf, sizeof(s_time_buf), clock_is_24h_style() ? "%H:%M" : "%I:%M", t);
  strftime(s_date_buf, sizeof(s_date_buf), clock_is_24h_style() ? "%a %b %d" : "%a %b %d  %p", t);
  text_layer_set_text(s_time_layer, s_time_buf);
  text_layer_set_text(s_date_layer, s_date_buf);
#endif
}

void ui_main_update(const SleepSession *session) {
  if (!s_main_window || !session) return;

  bool is_ringing = smart_alarm_is_active();
  SmartAlarmSettings *alarm = smart_alarm_get_settings();

  // 1. Header
  if (is_ringing) {
    snprintf(s_header_buf, sizeof(s_header_buf), "ALARM RINGING!");
  } else if (session->is_tracking && session->current_hr > 0) {
    snprintf(s_header_buf, sizeof(s_header_buf), "[REC]  HR %d", session->current_hr);
  } else if (session->is_tracking) {
    snprintf(s_header_buf, sizeof(s_header_buf), "SleepSense  [REC]");
  } else {
    snprintf(s_header_buf, sizeof(s_header_buf), "SleepSense  [IDLE]");
  }
  text_layer_set_text(s_header_layer, s_header_buf);

  // 2. Stage Hero
  if (is_ringing) {
    snprintf(s_stage_buf, sizeof(s_stage_buf), "WAKE UP!");
    text_layer_set_background_color(s_stage_layer, GColorOrange);
    text_layer_set_text_color(s_stage_layer, GColorBlack);
  } else if (!session->is_tracking) {
    snprintf(s_stage_buf, sizeof(s_stage_buf), "HOLD SELECT");
    text_layer_set_background_color(s_stage_layer, GColorClear);
#if defined(PBL_COLOR)
    text_layer_set_text_color(s_stage_layer, GColorWhite);
#else
    text_layer_set_text_color(s_stage_layer, GColorBlack);
#endif
  } else {
    snprintf(s_stage_buf, sizeof(s_stage_buf), "%s SLEEP", sleep_engine_stage_name(session->current_stage));
    GColor color = sleep_engine_stage_color(session->current_stage);
    text_layer_set_background_color(s_stage_layer, color);
    text_layer_set_text_color(s_stage_layer, (session->current_stage == SLEEP_STAGE_LIGHT) ? GColorBlack : GColorWhite);
  }
  text_layer_set_text(s_stage_layer, s_stage_buf);

  // 3. Duration & Cycles
  if (session->is_tracking) {
    uint32_t total_min = session->total_sleep_sec / 60;
    uint32_t hours = total_min / 60;
    uint32_t mins = total_min % 60;
    snprintf(s_duration_buf, sizeof(s_duration_buf), "%02luh %02lum  |  %d Cycles",
             hours, mins, session->cycle_count);
  } else {
    snprintf(s_duration_buf, sizeof(s_duration_buf), "Ready to track sleep");
  }
  text_layer_set_text(s_duration_layer, s_duration_buf);

  // 4. Sensors: Light & Mic
  const SensorSettings *sensors = sleep_engine_get_sensors();
  const char *sound_desc = "Quiet";
  if (!sensors->mic) {
    sound_desc = "Off";
  } else if (session->current_sound > 70) {
    sound_desc = "Loud";
  } else if (session->current_sound > 40) {
    sound_desc = "Mod";
  }

  snprintf(s_sensors_buf, sizeof(s_sensors_buf), "Light: %s  |  Mic: %s",
           sensors->light ? sleep_engine_light_name(session->current_light) : "Off", sound_desc);
  text_layer_set_text(s_sensors_layer, s_sensors_buf);

  // 5. Smart Alarm bar
  if (is_ringing) {
    snprintf(s_alarm_buf, sizeof(s_alarm_buf),
             alarm->snooze_minutes ? "SEL stop | DOWN snooze" : "Press button to stop");
  } else if (alarm->snooze_until) {
    struct tm *until = localtime(&alarm->snooze_until);
    snprintf(s_alarm_buf, sizeof(s_alarm_buf), "Snoozed until %02d:%02d",
             until->tm_hour, until->tm_min);
  } else if (alarm->enabled) {
    snprintf(s_alarm_buf, sizeof(s_alarm_buf), "Alarm %02d:%02d (%dm smart)",
             alarm->target_hour, alarm->target_min, alarm->window_minutes);
  } else {
    snprintf(s_alarm_buf, sizeof(s_alarm_buf), "Alarm: OFF (hold UP)");
  }
  text_layer_set_text(s_alarm_layer, s_alarm_buf);

  // Redraw hypnogram layer
  layer_mark_dirty(s_hypnogram_layer);
}

void ui_main_alarm_trigger(bool is_smart_wake) {
  ui_main_update(sleep_engine_get_session());
}

//! A button press while the alarm rings: Down snoozes (when enabled), everything else stops it.
//! Returns true if the press was used up by the alarm.
static bool prv_handle_alarm_press(bool snooze) {
  if (!smart_alarm_is_active()) {
    return false;
  }
  if (!(snooze && smart_alarm_snooze())) {
    smart_alarm_dismiss();
  }
  comm_send_session_update(sleep_engine_get_session()); // phone logs the alarm event
  ui_main_update(sleep_engine_get_session());
  return true;
}

// Changing anything needs a deliberate hold, so a stray press while asleep does nothing. The one
// exception is a ringing alarm: any press stops it (Down snoozes), as it must be easy half asleep.
#define HOLD_MS 800

static void prv_confirm_buzz(void) {
  vibes_short_pulse(); // tells a half-asleep wearer the hold was taken
}

static void prv_select_click_handler(ClickRecognizerRef recognizer, void *context) {
  prv_handle_alarm_press(false);
}

static void prv_select_long_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (prv_handle_alarm_press(false)) {
    return;
  }
  prv_confirm_buzz();
  sleep_engine_toggle_session();
  comm_send_session_update(sleep_engine_get_session());
  ui_main_update(sleep_engine_get_session());
}

// Voice dream journal: double-press Select
static void prv_select_double_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (prv_handle_alarm_press(false)) {
    return;
  }
  comm_start_voice_journal();
}

static void prv_up_click_handler(ClickRecognizerRef recognizer, void *context) {
  prv_handle_alarm_press(false);
}

static void prv_up_long_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (prv_handle_alarm_press(false)) {
    return;
  }
  prv_confirm_buzz();
  smart_alarm_toggle();
  comm_send_session_update(sleep_engine_get_session()); // phone-side copy learns of it at once
  ui_main_update(sleep_engine_get_session());
}

static void prv_down_click_handler(ClickRecognizerRef recognizer, void *context) {
  prv_handle_alarm_press(true);
}

static void prv_down_long_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (prv_handle_alarm_press(true)) {
    return;
  }
  prv_confirm_buzz();
  smart_alarm_cycle_window();
  comm_send_session_update(sleep_engine_get_session());
  ui_main_update(sleep_engine_get_session());
}

static void prv_click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click_handler);
  window_multi_click_subscribe(BUTTON_ID_SELECT, 2, 2, 300, true, prv_select_double_click_handler);
  window_long_click_subscribe(BUTTON_ID_SELECT, HOLD_MS, prv_select_long_click_handler, NULL);
  window_single_click_subscribe(BUTTON_ID_UP, prv_up_click_handler);
  window_long_click_subscribe(BUTTON_ID_UP, HOLD_MS, prv_up_long_click_handler, NULL);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_down_click_handler);
  window_long_click_subscribe(BUTTON_ID_DOWN, HOLD_MS, prv_down_long_click_handler, NULL);
}

static void prv_window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);

#if defined(PBL_COLOR)
  window_set_background_color(window, GColorOxfordBlue);
#else
  window_set_background_color(window, GColorWhite);
#endif

  int16_t w = bounds.size.w;
  int16_t y = PBL_IF_ROUND_ELSE(10, 4);

  // 1. Header Layer
  s_header_layer = text_layer_create(GRect(0, y, w, 18));
  text_layer_set_font(s_header_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD));
  text_layer_set_text_alignment(s_header_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_header_layer, GColorClear);
#if defined(PBL_COLOR)
  text_layer_set_text_color(s_header_layer, GColorLightGray);
#else
  text_layer_set_text_color(s_header_layer, GColorBlack);
#endif
  layer_add_child(window_layer, text_layer_get_layer(s_header_layer));
  y += 20;

  // 2. Stage Hero Layer
  s_stage_layer = text_layer_create(GRect(8, y, w - 16, 32));
  text_layer_set_font(s_stage_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_text_alignment(s_stage_layer, GTextAlignmentCenter);
  layer_add_child(window_layer, text_layer_get_layer(s_stage_layer));
  y += 36;

  // 3. Hypnogram Graph Layer (height 28 px)
  int16_t graph_margin = PBL_IF_ROUND_ELSE(20, 8);
  s_hypnogram_layer = layer_create(GRect(graph_margin, y, w - (graph_margin * 2), 26));
  layer_set_update_proc(s_hypnogram_layer, prv_hypnogram_update_proc);
  layer_add_child(window_layer, s_hypnogram_layer);
  y += 30;

  // 4. Duration & Cycles Layer
  s_duration_layer = text_layer_create(GRect(4, y, w - 8, 20));
  text_layer_set_font(s_duration_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_duration_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_duration_layer, GColorClear);
#if defined(PBL_COLOR)
  text_layer_set_text_color(s_duration_layer, GColorWhite);
#else
  text_layer_set_text_color(s_duration_layer, GColorBlack);
#endif
  layer_add_child(window_layer, text_layer_get_layer(s_duration_layer));
  y += 22;

  // 5. Sensors Info Layer (Light & Mic)
  s_sensors_layer = text_layer_create(GRect(4, y, w - 8, 18));
  text_layer_set_font(s_sensors_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  text_layer_set_text_alignment(s_sensors_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_sensors_layer, GColorClear);
#if defined(PBL_COLOR)
  text_layer_set_text_color(s_sensors_layer, GColorCeleste);
#else
  text_layer_set_text_color(s_sensors_layer, GColorBlack);
#endif
  layer_add_child(window_layer, text_layer_get_layer(s_sensors_layer));
  y += 20;

  // 6. Smart Alarm Bottom Status Layer
  s_alarm_layer = text_layer_create(GRect(4, y, w - 8, 18));
  text_layer_set_font(s_alarm_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD));
  text_layer_set_text_alignment(s_alarm_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_alarm_layer, GColorClear);
#if defined(PBL_COLOR)
  text_layer_set_text_color(s_alarm_layer, GColorChromeYellow);
#else
  text_layer_set_text_color(s_alarm_layer, GColorBlack);
#endif
  layer_add_child(window_layer, text_layer_get_layer(s_alarm_layer));

#if defined(PBL_PLATFORM_EMERY)
  // 7. Date and time under the alarm row
  // Largest digits that fit: the time takes all the height left under the alarm row
  // (the digit glyphs sit low in their frame, so the frame can overlap the alarm row)
  y += 12;
  s_time_layer = text_layer_create(GRect(0, y, w, 62));
  text_layer_set_font(s_time_layer, fonts_get_system_font(FONT_KEY_LECO_60_BOLD_NUMBERS_AM_PM));
  text_layer_set_text_alignment(s_time_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_time_layer, GColorClear);
  text_layer_set_text_color(s_time_layer, GColorWhite);
  layer_add_child(window_layer, text_layer_get_layer(s_time_layer));
  y += 62;

  s_date_layer = text_layer_create(GRect(4, y, w - 8, bounds.size.h - y));
  text_layer_set_font(s_date_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  text_layer_set_text_alignment(s_date_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_date_layer, GColorClear);
  text_layer_set_text_color(s_date_layer, GColorLightGray);
  layer_add_child(window_layer, text_layer_get_layer(s_date_layer));
#endif

  // Initial render
  ui_main_update(sleep_engine_get_session());
  ui_main_refresh_clock();
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_header_layer);
  text_layer_destroy(s_stage_layer);
  text_layer_destroy(s_duration_layer);
  text_layer_destroy(s_sensors_layer);
  text_layer_destroy(s_alarm_layer);
  layer_destroy(s_hypnogram_layer);
#if defined(PBL_PLATFORM_EMERY)
  text_layer_destroy(s_time_layer);
  text_layer_destroy(s_date_layer);
  s_time_layer = NULL;
  s_date_layer = NULL;
#endif
}

void ui_main_init(void) {
  s_main_window = window_create();
  window_set_click_config_provider(s_main_window, prv_click_config_provider);
  window_set_window_handlers(s_main_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });

  const bool animated = true;
  window_stack_push(s_main_window, animated);
}

void ui_main_deinit(void) {
  if (s_main_window) {
    window_destroy(s_main_window);
    s_main_window = NULL;
  }
}
