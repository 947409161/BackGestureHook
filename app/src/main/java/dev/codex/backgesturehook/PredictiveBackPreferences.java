package dev.codex.backgesturehook;

public final class PredictiveBackPreferences {
    public static final String GROUP = "predictive_back_opt_in";
    public static final String KEY_PACKAGES = "packages";
    public static final String KEY_HYPEROS_INDICATOR = "hyperos_indicator_style";
    public static final boolean DEFAULT_HYPEROS_INDICATOR = false;
    public static final String KEY_HYPEROS_SLIDE_ANIMATION =
            "hyperos_slide_back_animation";
    public static final boolean DEFAULT_HYPEROS_SLIDE_ANIMATION = false;
    public static final String KEY_MODULE_LOGGING = "module_logging";
    public static final boolean DEFAULT_MODULE_LOGGING = true;

    /** The vertical size of both Xiaomi side trigger areas, expressed as a percentage. */
    public static final String KEY_GESTURE_TRIGGER_HEIGHT_PERCENT =
            "gesture_trigger_height_percent";
    public static final int DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT = 100;
    public static final int MIN_GESTURE_TRIGGER_HEIGHT_PERCENT = 10;
    public static final int MAX_GESTURE_TRIGGER_HEIGHT_PERCENT = 100;

    /** The top offset of both side trigger areas within their available vertical travel. */
    public static final String KEY_GESTURE_TRIGGER_POSITION_PERCENT =
            "gesture_trigger_position_percent";
    public static final int DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT = 0;
    public static final int MIN_GESTURE_TRIGGER_POSITION_PERCENT = 0;
    public static final int MAX_GESTURE_TRIGGER_POSITION_PERCENT = 100;

    private PredictiveBackPreferences() {
    }
}
