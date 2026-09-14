package dev.codex.miuibackgesturehook.hooks.googleapp;

import android.content.SharedPreferences;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import io.github.libxposed.api.XposedInterface;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

/** Google-owned Live Translate action and its feature-specific preferences. */
final class GoogleAppLiveTranslateRuntime {
    private static final String LIVE_TRANSLATE_SYSTEM_FEATURE =
            "com.google.android.feature.CONTEXTUAL_SEARCH_LIVE_TRANSLATE";
    private static final int LIVE_TRANSLATE_ACTION_ID = 271520;
    private final GoogleAppRuntime.Host host;
    private volatile SharedPreferences liveTranslatePreferences;
    private volatile boolean liveTranslatePreferenceFailureLogged;

    GoogleAppLiveTranslateRuntime(GoogleAppRuntime.Host host) { this.host = host; }

    boolean needsResolution(Set<String> ids) {
        return !ids.contains("google_live_translate_action_visibility")
                || !ids.contains("google_live_translate_capability");
    }

    XposedInterface.Hooker replacement(String id) {
        switch (id) {
            case "google_live_translate_system_feature": return this::overrideLiveTranslateSystemFeature;
            case "google_live_translate_action_visibility": return this::preserveLiveTranslateActionVisibility;
            case "google_live_translate_capability": return this::overrideLiveTranslateBooleanGate;
            default: return null;
        }
    }

    void installDexHooks(
            ClassLoader classLoader,
            Set<String> existingHookIds,
            DexKitBridge bridge) throws Throwable {
        Class<?> actionClass = resolveLiveTranslateActionClass(classLoader, bridge);
        if (actionClass == null) {
            host.log(Log.WARN,
                    "Could not uniquely resolve the Android 16 live-translate action");
            return;
        }
        if (!existingHookIds.contains("google_live_translate_action_visibility")) {
            installActionVisibilityHook(actionClass);
        }
        if (!existingHookIds.contains("google_live_translate_capability")) {
            installCapabilityHook(actionClass);
        }
    }

    void installSystemFeatureHook(
            ClassLoader classLoader, Set<String> existingHookIds) {
        if (existingHookIds.contains("google_live_translate_system_feature")) {
            return;
        }
        try {
            Class<?> packageManagerClass = Class.forName(
                    "android.app.ApplicationPackageManager", false, classLoader);
            Method method = packageManagerClass.getDeclaredMethod(
                    "hasSystemFeature", String.class);
            host.install(method, "google_live_translate_system_feature", this::overrideLiveTranslateSystemFeature);
        } catch (Throwable throwable) {
            host.log(Log.WARN,
                    "Live-translate system-feature gate unavailable", throwable);
        }
    }

    private Class<?> resolveLiveTranslateActionClass(
            ClassLoader classLoader, DexKitBridge bridge) throws Throwable {
            FindMethod query = FindMethod.create().matcher(
                    MethodMatcher.create()
                            .paramCount(0)
                            .returnType("int")
                            .usingNumbers(Integer.valueOf(LIVE_TRANSLATE_ACTION_ID)));
            MethodDataList matches = bridge.findMethod(query);
            Class<?> resolved = null;
            for (MethodData match : matches) {
                if (match.getParamCount() != 0
                        || !"int".equals(match.getReturnTypeName())) {
                    continue;
                }
                Class<?> candidate = match.getClassInstance(classLoader);
                if (findExactBooleanMethod(candidate, "i") == null
                        || findCapabilityMethod(candidate) == null) {
                    continue;
                }
                if (resolved != null && resolved != candidate) {
                    host.log(Log.WARN,
                            "Ambiguous live-translate action classes: "
                                    + resolved.getName() + " and " + candidate.getName());
                    return null;
                }
                resolved = candidate;
            }
            return resolved;
    }

    private void installActionVisibilityHook(Class<?> actionClass) throws Throwable {
        Method visibility = findExactBooleanMethod(actionClass, "i");
        if (visibility == null) {
            throw new NoSuchMethodException(actionClass.getName() + ".i():boolean");
        }
        boolean deoptimized = host.deoptimize(visibility);
        host.install(visibility, "google_live_translate_action_visibility", this::preserveLiveTranslateActionVisibility);
        host.log(deoptimized ? Log.INFO : Log.WARN,
                "Prepared live-translate action visibility"
                        + ", owner=" + actionClass.getName()
                        + ", deoptimized=" + deoptimized);
    }

    private void installCapabilityHook(Class<?> actionClass) throws Throwable {
        Method capability = findCapabilityMethod(actionClass);
        if (capability == null) {
            throw new NoSuchMethodException(
                    actionClass.getName() + " live-translate capability");
        }
        boolean deoptimized = host.deoptimize(capability);
        host.install(capability, "google_live_translate_capability", this::overrideLiveTranslateBooleanGate);
        host.log(deoptimized ? Log.INFO : Log.WARN,
                "Prepared live-translate capability gate"
                        + ", executable=" + capability
                        + ", deoptimized=" + deoptimized);
    }

    private static Method findCapabilityMethod(Class<?> actionClass) {
        Constructor<?> matchingConstructor = null;
        for (Constructor<?> constructor : actionClass.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 3) {
                if (matchingConstructor != null) {
                    return null;
                }
                matchingConstructor = constructor;
            }
        }
        if (matchingConstructor == null) {
            return null;
        }
        Class<?>[] parameters = matchingConstructor.getParameterTypes();
        return parameters.length == 3
                ? findExactBooleanMethod(parameters[1], "a") : null;
    }

    private static Method findExactBooleanMethod(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name);
            if (method.getReturnType() != Boolean.TYPE
                    || method.getParameterCount() != 0) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object overrideLiveTranslateSystemFeature(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        List<Object> args = chain.getArgs();
        if (args.size() == 1
                && LIVE_TRANSLATE_SYSTEM_FEATURE.equals(args.get(0))
                && isContextualSearchLiveTranslateEnabled()) {
            return Boolean.TRUE;
        }
        return result;
    }

    private Object overrideLiveTranslateBooleanGate(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        return isContextualSearchLiveTranslateEnabled() ? Boolean.TRUE : result;
    }

    /**
     * Keep the native action-list visibility decision intact.  The capability hook above is
     * deliberately separate: it makes the feature usable once the native flow requests it,
     * but it must not manufacture a Translate item on the initial results page.
     */
    private Object preserveLiveTranslateActionVisibility(
            XposedInterface.Chain chain) throws Throwable {
        return chain.proceed();
    }

    private boolean isContextualSearchLiveTranslateEnabled() {
        try {
            SharedPreferences preferences = liveTranslatePreferences;
            if (preferences == null) {
                synchronized (this) {
                    preferences = liveTranslatePreferences;
                    if (preferences == null) {
                        preferences = host.preferences(
                                PredictiveBackPreferences.GROUP);
                        liveTranslatePreferences = preferences;
                    }
                }
            }
            boolean enabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE);
            boolean contextualSearchEnabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
            liveTranslatePreferenceFailureLogged = false;
            return enabled && contextualSearchEnabled;
        } catch (Throwable throwable) {
            if (!liveTranslatePreferenceFailureLogged) {
                liveTranslatePreferenceFailureLogged = true;
                host.log(Log.ERROR,
                        "Live-translate preference unavailable; preserving Google App behavior",
                        throwable);
            }
            return false;
        }
    }

}
