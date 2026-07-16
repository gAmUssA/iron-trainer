package io.gamov.irontrainer.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Replicate Python's float round()/format exactly. Python rounds the binary
 * double with ties-to-even; `new BigDecimal(double)` (the EXACT binary value,
 * not valueOf's canonical string) + HALF_EVEN reproduces it bit-for-bit — so
 * ported reason/summary strings and numeric fields match FastAPI
 * character-for-character. Shared across verticals (readiness, nutrition, …). */
public final class Py {

    private Py() {}

    /** Python truthiness for a nullable number: present and non-zero. */
    public static boolean truthy(Double x) {
        return x != null && x != 0.0;
    }

    public static boolean truthy(Integer x) {
        return x != null && x != 0;
    }

    /** Python round(x, n) → double. */
    public static double round(double x, int n) {
        return new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python round(x) → int. Emits as a JSON integer (90, not 90.0). */
    public static long roundInt(double x) {
        return new BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** Python f"{x:.0f}". */
    public static String f0(double x) {
        return new BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toBigInteger().toString();
    }

    /** Python f"{x:+.0f}" (always-signed). */
    public static String f0signed(double x) {
        String s = f0(x);
        return s.startsWith("-") ? s : "+" + s;
    }

    /** Python f"{x:.1f}". */
    public static String f1(double x) {
        return new BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Python f"{x:.2f}". */
    public static String f2(double x) {
        return new BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Python f"{x:.0%}" — multiply by 100, round ties-to-even, append %. */
    public static String pct0(double x) {
        return new BigDecimal(x * 100).setScale(0, RoundingMode.HALF_EVEN)
                .toBigInteger() + "%";
    }
}
