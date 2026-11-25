import Foundation

/// Raised when an external configuration document is rejected by strict
/// ingestion. Carries a coarse reason code so callers (and `status.json`) can
/// classify the failure without exposing device details or raw input.
///
/// The message is a short, non-secret explanation intended for the app-owned
/// `status.json`; it must never embed absolute host paths, serials, or other
/// device identifiers.
public struct ConfigError: Error, Equatable {

    // Reason codes mirror the Java ConfigException.Reason enum names exactly;
    // status.json publishes them verbatim.

    /// The raw document exceeded the hard byte bound.
    public static let oversized = "OVERSIZED"
    /// The bytes were not well-formed JSON (syntax/encoding/nesting).
    public static let malformed = "MALFORMED"
    /// A JSON object declared the same key more than once.
    public static let duplicateKey = "DUPLICATE_KEY"
    /// The top-level JSON value was not an object.
    public static let notObject = "NOT_OBJECT"
    /// An object contained a key outside the accepted schema.
    public static let unknownKey = "UNKNOWN_KEY"
    /// A known key held a value of the wrong JSON type.
    public static let wrongType = "WRONG_TYPE"
    /// A known key held a value outside its accepted range/domain.
    public static let outOfRange = "OUT_OF_RANGE"

    /// Coarse, verifier-safe classification of why a document was rejected.
    /// Always one of the static reason codes above.
    public let reason: String

    /// Short, non-secret explanation suitable for `status.json`.
    public let message: String

    public init(reason: String, message: String) {
        self.reason = reason
        self.message = message
    }
}
