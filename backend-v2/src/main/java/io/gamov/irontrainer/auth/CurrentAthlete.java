package io.gamov.irontrainer.auth;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.NotAuthorizedException;

/** Per-request tenancy — the CDI replacement for FastAPI's ContextVar.
 * Populated by BearerAuthFilter; repositories and resources inject this and
 * never touch athlete ids from user input. */
@RequestScoped
public class CurrentAthlete {

    private Integer athleteId;

    public void set(Integer id) {
        this.athleteId = id;
    }

    public int require() {
        if (athleteId == null) {
            throw new NotAuthorizedException("Bearer");
        }
        return athleteId;
    }
}
