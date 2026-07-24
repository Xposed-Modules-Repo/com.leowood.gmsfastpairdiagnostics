package com.leowood.gmsfastpairdiagnostics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Corrects Find Hub's WGS-84 device coordinates at its map UI boundary.
 *
 * <p>The hook is deliberately limited to the obfuscated Find Hub 3.1.636-1
 * marker pipeline. It does not alter Android Location, GMS, stored locations,
 * network requests, or coordinates outside the GCJ-02 coverage guard.</p>
 */
final class FindHubMapHook {
    private static final String TAG = "[FindHubMapFix] ";
    private static final double PI = Math.PI;
    private static final double EARTH_SEMI_MAJOR_AXIS = 6378245.0;
    private static final double ECCENTRICITY_SQUARED = 0.00669342162296594323;

    /**
     * A location proto may be passed through the UI pipeline more than once.
     * Identity tracking prevents an accidental second nonlinear conversion.
     */
    private static final Set<Object> CONVERTED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private FindHubMapHook() {
    }

    static void install(ClassLoader loader, String processName) {
        Class<?> fragment = XposedHelpers.findClassIfExists("hfo", loader);
        if (fragment == null) {
            log("unsupported Find Hub build: hfo not found");
            return;
        }

        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                fragment,
                "aN",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 1
                                || param.args[0] == null) {
                            return;
                        }
                        convertMarkerCollection(param.args[0]);
                    }
                });
        log("loaded process=" + processName + " hfo#aN overloads=" + hooks.size());
    }

    private static void convertMarkerCollection(Object collection) {
        int converted = 0;
        try {
            Iterator<?> iterator = iteratorOf(collection);
            while (iterator.hasNext()) {
                Object marker = iterator.next();
                Object locationWithAccuracy = getField(marker, "b");
                Object point = getField(locationWithAccuracy, "c");
                if (point == null || wasConverted(point)) {
                    continue;
                }

                double latitude = getDouble(point, "b");
                double longitude = getDouble(point, "c");
                if (!isGcj02Region(latitude, longitude)) {
                    continue;
                }

                Coordinate result = wgs84ToGcj02(latitude, longitude);
                setDouble(point, "b", result.latitude);
                setDouble(point, "c", result.longitude);
                markConverted(point);
                converted++;
                log("marker " + format(latitude) + "," + format(longitude)
                        + " -> " + format(result.latitude) + ","
                        + format(result.longitude));
            }
        } catch (Throwable error) {
            log("marker conversion failed: " + error);
        }
        if (converted > 0) {
            log("converted markers=" + converted);
        }
    }

    private static Iterator<?> iteratorOf(Object collection) throws Exception {
        if (collection instanceof Iterable<?>) {
            return ((Iterable<?>) collection).iterator();
        }
        Method iterator = collection.getClass().getMethod("iterator");
        return (Iterator<?>) iterator.invoke(collection);
    }

    private static Object getField(Object target, String name) throws Exception {
        if (target == null) {
            return null;
        }
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static double getDouble(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(target);
    }

    private static void setDouble(Object target, String name, double value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setDouble(target, value);
    }

    private static boolean wasConverted(Object point) {
        synchronized (CONVERTED) {
            return CONVERTED.contains(point);
        }
    }

    private static void markConverted(Object point) {
        synchronized (CONVERTED) {
            CONVERTED.add(point);
        }
    }

    /**
     * The conventional GCJ-02 coverage check, with explicit HK/Macau/Taiwan
     * exclusions. Coordinates outside it are returned completely unchanged.
     */
    private static boolean isGcj02Region(double latitude, double longitude) {
        if (longitude < 72.004 || longitude > 137.8347
                || latitude < 0.8293 || latitude > 55.8271) {
            return false;
        }
        // Hong Kong
        if (latitude >= 22.08 && latitude <= 22.58
                && longitude >= 113.82 && longitude <= 114.52) {
            return false;
        }
        // Macau
        if (latitude >= 22.06 && latitude <= 22.23
                && longitude >= 113.52 && longitude <= 113.64) {
            return false;
        }
        // Taiwan
        return !(latitude >= 21.80 && latitude <= 25.45
                && longitude >= 119.30 && longitude <= 122.10);
    }

    private static Coordinate wgs84ToGcj02(double latitude, double longitude) {
        double latitudeDelta = transformLatitude(
                longitude - 105.0, latitude - 35.0);
        double longitudeDelta = transformLongitude(
                longitude - 105.0, latitude - 35.0);
        double latitudeRadians = latitude / 180.0 * PI;
        double sinLatitude = Math.sin(latitudeRadians);
        double magic = 1.0 - ECCENTRICITY_SQUARED
                * sinLatitude * sinLatitude;
        double squareRootMagic = Math.sqrt(magic);
        latitudeDelta = latitudeDelta * 180.0
                / ((EARTH_SEMI_MAJOR_AXIS
                * (1.0 - ECCENTRICITY_SQUARED))
                / (magic * squareRootMagic) * PI);
        longitudeDelta = longitudeDelta * 180.0
                / (EARTH_SEMI_MAJOR_AXIS / squareRootMagic
                * Math.cos(latitudeRadians) * PI);
        return new Coordinate(
                latitude + latitudeDelta,
                longitude + longitudeDelta);
    }

    private static double transformLatitude(double x, double y) {
        double result = -100.0 + 2.0 * x + 3.0 * y
                + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * PI)
                + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(y * PI)
                + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        result += (160.0 * Math.sin(y / 12.0 * PI)
                + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return result;
    }

    private static double transformLongitude(double x, double y) {
        double result = 300.0 + x + 2.0 * y
                + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * PI)
                + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(x * PI)
                + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        result += (150.0 * Math.sin(x / 12.0 * PI)
                + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return result;
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
