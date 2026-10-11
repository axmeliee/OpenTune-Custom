import Foundation

/**
 * A parsed JSON document, navigated without ever throwing: every lookup on
 * the wrong shape answers nil, so a renderer YouTube moved or renamed costs
 * one missing row rather than the whole page. The same helpers the Android
 * parser uses (`o`, `a`, `s`, `runs`), so the two read alike.
 */
public indirect enum JSON: Equatable, Sendable {
    case object([String: JSON], order: [String])
    case array([JSON])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    public static func parse(_ data: Data) -> JSON? {
        guard let any = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { return nil }
        return JSON(any: any)
    }

    init(any: Any) {
        switch any {
        case let d as [String: Any]:
            var out: [String: JSON] = [:]
            for (k, v) in d { out[k] = JSON(any: v) }
            // JSONSerialization doesn't keep key order; depth-first walks only
            // need a stable one, and document order within arrays is what counts.
            self = .object(out, order: d.keys.sorted())
        case let a as [Any]:
            self = .array(a.map(JSON.init(any:)))
        case let s as String:
            self = .string(s)
        case let n as NSNumber:
            #if canImport(Darwin)
            if CFGetTypeID(n) == CFBooleanGetTypeID() { self = .bool(n.boolValue); return }
            #else
            if String(cString: n.objCType) == "c" { self = .bool(n.boolValue); return }
            #endif
            self = .number(n.doubleValue)
        default:
            self = .null
        }
    }

    public subscript(key: String) -> JSON? {
        if case let .object(d, _) = self { return d[key] }
        return nil
    }

    public var objectValues: [JSON] {
        if case let .object(d, order) = self { return order.compactMap { d[$0] } }
        return []
    }

    public var arrayValue: [JSON]? {
        if case let .array(a) = self { return a }
        return nil
    }

    public var stringValue: String? {
        switch self {
        case let .string(s): return s
        case let .number(n): return n == n.rounded() ? String(Int(n)) : String(n)
        case let .bool(b): return b ? "true" : "false"
        default: return nil
        }
    }

    public var doubleValue: Double? {
        switch self {
        case let .number(n): return n
        case let .string(s): return Double(s)
        default: return nil
        }
    }

    public var boolValue: Bool? {
        if case let .bool(b) = self { return b }
        return nil
    }
}

public extension Optional where Wrapped == JSON {
    func o(_ key: String) -> JSON? {
        guard let v = self?[key], case .object = v else { return nil }
        return v
    }

    func a(_ key: String) -> [JSON]? { self?[key]?.arrayValue }

    func s(_ key: String) -> String? {
        guard let v = self?[key] else { return nil }
        if case let .string(s) = v { return s }
        return nil
    }

    /** The text of a `runs` list, joined. */
    func runs() -> String {
        (a("runs") ?? []).map { Optional($0).s("text") ?? "" }.joined()
    }

    func firstRunText() -> String? { (a("runs")?.first).flatMap { Optional($0).s("text") } }
}

public extension JSON {
    func o(_ key: String) -> JSON? { Optional(self).o(key) }
    func a(_ key: String) -> [JSON]? { Optional(self).a(key) }
    func s(_ key: String) -> String? { Optional(self).s(key) }
    func runs() -> String { Optional(self).runs() }

    /** Depth-first collection of a named renderer, in document order. */
    func collect(_ name: String) -> [JSON] {
        var out: [JSON] = []
        func walk(_ node: JSON) {
            switch node {
            case let .object(d, order):
                if let hit = d[name], case .object = hit { out.append(hit) }
                for k in order { if let v = d[k] { walk(v) } }
            case let .array(items):
                items.forEach(walk)
            default:
                break
            }
        }
        walk(self)
        return out
    }

    /** The first string under [key] anywhere below, by name rather than by path. */
    func findString(_ key: String) -> String? {
        switch self {
        case let .object(d, order):
            if let v = d[key], case let .string(s) = v { return s }
            for k in order { if let found = d[k]?.findString(key) { return found } }
            return nil
        case let .array(items):
            for item in items { if let found = item.findString(key) { return found } }
            return nil
        default:
            return nil
        }
    }

    /** YouTube nests the explicit badge differently across search, album and queue renderers. */
    var hasExplicitBadge: Bool {
        switch self {
        case let .string(s): return s == "MUSIC_EXPLICIT_BADGE"
        case let .array(items): return items.contains { $0.hasExplicitBadge }
        case let .object(d, _): return d.values.contains { $0.hasExplicitBadge }
        default: return false
        }
    }
}

extension Array where Element == JSON {
    /** Last thumbnail is the largest. */
    var bestThumbnail: String? { last.s("url") }

    /** Catalogue art is square; a music-video upload's is widescreen. */
    var isNotSquare: Bool {
        guard let last, let w = last["width"]?.doubleValue, let h = last["height"]?.doubleValue, w > 0, h > 0 else { return false }
        let r = w / h
        return r < 0.85 || r > 1.15
    }
}
