package com.example.shop.mcp.core;

/** A refusal or failure whose message is written for the model and its user. */
public class ToolError extends RuntimeException {

    public enum Kind { DENIED, LIMITED, INVALID, TIMEOUT, ERROR }

    private final Kind kind;
    private final Integer retryAfterSeconds;

    public ToolError(Kind kind, String message) {
        this(kind, message, null);
    }

    public ToolError(Kind kind, String message, Integer retryAfterSeconds) {
        super(message);
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Kind kind() {
        return kind;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
