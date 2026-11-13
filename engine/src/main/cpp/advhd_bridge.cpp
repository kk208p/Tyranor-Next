// JNI bridge for the advhd-rs C ABI v2.
//
// The bridge owns the Android surface and a reusable RGBA buffer; the Rust
// runtime stays platform-agnostic. All calls are expected from one engine
// thread (AdvhdActivity), but the map is mutex-protected so a late destroy
// from the UI thread cannot corrupt state.
#include <jni.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "advhd_abi.h"

namespace {
void* library = nullptr;
std::mutex mutex;
std::string lastCreateError;
uint64_t nextToken = 1;

#define ADVHD_API_LIST(X) \
    X(abi_version) X(frame_width) X(frame_height) X(create_ex) X(destroy) \
    X(set_font) X(load_translation) X(tick) X(set_text_speed) X(set_auto) \
    X(advance) X(poll_event) X(choose) X(choice_count) X(choice_text) \
    X(choice_id) X(event_text) X(event_name) X(event_voice) X(last_error) \
    X(message_visible) X(history_count) X(history_text) X(frame) X(extract_asset) \
    X(save) X(load) X(is_dirty) X(invalidate) X(set_choice_overlay)

#define DECLARE(name) decltype(&advhd_##name) api_##name = nullptr;
ADVHD_API_LIST(DECLARE)
#undef DECLARE

struct Session {
    AdvhdRuntime* rt = nullptr;
    ANativeWindow* window = nullptr;
    std::vector<uint8_t> pixels;
};

std::unordered_map<uint64_t, Session> sessions;

bool loadLibrary() {
    if (library) return true;
    void* candidate = dlopen("libadvhd_abi.so", RTLD_NOW | RTLD_LOCAL);
    if (!candidate) return false;
    bool valid = true;
#define RESOLVE(name) api_##name = reinterpret_cast<decltype(api_##name)>(dlsym(candidate, "advhd_" #name)); valid = valid && api_##name != nullptr;
    ADVHD_API_LIST(RESOLVE)
#undef RESOLVE
    if (!valid || api_abi_version() != ADVHD_ABI_VERSION) {
        dlclose(candidate);
        return false;
    }
    library = candidate;
    return true;
}

Session* session(jlong token) {
    auto it = sessions.find(static_cast<uint64_t>(token));
    return it == sessions.end() ? nullptr : &it->second;
}

bool utf8(JNIEnv* env, jstring value, std::string& out, bool required) {
    if (!value) {
        return !required;
    }
    jsize length = env->GetStringLength(value);
    if (length > 8192) return false;
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return false;
    bool valid = true;
    out.clear();
    out.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp == 0) { valid = false; break; }
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (++i >= length || chars[i] < 0xDC00 || chars[i] > 0xDFFF) { valid = false; break; }
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i] - 0xDC00);
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            valid = false;
            break;
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 63)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63)));
            out.push_back(static_cast<char>(0x80 | (cp & 63)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 63)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63)));
            out.push_back(static_cast<char>(0x80 | (cp & 63)));
        }
    }
    env->ReleaseStringChars(value, chars);
    if (!valid || out.size() > 16384) return false;
    if (required && out.empty()) return false;
    return true;
}

jstring toJava(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

/** Read a C string produced by the runtime into a Java string. */
jstring readString(JNIEnv* env, jlong token, int32_t (*reader)(AdvhdRuntime*, char*, int32_t)) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (!s || !reader) return env->NewStringUTF("");
    std::vector<char> buf(16384);
    int32_t n = reader(s->rt, buf.data(), static_cast<int32_t>(buf.size()));
    if (n < 0) return env->NewStringUTF("");
    buf.back() = 0;
    return env->NewStringUTF(buf.data());
}

jlong create(JNIEnv* env, jclass, jstring root, jstring entry, jint encoding,
             jstring font, jint fontIndex) {
    std::lock_guard<std::mutex> guard(mutex);
    if (!loadLibrary()) {
        lastCreateError = "libadvhd_abi.so unavailable or ABI mismatch";
        return 0;
    }
    std::string r, e, f;
    if (!utf8(env, root, r, true) || !utf8(env, entry, e, false) || !utf8(env, font, f, false)) {
        lastCreateError = "invalid UTF-16 path/text";
        return 0;
    }
    std::vector<char> err(2048, 0);
    AdvhdRuntime* rt = api_create_ex(
        r.c_str(),
        e.empty() ? nullptr : e.c_str(),
        encoding,
        f.empty() ? nullptr : f.c_str(),
        static_cast<uint32_t>(fontIndex),
        err.data(),
        static_cast<int32_t>(err.size()));
    if (!rt) {
        lastCreateError = err.data();
        return 0;
    }
    if (nextToken == 0) {
        api_destroy(rt);
        lastCreateError = "handle budget exhausted";
        return 0;
    }
    uint64_t token = nextToken++;
    sessions.emplace(token, Session{rt, nullptr, {}});
    lastCreateError.clear();
    return static_cast<jlong>(token);
}

jstring createError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(mutex);
    return env->NewStringUTF(lastCreateError.c_str());
}

void destroy(JNIEnv*, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex);
    auto it = sessions.find(static_cast<uint64_t>(token));
    if (it == sessions.end()) return;
    if (it->second.window) ANativeWindow_release(it->second.window);
    api_destroy(it->second.rt);
    sessions.erase(it);
}

jint loadTranslation(JNIEnv* env, jclass, jlong token, jstring path) {
    std::string p;
    if (!utf8(env, path, p, true)) return -1;
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_load_translation(s->rt, p.c_str()) : -1;
}

jint tick(JNIEnv*, jclass, jlong token, jint deltaMs) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? static_cast<jint>(api_tick(s->rt, static_cast<uint32_t>(std::max(0, deltaMs)))) : 0;
}

void setTextSpeed(JNIEnv*, jclass, jlong token, jfloat cps) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (s) api_set_text_speed(s->rt, cps);
}

void setAuto(JNIEnv*, jclass, jlong token, jint enabled, jint delayMs) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (s) api_set_auto(s->rt, enabled, static_cast<uint32_t>(std::max(0, delayMs)));
}

jint advance(JNIEnv*, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_advance(s->rt) : ADVHD_EVENT_ERROR;
}

jint pollEvent(JNIEnv* env, jclass, jlong token, jlongArray out) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (!s || !out || env->GetArrayLength(out) < 9) return ADVHD_EVENT_NONE;
    AdvhdEventV2 ev{};
    ev.struct_size = sizeof(ev);
    jint kind = api_poll_event(s->rt, &ev);
    jlong values[9];
    values[0] = ev.kind;
    values[1] = ev.code;
    values[2] = ev.choice_count;
    values[3] = ev.repeats;
    values[4] = ev.is_voice;
    float fadeIn = ev.fade_in;
    float fadeOut = ev.fade_out;
    float volume = ev.volume;
    int32_t bitsIn, bitsOut, bitsVol;
    std::memcpy(&bitsIn, &fadeIn, sizeof(bitsIn));
    std::memcpy(&bitsOut, &fadeOut, sizeof(bitsOut));
    std::memcpy(&bitsVol, &volume, sizeof(bitsVol));
    values[5] = bitsIn;
    values[6] = bitsOut;
    values[7] = bitsVol;
    values[8] = ev.message_visible;
    env->SetLongArrayRegion(out, 0, 9, values);
    return kind;
}

jint choiceCount(JNIEnv*, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_choice_count(s->rt) : 0;
}

jstring choiceText(JNIEnv* env, jclass, jlong token, jint index) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (!s) return env->NewStringUTF("");
    std::vector<char> buf(8192, 0);
    if (api_choice_text(s->rt, index, buf.data(), static_cast<int32_t>(buf.size())) < 0) {
        return env->NewStringUTF("");
    }
    buf.back() = 0;
    return env->NewStringUTF(buf.data());
}

jint choiceId(JNIEnv*, jclass, jlong token, jint index) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_choice_id(s->rt, index) : -1;
}

jint choose(JNIEnv*, jclass, jlong token, jint index) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_choose(s->rt, index) : -1;
}

jstring eventText(JNIEnv* env, jclass, jlong token) { return readString(env, token, api_event_text); }
jstring eventName(JNIEnv* env, jclass, jlong token) { return readString(env, token, api_event_name); }
jstring eventVoice(JNIEnv* env, jclass, jlong token) { return readString(env, token, api_event_voice); }
jstring lastError(JNIEnv* env, jclass, jlong token) { return readString(env, token, api_last_error); }
jstring historyText(JNIEnv* env, jclass, jlong token, jint index) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (!s) return env->NewStringUTF("");
    std::vector<char> buf(8192, 0);
    if (api_history_text(s->rt, index, buf.data(), static_cast<int32_t>(buf.size())) < 0) {
        return env->NewStringUTF("");
    }
    buf.back() = 0;
    return env->NewStringUTF(buf.data());
}

jint messageVisible(JNIEnv*, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_message_visible(s->rt) : 0;
}

void setChoiceOverlay(JNIEnv*, jclass, jlong token, jint enabled) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (s) api_set_choice_overlay(s->rt, enabled);
}

jint historyCount(JNIEnv*, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_history_count(s->rt) : 0;
}

jint save(JNIEnv* env, jclass, jlong token, jstring path) {
    std::string p;
    if (!utf8(env, path, p, true)) return -1;
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_save(s->rt, p.c_str()) : -1;
}

jint load(JNIEnv* env, jclass, jlong token, jstring path) {
    std::string p;
    if (!utf8(env, path, p, true)) return -1;
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_load(s->rt, p.c_str()) : -1;
}

jint extractAsset(JNIEnv* env, jclass, jlong token, jstring name, jstring dest) {
    std::string n, d;
    if (!utf8(env, name, n, true) || !utf8(env, dest, d, true)) return -1;
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    return s ? api_extract_asset(s->rt, n.c_str(), d.c_str()) : -1;
}

void setSurface(JNIEnv* env, jclass, jlong token, jobject surface) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (!s) return;
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (s->window) ANativeWindow_release(s->window);
    s->window = window;
    api_invalidate(s->rt);
}

void clearSurface(JNIEnv*, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (!s) return;
    if (s->window) ANativeWindow_release(s->window);
    s->window = nullptr;
}

jint frame(JNIEnv*, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(token);
    if (!s || !s->window) return -1;
    if (api_is_dirty(s->rt) == 0) return 0;
    const uint32_t w = api_frame_width();
    const uint32_t h = api_frame_height();
    const size_t stride = static_cast<size_t>(w) * 4;
    if (s->pixels.size() != stride * h) s->pixels.resize(stride * h);
    if (api_frame(s->rt, s->pixels.data(), w, h, static_cast<uint32_t>(stride)) != 0) return -1;

    // Always present 32-bit RGBA; a Surface may otherwise default to RGB_565,
    // where writing four bytes per pixel runs off the end of the buffer.
    ANativeWindow_setBuffersGeometry(s->window, 0, 0, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(s->window, &buffer, nullptr) != 0) return -1;
    if (buffer.format != WINDOW_FORMAT_RGBA_8888 && buffer.format != WINDOW_FORMAT_RGBX_8888) {
        ANativeWindow_unlockAndPost(s->window);
        return -1;
    }
    auto* dst = static_cast<uint8_t*>(buffer.bits);
    const uint32_t dw = static_cast<uint32_t>(buffer.width);
    const uint32_t dh = static_cast<uint32_t>(buffer.height);
    const uint32_t dst_stride = static_cast<uint32_t>(buffer.stride);
    if (dst == nullptr || dw == 0 || dh == 0) {
        ANativeWindow_unlockAndPost(s->window);
        return -1;
    }
    // Preserve the 16:9 frame inside arbitrary screen aspect ratios with
    // black bars; nearest-neighbour is fine for an ADV at native 1280x720.
    const float scale = std::min(static_cast<float>(dw) / w, static_cast<float>(dh) / h);
    const uint32_t out_w = std::max(1u, static_cast<uint32_t>(w * scale));
    const uint32_t out_h = std::max(1u, static_cast<uint32_t>(h * scale));
    const uint32_t off_x = (dw - out_w) / 2;
    const uint32_t off_y = (dh - out_h) / 2;
    std::memset(dst, 0, static_cast<size_t>(dst_stride) * dh * 4);
    for (uint32_t y = 0; y < out_h; ++y) {
        const uint32_t sy = static_cast<uint32_t>(static_cast<uint64_t>(y) * h / out_h);
        const uint32_t* src_row = reinterpret_cast<const uint32_t*>(
            s->pixels.data() + static_cast<size_t>(sy) * stride);
        uint32_t* dst_row = reinterpret_cast<uint32_t*>(
            dst + static_cast<size_t>(off_y + y) * dst_stride * 4) + off_x;
        for (uint32_t x = 0; x < out_w; ++x) {
            const uint32_t sx = static_cast<uint32_t>(static_cast<uint64_t>(x) * w / out_w);
            dst_row[x] = src_row[sx];
        }
    }
    ANativeWindow_unlockAndPost(s->window);
    return 0;
}

jint nativeWidth(JNIEnv*, jclass) { return static_cast<jint>(api_frame_width()); }
jint nativeHeight(JNIEnv*, jclass) { return static_cast<jint>(api_frame_height()); }

JNINativeMethod methods[] = {
    {"create", "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;I)J", reinterpret_cast<void*>(create)},
    {"lastCreateError", "()Ljava/lang/String;", reinterpret_cast<void*>(createError)},
    {"loadTranslation", "(JLjava/lang/String;)I", reinterpret_cast<void*>(loadTranslation)},
    {"tick", "(JI)I", reinterpret_cast<void*>(tick)},
    {"setTextSpeed", "(JF)V", reinterpret_cast<void*>(setTextSpeed)},
    {"setAuto", "(JII)V", reinterpret_cast<void*>(setAuto)},
    {"destroy", "(J)V", reinterpret_cast<void*>(destroy)},
    {"advance", "(J)I", reinterpret_cast<void*>(advance)},
    {"pollEvent", "(J[J)I", reinterpret_cast<void*>(pollEvent)},
    {"choiceCount", "(J)I", reinterpret_cast<void*>(choiceCount)},
    {"choiceText", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(choiceText)},
    {"choiceId", "(JI)I", reinterpret_cast<void*>(choiceId)},
    {"choose", "(JI)I", reinterpret_cast<void*>(choose)},
    {"eventText", "(J)Ljava/lang/String;", reinterpret_cast<void*>(eventText)},
    {"eventName", "(J)Ljava/lang/String;", reinterpret_cast<void*>(eventName)},
    {"eventVoice", "(J)Ljava/lang/String;", reinterpret_cast<void*>(eventVoice)},
    {"lastError", "(J)Ljava/lang/String;", reinterpret_cast<void*>(lastError)},
    {"messageVisible", "(J)I", reinterpret_cast<void*>(messageVisible)},
    {"setChoiceOverlay", "(JI)V", reinterpret_cast<void*>(setChoiceOverlay)},
    {"historyCount", "(J)I", reinterpret_cast<void*>(historyCount)},
    {"historyText", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(historyText)},
    {"save", "(JLjava/lang/String;)I", reinterpret_cast<void*>(save)},
    {"load", "(JLjava/lang/String;)I", reinterpret_cast<void*>(load)},
    {"extractAsset", "(JLjava/lang/String;Ljava/lang/String;)I", reinterpret_cast<void*>(extractAsset)},
    {"setSurface", "(JLandroid/view/Surface;)V", reinterpret_cast<void*>(setSurface)},
    {"clearSurface", "(J)V", reinterpret_cast<void*>(clearSurface)},
    {"frame", "(J)I", reinterpret_cast<void*>(frame)},
    {"nativeWidth", "()I", reinterpret_cast<void*>(nativeWidth)},
    {"nativeHeight", "()I", reinterpret_cast<void*>(nativeHeight)},
};
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("com/core/advhd/NativeAdvhd");
    if (!cls) return JNI_ERR;
    jint result = env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0]));
    env->DeleteLocalRef(cls);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    std::lock_guard<std::mutex> guard(mutex);
    for (auto& entry : sessions) {
        if (entry.second.window) ANativeWindow_release(entry.second.window);
        api_destroy(entry.second.rt);
    }
    sessions.clear();
    if (library) {
        dlclose(library);
        library = nullptr;
    }
}
