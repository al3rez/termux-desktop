package android.util;

public final class Log {
    public static int e(String tag, String msg) { System.err.println("E/" + tag + ": " + msg); return 0; }
    public static int w(String tag, String msg) { System.err.println("W/" + tag + ": " + msg); return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int v(String tag, String msg) { return 0; }
}
