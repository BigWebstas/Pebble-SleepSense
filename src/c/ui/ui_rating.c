#include "ui_rating.h"
#include "../comm/comm.h"

#define MAX_STARS 5
#define DEFAULT_STARS 3
#define STAR_POINTS 10

static Window *s_window;
static Layer *s_layer;
static GPath *s_star_path;
static uint8_t s_stars;

static GPoint s_star_points[STAR_POINTS];
static GPathInfo s_star_info = { .num_points = STAR_POINTS, .points = s_star_points };

//! A five-pointed star around (0, 0), point up: outer and inner corners alternate every 36 degrees
static void prv_build_star(int16_t radius) {
  for (int i = 0; i < STAR_POINTS; i++) {
    int32_t angle = TRIG_MAX_ANGLE * i / STAR_POINTS;
    int16_t r = (i % 2 == 0) ? radius : radius * 40 / 100;
    s_star_points[i] = GPoint(r * sin_lookup(angle) / TRIG_MAX_RATIO, -r * cos_lookup(angle) / TRIG_MAX_RATIO);
  }
}

static void prv_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, "How did you sleep?", fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
                     GRect(0, bounds.size.h / 2 - 66, bounds.size.w, 30),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);

  int16_t step = bounds.size.w / MAX_STARS;
  for (int i = 0; i < MAX_STARS; i++) {
    gpath_move_to(s_star_path, GPoint(step * i + step / 2, bounds.size.h / 2));
    if (i < s_stars) {
      graphics_context_set_fill_color(ctx, PBL_IF_COLOR_ELSE(GColorYellow, GColorWhite));
      gpath_draw_filled(ctx, s_star_path);
    }
    graphics_context_set_stroke_color(ctx, PBL_IF_COLOR_ELSE(GColorChromeYellow, GColorWhite));
    gpath_draw_outline(ctx, s_star_path);
  }

  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, "UP/DOWN rate  SEL save", fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD),
                     GRect(0, bounds.size.h - PBL_IF_ROUND_ELSE(40, 24), bounds.size.w, 18),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

static void prv_up_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_stars < MAX_STARS) {
    s_stars++;
    layer_mark_dirty(s_layer);
  }
}

static void prv_down_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_stars > 1) {
    s_stars--;
    layer_mark_dirty(s_layer);
  }
}

static void prv_select_handler(ClickRecognizerRef recognizer, void *context) {
  comm_send_rating(s_stars);
  window_stack_remove(s_window, true);
}

static void prv_click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_UP, prv_up_handler);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_down_handler);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_handler);
}

static void prv_window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);
  prv_build_star(bounds.size.w / MAX_STARS * 40 / 100);
  s_star_path = gpath_create(&s_star_info);
  s_layer = layer_create(bounds);
  layer_set_update_proc(s_layer, prv_update_proc);
  layer_add_child(window_layer, s_layer);
}

static void prv_window_unload(Window *window) {
  layer_destroy(s_layer);
  gpath_destroy(s_star_path);
  s_layer = NULL;
  s_star_path = NULL;
  window_destroy(s_window); // one-off screen: built for each night
  s_window = NULL;
}

void ui_rating_show(void) {
  if (s_window) {
    return;
  }
  s_stars = DEFAULT_STARS;
  s_window = window_create();
  window_set_background_color(s_window, PBL_IF_COLOR_ELSE(GColorOxfordBlue, GColorBlack));
  window_set_click_config_provider(s_window, prv_click_config_provider);
  window_set_window_handlers(s_window, (WindowHandlers){ .load = prv_window_load, .unload = prv_window_unload });
  window_stack_push(s_window, true);
}
