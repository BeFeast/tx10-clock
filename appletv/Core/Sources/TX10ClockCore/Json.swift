import Foundation

/// The value model produced by ``Json/parse(_:)``.
///
/// Objects preserve document order and are represented as key/value pairs (the
/// Java parser used a `LinkedHashMap`); duplicate keys have already been
/// rejected by the parser. Integral numbers stay `.int` and fractional numbers
/// become `.double` — the distinction is load-bearing for strict validation.
public enum JSONValue {
    case object([(String, JSONValue)])
    case array([JSONValue])
    case string(String)
    case int(Int64)
    case double(Double)
    case bool(Bool)
    case null

    /// The value for `key` when this is an object, else `nil`.
    public subscript(key: String) -> JSONValue? {
        guard case .object(let pairs) = self else { return nil }
        return pairs.first { $0.0 == key }?.1
    }
}

extension JSONValue: Equatable {
    public static func == (lhs: JSONValue, rhs: JSONValue) -> Bool {
        switch (lhs, rhs) {
        case (.object(let a), .object(let b)):
            return a.count == b.count
                && zip(a, b).allSatisfy { $0.0 == $1.0 && $0.1 == $1.1 }
        case (.array(let a), .array(let b)):
            return a == b
        case (.string(let a), .string(let b)):
            return a == b
        case (.int(let a), .int(let b)):
            return a == b
        case (.double(let a), .double(let b)):
            return a == b
        case (.bool(let a), .bool(let b)):
            return a == b
        case (.null, .null):
            return true
        default:
            return false
        }
    }
}

/// A tiny, strict, dependency-free JSON reader used for external configuration.
///
/// Strictness is the point. Beyond the JSON grammar it enforces:
/// - Duplicate keys in any object are rejected (many lenient parsers silently
///   keep the last one).
/// - Nesting depth is bounded so a pathological document cannot exhaust the
///   stack.
/// - No trailing content is allowed after the top-level value.
public enum Json {

    /// Maximum object/array nesting the reader will descend before rejecting.
    public static let maxDepth = 32

    /// Parse a complete JSON document. Throws ``ConfigError`` with reason
    /// `MALFORMED` for any syntax error and `DUPLICATE_KEY` for a repeated
    /// object key.
    public static func parse(_ text: String) throws -> JSONValue {
        var parser = Parser(text)
        parser.skipWhitespace()
        let value = try parser.readValue()
        parser.skipWhitespace()
        if parser.pos != parser.units.count {
            throw malformed("trailing content after JSON value")
        }
        return value
    }

    static func malformed(_ message: String) -> ConfigError {
        ConfigError(reason: ConfigError.malformed, message: message)
    }
}

private func cu(_ scalar: Unicode.Scalar) -> UInt16 {
    UInt16(scalar.value)
}

// The cursor walks UTF-16 code units so \u escapes, surrogate pairs, and the
// control-character check behave exactly like the Java char-based parser.
private struct Parser {

    let units: [UInt16]
    var pos = 0
    var depth = 0

    init(_ text: String) {
        units = Array(text.utf16)
    }

    mutating func readValue() throws -> JSONValue {
        if pos >= units.count {
            throw Json.malformed("unexpected end of input")
        }
        let c = units[pos]
        switch c {
        case cu("{"):
            return .object(try readObject())
        case cu("["):
            return .array(try readArray())
        case cu("\""):
            return .string(try readString())
        case cu("t"), cu("f"):
            return .bool(try readBoolean())
        case cu("n"):
            try readNull()
            return .null
        default:
            if c == cu("-") || (c >= cu("0") && c <= cu("9")) {
                return try readNumber()
            }
            throw Json.malformed("unexpected character '\(describe(c))'")
        }
    }

    private mutating func readObject() throws -> [(String, JSONValue)] {
        try enter()
        try expect(cu("{"))
        var object: [(String, JSONValue)] = []
        skipWhitespace()
        if try peek() == cu("}") {
            pos += 1
            leave()
            return object
        }
        while true {
            skipWhitespace()
            if try peek() != cu("\"") {
                throw Json.malformed("expected string key in object")
            }
            let key = try readString()
            skipWhitespace()
            try expect(cu(":"))
            skipWhitespace()
            let value = try readValue()
            if object.contains(where: { $0.0 == key }) {
                throw ConfigError(reason: ConfigError.duplicateKey,
                                  message: "duplicate key '\(key)'")
            }
            object.append((key, value))
            skipWhitespace()
            let c = try next("expected ',' or '}' in object")
            if c == cu("}") {
                break
            }
            if c != cu(",") {
                throw Json.malformed("expected ',' or '}' in object")
            }
        }
        leave()
        return object
    }

    private mutating func readArray() throws -> [JSONValue] {
        try enter()
        try expect(cu("["))
        var array: [JSONValue] = []
        skipWhitespace()
        if try peek() == cu("]") {
            pos += 1
            leave()
            return array
        }
        while true {
            skipWhitespace()
            array.append(try readValue())
            skipWhitespace()
            let c = try next("expected ',' or ']' in array")
            if c == cu("]") {
                break
            }
            if c != cu(",") {
                throw Json.malformed("expected ',' or ']' in array")
            }
        }
        leave()
        return array
    }

    private mutating func readString() throws -> String {
        try expect(cu("\""))
        var buffer: [UInt16] = []
        while true {
            if pos >= units.count {
                throw Json.malformed("unterminated string")
            }
            let c = units[pos]
            pos += 1
            if c == cu("\"") {
                return String(decoding: buffer, as: UTF16.self)
            }
            if c == cu("\\") {
                if pos >= units.count {
                    throw Json.malformed("unterminated escape")
                }
                let e = units[pos]
                pos += 1
                switch e {
                case cu("\""): buffer.append(cu("\""))
                case cu("\\"): buffer.append(cu("\\"))
                case cu("/"): buffer.append(cu("/"))
                case cu("b"): buffer.append(0x08)
                case cu("f"): buffer.append(0x0C)
                case cu("n"): buffer.append(cu("\n"))
                case cu("r"): buffer.append(cu("\r"))
                case cu("t"): buffer.append(cu("\t"))
                case cu("u"): buffer.append(try readUnicodeEscape())
                default: throw Json.malformed("invalid escape '\\\(describe(e))'")
                }
            } else if c < 0x20 {
                throw Json.malformed("unescaped control character in string")
            } else {
                buffer.append(c)
            }
        }
    }

    private mutating func readUnicodeEscape() throws -> UInt16 {
        if pos + 4 > units.count {
            throw Json.malformed("truncated \\u escape")
        }
        var value: UInt16 = 0
        for _ in 0..<4 {
            let h = units[pos]
            pos += 1
            guard let digit = hexDigit(h) else {
                throw Json.malformed("invalid \\u escape")
            }
            value = (value << 4) | digit
        }
        return value
    }

    private func hexDigit(_ c: UInt16) -> UInt16? {
        switch c {
        case cu("0")...cu("9"): return c - cu("0")
        case cu("a")...cu("f"): return c - cu("a") + 10
        case cu("A")...cu("F"): return c - cu("A") + 10
        default: return nil
        }
    }

    private mutating func readBoolean() throws -> Bool {
        if match("true") {
            return true
        }
        if match("false") {
            return false
        }
        throw Json.malformed("invalid literal")
    }

    private mutating func readNull() throws {
        if match("null") {
            return
        }
        throw Json.malformed("invalid literal")
    }

    private mutating func readNumber() throws -> JSONValue {
        let start = pos
        var floating = false
        if units[pos] == cu("-") {
            pos += 1
        }
        // Permissive scan then strict parse, mirroring the Java scanner: any
        // '.', 'e', 'E', '+', '-' inside the token flags it fractional, and a
        // token the strict numeric parser rejects is malformed.
        while pos < units.count {
            let c = units[pos]
            if c >= cu("0") && c <= cu("9") {
                pos += 1
            } else if c == cu(".") || c == cu("e") || c == cu("E")
                        || c == cu("+") || c == cu("-") {
                floating = true
                pos += 1
            } else {
                break
            }
        }
        let token = String(decoding: units[start..<pos], as: UTF16.self)
        if floating {
            guard let d = Double(token) else {
                throw Json.malformed("invalid number '\(token)'")
            }
            return .double(d)
        }
        guard let i = Int64(token) else {
            throw Json.malformed("invalid number '\(token)'")
        }
        return .int(i)
    }

    // --- low-level cursor helpers -------------------------------------------

    private mutating func enter() throws {
        depth += 1
        if depth > Json.maxDepth {
            throw Json.malformed("nesting too deep")
        }
    }

    private mutating func leave() {
        depth -= 1
    }

    mutating func skipWhitespace() {
        while pos < units.count {
            let c = units[pos]
            if c == cu(" ") || c == cu("\t") || c == cu("\n") || c == cu("\r") {
                pos += 1
            } else {
                break
            }
        }
    }

    private func peek() throws -> UInt16 {
        if pos >= units.count {
            throw Json.malformed("unexpected end of input")
        }
        return units[pos]
    }

    private mutating func next(_ message: String) throws -> UInt16 {
        if pos >= units.count {
            throw Json.malformed(message)
        }
        let c = units[pos]
        pos += 1
        return c
    }

    private mutating func expect(_ c: UInt16) throws {
        if pos >= units.count || units[pos] != c {
            throw Json.malformed("expected '\(describe(c))'")
        }
        pos += 1
    }

    private mutating func match(_ literal: String) -> Bool {
        let expected = Array(literal.utf16)
        guard pos + expected.count <= units.count else { return false }
        for (i, u) in expected.enumerated() where units[pos + i] != u {
            return false
        }
        pos += expected.count
        return true
    }

    private func describe(_ c: UInt16) -> String {
        String(decoding: [c], as: UTF16.self)
    }
}
