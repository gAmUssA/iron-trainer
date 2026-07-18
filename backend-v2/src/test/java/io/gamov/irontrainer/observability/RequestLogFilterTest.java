package io.gamov.irontrainer.observability;

import org.junit.jupiter.api.Test;

import static io.gamov.irontrainer.observability.RequestLogFilter.Level;
import static io.gamov.irontrainer.observability.RequestLogFilter.level;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestLogFilterTest {

    @Test
    void serverErrorsAreErrorRegardlessOfMethod() {
        assertEquals(Level.ERROR, level(500, "GET"));
        assertEquals(Level.ERROR, level(500, "POST"));
        assertEquals(Level.ERROR, level(503, "PUT"));
    }

    @Test
    void successfulWritesAreInfo() {
        assertEquals(Level.INFO, level(200, "POST"));
        assertEquals(Level.INFO, level(201, "PUT"));
        assertEquals(Level.INFO, level(204, "DELETE"));
        assertEquals(Level.INFO, level(200, "PATCH"));
    }

    @Test
    void clientErrorsOnWritesStayInfoNotError() {
        // A 404/422 on a write is an expected client error, not a 5xx failure,
        // but we still want the mutation attempt at INFO for the flip.
        assertEquals(Level.INFO, level(404, "POST"));
        assertEquals(Level.INFO, level(422, "POST"));
    }

    @Test
    void readsAndClientErrorsAreDebug() {
        assertEquals(Level.DEBUG, level(200, "GET"));
        assertEquals(Level.DEBUG, level(404, "GET"));
        assertEquals(Level.DEBUG, level(401, "GET"));
        assertEquals(Level.DEBUG, level(304, "GET"));
    }
}
