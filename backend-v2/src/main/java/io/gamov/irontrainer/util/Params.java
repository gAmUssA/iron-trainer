package io.gamov.irontrainer.util;

import jakarta.ws.rs.WebApplicationException;
import java.util.Locale;

/** Query-param coercion matching FastAPI/pydantic semantics: the lax bool
 * truthy/falsy string set, int parsing that 422s on malformed input, and
 * Python-slice bounds (so a negative limit/weeks behaves like Python's
 * seq[:n] / seq[-n:]). */
public final class Params {

    private Params() {}

    /** pydantic lax bool: {1,true,t,yes,y,on} / {0,false,f,no,n,off}; else 422. */
    public static boolean bool(String v) {
        if (v == null) {
            throw unprocessable();
        }
        switch (v.strip().toLowerCase(Locale.ROOT)) {
            case "1": case "true": case "t": case "yes": case "y": case "on":
                return true;
            case "0": case "false": case "f": case "no": case "n": case "off":
                return false;
            default:
                throw unprocessable();
        }
    }

    /** Integer query param; 422 on a non-integer value (FastAPI parity). */
    public static int intParam(String v) {
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException | NullPointerException e) {
            throw unprocessable();
        }
    }

    /** Resolve Python `seq[:stop]` — a (possibly negative) stop → [0, len]. */
    public static int sliceStop(int stop, int len) {
        return stop < 0 ? Math.max(len + stop, 0) : Math.min(stop, len);
    }

    /** Resolve Python `seq[start:]` — a (possibly negative) start → [0, len]. */
    public static int sliceStart(int start, int len) {
        return start < 0 ? Math.max(len + start, 0) : Math.min(start, len);
    }

    private static WebApplicationException unprocessable() {
        return new WebApplicationException(422);
    }
}
