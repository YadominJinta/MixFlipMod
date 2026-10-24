import android.graphics.Insets;
import android.graphics.Rect;
import android.util.SparseIntArray;
import android.view.WindowInsets;
import java.lang.reflect.Method;

/** Runs against the phone framework using captured window geometry. */
public class ImeInsetsRegression {
    static final Class<?> STATE;
    static final Class<?> SOURCE;
    static {
        try {
            STATE = Class.forName("android.view.InsetsState");
            SOURCE = Class.forName("android.view.InsetsSource");
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        return method.invoke(target, args);
    }
    static Object state(Rect cutout) throws Exception {
        Object state = STATE.getConstructor().newInstance();
        Rect display = new Rect(0, 0, 1510, 1740);
        call(state, "setDisplayFrame", new Class[]{Rect.class}, display);
        add(state, 3, WindowInsets.Type.ime(), new Rect(0, 828, 1510, 1740), display);
        add(state, 7, WindowInsets.Type.displayCutout(), cutout, display);
        return state;
    }
    static void add(Object state, int id, int type, Rect frame, Rect display) throws Exception {
        Object source = SOURCE.getConstructor(int.class, int.class).newInstance(id, type);
        call(source, "setFrame", new Class[]{Rect.class}, frame);
        call(source, "setVisible", new Class[]{boolean.class}, true);
        call(source, "updateSideHint", new Class[]{Rect.class}, display);
        call(state, "addSource", new Class[]{SOURCE}, source);
    }
    static Insets visible(Object state, Rect window) throws Exception {
        return (Insets) call(state, "calculateVisibleInsets",
                new Class[]{Rect.class, int.class, int.class, int.class, int.class}, window, 1, 1, 16, 0);
    }
    static Insets content(Object state, Rect window) throws Exception {
        WindowInsets insets = (WindowInsets) call(state, "calculateInsets",
                new Class[]{Rect.class, STATE, boolean.class, int.class, int.class, int.class, int.class, int.class, SparseIntArray.class},
                window, null, false, 16, 0, 0, 1, 1, null);
        return ((WindowInsets) call(insets, "consumeCutout", new Class[]{})).getSystemWindowInsets();
    }
    static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS: " + description);
    }
    public static void main(String[] args) throws Exception {
        Rect reverseWindow = new Rect(399, 0, 1410, 1740);
        Object original = state(new Rect(0, 0, 498, 1740));
        Insets before = visible(original, reverseWindow);
        Insets ci = content(original, reverseWindow);
        System.out.println("Before: content=" + ci + "; visible=" + before);
        check(ci.left == 0 && before.left == 99 && before.bottom == 912,
                "reverse rotation reproduces spurious left inset that enables root panning");
        Object copy = STATE.getConstructor(STATE, boolean.class).newInstance(original, true);
        call(copy, "removeSource", new Class[]{int.class}, 7);
        Insets after = visible(copy, reverseWindow);
        System.out.println("After: content=" + content(copy, reverseWindow) + "; visible=" + after);
        check(after.equals(ci), "cleaning consumed cutout aligns visible and content insets");
        check(after.bottom == 912, "keyboard height remains unchanged");
        check(visible(original, reverseWindow).left == 99, "shared original state is unchanged");
        Object normal = state(new Rect(1013, 0, 1510, 1740));
        Rect normalWindow = new Rect(0, 0, 1011, 1740);
        check(visible(normal, normalWindow).equals(content(normal, normalWindow)),
                "normal rotation has no spurious inset, matching working screenshot");
    }
}
