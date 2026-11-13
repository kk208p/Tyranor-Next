/* AdvHD runtime C ABI v2 — vendored from advhd-rs include/advhd.h.
 * Source: https://github.com/kk208p/advhd-rs (MPL-2.0), ABI version 2.
 * Keep the exported symbol set in sync with the .so when updating it.
 *
 * Threading: a runtime handle is single-owner. Serialize calls from one
 * engine thread; never destroy while another call is in flight.
 */
#ifndef ADVHD_ABI_H
#define ADVHD_ABI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ADVHD_ABI_VERSION 2

#define ADVHD_EVENT_NONE 0
#define ADVHD_EVENT_SAY 1
#define ADVHD_EVENT_CHOICE 2
#define ADVHD_EVENT_AUDIO 3
#define ADVHD_EVENT_UI 4
#define ADVHD_EVENT_MESSAGE 5
#define ADVHD_EVENT_END 6
#define ADVHD_EVENT_ERROR 7
#define ADVHD_EVENT_MOVIE 8

/* Movie codes carried in AdvhdEventV2.code. */
#define ADVHD_MOVIE_PLAY_WAIT 1
#define ADVHD_MOVIE_PLAY 2
#define ADVHD_MOVIE_STOP 3

#define ADVHD_AUDIO_MUSIC 1
#define ADVHD_AUDIO_SOUND 2
#define ADVHD_AUDIO_STOP_MUSIC 3
#define ADVHD_AUDIO_STOP_SOUND 4
#define ADVHD_AUDIO_STOP_VOICE 5

typedef struct AdvhdRuntime AdvhdRuntime;

typedef struct AdvhdEventV2 {
    uint32_t struct_size;
    int32_t kind;
    int32_t code;
    int32_t choice_count;
    int32_t repeats;
    int32_t is_voice;
    float fade_in;
    float fade_out;
    float volume;
    int32_t message_visible;
} AdvhdEventV2;

AdvhdRuntime *advhd_create_ex(const char *dir_utf8, const char *entry_utf8,
                              int32_t encoding, const char *font_path_utf8,
                              uint32_t font_index, char *err, int32_t err_cap);
AdvhdRuntime *advhd_create(const char *dir_utf8);
AdvhdRuntime *advhd_create_with_entry(const char *dir_utf8, const char *entry_utf8);
void advhd_destroy(AdvhdRuntime *rt);

int32_t advhd_abi_version(void);
uint32_t advhd_frame_width(void);
uint32_t advhd_frame_height(void);

int32_t advhd_set_font(AdvhdRuntime *rt, const char *path_utf8, uint32_t font_index);
int32_t advhd_load_translation(AdvhdRuntime *rt, const char *path_utf8);
/* Typewriter / auto-advance timers. tick returns TICK_TEXT_DONE=1 and
 * TICK_AUTO_DUE=2 bits; the host calls advhd_advance on AUTO_DUE. */
uint32_t advhd_tick(AdvhdRuntime *rt, uint32_t delta_ms);
void advhd_set_text_speed(AdvhdRuntime *rt, float cps);
void advhd_set_auto(AdvhdRuntime *rt, int32_t enabled, uint32_t delay_ms);
/* Lua UI instrumented trace (Lua-enabled .so variants; -1 when inactive). */
int32_t advhd_lua_trace(AdvhdRuntime *rt, char *buf, int32_t cap);
/* Lua UI probe: boot the host (idempotent), evaluate code, return trace
 * length (-1 on boot failure). */
int32_t advhd_lua_probe(AdvhdRuntime *rt, const char *code_utf8);
int32_t advhd_advance(AdvhdRuntime *rt);
int32_t advhd_poll_event(AdvhdRuntime *rt, AdvhdEventV2 *out);
int32_t advhd_choose(AdvhdRuntime *rt, int32_t index);
int32_t advhd_choice_count(AdvhdRuntime *rt);
int32_t advhd_choice_text(AdvhdRuntime *rt, int32_t index, char *buf, int32_t cap);
int32_t advhd_choice_id(AdvhdRuntime *rt, int32_t index);
int32_t advhd_event_text(AdvhdRuntime *rt, char *buf, int32_t cap);
int32_t advhd_event_name(AdvhdRuntime *rt, char *buf, int32_t cap);
int32_t advhd_event_voice(AdvhdRuntime *rt, char *buf, int32_t cap);
int32_t advhd_last_error(AdvhdRuntime *rt, char *buf, int32_t cap);
int32_t advhd_message_visible(AdvhdRuntime *rt);
int32_t advhd_history_count(AdvhdRuntime *rt);
int32_t advhd_history_text(AdvhdRuntime *rt, int32_t index, char *buf, int32_t cap);
int32_t advhd_debug_state(AdvhdRuntime *rt, char *buf, int32_t cap);
int32_t advhd_frame(AdvhdRuntime *rt, uint8_t *buf, uint32_t w, uint32_t h, uint32_t stride);
void advhd_invalidate(AdvhdRuntime *rt);
void advhd_set_choice_overlay(AdvhdRuntime *rt, int32_t enabled);
int32_t advhd_is_dirty(AdvhdRuntime *rt);
int32_t advhd_extract_asset(AdvhdRuntime *rt, const char *name_utf8, const char *dest_path_utf8);
int32_t advhd_save(AdvhdRuntime *rt, const char *path_utf8);
int32_t advhd_load(AdvhdRuntime *rt, const char *path_utf8);

#ifdef __cplusplus
}
#endif

#endif /* ADVHD_ABI_H */
