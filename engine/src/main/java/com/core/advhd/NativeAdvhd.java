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
    static native void destroy(long handle);

    static native int advance(long handle);
    /** out = [kind, code, choiceCount, repeats, isVoice, fadeIn(bits), fadeOut(bits), volume(bits), messageVisible]. */
    static native int pollEvent(long handle, long[] out);
    static native int choiceCount(long handle);
    static native String choiceText(long handle, int index);
    static native int choiceId(long handle, int index);
    static native int choose(long handle, int index);

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

    static native int nativeWidth();
    static native int nativeHeight();

    static {
        System.loadLibrary("advhd_bridge");
    }
}
