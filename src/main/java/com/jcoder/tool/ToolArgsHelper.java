package com.jcoder.tool;

import java.util.Map;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */

// 让AI写个工具类
public final class ToolArgsHelper {

    private ToolArgsHelper() {
    }

    public static String stringArg(Map<String, Object> args, String name, String defaultValue) {
        if (args == null) {
            return defaultValue;
        }

        Object value = args.get(name);
        return value instanceof String stringValue ? stringValue : defaultValue;
    }

    public static int intArg(Map<String, Object> args, String name, int defaultValue) {
        if (args == null) {
            return defaultValue;
        }

        Object value = args.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        return defaultValue;
    }

    public static long longArg(Map<String, Object> args, String name, long defaultValue) {
        if (args == null) {
            return defaultValue;
        }

        Object value = args.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }

        if (value instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        return defaultValue;
    }

    public static double doubleArg(Map<String, Object> args, String name, double defaultValue) {
        if (args == null) {
            return defaultValue;
        }

        Object value = args.get(name);
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        return defaultValue;
    }

    public static boolean booleanArg(Map<String, Object> args, String name, boolean defaultValue) {
        if (args == null) {
            return defaultValue;
        }

        Object value = args.get(name);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (value instanceof String stringValue) {
            if ("true".equalsIgnoreCase(stringValue)) {
                return true;
            }
            if ("false".equalsIgnoreCase(stringValue)) {
                return false;
            }
        }

        return defaultValue;
    }
}
