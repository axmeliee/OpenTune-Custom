import Foundation
import OpenTuneCore

/** Likes and history for the screens, on top of the core's store. */
@MainActor
final class Library: ObservableObject {
    @Published private(set) var liked: [Song] = []
    @Published private(set) var recent: [Song] = []
    @Published private(set) var historyCount = 0

    private let store: LibraryStore

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("OpenTune")
        store = LibraryStore(directory: dir)
        refresh()
    }

    func isLiked(_ song: Song) -> Bool { liked.contains { $0.videoId == song.videoId } }

    func toggleLike(_ song: Song) {
        store.toggleLike(song)
        refresh()
    }

    func recordPlay(_ song: Song) {
        store.recordPlay(song)
        refresh()
    }

    func clearHistory() {
        store.clearHistory()
        refresh()
    }

    var history: [PlayRecord] { store.history }

    private func refresh() {
        liked = store.liked
        recent = store.recentSongs(limit: 20)
        historyCount = store.history.count
    }
}
