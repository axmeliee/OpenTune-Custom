import OpenTuneCore
import SwiftUI

struct RootView: View {
    @EnvironmentObject private var player: Player
    @State private var showPlayer = false

    var body: some View {
        TabView {
            RoutedStack { HomeView() }
                .safeAreaInset(edge: .bottom) { miniSpace }
                .tabItem { Label("Home", systemImage: "house.fill") }
            RoutedStack { SearchView() }
                .safeAreaInset(edge: .bottom) { miniSpace }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
            RoutedStack { LibraryView() }
                .safeAreaInset(edge: .bottom) { miniSpace }
                .tabItem { Label("Library", systemImage: "square.stack.fill") }
        }
        .overlay(alignment: .bottom) {
            if player.current != nil {
                MiniPlayer { showPlayer = true }
                    .padding(.horizontal, 8)
                    .padding(.bottom, 54)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: player.current?.videoId)
        .sheet(isPresented: $showPlayer) {
            PlayerView()
                .presentationDragIndicator(.visible)
        }
        .background(Theme.background.ignoresSafeArea())
    }

    /** Room under each page for the mini player when one shows. */
    @ViewBuilder private var miniSpace: some View {
        if player.current != nil { Color.clear.frame(height: 68) }
    }
}

/** The playing song above the tab bar; tap for the full player. */
struct MiniPlayer: View {
    @EnvironmentObject private var player: Player
    let open: () -> Void

    var body: some View {
        if let song = player.current {
            HStack(spacing: 10) {
                Artwork(url: song.thumbnailUrl).frame(width: 42, height: 42)
                VStack(alignment: .leading, spacing: 1) {
                    Text(song.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    SubtitleLine(text: song.artist, explicit: song.isExplicit == true, font: .caption)
                }
                Spacer(minLength: 4)
                Button { player.toggle() } label: {
                    ZStack {
                        Circle().trim(from: 0, to: player.duration > 0 ? player.position / player.duration : 0)
                            .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        if player.isBuffering && !player.isPlaying { ProgressView().scaleEffect(0.7) } else {
                            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 16, weight: .bold))
                        }
                    }
                    .frame(width: 40, height: 40)
                }
                .buttonStyle(.plain)
                Button { player.next() } label: { Image(systemName: "forward.fill").frame(width: 36, height: 40) }
                    .buttonStyle(.plain)
            }
            .padding(.horizontal, 10)
            .frame(height: 60)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(.white.opacity(0.08)))
            .contentShape(Rectangle())
            .onTapGesture(perform: open)
            .gesture(DragGesture(minimumDistance: 20).onEnded { v in
                if v.translation.height < -30 { open() } else if v.translation.width < -60 { player.next() } else if v.translation.width > 60 { player.previous() }
            })
        }
    }
}
