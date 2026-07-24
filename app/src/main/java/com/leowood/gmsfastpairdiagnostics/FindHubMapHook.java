package com.leowood.gmsfastpairdiagnostics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Read-only coordinate diagnostics for the Google Find Hub app.
 *
 * <p>This intentionally does not transform coordinates. It records the stable
 * Google Maps SDK entry points used for markers and camera movement so the
 * Find Hub call site for a locator tag can be identified before a narrowly
 * scoped WGS-84 -> GCJ-02 correction is enabled.</p>
 */
final class FindHubMapHook {
    private static final String TAG = "[FindHubMapDiag] ";
    private static final int MAX_EVENTS = 200;
    private static final Set<String> HOOKED = new HashSet<>();
    private static final Set<String> LOGGED_EVENTS = new HashSet<>();
    private static int eventCount;

    private FindHubMapHook() {
    }

    static void install(ClassLoader loader, String processName) {
        log("loaded process=" + processName);
        hookLatLngArgument(
                loader,
                "com.google.android.gms.maps.model.MarkerOptions",
                "position",
                "MarkerOptions.position");
        hookLatLngArgument(
                loader,
                "com.google.android.gms.maps.model.Marker",
                "setPosition",
                "Marker.setPosition");
        hookLatLngArgument(
                loader,
                "com.google.android.gms.maps.CameraUpdateFactory",
                "newLatLng",
                "CameraUpdateFactory.newLatLng");
        hookLatLngArgument(
                loader,
                "com.google.android.gms.maps.CameraUpdateFactory",
                "newLatLngZoom",
                "CameraUpdateFactory.newLatLngZoom");
        hookCameraPosition(loader);
    }

    private static void hookLatLngArgument(
            ClassLoader loader, String className, String methodName, String source) {
        Class<?> target = XposedHelpers.findClassIfExists(className, loader);
        if (target == null) {
            log("class not found " + className);
            return;
        }

        String key = className + "#" + methodName;
        synchronized (HOOKED) {
            if (!HOOKED.add(key)) {
                return;
            }
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                target,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Coordinate coordinate = findCoordinate(param.args);
                        if (coordinate != null) {
                            record(source, coordinate, param.method);
                        }
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void hookCameraPosition(ClassLoader loader) {
        Class<?> factory = XposedHelpers.findClassIfExists(
                "com.google.android.gms.maps.CameraUpdateFactory", loader);
        if (factory == null) {
            return;
        }

        String key = factory.getName() + "#newCameraPosition";
        synchronized (HOOKED) {
            if (!HOOKED.add(key)) {
                return;
            }
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                factory,
                "newCameraPosition",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || param.args[0] == null) {
                            return;
                        }
                        Object target = readField(param.args[0], "target");
                        Coordinate coordinate = readCoordinate(target);
                        if (coordinate != null) {
                            record(
                                    "CameraUpdateFactory.newCameraPosition",
                                    coordinate,
                                    param.method);
                        }
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static Coordinate findCoordinate(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            Coordinate coordinate = readCoordinate(arg);
            if (coordinate != null) {
                return coordinate;
            }
        }
        return null;
    }

    private static Coordinate readCoordinate(Object value) {
        if (value == null
                || !"com.google.android.gms.maps.model.LatLng"
                .equals(value.getClass().getName())) {
            return null;
        }
        Object latitude = readField(value, "latitude");
        Object longitude = readField(value, "longitude");
        if (!(latitude instanceof Number) || !(longitude instanceof Number)) {
            return null;
        }
        return new Coordinate(
                ((Number) latitude).doubleValue(),
                ((Number) longitude).doubleValue());
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable error) {
            log("cannot read " + target.getClass().getName() + "." + name
                    + ": " + error);
            return null;
        }
    }

    private static void record(String source, Coordinate coordinate, Object method) {
        String eventKey = source + ":"
                + String.format(Locale.US, "%.5f,%.5f",
                coordinate.latitude, coordinate.longitude);
        synchronized (LOGGED_EVENTS) {
            if (eventCount >= MAX_EVENTS || !LOGGED_EVENTS.add(eventKey)) {
                return;
            }
            eventCount++;
        }

        String signature = method instanceof Method
                ? ((Method) method).toGenericString()
                : String.valueOf(method);
        log(source
                + " lat=" + format(coordinate.latitude)
                + " lon=" + format(coordinate.longitude)
                + " mainlandCandidate=" + isMainlandCandidate(
                coordinate.latitude, coordinate.longitude)
                + " method=" + signature);
        log("callers:\n" + relevantStack());
    }

    /**
     * Deliberately broad diagnostic filter. A production correction must use a
     * mainland polygon and explicit exclusions instead of this rectangle.
     */
    private static boolean isMainlandCandidate(double latitude, double longitude) {
        return longitude >= 72.004
                && longitude <= 137.8347
                && latitude >= 0.8293
                && latitude <= 55.8271;
    }

    private static String relevantStack() {
        StringBuilder out = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        int written = 0;
        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            if (className.equals(FindHubMapHook.class.getName())
                    || className.startsWith("de.robv.android.xposed.")
                    || className.startsWith("com.google.android.gms.maps.")) {
                continue;
            }
            out.append("  at ").append(frame).append('\n');
            if (++written >= 18) {
                break;
            }
        }
        return out.toString();
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.7f", value);
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }

    private static final class Coordinate {
        final double latitude;
        final double longitude;

        Coordinate(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
