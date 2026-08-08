package io.gamov.irontrainer.admin;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

/** Binds AdminAuthFilter to admin data endpoints — everything under /api/admin
 * EXCEPT login/logout requires a valid admin_session (bean gfb3). */
@NameBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface RequireAdmin {
}
