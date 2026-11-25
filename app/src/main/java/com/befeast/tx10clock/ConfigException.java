package com.befeast.tx10clock;

/**
 * Raised when an external configuration document is rejected by strict
 * ingestion. Carries a coarse {@link Reason} so callers (and {@code status.json})
 * can classify the failure without exposing device details or raw input.
 *
 * <p>The message is a short, non-secret explanation intended for the app-owned
 * {@code status.json}; it must never embed absolute host paths, serials, or
 * other device identifiers.
 */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Coarse, verifier-safe classification of why a document was rejected. */
    public enum Reason {
        /** The raw document exceeded the hard byte bound. */
        OVERSIZED,
        /** The bytes were not well-formed JSON (syntax/encoding/nesting). */
        MALFORMED,
        /** A JSON object declared the same key more than once. */
        DUPLICATE_KEY,
        /** The top-level JSON value was not an object. */
        NOT_OBJECT,
        /** An object contained a key outside the accepted schema. */
        UNKNOWN_KEY,
        /** A known key held a value of the wrong JSON type. */
        WRONG_TYPE,
        /** A known key held a value outside its accepted range/domain. */
        OUT_OF_RANGE,
    }

    private final Reason reason;

    public ConfigException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
