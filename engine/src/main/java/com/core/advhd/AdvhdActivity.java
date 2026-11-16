package com.core.advhd;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.core.engine.EngineSessionRegistry;
import com.core.engine.LaunchContract;
import com.core.engine.R;
import com.core.nativeplugin.IjkRuntime;
import com.core.ons.OnsIjkVideoView;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fullscreen AdvHD host. Native VM/rendering run on one engine thread; the UI
 * thread only presents surfaces, dialogs and audio.
 */
public final class AdvhdActivity extends AppCompatActivity
        implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private static final int EVENT_SAY = 1;
    private static final int EVENT_CHOICE = 2;
    private static final int EVENT_AUDIO = 3;
    private static final int EVENT_UI = 4;
    private static final int EVENT_MESSAGE = 5;
    private static final int EVENT_END = 6;
    private static final int EVENT_ERROR = 7;
    private static final int EVENT_MOVIE = 8;
    private static final int MOVIE_PLAY_WAIT = 1;
    private static final int MOVIE_PLAY = 2;
    private static final int MOVIE_STOP = 3;
    private static final int AUDIO_MUSIC = 1;
    private static final int AUDIO_SOUND = 2;
    private static final int AUDIO_STOP_MUSIC = 3;
    private static final int AUDIO_STOP_SOUND = 4;
    private static final int AUDIO_STOP_VOICE = 5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean framePending = new AtomicBoolean(false);
    private final List<MediaPlayer> effects = new ArrayList<>();
    private ExecutorService engine;
    private volatile long handle;
    private Payload payload;
    private SurfaceView surface;
    private FrameLayout root;
    private TextView loading;
    private AlertDialog dialog;
    private MediaPlayer bgm;
    private MediaPlayer voice;
    private FrameLayout movieContainer;
    private OnsIjkVideoView movieView;
    private boolean movieWait, moviePlaying;
    private String bgmPath;
    private boolean bgmPrepared;
    private boolean bgmWanted, resumed, destroyed, surfaceReady, frameScheduled;
    private boolean choicePending, processVerified;
    private long firstFrameLogged;
    private long lastFrameNanos;
    /** Last forwarded pointer position (MOVE throttle). */
    private float lastPointerX, lastPointerY;
    private boolean autoMode;
    /** Engine-thread frame counter: pump heartbeat + Lua trace cadence. */
    private long frameCount;
    private int lastTraceLen = -1;
    /** Last target volume applied to the BGM player (for fade-out ramps). */
    private float bgmVolume = 1f;
    /** advhd_tick flag: the auto-advance delay elapsed; host must advance. */
    private static final int TICK_AUTO_DUE = 2;

    private static final class Payload {
        final String identity, root, user, font, nls, entry, translation;

        Payload(Intent intent, File filesDir) throws IOException {
            if (intent == null || !LaunchContract.LAUNCH_MODE_ADVHD.equals(
                    intent.getStringExtra(LaunchContract.LAUNCH_MODE))) {
                throw new IllegalArgumentException("mode");
            }
            identity = required(intent, LaunchContract.ROOT_URI);
            root = required(intent, LaunchContract.PATH);
            user = required(intent, LaunchContract.ADVHD_USER_DATA_DIR);
            AdvhdPaths.validateUserDataDir(filesDir, identity, user);
            String selected = intent.getStringExtra(LaunchContract.ADVHD_NLS);
            nls = selected == null ? "sjis" : selected;
            if (!nls.equals("sjis") && !nls.equals("gbk") && !nls.equals("big5")
                    && !nls.equals("utf8") && !nls.equals("utf16le")) {
                throw new IllegalArgumentException("encoding");
            }
            String selectedFont = intent.getStringExtra(LaunchContract.ADVHD_FONT_PATH);
            font = selectedFont == null ? "" : selectedFont;
            if (font.length() > 8192 || font.indexOf('\0') >= 0) throw new IllegalArgumentException("font");
            String selectedEntry = intent.getStringExtra(LaunchContract.ADVHD_ENTRY);
            entry = selectedEntry == null ? "" : selectedEntry.trim().toLowerCase(java.util.Locale.ROOT);
            if (entry.length() > 128 || !entry.matches("[a-z0-9_.\\-]*")) {
                throw new IllegalArgumentException("entry");
            }
            String selectedTranslation = intent.getStringExtra(LaunchContract.ADVHD_TRANSLATION);
            translation = selectedTranslation == null ? "" : selectedTranslation;
            if (!translation.isEmpty()) {
                File root = new File(new File(filesDir, "advhd"), "translations").getCanonicalFile();
                File file = new File(translation).getCanonicalFile();
                if (!file.toPath().startsWith(root.toPath()) || !file.isFile() || !file.canRead()) {
                    throw new IllegalArgumentException("translation");
                }
            }
        }

        private static String required(Intent intent, String name) {
            String value = intent.getStringExtra(name);
            if (value == null || value.isEmpty() || value.length() > 8192 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(name);
            }
            return value;
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        processVerified = isOwnEngineProcess();
        if (!processVerified) {
            finish();
            return;
        }
        try {
            payload = new Payload(getIntent(), getFilesDir());
        } catch (RuntimeException | IOException e) {
            toast(R.string.engine_advhd_invalid_config);
            finish();
            return;
        }
        File userDir = new File(payload.user);
        if (!userDir.isDirectory() && !userDir.mkdirs()) {
            toast(R.string.engine_advhd_user_dir_failed);
            finish();
            return;
        }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.setKeepScreenOn(true);
        surface.setFocusableInTouchMode(true);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        loading = new TextView(this);
        loading.setText(text(R.string.engine_advhd_loading));
        loading.setTextColor(Color.WHITE);
        loading.setTextSize(14);
        loading.setGravity(android.view.Gravity.CENTER);
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        // Visible menu affordance: ADV titles have no native touch UI here, and
        // relying on the system back gesture alone is not discoverable.
        TextView menuButton = new TextView(this);
        menuButton.setText("≡");
        menuButton.setTextSize(24);
        menuButton.setTextColor(Color.WHITE);
        menuButton.setGravity(android.view.Gravity.CENTER);
        menuButton.setContentDescription(text(R.string.engine_advhd_menu));
        GradientDrawable menuBackground = new GradientDrawable();
        menuBackground.setColor(0x66000000);
        menuBackground.setCornerRadius(8 * getResources().getDisplayMetrics().density);
        menuButton.setBackground(menuBackground);
        menuButton.setOnClickListener(v -> showMenu());
        // Debug affordance: long-press runs the Lua UI probe (boot the
        // instrumented host, evaluate openTitle(), dump the native-API trace).
        menuButton.setOnLongClickListener(v -> {
            probeLua("openTitle()");
            return true;
        });
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
                (int) (44 * getResources().getDisplayMetrics().density),
                (int) (44 * getResources().getDisplayMetrics().density),
                android.view.Gravity.TOP | android.view.Gravity.END);
        menuParams.setMargins(0, (int) (12 * getResources().getDisplayMetrics().density),
                (int) (12 * getResources().getDisplayMetrics().density), 0);
        root.addView(menuButton, menuParams);
        setContentView(root);

        surface.setOnTouchListener((view, event) -> touch(event));
        surface.setClickable(true);
        // Some vendor SurfaceViews do not forward the listener callback; the
        // root catches anything the child does not consume.
        root.setOnTouchListener((view, event) -> touch(event));
        root.setClickable(true);
        surface.getHolder().addCallback(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (dialog != null) { dialog.dismiss(); return; }
                showMenu();
            }
        });

        engine = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AdvHD-engine");
            t.setDaemon(true);
            return t;
        });
        EngineSessionRegistry.record(this, EngineSessionRegistry.HOST_ADVHD, payload.identity);
        engine.execute(this::createRuntime);
        immersive();
    }

    private void createRuntime() {
        int encoding;
        switch (payload.nls) {
            case "gbk": encoding = 1; break;
            case "big5": encoding = 2; break;
            case "utf8": encoding = 3; break;
            case "utf16le": encoding = 4; break;
            default: encoding = 0; break;
        }
        long startedAt = System.nanoTime();
        long created = NativeAdvhd.create(payload.root, payload.entry, encoding, payload.font, 0);
        if (created == 0) {
            String error = NativeAdvhd.lastCreateError();
            ui.post(() -> showFatal(error));
            return;
        }
        handle = created;
        NativeAdvhd.setChoiceOverlay(created, 0);
        if (!payload.translation.isEmpty()) {
            int loaded = NativeAdvhd.loadTranslation(created, payload.translation);
            android.util.Log.i("AdvhdHost", "translation load rc=" + loaded);
        }
        // Debug hook: when the pref carries a Lua snippet (e.g. "openTitle()"),
        // run it through the instrumented host right after boot and persist the
        // trace to files/advhd/lua_trace.txt. Device experiments stay one launch
        // away from evidence without any UI navigation.
        String probeCode = getSharedPreferences("tyranor_prefs", MODE_PRIVATE)
                .getString("advhd_lua_probe_code", "");
        if (!probeCode.isEmpty()) {
            engine.execute(() -> {
                File out = new File(new File(getFilesDir(), "advhd"), "lua_trace.txt");
                try {
                    try (java.io.FileWriter w = new java.io.FileWriter(out, false)) {
                        w.write("PROBE MARKER code=" + probeCode + "\n");
                    }
                } catch (IOException ignored) {
                }
                int n = NativeAdvhd.luaProbe(handle, probeCode);
                String trace = NativeAdvhd.luaTrace(handle);
                String body = trace == null ? "" : trace;
                String summary = "probe code=" + probeCode + " rc=" + n + " lines="
                        + (body.isEmpty() ? 0 : body.split("\n").length);
                android.util.Log.i("AdvhdHost", "lua " + summary);
                try {
                    try (java.io.FileWriter w = new java.io.FileWriter(out, false)) {
                        w.write(summary + "\n" + body);
                    }
                } catch (IOException ignored) {
                }
            });
        }
        android.util.Log.i("AdvhdHost", "create ms=" + ((System.nanoTime() - startedAt) / 1_000_000));
        ui.post(() -> {
            if (destroyed) return;
            if (surfaceReady) attachSurface();
            advanceOnce();
            scheduleFrame();
        });
    }

    private void attachSurface() {
        if (handle == 0 || surface == null) return;
        final android.view.Surface s = surface.getHolder().getSurface();
        if (s == null || !s.isValid()) return;
        engine.execute(() -> NativeAdvhd.setSurface(handle, s));
    }

    // ---------- frames ----------

    @Override public void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        if (!resumed || destroyed || handle == 0 || !surfaceReady) return;
        if (framePending.compareAndSet(false, true)) {
            engine.execute(() -> {
                long startedAt = System.nanoTime();
                try {
                    int deltaMs = lastFrameNanos == 0 ? 0
                            : (int) Math.min(100, (frameTimeNanos - lastFrameNanos) / 1_000_000);
                    lastFrameNanos = frameTimeNanos;
                    int flags = NativeAdvhd.tick(handle, deltaMs);
                    if ((flags & TICK_AUTO_DUE) != 0) {
                        pollAndPost(); // auto-advance on the engine thread itself
                    }
                    NativeAdvhd.frame(handle);
                    frameCount++;
                    if (frameCount % 300 == 1) {
                        android.util.Log.i("AdvhdHost", "tick frames=" + frameCount);
                    }
                    if (frameCount % 60 == 0) {
                        refreshLuaTraceFile();
                    }
                    if (firstFrameLogged == 0) {
                        firstFrameLogged = Math.max(1, (System.nanoTime() - startedAt) / 1_000_000);
                        android.util.Log.i("AdvhdHost", "first frame ms=" + firstFrameLogged);
                    }
                } finally {
                    framePending.set(false);
                }
            });
        }
        scheduleFrame();
    }

    private void scheduleFrame() {
        if (resumed && !destroyed && handle != 0 && surfaceReady && !frameScheduled) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stopFrames() {
        Choreographer.getInstance().removeFrameCallback(this);
        frameScheduled = false;
    }

    // ---------- input ----------

    private boolean touch(MotionEvent event) {
        if (moviePlaying) return true;
        if (dialog != null || choicePending) return true;
        int a = event.getActionMasked();
        if (handle != 0 && !destroyed
                && (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE
                        || a == MotionEvent.ACTION_UP)) {
            float x = event.getX();
            float y = event.getY();
            // MOVE floods; forward only real position changes. DOWN/UP always
            // dispatch. Mapping: 0=move 1=press 2=release.
            if (a != MotionEvent.ACTION_MOVE || x != lastPointerX || y != lastPointerY) {
                lastPointerX = x;
                lastPointerY = y;
                final int act = a == MotionEvent.ACTION_DOWN ? 1
                        : a == MotionEvent.ACTION_UP ? 2 : 0;
                final float fx = x;
                final float fy = y;
                engine.execute(() -> NativeAdvhd.pointer(handle, fx, fy, act));
            }
        }
        if (a == MotionEvent.ACTION_UP) {
            advanceOnce();
        }
        return true;
    }

    private void advanceOnce() {
        if (handle == 0 || destroyed) return;
        engine.execute(this::pollAndPost);
    }

    /** Engine thread: one advance + poll, then hand the event to the UI. */
    private void pollAndPost() {
        int kind = NativeAdvhd.advance(handle);
        long[] raw = new long[9];
        NativeAdvhd.pollEvent(handle, raw);
        String text = NativeAdvhd.eventText(handle);
        String name = NativeAdvhd.eventName(handle);
        String voiceFile = NativeAdvhd.eventVoice(handle);
        String error = kind == EVENT_ERROR ? NativeAdvhd.lastError(handle) : "";
        ui.post(() -> onEvent(kind, raw, text, name, voiceFile, error));
    }

    private void onEvent(int kind, long[] raw, String text, String name, String voiceFile, String error) {
        if (destroyed) return;
        if (kind != 0 && loading != null) {
            root.removeView(loading);
            loading = null;
        }
        switch (kind) {
            case EVENT_SAY:
                choicePending = false;
                break;
            case EVENT_CHOICE:
                choicePending = true;
                showChoices();
                break;
            case EVENT_AUDIO:
                onAudio((int) raw[1], name, text, (int) raw[3], raw[4] != 0,
                        Float.intBitsToFloat((int) raw[7]),
                        Float.intBitsToFloat((int) raw[5]),
                        Float.intBitsToFloat((int) raw[6]));
                break;
            case EVENT_MESSAGE:
                break;
            case EVENT_UI:
                logLuaTrace();
                break;
            case EVENT_MOVIE:
                if (raw[1] == MOVIE_STOP) {
                    dismissMovie(false);
                } else {
                    playMovie(text, raw[1] == MOVIE_PLAY_WAIT);
                }
                break;
            case EVENT_END:
                showDialog(text(R.string.engine_advhd_end_title), text(R.string.engine_advhd_end_message),
                        new String[]{text(R.string.engine_exit_game)}, new Runnable[]{this::finish}, () -> {});
                break;
            case EVENT_ERROR:
                showFatal(error);
                break;
            default:
                break;
        }
        scheduleFrame();
    }

    private void showChoices() {
        int count = NativeAdvhd.choiceCount(handle);
        if (count <= 0) {
            choicePending = false;
            return;
        }
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            String label = NativeAdvhd.choiceText(handle, i);
            labels[i] = (i + 1) + ". " + (label == null ? "" : label.trim());
        }
        Runnable[] actions = new Runnable[count];
        for (int i = 0; i < count; i++) {
            final int index = i;
            actions[i] = () -> chooseAndAdvance(index);
        }
        showDialog(text(R.string.engine_advhd_choice_title), "",
                labels, actions, () -> choicePending = true);
    }

    private void chooseAndAdvance(int index) {
        choicePending = false;
        engine.execute(() -> {
            int result = NativeAdvhd.choose(handle, index);
            if (result != 0) {
                String error = NativeAdvhd.lastError(handle);
                ui.post(() -> showFatal(error));
                return;
            }
            int kind = NativeAdvhd.advance(handle);
            long[] raw = new long[9];
            NativeAdvhd.pollEvent(handle, raw);
            String text = NativeAdvhd.eventText(handle);
            String name = NativeAdvhd.eventName(handle);
            String voiceFile = NativeAdvhd.eventVoice(handle);
            String error = kind == EVENT_ERROR ? NativeAdvhd.lastError(handle) : "";
            ui.post(() -> onEvent(kind, raw, text, name, voiceFile, error));
        });
    }

    // ---------- audio ----------

    private void onAudio(int code, String channel, String file, int repeats, boolean isVoice,
                         float volume, float fadeInSec, float fadeOutSec) {
        if (code == AUDIO_MUSIC) {
            playMusic(file, repeats < 0, volume, fadeInSec, fadeOutSec);
        } else if (code == AUDIO_SOUND) {
            playSound(file, isVoice, volume, fadeInSec);
        } else if (code == AUDIO_STOP_MUSIC) {
            releaseBgm();
        } else if (code == AUDIO_STOP_SOUND) {
            if (channel == null || channel.isEmpty() || channel.equals("*")) releaseEffects();
        } else if (code == AUDIO_STOP_VOICE) {
            releaseVoice();
        }
    }

    /** Ramp a player's volume; clamped to a sane window (engine values are seconds). */
    private void rampVolume(MediaPlayer player, float from, float to, float seconds) {
        int ms = Math.round(seconds * 1000f);
        if (player == null || ms <= 0) {
            try { player.setVolume(to, to); } catch (RuntimeException ignored) {}
            return;
        }
        ms = Math.min(ms, 5000);
        android.animation.ValueAnimator ani = android.animation.ValueAnimator.ofFloat(from, to);
        ani.setDuration(ms);
        ani.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            try { player.setVolume(v, v); } catch (RuntimeException ignored) {}
        });
        ani.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a0) {
                try { player.setVolume(to, to); } catch (RuntimeException ignored) {}
            }
        });
        ani.start();
    }

    private void playMusic(String file, boolean loop, float volume, float fadeInSec, float fadeOutSec) {
        if (file == null || file.isEmpty() || handle == 0) return;
        final String name = safeAudioName(file);
        engine.execute(() -> {
            String path = ensureAudio(name, file);
            if (path == null) return;
            ui.post(() -> {
                MediaPlayer old = bgm;
                bgmPrepared = false;
                bgm = null;
                // Crossfade: the event carries both fades; the old BGM ramps out
                // while the new one ramps in. No old player -> plain release.
                if (old != null && fadeOutSec > 0.01f) {
                    fadeOutAndRelease(old, fadeOutSec);
                } else if (old != null) {
                    try { old.release(); } catch (RuntimeException ignored) {}
                }
                bgm = createPlayer(path, loop, volume, fadeInSec);
                bgmPath = path;
                bgmWanted = true;
            });
        });
    }

    /** Fade a doomed player to silence, then release it off the UI thread. */
    private void fadeOutAndRelease(MediaPlayer player, float seconds) {
        int ms = Math.min(Math.round(seconds * 1000f), 5000);
        if (ms <= 0) {
            try { player.release(); } catch (RuntimeException ignored) {}
            return;
        }
        android.animation.ValueAnimator ani = android.animation.ValueAnimator.ofFloat(bgmVolume, 0f);
        ani.setDuration(ms);
        ani.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            try { player.setVolume(v, v); } catch (RuntimeException ignored) {}
        });
        ani.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a0) {
                engine.execute(() -> { try { player.release(); } catch (RuntimeException ignored) {} });
            }
        });
        ani.start();
    }

    private void playSound(String file, boolean isVoice, float volume, float fadeInSec) {
        if (file == null || file.isEmpty() || handle == 0) return;
        final String name = safeAudioName(file);
        engine.execute(() -> {
            String path = ensureAudio(name, file);
            if (path == null) return;
            ui.post(() -> {
                if (isVoice) {
                    releaseVoice();
                    voice = createPlayer(path, false, volume, fadeInSec);
                } else {
                    if (effects.size() >= 8) {
                        MediaPlayer oldest = effects.remove(0);
                        try { oldest.release(); } catch (RuntimeException ignored) {}
                    }
                    MediaPlayer player = createPlayer(path, false, volume, fadeInSec);
                    if (player != null) {
                        effects.add(player);
                        player.setOnCompletionListener(p -> {
                            effects.remove(p);
                            try { p.release(); } catch (RuntimeException ignored) {}
                        });
                    }
                }
            });
        });
    }

    // ---------- movie (WMV/ASF in .dat) ----------

    /** Extract the original `.dat` payload and play it with IJK's FFmpeg decoder. */
    private void playMovie(String file, boolean wait) {
        android.util.Log.i("AdvhdHost", "movie request file=" + file + " wait=" + wait);
        if (file == null || file.isEmpty()) {
            if (wait) advanceOnce();
            return;
        }
        final String name = safeAudioName(file);
        engine.execute(() -> {
            boolean loaded = IjkRuntime.load(this);
            String path = loaded ? ensureVideo(name, file) : null;
            ui.post(() -> {
                if (destroyed) return;
                if (path == null) {
                    android.util.Log.i("AdvhdHost", "movie unavailable: " + file);
                    if (wait) advanceOnce(); // never block the script on a failed video
                    return;
                }
                startMovieView(path, wait);
            });
        });
    }

    private String ensureVideo(String safeName, String reference) {
        // OP/ED videos are loose files in the game root (docs/format/arc.md),
        // not ARC entries. Match without case first; fall back to ARC extraction.
        File rootDir = new File(payload.root);
        File[] entries = rootDir.listFiles();
        if (entries != null) {
            String wanted = reference.substring(reference.lastIndexOf('/') + 1);
            for (File entry : entries) {
                if (entry.isFile() && entry.getName().equalsIgnoreCase(wanted)) {
                    return entry.canRead() ? entry.getAbsolutePath() : null;
                }
            }
        }
        File dir = new File(payload.user, "video");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File target = new File(dir, safeName);
        if (target.isFile() && target.length() > 0) return target.getAbsolutePath();
        int written = NativeAdvhd.extractAsset(handle, reference, target.getAbsolutePath());
        return written > 0 ? target.getAbsolutePath() : null;
    }

    private void startMovieView(String path, boolean wait) {
        dismissMovie(false);
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);
        container.setClickable(true);
        OnsIjkVideoView view = new OnsIjkVideoView(this);
        view.setCallback(new OnsIjkVideoView.Callback() {
            @Override public void onFinished() {
                ui.post(() -> finishMovie(true));
            }

            @Override public void onFailed(int what, int extra) {
                android.util.Log.i("AdvhdHost", "movie failed what=" + what + " extra=" + extra);
                ui.post(() -> finishMovie(true));
            }
        });
        container.addView(view, new FrameLayout.LayoutParams(-1, -1));
        container.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) finishMovie(true);
            return true;
        });
        root.addView(container, new FrameLayout.LayoutParams(-1, -1));
        movieContainer = container;
        movieView = view;
        movieWait = wait;
        moviePlaying = true;
        android.util.Log.i("AdvhdHost", "movie start path=" + path + " wait=" + wait);
        if (bgm != null && bgmPrepared) {
            try { bgm.pause(); } catch (RuntimeException ignored) {}
        }
        view.playPath(path);
    }

    private void finishMovie(boolean advance) {
        boolean wasWait = movieWait;
        movieWait = false;
        moviePlaying = false;
        dismissMovie(false);
        if (bgm != null && bgmWanted && bgmPrepared && resumed) {
            try { bgm.start(); } catch (RuntimeException ignored) {}
        }
        if (advance && wasWait) advanceOnce();
    }

    private void dismissMovie(boolean advance) {
        boolean wasWait = movieWait;
        movieWait = false;
        moviePlaying = false;
        if (movieView != null) {
            try { movieView.release(); } catch (RuntimeException ignored) {}
            movieView = null;
        }
        if (movieContainer != null) {
            root.removeView(movieContainer);
            movieContainer = null;
        }
        if (advance && wasWait) advanceOnce();
    }

    private String ensureAudio(String safeName, String reference) {
        File dir = new File(payload.user, "audio");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File target = new File(dir, safeName);
        if (target.isFile() && target.length() > 0) return target.getAbsolutePath();
        int written = NativeAdvhd.extractAsset(handle, reference, target.getAbsolutePath());
        return written > 0 ? target.getAbsolutePath() : null;
    }

    private String safeAudioName(String file) {
        String base = file;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.isEmpty()) base = "audio.ogg";
        StringBuilder out = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }

    private MediaPlayer createPlayer(String path, boolean loop, float volume, float fadeInSec) {
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(path);
            player.setLooping(loop);
            // The ABI maps an absent script volume to -1f; treat as full.
            float target = (volume >= 0f && volume <= 1f) ? volume : 1f;
            bgmVolume = target;
            player.setOnPreparedListener(p -> {
                if (p == bgm) bgmPrepared = true;
                if (resumed) {
                    try { p.start(); } catch (RuntimeException ignored) {}
                }
            });
            if (fadeInSec > 0.01f) {
                // Engine fade values are seconds; ramp after the player starts.
                player.setVolume(0f, 0f);
                player.setOnPreparedListener(p -> {
                    if (p == bgm) bgmPrepared = true;
                    if (resumed) {
                        try { p.start(); } catch (RuntimeException ignored) {}
                        rampVolume(p, 0f, target, fadeInSec);
                    }
                });
            } else {
                player.setVolume(target, target);
            }
            player.prepareAsync();
            return player;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void releaseBgm() {
        bgmWanted = false;
        bgmPrepared = false;
        if (bgm != null) {
            try { bgm.release(); } catch (RuntimeException ignored) {}
            bgm = null;
        }
        bgmPath = null;
    }

    private void releaseVoice() {
        if (voice != null) {
            try { voice.release(); } catch (RuntimeException ignored) {}
            voice = null;
        }
    }

    private void releaseEffects() {
        for (MediaPlayer player : effects) {
            try { player.release(); } catch (RuntimeException ignored) {}
        }
        effects.clear();
    }

    // ---------- menu ----------

    private void showMenu() {
        if (handle == 0 || destroyed || dialog != null) return;
        showDialog(text(R.string.engine_prompt), "",
                new String[]{
                        text(R.string.engine_advhd_resume),
                        autoMode ? text(R.string.engine_advhd_auto_off)
                                 : text(R.string.engine_advhd_auto_on),
                        text(R.string.engine_advhd_save),
                        text(R.string.engine_advhd_load),
                        text(R.string.engine_advhd_backlog),
                        text(R.string.engine_advhd_lua_probe),
                        text(R.string.engine_exit_game),
                },
                new Runnable[]{() -> {}, this::toggleAuto, () -> showSlotPicker(true),
                        () -> showSlotPicker(false),
                        this::showBacklog, () -> probeLua("openTitle()"),
                        this::showExitConfirmation},
                () -> {});
    }

    /** Session-local auto mode; the runtime reveals text at 40 cps until then. */
    private void toggleAuto() {
        autoMode = !autoMode;
        if (handle != 0) NativeAdvhd.setAuto(handle, autoMode ? 1 : 0, 1200);
        Toast.makeText(this, autoMode ? R.string.engine_advhd_auto_on
                                      : R.string.engine_advhd_auto_off,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * The runtime boots the instrumented Lua UI host on the first engine UI
     * call (openTitle/openConfig/...); dump its trace so the native-API
     * evidence lands in logcat. Bounded: summary + first 40 lines.
     */
    private void logLuaTrace() {
        if (handle == 0 || destroyed) return;
        engine.execute(() -> {
            String trace = NativeAdvhd.luaTrace(handle);
            if (trace == null || trace.isEmpty()) return;
            String[] lines = trace.split("\n");
            android.util.Log.i("AdvhdHost", "lua trace lines=" + lines.length);
            int shown = Math.min(lines.length, 40);
            for (int i = 0; i < shown; i++) {
                android.util.Log.i("AdvhdHost", "lua[" + i + "] " + lines[i]);
            }
            if (lines.length > shown) {
                android.util.Log.i("AdvhdHost", "lua ... " + (lines.length - shown) + " more");
            }
        });
    }

    /** Engine thread: persist the Lua UI trace whenever it grows. probeLua
     * only wrote the file once at boot, so pump/pointer activity after the
     * probe (PROCESS handshakes, EVAL FAILs, menu traces) was invisible to
     * run-as pulls — refresh at ~1 Hz so the file reflects the live trace. */
    private void refreshLuaTraceFile() {
        if (handle == 0 || destroyed) return;
        String trace = NativeAdvhd.luaTrace(handle);
        if (trace == null || trace.isEmpty()) return;
        if (trace.length() == lastTraceLen) return;
        lastTraceLen = trace.length();
        try {
            File out = new File(new File(getFilesDir(), "advhd"), "lua_trace.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(out)) {
                w.write("lines=" + trace.split("\n").length + "\n" + trace);
            }
        } catch (IOException ignored) {
        }
    }

    /** Debug: evaluate a Lua snippet in the instrumented UI host, then show
     * and persist the trace (logcat is unreliable on some vendor ROMs). */
    private void probeLua(String code) {
        if (handle == 0 || destroyed) return;
        Toast.makeText(this, "Lua probe: " + code, Toast.LENGTH_SHORT).show();
        engine.execute(() -> {
            int n = NativeAdvhd.luaProbe(handle, code);
            String trace = NativeAdvhd.luaTrace(handle);
            String body = trace == null ? "" : trace;
            String summary = "probe rc=" + n + " lines="
                    + (body.isEmpty() ? 0 : body.split("\n").length);
            android.util.Log.i("AdvhdHost", "lua " + summary);
            try {
                File out = new File(new File(getFilesDir(), "advhd"), "lua_trace.txt");
                try (java.io.FileWriter w = new java.io.FileWriter(out)) {
                    w.write(summary + "\n" + body);
                }
            } catch (IOException ignored) {
            }
            String dialogBody = body.length() > 6000
                    ? body.substring(0, 6000) + "\n… (see files/advhd/lua_trace.txt)"
                    : body;
            ui.post(() -> showDialog("Lua UI trace", dialogBody.isEmpty() ? "(empty)"
                    : dialogBody, new String[]{text(R.string.engine_ok)},
                    new Runnable[]{() -> {}}, () -> {}));
        });
    }

    /** Text history in a scrollable dialog (the VM keeps the last 300 lines). */
    private void showBacklog() {
        int count = NativeAdvhd.historyCount(handle);
        if (count <= 0) {
            Toast.makeText(this, R.string.engine_advhd_backlog_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        int start = Math.max(0, count - 120);
        StringBuilder text = new StringBuilder();
        for (int i = start; i < count; i++) {
            String line = NativeAdvhd.historyText(handle, i);
            if (line == null || line.isEmpty()) continue;
            text.append(line).append('\n');
        }
        showDialog(text(R.string.engine_advhd_backlog), text.toString(),
                new String[]{text(R.string.engine_cancel)}, new Runnable[]{() -> {}}, () -> {});
    }

    private static final int SLOT_COUNT = 8;

    private File slotFile(int n) {
        return new File(payload.user, "slot" + n + ".json");
    }

    /** Slot picker for the menu save/load entries. Saving writes the current
     * frame next to the slot JSON (slotN.png) for the app-side save manager. */
    private void showSlotPicker(boolean saving) {
        if (handle == 0 || destroyed || dialog != null) return;
        java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.text.DateFormat date = android.text.format.DateFormat.getDateFormat(this);
        java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(this);
        for (int n = 1; n <= SLOT_COUNT; n++) {
            File f = slotFile(n);
            if (f.isFile()) {
                labels.add(getString(R.string.engine_advhd_slot_used, n,
                        date.format(f.lastModified()) + " " + time.format(f.lastModified())));
                ids.add(n);
            } else if (saving) {
                labels.add(getString(R.string.engine_advhd_slot_empty, n));
                ids.add(n);
            }
        }
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.engine_advhd_no_saves, Toast.LENGTH_SHORT).show();
            return;
        }
        String title = text(saving ? R.string.engine_advhd_save : R.string.engine_advhd_load);
        showDialog(title, "", labels.toArray(new String[0]),
                ids.stream().map(n -> (Runnable) () -> {
                    if (saving) saveSlot(n);
                    else loadSlot(n);
                }).toArray(Runnable[]::new), () -> {});
    }

    private void saveSlot(int n) {
        engine.execute(() -> {
            int result = NativeAdvhd.save(handle, slotFile(n).getAbsolutePath());
            if (result == 0) writeThumbnail(n);
            ui.post(() -> Toast.makeText(this,
                    result == 0 ? R.string.engine_advhd_saved : R.string.engine_advhd_save_failed,
                    Toast.LENGTH_SHORT).show());
        });
    }

    /** Dump the current frame as slotN.png (thumbnail for the save manager). */
    private void writeThumbnail(int n) {
        int[] argb = NativeAdvhd.renderFrame(handle);
        if (argb == null) return;
        int w = NativeAdvhd.nativeWidth();
        int h = NativeAdvhd.nativeHeight();
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, w, 0, 0, w, h);
        try (java.io.FileOutputStream fos =
                     new java.io.FileOutputStream(new File(payload.user, "slot" + n + ".png"))) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
        } catch (java.io.IOException ignored) {
        }
        bmp.recycle();
    }

    private void loadSlot(int n) {
        engine.execute(() -> {
            int result = NativeAdvhd.load(handle, slotFile(n).getAbsolutePath());
            if (result != 0) {
                String error = NativeAdvhd.lastError(handle);
                ui.post(() -> Toast.makeText(this,
                        getString(R.string.engine_advhd_load_failed, error), Toast.LENGTH_LONG).show());
                return;
            }
            long[] raw = new long[9];
            int kind = NativeAdvhd.pollEvent(handle, raw);
            // A save sits just after the displayed line (and after Select), so a
            // fresh menu load has no pending event: advance until the next line
            // or choice, exactly like a normal tap.
            for (int i = 0; i < 4 && (kind == 0 || kind == EVENT_MESSAGE); i++) {
                kind = NativeAdvhd.advance(handle);
                NativeAdvhd.pollEvent(handle, raw);
            }
            String text = NativeAdvhd.eventText(handle);
            String name = NativeAdvhd.eventName(handle);
            String voiceFile = NativeAdvhd.eventVoice(handle);
            final int finalKind = kind;
            ui.post(() -> onEvent(finalKind, raw, text, name, voiceFile, ""));
        });
    }

    private void showExitConfirmation() {
        showDialog(text(R.string.engine_exit_game), text(R.string.engine_advhd_exit_message),
                new String[]{text(R.string.engine_ok), text(R.string.engine_cancel)},
                new Runnable[]{this::finish, () -> {}}, () -> {});
    }

    private void showFatal(String detail) {
        if (destroyed || isFinishing()) return;
        showDialog(text(R.string.engine_advhd_failed),
                detail == null || detail.isEmpty() ? text(R.string.engine_advhd_failed_message) : detail,
                new String[]{text(R.string.engine_exit_game)}, new Runnable[]{this::finish}, () -> {});
    }

    private void showDialog(String title, String message, String[] labels, Runnable[] actions, Runnable cancel) {
        if (dialog != null) dialog.dismiss();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(8 * getResources().getDisplayMetrics().density);
        content.setBackground(background);
        TextView heading = dialogText(title, 15);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(heading);
        if (message != null && !message.isEmpty()) content.addView(dialogText(message, 14));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(content);
        AlertDialog shown = new AlertDialog.Builder(this).setView(scroll).create();
        dialog = shown;
        for (int i = 0; i < labels.length; i++) {
            TextView button = dialogText(labels[i], 14);
            button.setPadding(0, pad / 2, 0, pad / 2);
            button.setBackground(null);
            button.setSoundEffectsEnabled(false);
            button.setHapticFeedbackEnabled(false);
            final Runnable action = actions[i];
            button.setOnClickListener(v -> {
                if (dialog != shown) return;
                dialog = null;
                shown.setOnDismissListener(null);
                shown.dismiss();
                action.run();
            });
            content.addView(button);
        }
        shown.setOnCancelListener(d -> cancel.run());
        shown.setOnDismissListener(d -> {
            if (dialog == shown) dialog = null;
        });
        shown.show();
        if (shown.getWindow() != null) {
            shown.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private TextView dialogText(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.BLACK);
        return view;
    }

    // ---------- lifecycle ----------

    @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {}

    @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        surfaceReady = true;
        attachSurface();
        scheduleFrame();
    }

    @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        surfaceReady = false;
        stopFrames();
        if (handle != 0) engine.execute(() -> NativeAdvhd.clearSurface(handle));
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (moviePlaying && movieView != null) {
            try { movieView.resumePlayback(); } catch (RuntimeException ignored) {}
        }
        if (bgm != null && bgmWanted && bgmPrepared) {
            try { bgm.start(); } catch (RuntimeException ignored) {}
        }
        scheduleFrame();
        immersive();
    }

    @Override protected void onPause() {
        resumed = false;
        stopFrames();
        if (moviePlaying && movieView != null) {
            try { movieView.pausePlayback(); } catch (RuntimeException ignored) {}
        }
        if (bgm != null && bgmPrepared) {
            try { bgm.pause(); } catch (RuntimeException ignored) {}
        }
        releaseVoice();
        releaseEffects();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (payload != null && intent != null && payload.identity.equals(
                intent.getStringExtra(LaunchContract.ROOT_URI)) && !destroyed) {
            surface.requestFocus();
        } else {
            toast(R.string.engine_another_game_running);
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopFrames();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        releaseBgm();
        releaseVoice();
        releaseEffects();
        dismissMovie(false);
        final long h = handle;
        handle = 0;
        if (engine != null) {
            if (h != 0) engine.execute(() -> NativeAdvhd.destroy(h));
            engine.shutdown();
        }
        EngineSessionRegistry.clear(this, EngineSessionRegistry.HOST_ADVHD);
        super.onDestroy();
        // Android would otherwise keep this process cached; the launcher would
        // treat any live :advhd process as a busy session.
        if (processVerified && isFinishing()) {
            Process.killProcess(Process.myPid());
        }
    }

    private void immersive() {
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.hide(WindowInsetsCompat.Type.systemBars());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private boolean isOwnEngineProcess() {
        String expected = getPackageName() + ":advhd";
        if (Build.VERSION.SDK_INT >= 28) return expected.equals(android.app.Application.getProcessName());
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager != null && manager.getRunningAppProcesses() != null) {
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                if (process.pid == Process.myPid() && process.uid == Process.myUid()) {
                    return expected.equals(process.processName);
                }
            }
        }
        return false;
    }

    private String text(int id) { return getString(id); }

    private void toast(int id) { Toast.makeText(this, text(id), Toast.LENGTH_LONG).show(); }
}
