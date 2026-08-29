package dev.codex.backgesturehook.hooks.googleapp;

import android.content.SharedPreferences;
import java.lang.reflect.Executable;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import dev.codex.backgesturehook.PredictiveBackPreferences;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoogleAppRuntimeTest {
    private static final class Host implements GoogleAppRuntime.Host {
        final Map<String, Boolean> values = new HashMap<>();
        boolean unavailable;
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (unavailable) throw new IllegalStateException("preferences unavailable");
                    if (method.getName().equals("getBoolean")) return values.getOrDefault(args[0], (Boolean) args[1]);
                    throw new AssertionError(method);
                });
        @Override public void install(Executable executable, String id, XposedInterface.Hooker hooker) {
            throw new AssertionError("Callbacks must not install hooks");
        }
        @Override public boolean deoptimize(Executable executable) { throw new AssertionError(); }
        @Override public SharedPreferences preferences(String group) { return preferences; }
        @Override public void ensureDexKit() { throw new AssertionError("Callbacks must not open DexKit"); }
        @Override public void log(int priority, String message, Throwable failure) { }
    }

    private static XposedInterface.Chain chain(Object result, Throwable failure) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "proceed": if (failure != null) throw failure; return result;
                        case "getArgs": return Collections.emptyList();
                        default: throw new AssertionError("Unexpected access: " + method);
                    }
                });
    }

    @Test public void featurePreferencesStayIndependentAndApplyToExistingCallbacks() throws Throwable {
        Host host = new Host();
        GoogleAppRuntime runtime = new GoogleAppRuntime(host);
        XposedInterface.Hooker thumbnail = runtime.replacement("google_lens_screen_thumbnail_retention");
        XposedInterface.Hooker translate = runtime.replacement("google_live_translate_capability");
        assertEquals(false, thumbnail.intercept(chain(false, null)));
        assertEquals(false, translate.intercept(chain(false, null)));
        host.values.put(PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, true);
        host.values.put(PredictiveBackPreferences.KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX, true);
        assertEquals(true, thumbnail.intercept(chain(false, null)));
        assertEquals(false, translate.intercept(chain(false, null)));
        host.values.put(PredictiveBackPreferences.KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX, false);
        host.values.put(PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE, true);
        assertEquals(false, thumbnail.intercept(chain(false, null)));
        assertEquals(true, translate.intercept(chain(false, null)));
        host.values.put(PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, false);
        assertEquals(false, translate.intercept(chain(false, null)));
        host.unavailable = true;
        assertEquals(false, thumbnail.intercept(chain(false, null)));
        assertEquals(false, translate.intercept(chain(false, null)));
    }

    @Test public void retiredHandlesAndUnresolvedBridgesPreserveNativeResults() throws Throwable {
        GoogleAppRuntime runtime = new GoogleAppRuntime(new Host());
        for (String id : new String[]{"google_lens_aim_screen_capability",
                "google_lens_diagnostic_model_301805186", "google_lens_diagnostic_eligibility_301805186",
                "google_lens_diagnostic_enrollment_301805186", "google_lens_diagnostic_labs_301805186",
                "google_lens_diagnostic_response_301805186", "google_lens_aim_eligibility_bridge",
                "google_lens_aim_hint_bridge"}) {
            assertEquals(false, runtime.replacement(id).intercept(chain(false, null)));
        }
        assertNull(runtime.replacement("server_contextual_search_start"));
    }

    @Test public void originalExceptionsAreNeverConvertedToSuccess() throws Throwable {
        GoogleAppRuntime runtime = new GoogleAppRuntime(new Host());
        IllegalStateException original = new IllegalStateException("original failure");
        for (String id : new String[]{"google_lens_screen_thumbnail_retention",
                "google_lens_aim_eligibility_bridge", "google_lens_aim_hint_bridge",
                "google_live_translate_capability"}) {
            try { runtime.replacement(id).intercept(chain(false, original)); fail(id); }
            catch (IllegalStateException observed) { assertSame(original, observed); }
        }
    }
}
