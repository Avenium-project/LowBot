package io.lowbot.core;

/** A user-facing error with the HTTP status the local API returns for it. */
public class ApiError extends RuntimeException {
    public final int status;

    public ApiError(int status, String message) {
        super(message);
        this.status = status;
    }
}
