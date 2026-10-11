import Foundation

/** One listen: a song that played long enough to count. */
public struct PlayRecord: Codable, Hashable, Sendable {
    public var song: Song
    public var playedAt: Date
}

/**
 * Liked songs and listening history, kept on the phone in one JSON file, the
 * way the Android app keeps them. Nothing leaves the device.
 */
public final class LibraryStore: @unchecked Sendable {
    struct Document: Codable {
        var liked: [Song] = []
        var history: [PlayRecord] = []
    }

    private let file: URL
    private let queue = DispatchQueue(label: "opentune.library")
    private var doc: Document

    /** How many plays history keeps. */
    public static let historyLimit = 500

    public init(directory: URL) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        file = directory.appendingPathComponent("library.json")
        if let data = try? Data(contentsOf: file), let d = try? JSONDecoder().decode(Document.self, from: data) {
            doc = d
        } else {
            doc = Document()
        }
    }

    public var liked: [Song] { queue.sync { doc.liked } }
    public var history: [PlayRecord] { queue.sync { doc.history } }

    public func isLiked(_ videoId: String) -> Bool { queue.sync { doc.liked.contains { $0.videoId == videoId } } }

    /** Likes or unlikes [song]; returns whether it is now liked. */
    @discardableResult
    public func toggleLike(_ song: Song) -> Bool {
        queue.sync {
            if let i = doc.liked.firstIndex(where: { $0.videoId == song.videoId }) {
                doc.liked.remove(at: i)
                save()
                return false
            }
            doc.liked.insert(song, at: 0)
            save()
            return true
        }
    }

    public func recordPlay(_ song: Song, at date: Date = Date()) {
        queue.sync {
            // Played again soon after: one listen, moved to the top.
            doc.history.removeAll { $0.song.videoId == song.videoId && date.timeIntervalSince($0.playedAt) < 600 }
            doc.history.insert(PlayRecord(song: song, playedAt: date), at: 0)
            if doc.history.count > Self.historyLimit { doc.history.removeLast(doc.history.count - Self.historyLimit) }
            save()
        }
    }

    /** Recent distinct songs, newest first. */
    public func recentSongs(limit: Int = 20) -> [Song] {
        var seen = Set<String>()
        return history.map(\.song).filter { seen.insert($0.videoId).inserted }.prefix(limit).map { $0 }
    }

    public func clearHistory() {
        queue.sync {
            doc.history = []
            save()
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(doc) else { return }
        try? data.write(to: file, options: .atomic)
    }
}
