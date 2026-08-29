package dev.codex.backgesturehook.hooks.googleapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.lang.reflect.Executable;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.libxposed.api.XposedInterface;
import org.luckypray.dexkit.DexKitBridge;

/** Coordinates independent Google features without inheriting any process runtime. */
public final class GoogleAppRuntime {
    public static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";

    /** Framework operations supplied by the module's lifecycle owner. */
    public interface Host {
        void install(Executable executable, String id, XposedInterface.Hooker hooker);
        boolean deoptimize(Executable executable);
        SharedPreferences preferences(String group);
        void ensureDexKit();
        void log(int priority, String message, Throwable throwable);
        default void log(int priority, String message) { log(priority, message, null); }
    }

    private final Host host;
    private final GoogleAppLiveTranslateRuntime liveTranslate;
    private final GoogleAppLensRuntime lens;
    private final AtomicInteger dexResolutionInFlight = new AtomicInteger();
    private volatile String sourceDir;

    public GoogleAppRuntime(Host host) {
        this.host = host;
        liveTranslate = new GoogleAppLiveTranslateRuntime(host);
        lens = new GoogleAppLensRuntime(host);
    }

    public boolean isResolvingDex() { return dexResolutionInFlight.get() != 0; }

    public String resolveSourceDir() {
        if (sourceDir != null && !sourceDir.isEmpty()) return sourceDir;
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null);
            if (app instanceof Context) sourceDir = ((Context) app).getApplicationInfo().sourceDir;
        } catch (Throwable throwable) {
            host.log(Log.WARN, "Could not recover Google App source path", throwable);
        }
        return sourceDir;
    }

    public void install(ClassLoader loader, String apk, Set<String> ids) {
        sourceDir = apk;
        liveTranslate.installSystemFeatureHook(loader, ids);
        boolean translateNeeded = liveTranslate.needsResolution(ids);
        boolean lensNeeded = lens.needsResolution(ids);
        if (!translateNeeded && !lensNeeded) return;
        if (apk == null || apk.isEmpty()) {
            host.log(Log.WARN, "Google App source unavailable; preserving native behavior");
            return;
        }
        dexResolutionInFlight.incrementAndGet();
        try {
            host.ensureDexKit();
            try (DexKitBridge bridge = DexKitBridge.create(apk)) {
                if (translateNeeded) {
                    try { liveTranslate.installDexHooks(loader, ids, bridge); }
                    catch (Throwable failure) { host.log(Log.ERROR, "Live Translate resolution failed", failure); }
                }
                if (lensNeeded) {
                    try { lens.install(loader, ids, bridge); }
                    catch (Throwable failure) { host.log(Log.ERROR, "Ask Screen resolution failed", failure); }
                }
            }
        } catch (Throwable failure) {
            host.log(Log.ERROR, "Google dex resolution unavailable", failure);
        } finally {
            dexResolutionInFlight.decrementAndGet();
        }
    }

    public XposedInterface.Hooker replacement(String id) {
        XposedInterface.Hooker hooker = liveTranslate.replacement(id);
        if (hooker != null) return hooker;
        hooker = lens.replacement(id);
        if (hooker != null) return hooker;
        // Neutralize retired handles from already-installed investigation builds.
        if (id.equals("google_lens_aim_screen_capability")
                || id.startsWith("google_lens_diagnostic_")) return XposedInterface.Chain::proceed;
        return null;
    }
}
