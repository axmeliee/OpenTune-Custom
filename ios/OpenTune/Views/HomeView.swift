import OpenTuneCore
import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var library: Library
    @Environment(\.openRoute) private var openRoute
    @State private var shelves: [HomeShelf] = []
    @State private var continuation: String?
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                header
                if !library.recent.isEmpty {
                    shelf(title: "Listen again", items: library.recent.map {
                        ShelfItem(title: $0.title, subtitle: $0.artist, thumbnailUrl: $0.thumbnailUrl, videoId: $0.videoId, browseId: nil, explicit: $0.isExplicit == true)
                    }, songs: library.recent)
                }
                ForEach(shelves) { s in shelf(title: s.title, strapline: s.strapline, items: s.items) }
                if loading {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if let error, shelves.isEmpty {
                    ErrorState(message: error) { Task { await load() } }
                } else if continuation != nil {
                    Color.clear.frame(height: 1).onAppear { Task { await loadMore() } }
                }
            }
            .padding(.vertical)
        }
        .background(Theme.background)
        .refreshable { await load() }
        .task { if shelves.isEmpty { await load() } }
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(greeting).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.accent)
            Text("What's playing?").font(.largeTitle.weight(.bold))
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 5..<12: return "Good morning"
        case 12..<17: return "Good afternoon"
        case 17..<22: return "Good evening"
        default: return "Late night"
        }
    }

    private func shelf(title: String, strapline: String = "", items: [ShelfItem], songs: [Song]? = nil) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(title: title, strapline: strapline).padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(items) { item in
                        Button { open(item, in: items, songs: songs) } label: { ShelfCard(item: item) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private func open(_ item: ShelfItem, in items: [ShelfItem], songs: [Song]?) {
        if let browseId = item.browseId {
            openRoute?(.of(browseId: browseId, title: item.title))
        } else if let songs, let i = songs.firstIndex(where: { $0.videoId == item.videoId }) {
            player.play(songs, from: i)
        } else if let song = item.song {
            // A lone song from a shelf starts a radio after it.
            player.play([song])
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            let page = try await MusicRepository.home()
            shelves = page.shelves
            continuation = page.continuation
            error = nil
        } catch {
            self.error = "Couldn't load Home. \(error)"
        }
    }

    private func loadMore() async {
        guard let token = continuation, !loading else { return }
        continuation = nil
        if let page = try? await MusicRepository.moreHome(token) {
            shelves += page.shelves.filter { s in !shelves.contains { $0.title == s.title } }
            continuation = page.continuation
        }
    }
}
