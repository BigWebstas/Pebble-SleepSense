#include "ui_alarm_edit.h"
#include "ui_main.h"
#include "../comm/comm.h"
#include "../engine/sleep_engine.h"
#include "../engine/smart_alarm.h"

#define REPEAT_MS 100

static Window *s_window;
static Layer *s_layer;
static uint8_t s_hour;
static uint8_t s_min;
static bool s_editing_min;

static void prv_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  GFont font = fonts_get_system_font(FONT_KEY_BITHAM_34_MEDIUM_NUMBERS);
  int16_t field_w = 56;
  int16_t gap = 16; // room for the colon
  int16_t left = (bounds.size.w - 2 * field_w - gap) / 2;
  int16_t top = bounds.size.h / 2 - 24;

  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, "Alarm time", fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
                     GRect(0, top - 40, bounds.size.w, 30),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);

  // The field being changed is underlined
  GRect hour_box = GRect(left, top, field_w, 40);
  GRect min_box = GRect(left + field_w + gap, top, field_w, 40);
  char buf[4]; // two digits, with room for the compiler's worst case
  snprintf(buf, sizeof(buf), "%02d", s_hour);
  graphics_draw_text(ctx, buf, font, hour_box, GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  snprintf(buf, sizeof(buf), "%02d", s_min);
  graphics_draw_text(ctx, buf, font, min_box, GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  graphics_draw_text(ctx, ":", font, GRect(left + field_w, top - 2, gap, 40),
                     GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  GRect active = s_editing_min ? min_box : hour_box;
  graphics_context_set_fill_color(ctx, PBL_IF_COLOR_ELSE(GColorYellow, GColorWhite));
  graphics_fill_rect(ctx, GRect(active.origin.x + 4, active.origin.y + 42, active.size.w - 8, 3), 0, GCornerNone);

  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, s_editing_min ? "UP/DOWN minute  SEL save" : "UP/DOWN hour  SEL next",
                     fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD),
                     GRect(0, bounds.size.h - PBL_IF_ROUND_ELSE(40, 24), bounds.size.w, 18),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

static void prv_step(int8_t delta) {
  if (s_editing_min) {
    s_min = (s_min + 60 + delta) % 60;
  } else {
    s_hour = (s_hour + 24 + delta) % 24;
  }
  layer_mark_dirty(s_layer);
}

static void prv_up_handler(ClickRecognizerRef recognizer, void *context) {
  prv_step(1);
}

static void prv_down_handler(ClickRecognizerRef recognizer, void *context) {
  prv_step(-1);
}

static void prv_select_handler(ClickRecognizerRef recognizer, void *context) {
  if (!s_editing_min) {
    s_editing_min = true;
    layer_mark_dirty(s_layer);
    return;
  }
  smart_alarm_set_target(s_hour, s_min);
  if (!smart_alarm_get_settings()->enabled) {
    smart_alarm_toggle(); // choosing a time means the alarm is wanted
  }
  comm_send_session_update(sleep_engine_get_session()); // the phone learns the new time at once
  ui_main_update(sleep_engine_get_session());
  window_stack_remove(s_window, true);
}

static void prv_click_config_provider(void *context) {
  window_single_repeating_click_subscribe(BUTTON_ID_UP, REPEAT_MS, prv_up_handler);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, REPEAT_MS, prv_down_handler);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_handler);
}

static void prv_window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  s_layer = layer_create(layer_get_bounds(window_layer));
  layer_set_update_proc(s_layer, prv_update_proc);
  layer_add_child(window_layer, s_layer);
}

static void prv_window_unload(Window *window) {
  layer_destroy(s_layer);
  s_layer = NULL;
  window_destroy(s_window); // one-off screen: built for each edit
  s_window = NULL;
}

void ui_alarm_edit_show(void) {
  if (s_window) {
    return;
  }
  const SmartAlarmSettings *alarm = smart_alarm_get_settings();
  s_hour = alarm->target_hour;
  s_min = alarm->target_min;
  s_editing_min = false;
  s_window = window_create();
  window_set_background_color(s_window, PBL_IF_COLOR_ELSE(GColorOxfordBlue, GColorBlack));
  window_set_click_config_provider(s_window, prv_click_config_provider);
  window_set_window_handlers(s_window, (WindowHandlers){ .load = prv_window_load, .unload = prv_window_unload });
  window_stack_push(s_window, true);
}
