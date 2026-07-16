package io.gamov.irontrainer.readiness;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Replicate Python's float round()/format exactly. Python rounds the binary
 * double with ties-to-even; `new BigDecimal(double)` (the EXACT binary value,
 * not valueOf's canonical string) + HALF_EVEN reproduces it bit-for-bit — so
 * readiness reason strings match FastAPI character-for-character. */
final class Py {

    private Py() {}

    /** Python round(x, n) → double. */
    static double round(double x, int n) {
        return new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python f"{x:.0f}". */
    static String f0(double x) {
        return new BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toBigInteger().toString();
    }

    /** Python f"{x:+.0f}" (always-signed). */
    static String f0signed(double x) {
        String s = f0(x);
        return s.startsWith("-") ? s : "+" + s;
    }

    /** Python f"{x:.1f}". */
    static String f1(double x) {
        return new BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Python f"{x:.2f}". */
    static String f2(double x) {
        return new BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Python f"{x:.0%}" — multiply by 100, round ties-to-even, append %. */
    static String pct0(double x) {
        return new BigDecimal(x * 100).setScale(0, RoundingMode.HALF_EVEN)
                .toBigInteger() + "%";
    }
}
