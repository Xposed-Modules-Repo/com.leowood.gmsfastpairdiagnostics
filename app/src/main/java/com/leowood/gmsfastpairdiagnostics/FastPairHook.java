package com.leowood.gmsfastpairdiagnostics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Diagnostics for the exact obfuscated classes shipped in Google Play services
 * 26.26.34 (260400-945364269).
 *
 * This version deliberately does not change a return value. It records the
 * decision chain so the actual rejecting predicate can be identified safely.
 */
public final class FastPairHook implements IXposedHookLoadPackage {
    private static final String TAG = "[GmsFastPairDiag] ";
    private static final Pattern MAC =
            Pattern.compile("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}");
    private static final Set<String> HOOKED = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.google.android.gms".equals(lpparam.packageName)) {
            return;
        }

        log("loaded process=" + lpparam.processName);
        hookFinalDecision(lpparam.classLoader);
        hookEligibilityPredicates(lpparam.classLoader);
        hookInitialPairingObserver(lpparam.classLoader);
    }

    private static void hookFinalDecision(ClassLoader loader) {
        Class<?> manager = XposedHelpers.findClassIfExists("drgg", loader);
        if (manager == null) {
            log("drgg not found; this GMS build uses different obfuscation");
            return;
        }

        hookAll(manager, "g", true);
    }

    private static void hookEligibilityPredicates(ClassLoader loader) {
        Class<?> halfSheetPolicy = XposedHelpers.findClassIfExists("dqqi", loader);
        if (halfSheetPolicy != null) {
            // These methods form the decision chain called by drgg.g(dreq).
            for (String name : new String[]{"N", "F", "H", "G", "B", "E"}) {
                hookAll(halfSheetPolicy, name, false);
            }
        } else {
            log("dqqi not found");
        }

        Class<?> manager = XposedHelpers.findClassIfExists("drgg", loader);
        if (manager != null) {
            for (String name : new String[]{"h", "e", "j"}) {
                hookAll(manager, name, false);
            }
        }

        Class<?> environment = XposedHelpers.findClassIfExists("fsdw", loader);
        if (environment != null) {
            hookAll(environment, "b", false);
        }
    }

    private static void hookInitialPairingObserver(ClassLoader loader) {
        Class<?> checker = XposedHelpers.findClassIfExists("dpyf", loader);
        if (checker == null) {
            log("dpyf (InitialPairingDeviceChecker) not found");
            return;
        }

        // dpyf.a() only obtains the Bluetooth/discovery state used by the
        // cached-device check. It is observed here to prove ordering.
        hookAll(checker, "a", false);
        hookAll(checker, "i", false);
    }

    private static synchronized void hookAll(
            Class<?> clazz, String methodName, boolean includeStack) {
        String key = clazz.getName() + "#" + methodName;
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                clazz,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isRelevant(param)) {
                            return;
                        }

                        StringBuilder message = new StringBuilder();
                        message.append(signature((Method) param.method))
                                .append(" result=")
                                .append(safe(param.getResult()));

                        Object request = findRequest(param.args);
                        if (request != null) {
                            message.append(" request=").append(describeRequest(request));
                        }

                        if (param.hasThrowable()) {
                            message.append(" throwable=").append(param.getThrowable());
                        }

                        log(message.toString());
                        if (includeStack) {
                            log("decision stack:\n"
                                    + android.util.Log.getStackTraceString(new Throwable()));
                        }
                    }
                });

        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static boolean isRelevant(XC_MethodHook.MethodHookParam param) {
        if (param.method.getDeclaringClass().getName().equals("dpyf")) {
            return true;
        }
        if (param.method.getDeclaringClass().getName().equals("fsdw")) {
            return true;
        }
        return findRequest(param.args) != null;
    }

    private static Object findRequest(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg != null && "dreq".equals(arg.getClass().getName())) {
                return arg;
            }
        }
        return null;
    }

    private static String describeRequest(Object request) {
        StringBuilder out = new StringBuilder("{");
        Field[] fields = request.getClass().getDeclaredFields();
        int written = 0;
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            if (!(type == String.class
                    || type == int.class
                    || type == boolean.class
                    || type == long.class)) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (written++ > 0) {
                    out.append(", ");
                }
                out.append(field.getName())
                        .append('=')
                        .append(safe(field.get(request)));
                if (written >= 24) {
                    out.append(", …");
                    break;
                }
            } catch (Throwable ignored) {
                // Obfuscated GMS builds may deny access to individual fields.
            }
        }
        return out.append('}').toString();
    }

    private static String signature(Method method) {
        return method.getDeclaringClass().getName()
                + "#"
                + method.getName()
                + Arrays.toString(method.getParameterTypes());
    }

    private static String safe(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        text = MAC.matcher(text).replaceAll("XX:XX:XX:XX:XX:XX");
        if (text.length() > 300) {
            return text.substring(0, 300) + "…";
        }
        return text;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }
}
