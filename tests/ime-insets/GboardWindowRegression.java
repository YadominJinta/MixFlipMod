import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.lang.reflect.Method;

/** Exercises the phone's real WindowLayout with and without cutout fitting. */
public class GboardWindowRegression {
    static final Class<?> STATE;
    static final Class<?> SOURCE;
    static final Class<?> FRAMES;
    static final Class<?> LAYOUT;
    static {
        try {
            STATE = Class.forName("android.view.InsetsState");
            SOURCE = Class.forName("android.view.InsetsSource");
            FRAMES = Class.forName("android.window.ClientWindowFrames");
            LAYOUT = Class.forName("android.view.WindowLayout");
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        return method.invoke(target, args);
    }
    static Object state(Rect cutout) throws Exception {
        Object state = STATE.getConstructor().newInstance();
        Rect display = new Rect(0, 0, 1208, 1392);
        call(state, "setDisplayFrame", new Class[]{Rect.class}, display);
        if (cutout != null) {
            Object source = SOURCE.getConstructor(int.class, int.class)
                    .newInstance(7, WindowInsets.Type.displayCutout());
            call(source, "setFrame", new Class[]{Rect.class}, cutout);
            call(source, "setVisible", new Class[]{boolean.class}, true);
            call(source, "updateSideHint", new Class[]{Rect.class}, display);
            call(state, "addSource", new Class[]{SOURCE}, source);
        }
        return state;
    }
    static WindowManager.LayoutParams attrs() throws Exception {
        WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_INPUT_METHOD,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSPARENT);
        attrs.packageName = "com.google.android.inputmethod.latin";
        attrs.gravity = Gravity.BOTTOM;
        attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        call(attrs, "setFitInsetsTypes", new Class[]{int.class}, WindowInsets.Type.systemBars());
        return attrs;
    }
    static Rect layout(Object state, WindowManager.LayoutParams attrs) throws Exception {
        return layout(state, attrs, new Rect(0, 0, 1208, 1392));
    }
    static Rect layout(Object state, WindowManager.LayoutParams attrs, Rect bounds) throws Exception {
        Object frames = FRAMES.getConstructor().newInstance();
        call(LAYOUT.getConstructor().newInstance(), "computeFrames",
                new Class[]{WindowManager.LayoutParams.class, STATE, Rect.class, Rect.class,
                        int.class, int.class, int.class, int.class, float.class, FRAMES},
                attrs, state, new Rect(-100000, -100000, 100000, 100000),
                bounds, 1, 1208, -1, WindowInsets.Type.systemBars(), 1.0f, frames);
        return new Rect((Rect) FRAMES.getField("frame").get(frames));
    }
    static WindowManager.LayoutParams fit(WindowManager.LayoutParams original) throws Exception {
        WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
        copy.copyFrom(original);
        int types = (Integer) call(original, "getFitInsetsTypes", new Class[]{});
        call(copy, "setFitInsetsTypes", new Class[]{int.class}, types | WindowInsets.Type.displayCutout());
        return copy;
    }
    static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS: " + description);
    }
    public static void main(String[] args) throws Exception {
        WindowManager.LayoutParams original = attrs();
        Object right = state(new Rect(810, 0, 1208, 1392));
        Rect before = layout(right, original);
        Rect after = layout(right, fit(original));
        System.out.println("Right camera: before=" + before + "; after=" + after);
        check(before.equals(new Rect(0, 0, 1208, 1392)), "unmodified Gboard spans camera area");
        check(after.equals(new Rect(0, 0, 810, 1392)), "right camera confines the window to the left safe area");
        check(!Rect.intersects(after, new Rect(810, 664, 1208, 1392)), "keyboard cannot intersect the physical camera rectangle");
        check(layout(state(null), fit(original)).equals(before), "no cutout leaves full window unchanged");
        check(layout(right, original).equals(before), "original IME parameters remain unchanged");
        Object left = state(new Rect(0, 0, 398, 1392));
        Object fullWidthState = STATE.getConstructor(STATE, boolean.class).newInstance(left, true);
        call(fullWidthState, "removeSource", new Class[]{int.class}, 7);
        Rect fullWidth = layout(fullWidthState, original);
        check(fullWidth.equals(before), "top-left camera keeps full window width and height for native Gboard sizing");
        android.graphics.Insets visible = (android.graphics.Insets) call(fullWidthState, "calculateVisibleInsets",
                new Class[]{Rect.class, int.class, int.class, int.class, int.class}, fullWidth, 2011, 0, 16, 0);
        check(visible.left == 0, "full-width client has no stale left inset");
        android.graphics.Insets originalVisible = (android.graphics.Insets) call(left, "calculateVisibleInsets",
                new Class[]{Rect.class, int.class, int.class, int.class, int.class}, fullWidth, 2011, 0, 16, 0);
        check(originalVisible.left == 398, "cleaning the full-width copy preserves the shared camera inset");
    }
}
