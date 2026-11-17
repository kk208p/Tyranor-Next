package com.core.advhd;

import android.view.Surface;

/**
 * JNI transport only. Every call, including loading, belongs to the AdvHD
 * engine thread owned by {@link AdvhdActivity}. Handles are process-local
 * tokens, never native addresses.
 */
public final class NativeAdvhd {
    private NativeAdvhd() {}

    static native long create(String resourceRoot, String entry, int encoding,
                              String fontPath, int fontIndex);
    static native String lastCreateError();
    /** Load a community translation overlay (JSON name/choice/line maps). */
    static native int loadTranslation(long handle, String path);
    /** Typewriter/auto timers; returns TICK_TEXT_DONE=1 / TICK_AUTO_DUE=2 bits. */
    static native int tick(long handle, int deltaMs);
    static native void setTextSpeed(long handle, float cps);
    static native void setAuto(long handle, int enabled, int delayMs);
    /** Skip mode 0/1/2; EVENT_SKIPSTOP (=9) reports the script's own stop. */
    static native void setSkip(long handle, int mode);
    static native int getSkip(long handle);
    /** Diagnostic: 1 = renderer has a usable font. */
    static native int fontOk(long handle);
    /** Instrumented Lua UI trace; "" when the runtime has no Lua session. */
    static native String luaTrace(long handle);
    /** Debug probe: boot the Lua host and evaluate a snippet. */
    static native int luaProbe(long handle, String code);
    static native void destroy(long handle);

    static native int advance(long handle);
    /** out = [kind, code, choiceCount, repeats, isVoice, fadeIn(bits), fadeOut(bits), volume(bits), messageVisible]. */
    static native int pollEvent(long handle, long[] out);
    static native int choiceCount(long handle);
    static native String choiceText(long handle, int index);
    static native int choiceId(long handle, int index);
    static native int choose(long handle, int index);
    /** Lua UI pointer input (UI-space coords after bridge letterbox inverse).
     * action 0=move 1=press 2=release; returns the hit chroma part (0=none). */
    static native int pointer(long handle, float x, float y, int action);

    static native String eventText(long handle);
    static native String eventName(long handle);
    static native String eventVoice(long handle);
    static native String lastError(long handle);
    static native int messageVisible(long handle);
    static native void setChoiceOverlay(long handle, int enabled);
    static native int historyCount(long handle);
    static native String historyText(long handle, int index);

    static native int save(long handle, String path);
    static native int load(long handle, String path);
    static native int extractAsset(long handle, String name, String destPath);

    static native void setSurface(long handle, Surface surface);
    static native void clearSurface(long handle);
    static native int frame(long handle);
    /** Current frame as ARGB color ints (w*nativeHeight()), null on failure;
     * for slot thumbnails (save-slot picker / save management). */
    static native int[] renderFrame(long handle);

    static native int nativeWidth();
    static native int nativeHeight();

    static {
        System.loadLibrary("advhd_bridge");
    }
}
