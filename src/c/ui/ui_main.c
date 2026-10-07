#include "ui_main.h"
#include "../engine/sleep_engine.h"
#include "../engine/smart_alarm.h"
#include "../comm/comm.h"

static Window *s_main_window;
static TextLayer *s_header_layer;
static TextLayer *s_stage_layer;
static TextLayer *s_duration_layer;
static TextLayer *s_alarm_layer;
static TextLayer *s_countdown_layer;
#if defined(PBL_PLATFORM_EMERY)
// Only the Time 2 screen has room under the alarm row
static TextLayer *s_time_layer;
static TextLayer *s_date_layer;
static char s_time_buf[16];
static char s_date_buf[24];
#endif
static Layer *s_hypnogram_layer;
static Layer *s_bell_layer;
static GPath *s_bell_path;
static AppTimer *s_bell_timer;
static int32_t s_bell_phase;
static int16_t s_bell_pivot_y;
static int16_t s_bell_clapper_len;
static int16_t s_bell_clapper_r;
static bool s_bell_on;

static char s_header_buf[32];
static char s_stage_buf[32];
static char s_duration_buf[48];
static char s_alarm_buf[48];
static char s_countdown_buf[32];

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

// --- Ringing alarm: a bell swinging from a beam, with the backlight held on ---

#define BELL_FRAME_MS 33
#define BELL_PHASE_STEP (TRIG_MAX_ANGLE * BELL_FRAME_MS / 600)   // one swing every 600 ms
#define BELL_SWING (TRIG_MAX_ANGLE * 28 / 360)  // 28 degrees either way
#define BELL_UNITS 110                          // the bell below is drawn on a 110-unit-tall grid
#define BELL_CLAPPER_LEN 108
#define BELL_CLAPPER_R 8

//! The bell outline, hanging from (0, 0): a rounded crown and shoulders flaring out to the lip
static const GPoint s_bell_points[] = {
  {6, 0}, {7, 1}, {9, 3}, {12, 6}, {16, 9}, {19, 13},
  {22, 17}, {25, 22}, {27, 27}, {29, 33}, {30, 39}, {31, 45},
  {33, 51}, {35, 56}, {37, 61}, {39, 66}, {42, 71}, {44, 75},
  {47, 79}, {50, 83}, {52, 86}, {54, 89}, {55, 91}, {56, 93},
  {57, 95}, {58, 97}, {58, 98}, {-58, 98}, {-58, 97}, {-57, 95},
  {-56, 93}, {-55, 91}, {-54, 89}, {-52, 86}, {-50, 83}, {-47, 79},
  {-44, 75}, {-42, 71}, {-39, 66}, {-37, 61}, {-35, 56}, {-33, 51},
  {-31, 45}, {-30, 39}, {-29, 33}, {-27, 27}, {-25, 22}, {-22, 17},
  {-19, 13}, {-16, 9}, {-12, 6}, {-9, 3}, {-7, 1}, {-6, 0},
};

static void prv_bell_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);

  GPoint pivot = GPoint(bounds.size.w / 2, s_bell_pivot_y);

  // The timber beam the bell hangs from
  graphics_context_set_fill_color(ctx, PBL_IF_COLOR_ELSE(GColorWindsorTan, GColorWhite));
  graphics_fill_rect(ctx, GRect(0, pivot.y - 10, bounds.size.w, 10), 0, GCornerNone);

  int32_t swing = sin_lookup(s_bell_phase) * BELL_SWING / TRIG_MAX_RATIO;
  gpath_rotate_to(s_bell_path, swing);
  gpath_move_to(s_bell_path, pivot);
  graphics_context_set_fill_color(ctx, PBL_IF_COLOR_ELSE(GColorYellow, GColorWhite));
  gpath_draw_filled(ctx, s_bell_path);
  graphics_context_set_stroke_color(ctx, PBL_IF_COLOR_ELSE(GColorChromeYellow, GColorBlack));
  graphics_context_set_stroke_width(ctx, 1); // thicker strokes are much slower to draw
  gpath_draw_outline(ctx, s_bell_path);
  graphics_context_set_fill_color(ctx, PBL_IF_COLOR_ELSE(GColorDarkGray, GColorWhite));
  graphics_fill_circle(ctx, pivot, 5); // the axle

  // The clapper swings a little further, and a little behind
  int32_t lag = (s_bell_phase + TRIG_MAX_ANGLE - TRIG_MAX_ANGLE / 12) % TRIG_MAX_ANGLE;
  int32_t clap = sin_lookup(lag) * BELL_SWING * 3 / 2 / TRIG_MAX_RATIO;
  GPoint clapper = GPoint(pivot.x - s_bell_clapper_len * sin_lookup(clap) / TRIG_MAX_RATIO,
                          pivot.y + s_bell_clapper_len * cos_lookup(clap) / TRIG_MAX_RATIO);
  graphics_context_set_fill_color(ctx, PBL_IF_COLOR_ELSE(GColorOrange, GColorWhite));
  graphics_fill_circle(ctx, clapper, s_bell_clapper_r);

  // Ring marks beside the bell at the ends of each swing
  if (abs(swing) > BELL_SWING / 2) {
    int16_t mid = pivot.y + s_bell_clapper_len / 2;
    int16_t near = s_bell_clapper_len * 60 / 100;
    int16_t far = s_bell_clapper_len * 80 / 100;
    graphics_context_set_stroke_color(ctx, PBL_IF_COLOR_ELSE(GColorOrange, GColorWhite));
    graphics_context_set_stroke_width(ctx, 3);
    for (int side = -1; side <= 1; side += 2) {
      graphics_draw_line(ctx, GPoint(pivot.x + side * near, mid - 12), GPoint(pivot.x + side * far, mid - 24));
      graphics_draw_line(ctx, GPoint(pivot.x + side * near, mid + 12), GPoint(pivot.x + side * far, mid + 24));
    }
  }

  // Two short lines on a round screen, where one long one would be cut off at both ends
  bool can_snooze = smart_alarm_get_settings()->snooze_minutes != 0;
  const char *hint = can_snooze ? PBL_IF_ROUND_ELSE("SEL stop\nUP/DOWN snooze", "SEL stop  UP/DOWN snooze")
                                : "Press to stop";
  int16_t hint_h = PBL_IF_ROUND_ELSE(can_snooze ? 34 : 18, 18);
  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, hint, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD),
                     GRect(0, bounds.size.h - hint_h - PBL_IF_ROUND_ELSE(14, 4), bounds.size.w, hint_h),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

static bool prv_handle_alarm_press(bool snooze);

// Touch screens (Pebble Time 2): a tap snoozes, like Up and Down; elsewhere this is never called
static void prv_bell_touch_handler(const TouchEvent *event, void *context) {
  if (event->type == TouchEvent_Touchdown) {
    prv_handle_alarm_press(true);
  }
}

static void prv_bell_hide(void) {
  s_bell_on = false;
  touch_service_unsubscribe();
  if (s_bell_timer) {
    app_timer_cancel(s_bell_timer);
    s_bell_timer = NULL;
  }
  layer_set_hidden(s_bell_layer, true);
  light_enable(false); // back to the normal backlight behaviour
}

static void prv_bell_tick(void *context) {
  s_bell_timer = NULL;
  if (!smart_alarm_is_active()) { // stopped, snoozed or timed out
    prv_bell_hide();
    return;
  }
  s_bell_phase = (s_bell_phase + BELL_PHASE_STEP) % TRIG_MAX_ANGLE;
  layer_mark_dirty(s_bell_layer);
  s_bell_timer = app_timer_register(BELL_FRAME_MS, prv_bell_tick, NULL);
}

static void prv_bell_show(void) {
  if (s_bell_on) return;
  s_bell_on = true;
  s_bell_phase = 0;
  layer_set_hidden(s_bell_layer, false);
  light_enable(true);
  touch_service_subscribe(prv_bell_touch_handler, NULL); // only while ringing: the sensor costs power
  s_bell_timer = app_timer_register(BELL_FRAME_MS, prv_bell_tick, NULL);
}

static void prv_bell_create(Layer *window_layer, GRect bounds) {
  int16_t size = (bounds.size.w < bounds.size.h ? bounds.size.w : bounds.size.h) * 54 / 100;
  // The path keeps pointing at these, so they must outlive this function
  static GPoint s_scaled[ARRAY_LENGTH(s_bell_points)];
  static GPathInfo s_info = { .num_points = ARRAY_LENGTH(s_bell_points), .points = s_scaled };
  for (uint32_t i = 0; i < ARRAY_LENGTH(s_bell_points); i++) {
    s_scaled[i] = GPoint(s_bell_points[i].x * size / BELL_UNITS, s_bell_points[i].y * size / BELL_UNITS);
  }
  s_bell_path = gpath_create(&s_info);
  s_bell_clapper_len = BELL_CLAPPER_LEN * size / BELL_UNITS;
  s_bell_clapper_r = BELL_CLAPPER_R * size / BELL_UNITS;
  // Hung from the top of the screen, under the beam
  s_bell_pivot_y = PBL_IF_ROUND_ELSE(16, 12);

  s_bell_layer = layer_create(bounds);
  layer_set_update_proc(s_bell_layer, prv_bell_update_proc);
  layer_set_hidden(s_bell_layer, true);
  layer_add_child(window_layer, s_bell_layer);
}

static void prv_bell_destroy(void) {
  if (s_bell_on) prv_bell_hide();
  layer_destroy(s_bell_layer);
  gpath_destroy(s_bell_path);
  s_bell_layer = NULL;
  s_bell_path = NULL;
}

//! "Next alarm in 4h32m" under the alarm row: blank while the alarm is off or ringing
static void prv_refresh_countdown(void) {
  if (!s_countdown_layer) return;
  SmartAlarmSettings *alarm = smart_alarm_get_settings();
  time_t now = time(NULL);
  int32_t mins = -1;
  if (alarm->enabled && !smart_alarm_is_active()) {
    if (alarm->snooze_until) {
      mins = alarm->snooze_until > now ? (alarm->snooze_until - now + 59) / 60 : 0;
    } else {
      struct tm *t = localtime(&now);
      mins = (alarm->target_hour * 60 + alarm->target_min - (t->tm_hour * 60 + t->tm_min) + 1440) % 1440;
      if (mins == 0 && alarm->triggered) mins = 1440; // already rang this minute: the next one is tomorrow
    }
  }
  if (mins < 0) {
    s_countdown_buf[0] = '\0';
  } else if (mins >= 60) {
    snprintf(s_countdown_buf, sizeof(s_countdown_buf), "Next alarm in %ldh%02ldm", (long)(mins / 60), (long)(mins % 60));
  } else {
    snprintf(s_countdown_buf, sizeof(s_countdown_buf), "Next alarm in %ldm", (long)mins);
  }
  text_layer_set_text(s_countdown_layer, s_countdown_buf);
}

void ui_main_refresh_clock(void) {
  prv_refresh_countdown();
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

  // 4. Smart Alarm bar
  if (is_ringing) {
    snprintf(s_alarm_buf, sizeof(s_alarm_buf),
             alarm->snooze_minutes ? "SEL stop | UP/DOWN snooze" : "Press button to stop");
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
  prv_refresh_countdown();

  // Redraw hypnogram layer
  layer_mark_dirty(s_hypnogram_layer);

  if (is_ringing) {
    prv_bell_show();
  } else if (s_bell_on) {
    prv_bell_hide();
  }
}

void ui_main_alarm_trigger(bool is_smart_wake) {
  ui_main_update(sleep_engine_get_session());
}

//! A button press while the alarm rings: Up and Down snooze (when enabled), Select stops it.
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
// exception is a ringing alarm: any press stops it (Up and Down snooze), as it must be easy half asleep.
#define HOLD_MS 800

static void prv_confirm_buzz(void) {
  vibes_short_pulse(); // tells a half-asleep wearer the hold was taken
}

// A tap refreshes: the screen from the watch's own state, and the phone's side (alarm, history)
static void prv_select_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (prv_handle_alarm_press(false)) {
    return;
  }
  ui_main_update(sleep_engine_get_session());
  ui_main_refresh_clock();
  comm_request_refresh();
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
  prv_handle_alarm_press(true);
}

static void prv_up_long_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (prv_handle_alarm_press(true)) {
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

  // 5. Smart Alarm Bottom Status Layer
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
  y += 17;

  // 5b. Time until the alarm
  s_countdown_layer = text_layer_create(GRect(4, y, w - 8, 22));
  text_layer_set_font(s_countdown_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_alignment(s_countdown_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_countdown_layer, GColorClear);
  text_layer_set_text_color(s_countdown_layer, PBL_IF_COLOR_ELSE(GColorWhite, GColorBlack));
  layer_add_child(window_layer, text_layer_get_layer(s_countdown_layer));

#if defined(PBL_PLATFORM_EMERY)
  // 6. Date and time under the alarm row
  // Largest digits that fit: the time takes all the height left under the alarm row
  // (the digit glyphs sit low in their frame, so the frame can overlap the alarm row)
  y += 14; // the countdown row above takes 17 of the 31 px between the alarm row and the digits
  s_time_layer = text_layer_create(GRect(0, y, w, 62));
  text_layer_set_font(s_time_layer, fonts_get_system_font(FONT_KEY_LECO_60_BOLD_NUMBERS_AM_PM));
  text_layer_set_text_alignment(s_time_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_time_layer, GColorClear);
  text_layer_set_text_color(s_time_layer, GColorWhite);
  layer_add_child(window_layer, text_layer_get_layer(s_time_layer));
  y += 62;

  s_date_layer = text_layer_create(GRect(4, y, w - 8, bounds.size.h - y));
  text_layer_set_font(s_date_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_alignment(s_date_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_date_layer, GColorClear);
  text_layer_set_text_color(s_date_layer, GColorWhite);
  layer_add_child(window_layer, text_layer_get_layer(s_date_layer));
#endif

  // Covers everything else while the alarm rings, so it goes on last
  prv_bell_create(window_layer, bounds);

  // Initial render
  ui_main_update(sleep_engine_get_session());
  ui_main_refresh_clock();
}

static void prv_window_unload(Window *window) {
  prv_bell_destroy();
  text_layer_destroy(s_header_layer);
  text_layer_destroy(s_stage_layer);
  text_layer_destroy(s_duration_layer);
  text_layer_destroy(s_alarm_layer);
  text_layer_destroy(s_countdown_layer);
  s_countdown_layer = NULL;
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
