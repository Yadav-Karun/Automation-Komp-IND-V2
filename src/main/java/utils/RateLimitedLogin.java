package utils;

/**
 * The application answered a sign-in with "Too many sign-in attempts. Please wait a few minutes and try again." Suites that can close the browser and open a new one
 * (user, 2026-11-12: "just close the browser and open again") catch this, restart the browser and sign in again instead of waiting.
 */
public class RateLimitedLogin extends RuntimeException {
    public RateLimitedLogin(String message) {
        super(message);
    }
}
