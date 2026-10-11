import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct LyricLine: Codable, Hashable, Sendable {
    public var startMs: Int64
    public var endMs: Int64
    public var text: String
}

public enum Lyrics: Codable, Hashable, Sendable {
    case synced([LyricLine], source: String)
    case plain(String, source: String)

    public var source: String {
        switch self {
        case let .synced(_, s), let .plain(_, s): return s
        }
    }
}

public extension Array where Element == LyricLine {
    /** Index of the line playing at [positionMs], or -1 before the first one. */
    func activeIndex(_ positionMs: Int64) -> Int {
        var lo = 0, hi = count - 1, found = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if self[mid].startMs <= positionMs { found = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        return found
    }
}

/** LRC, including the enhanced `<mm:ss.xx>` word tags some sources carry (stripped here). */
public enum LrcParser {
    static let lineTag = try! NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]"#)
    static let wordTag = try! NSRegularExpression(pattern: #"<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>"#)
    static let offsetTag = try! NSRegularExpression(pattern: #"\[offset:\s*([+-]?\d+)\]"#, options: [.caseInsensitive])
    static let lastLineMs: Int64 = 5_000

    public static func parse(_ lrc: String, durationMs: Int64 = 0) -> [LyricLine] {
        let ns = lrc as NSString
        let offset = offsetTag.firstMatch(in: lrc, range: NSRange(location: 0, length: ns.length))
            .flatMap { Int64(ns.substring(with: $0.range(at: 1))) } ?? 0
        var raw: [(Int64, String)] = []
        for line in lrc.components(separatedBy: .newlines) {
            let l = line as NSString
            let tags = lineTag.matches(in: line, range: NSRange(location: 0, length: l.length))
            guard let first = tags.first, first.range.location == 0, let last = tags.last else { continue }
            let text = l.substring(from: last.range.location + last.range.length)
            for t in tags { raw.append((max(0, ms(t, in: l) - offset), text)) }
        }
        raw.sort { $0.0 < $1.0 }
        var out: [LyricLine] = []
        for (i, (start, body)) in raw.enumerated() {
            let end = i + 1 < raw.count ? raw[i + 1].0 : (durationMs > start ? min(durationMs, start + lastLineMs * 2) : start + lastLineMs)
            let stripped = wordTag.stringByReplacingMatches(in: body, range: NSRange(location: 0, length: (body as NSString).length), withTemplate: "")
            let text = stripped.components(separatedBy: .whitespaces).filter { !$0.isEmpty }.joined(separator: " ")
            if text.isEmpty { continue } // an instrumental gap
            out.append(LyricLine(startMs: start, endMs: end, text: text))
        }
        return out
    }

    static func ms(_ m: NSTextCheckingResult, in s: NSString) -> Int64 {
        let min = Int64(s.substring(with: m.range(at: 1))) ?? 0
        let sec = Int64(s.substring(with: m.range(at: 2))) ?? 0
        var frac: Int64 = 0
        if m.range(at: 3).location != NSNotFound {
            let f = s.substring(with: m.range(at: 3))
            switch f.count {
            case 1: frac = (Int64(f) ?? 0) * 100
            case 2: frac = (Int64(f) ?? 0) * 10
            default: frac = Int64(f.prefix(3)) ?? 0
            }
        }
        return min * 60_000 + sec * 1_000 + frac
    }
}

/**
 * Turns a YouTube upload's title and channel into what a lyrics database
 * matches: "The Weeknd - Blinding Lights (Official Video)" by "TheWeekndVEVO"
 * becomes "Blinding Lights" by "The Weeknd". Same rules as on Android.
 */
public enum TrackNameCleaner {
    static let noise = try! NSRegularExpression(
        pattern: #"\s*[(\[](?:[^)\]]*\b(?:official|lyrics?|lyric video|audio|video|visuali[sz]er|mv|m/v|hd|hq|4k|remaster(?:ed)?|explicit|clean|color coded|deluxe|bonus|mono|stereo|radio edit|(?:single|album) version)\b[^)\]]*)[)\]]"#,
        options: [.caseInsensitive])
    static let releaseSuffix = try! NSRegularExpression(
        pattern: #"\s+[-–—]\s+(?:(?:\d{4}\s+)?remaster(?:ed)?(?:\s+(?:version|\d{4}))?|radio edit|(?:single|album|mono|stereo) version|mono|stereo|bonus track|deluxe(?:\s+edition)?)\s*$"#,
        options: [.caseInsensitive])
    static let feat = try! NSRegularExpression(pattern: #"\s*[(\[]?\s*\b(?:feat\.?|ft\.?|featuring)\s.*$"#, options: [.caseInsensitive])
    static let channelSuffix = try! NSRegularExpression(pattern: #"\s*(?:VEVO|- Topic|Official|Music)$"#, options: [.caseInsensitive])
    static let dash = try! NSRegularExpression(pattern: #"\s+[-–—]\s+"#)

    public static func clean(title: String, artist: String) -> (title: String, artist: String) {
        var t = replace(releaseSuffix, replace(noise, title))
        var a = replace(channelSuffix, artist.trimmingCharacters(in: .whitespaces)).trimmingCharacters(in: .whitespaces)
        let tn = t as NSString
        if let d = dash.firstMatch(in: t, range: NSRange(location: 0, length: tn.length)) {
            let left = tn.substring(to: d.range.location).trimmingCharacters(in: .whitespaces)
            let right = tn.substring(from: d.range.location + d.range.length).trimmingCharacters(in: .whitespaces)
            let leftKey = left.lowercased().replacingOccurrences(of: " ", with: "")
            let artistKey = a.lowercased().replacingOccurrences(of: " ", with: "")
            if a.isEmpty || leftKey.contains(artistKey) || artistKey.contains(leftKey) {
                a = left
                t = right
            }
        }
        t = replace(feat, t)
        a = replace(feat, a)
        a = (a.components(separatedBy: ",").first ?? a).components(separatedBy: " & ").first?.trimmingCharacters(in: .whitespaces) ?? a
        t = t.replacingOccurrences(of: #"\s*["“”]\s*"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        return (t.isEmpty ? title.trimmingCharacters(in: .whitespaces) : t, a.isEmpty ? artist.trimmingCharacters(in: .whitespaces) : a)
    }

    static func replace(_ re: NSRegularExpression, _ s: String) -> String {
        re.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: "")
    }
}

/**
 * Lyrics from LRCLIB (lrclib.net): free, keyless, mostly line-synced. The
 * exact match and the search by fields go out together; then a free-text
 * search; then the title alone, taking only a hit by the same artist.
 */
public enum Lrclib {
    static let base = "https://lrclib.net/api"
    static let userAgent = "OpenTune iOS (https://github.com/justshivv/Opentune)"
    static let toleranceS = 8.0
    static let closeS = 2.0

    public static func lookup(title: String, artist: String, durationMs: Int64, session: URLSession = .shared) async throws -> Lyrics? {
        let cleaned = TrackNameCleaner.clean(title: title, artist: artist)
        let durationS = Double(durationMs) / 1000
        var plain: Lyrics?
        func take(_ found: Lyrics?) -> Lyrics? {
            if case .synced = found { return found }
            if plain == nil { plain = found }
            return nil
        }
        async let exact: JSON? = durationMs > 0
            ? try? request("get", ["track_name": cleaned.title, "artist_name": cleaned.artist, "duration": String(Int(durationS))], session)
            : nil
        async let byFields: JSON? = try? request("search", ["track_name": cleaned.title, "artist_name": cleaned.artist], session)
        if let e = await exact, let hit = take(toLyrics(e, durationMs)) { return hit }
        if let hit = take(pick(await byFields, durationS: durationS).flatMap { toLyrics($0, durationMs) }) { return hit }
        let byQuery = try? await request("search", ["q": "\(cleaned.artist) \(cleaned.title)"], session)
        if let hit = take(pick(byQuery, durationS: durationS).flatMap { toLyrics($0, durationMs) }) { return hit }
        let byTitle = try? await request("search", ["track_name": cleaned.title], session)
        if let hit = take(pick(sameArtist(byTitle, cleaned.artist), durationS: durationS).flatMap { toLyrics($0, durationMs) }) { return hit }
        return plain
    }

    /** The synced result closest in length (within 2 s, else within 8 s), else the closest plain one. */
    static func pick(_ results: JSON?, durationS: Double) -> JSON? {
        let all = results?.arrayValue ?? []
        func gap(_ o: JSON) -> Double { durationS <= 0 ? 0 : abs((o["duration"]?.doubleValue ?? durationS) - durationS) }
        let near = all.filter { durationS <= 0 || gap($0) <= toleranceS }.sorted { gap($0) < gap($1) }
        let synced = near.filter { !($0.s("syncedLyrics") ?? "").isEmpty }
        return synced.first { gap($0) <= closeS } ?? synced.first ?? near.first { !($0.s("plainLyrics") ?? "").isEmpty }
    }

    /** Results whose artist matches loosely: one name inside the other, ignoring case, accents and punctuation. */
    static func sameArtist(_ results: JSON?, _ artist: String) -> JSON {
        let want = loose(artist)
        guard want.count >= 2 else { return .array([]) }
        return .array((results?.arrayValue ?? []).filter { o in
            let got = loose(o.s("artistName") ?? "")
            return got.count >= 2 && (got.contains(want) || want.contains(got))
        })
    }

    static func loose(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .lowercased().filter { $0.isLetter || $0.isNumber }
    }

    static func toLyrics(_ o: JSON, _ durationMs: Int64) -> Lyrics? {
        if let lrc = o.s("syncedLyrics"), !lrc.isEmpty {
            let lines = LrcParser.parse(lrc, durationMs: durationMs)
            if !lines.isEmpty { return .synced(lines, source: "LRCLIB") }
        }
        if let text = o.s("plainLyrics"), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .plain(text.trimmingCharacters(in: .whitespacesAndNewlines), source: "LRCLIB")
        }
        return nil
    }

    static func request(_ path: String, _ params: [String: String], _ session: URLSession) async throws -> JSON? {
        var c = URLComponents(string: "\(base)/\(path)")!
        c.queryItems = params.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: c.url!, timeoutInterval: 10)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        for attempt in 0...2 {
            let (data, response) = try await session.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            if code == 404 { return nil }
            if code == 429 || code >= 500 {
                if attempt == 2 { throw Innertube.Failure.badResponse(code) }
                try await Task.sleep(nanoseconds: UInt64(attempt + 1) * 900_000_000)
                continue
            }
            return JSON.parse(data)
        }
        return nil
    }
}
