package io.github.abdurazaaqmohammed.features.script;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runs JavaScript automation scripts with Rhino, exposing the {@link MpApi}
 * instance as the global {@code mp} object and a global {@code print()}.
 *
 * <p>Rhino runs in interpreted mode ({@code setOptimizationLevel(-1)}) which
 * is required on Android. Scripts execute on a background thread; results go
 * to the {@link Listener}.
 */
public final class MpScriptEngine {

    public interface Listener {
        void onPrint(String text);

        void onError(String error);

        void onDone(String result);
    }

    private MpScriptEngine() {
    }

    /**
     * Evaluates {@code script} on a background thread.
     *
     * @param script     JavaScript source
     * @param scriptName shown in error messages
     * @param api        the {@code mp} object
     * @param listener   receives prints, errors and the final result
     */
    public static void eval(String script, String scriptName, MpApi api, Listener listener) {
        new Thread(() -> {
            org.mozilla.javascript.Context cx = null;
            try {
                cx = org.mozilla.javascript.Context.enter();
                // Mandatory on Android: the optimizer emits JVM bytecode ART cannot run.
                cx.setOptimizationLevel(-1);
                Scriptable scope = cx.initStandardObjects();
                // Expose mp as real JS functions (not the raw Java object) so that
                // returned Strings/booleans/numbers/arrays are genuine JS primitives:
                // mp.read(f) === 'x' and mp.list(d).join(',') then behave as expected.
                ScriptableObject.putProperty(scope, "mp", wrapApi(cx, scope, api));
                ScriptableObject.putProperty(scope, "print", new BaseFunction() {
                    @Override
                    public Object call(org.mozilla.javascript.Context cx, Scriptable scope,
                                       Scriptable thisObj, Object[] args) {
                        api.log(args);
                        return Undefined.instance;
                    }
                });
                Object result = cx.evaluateString(scope, script, scriptName, 1, null);
                String out = (result == null || result == Undefined.instance)
                        ? null : org.mozilla.javascript.Context.toString(result);
                listener.onDone(out);
            } catch (Exception e) {
                listener.onError(describe(e));
            } finally {
                if (cx != null) org.mozilla.javascript.Context.exit();
            }
        }, "mp-script").start();
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) msg = e.toString();
        return msg;
    }

    /**
     * Builds the {@code mp} object: one JS function per public {@link MpApi}
     * method, converting arguments to Java types and return values back to JS
     * primitives (String/boolean/number/Array).
     */
    private static Scriptable wrapApi(org.mozilla.javascript.Context cx,
                                      Scriptable scope, MpApi api) {
        ScriptableObject mp = new NativeObjectShim();
        for (Method m : MpApi.class.getMethods()) {
            if (m.getDeclaringClass() != MpApi.class || m.isSynthetic()) continue;
            BaseFunction fn = new BaseFunction() {
                @Override
                public Object call(org.mozilla.javascript.Context cx, Scriptable scope,
                                   Scriptable thisObj, Object[] args) {
                    try {
                        Object result = m.invoke(api, toJavaArgs(m, args));
                        return toJs(cx, scope, result);
                    } catch (InvocationTargetException e) {
                        // Convert to a script exception so JS try/catch can catch it.
                        throw org.mozilla.javascript.Context.throwAsScriptRuntimeEx(
                                e.getTargetException());
                    } catch (Exception e) {
                        throw org.mozilla.javascript.Context.throwAsScriptRuntimeEx(e);
                    }
                }
            };
            ScriptableObject.putProperty(mp, m.getName(), fn);
        }
        return mp;
    }

    /** Plain JS object to hang the wrapped functions on. */
    private static class NativeObjectShim extends ScriptableObject {
        @Override
        public String getClassName() {
            return "MpApi";
        }
    }

    private static Object[] toJavaArgs(Method m, Object[] args) {
        if (m.isVarArgs()) {
            // Only log(Object...): hand the raw JS values over.
            return new Object[]{args};
        }
        Class<?>[] ps = m.getParameterTypes();
        Object[] out = new Object[ps.length];
        for (int i = 0; i < ps.length; i++) {
            Object a = i < args.length ? args[i] : null;
            Class<?> t = ps[i];
            if (t == String.class) {
                out[i] = a == null || a == Undefined.instance
                        ? null : org.mozilla.javascript.Context.toString(a);
            } else if (t == boolean.class) {
                out[i] = org.mozilla.javascript.Context.toBoolean(a);
            } else if (t == long.class) {
                out[i] = (long) org.mozilla.javascript.Context.toNumber(a);
            } else if (t == int.class) {
                out[i] = (int) org.mozilla.javascript.Context.toNumber(a);
            } else {
                out[i] = a;
            }
        }
        return out;
    }

    private static Object toJs(org.mozilla.javascript.Context cx, Scriptable scope, Object v) {
        if (v == null) return Undefined.instance;
        if (v instanceof String || v instanceof Boolean) return v; // JS primitives
        if (v instanceof Number) return ((Number) v).doubleValue(); // JS number
        if (v instanceof String[]) {
            String[] arr = (String[]) v;
            Scriptable jsArr = cx.newArray(scope, arr.length);
            for (int i = 0; i < arr.length; i++) jsArr.put(i, jsArr, arr[i]);
            return jsArr;
        }
        return v;
    }
}
