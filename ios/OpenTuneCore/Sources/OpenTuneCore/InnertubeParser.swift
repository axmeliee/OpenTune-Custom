import Foundation

/**
 * Reads YouTube Music's InnerTube responses into the app's models. A port of
 * the Android app's `InnertubeParser`, rule for rule, so a page reads the same
 * on both: browse rows are told from tracks first, video uploads are kept off
 * music shelves, and a release's rows borrow the artist from its header.
 */
public enum InnertubeParser {

    // MARK: Search

    public static func parseSearch(_ response: JSON) -> [SearchResult] {
        var seen = Set<String>()
        var out: [SearchResult] = []
        let cards = response.collect("musicCardShelfRenderer")
        for card in cards {
            if let song = cardShelfSong(card) {
                if !song.isVideo, seen.insert("v:\(song.videoId)").inserted { out.append(.topTrack(song)) }
            } else if let item = cardShelfBrowse(card) {
                if seen.insert("b:\(item.browseId)").inserted { out.append(.browse(item)) }
            }
        }
        // Rows inside an artist's promoted card are billed by the card.
        var cardCredit: [String: Credits] = [:]
        for card in cards {
            guard let credit = cardShelfCredit(card) else { continue }
            for row in card.collect("musicResponsiveListItemRenderer") {
                if let id = rowVideoId(row) { cardCredit[id] = credit }
            }
        }
        for row in response.collect("musicResponsiveListItemRenderer") {
            // Browse rows first: an album row also carries a "play album" videoId.
            if let item = browseItem(row) {
                if seen.insert("b:\(item.browseId)").inserted { out.append(.browse(item)) }
            } else if let song = responsiveListItem(row, fallback: rowVideoId(row).flatMap { cardCredit[$0] } ?? Credits()) {
                if !song.isVideo, seen.insert("v:\(song.videoId)").inserted { out.append(.track(song)) }
            }
        }
        return out
    }

    public static func parseSearchSuggestions(_ response: JSON) -> [String] {
        var seen = Set<String>()
        return response.collect("searchSuggestionRenderer").compactMap { r in
            let q = r.o("navigationEndpoint").o("searchEndpoint").s("query") ?? r.o("suggestion").runs()
            return q.isEmpty || !seen.insert(q).inserted ? nil : q
        }
    }

    // MARK: Home

    public static func parseHome(_ response: JSON) -> [HomeShelf] {
        let sections = response.o("contents").o("singleColumnBrowseResultsRenderer").a("tabs")?.first
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents") ?? []
        return sections.compactMap { section in
            if let c = section.o("musicCarouselShelfRenderer") { return carouselShelf(c) }
            if let s = section.o("musicShelfRenderer") { return plainShelf(s) }
            return nil
        }
    }

    /** The token for more Home shelves, on the first page or on a continuation. */
    public static func sectionListContinuation(_ response: JSON) -> String? {
        let list = response.o("contents").o("singleColumnBrowseResultsRenderer").a("tabs")?.first
            .o("tabRenderer").o("content").o("sectionListRenderer")
            ?? response.o("continuationContents").o("sectionListContinuation")
        guard let list else { return nil }
        return list.a("continuations")?.first.o("nextContinuationData").s("continuation")
            ?? list.a("contents")?.last.o("continuationItemRenderer").o("continuationEndpoint").o("continuationCommand").s("token")
    }

    public static func parseHomeContinuation(_ root: JSON) -> [HomeShelf] {
        var out: [HomeShelf] = []
        func walk(_ node: JSON) {
            switch node {
            case let .object(d, order):
                if let c = d["musicCarouselShelfRenderer"], let shelf = carouselShelf(c) { out.append(shelf) }
                if let s = d["musicShelfRenderer"], let shelf = plainShelf(s) { out.append(shelf) }
                for k in order { if let v = d[k] { walk(v) } }
            case let .array(items): items.forEach(walk)
            default: break
            }
        }
        walk(root)
        return out
    }

    static func carouselShelf(_ carousel: JSON) -> HomeShelf? {
        let header = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
        let title = header.o("title").runs()
        if matches(videoWord, title) { return nil }
        let items: [ShelfItem] = (carousel.a("contents") ?? []).compactMap { item in
            if let two = twoRowItem(item.o("musicTwoRowItemRenderer")) { return two }
            if let song = responsiveListItem(item.o("musicResponsiveListItemRenderer")), !song.isVideo {
                return ShelfItem(title: song.title, subtitle: song.artist, thumbnailUrl: song.thumbnailUrl, videoId: song.videoId, browseId: nil, explicit: song.isExplicit == true)
            }
            return artistRow(item.o("musicResponsiveListItemRenderer"))
        }
        guard !items.isEmpty else { return nil }
        return HomeShelf(title: title.isEmpty ? "For you" : title, items: items, strapline: header.o("strapline").runs())
    }

    static func plainShelf(_ shelf: JSON) -> HomeShelf? {
        let title = shelf.o("title").runs()
        if matches(videoWord, title) { return nil }
        let items = (shelf.a("contents") ?? []).compactMap { responsiveListItem($0.o("musicResponsiveListItemRenderer")) }
            .filter { !$0.isVideo }
            .map { ShelfItem(title: $0.title, subtitle: $0.artist, thumbnailUrl: $0.thumbnailUrl, videoId: $0.videoId, browseId: nil, explicit: $0.isExplicit == true) }
        return items.isEmpty ? nil : HomeShelf(title: title.isEmpty ? "For you" : title, items: items)
    }

    // MARK: Artist

    public static func parseArtist(_ response: JSON) -> ArtistPage {
        let sections = response.o("contents").o("singleColumnBrowseResultsRenderer").a("tabs")?.first
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents") ?? []
        let header = response["header"]
        let name = artistName(header)
        let credit = Credits(artistName: name)
        var songs: [Song] = []
        var moreSongs: String?
        var shelves: [HomeShelf] = []
        for section in sections {
            if let shelf = section.o("musicShelfRenderer") {
                for row in shelf.a("contents") ?? [] {
                    if let s = responsiveListItem(row.o("musicResponsiveListItemRenderer"), fallback: credit) { songs.append(s) }
                }
                if moreSongs == nil {
                    moreSongs = shelf.o("title").a("runs")?.first.o("navigationEndpoint").o("browseEndpoint").s("browseId")
                }
            }
            if let carousel = section.o("musicCarouselShelfRenderer") {
                let h = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
                let title = h.o("title").runs()
                if matches(videoWord, title) { continue }
                let items = (carousel.a("contents") ?? []).compactMap { twoRowItem($0.o("musicTwoRowItemRenderer")) }.filter { $0.browseId != nil }
                let more = h.o("title").a("runs")?.lazy.compactMap { Optional($0).o("navigationEndpoint").o("browseEndpoint") }.first
                    ?? h.o("moreContentButton").o("buttonRenderer").o("navigationEndpoint").o("browseEndpoint")
                if !title.isEmpty, !items.isEmpty {
                    shelves.append(HomeShelf(title: title, items: items, moreBrowseId: more.s("browseId"), moreParams: more.s("params")))
                }
            }
        }
        let immersive = header.o("musicImmersiveHeaderRenderer")
        let b2 = immersive.o("subscriptionButton2").o("subscribeButtonRenderer")
        let b1 = immersive.o("subscriptionButton").o("subscribeButtonRenderer")
        return ArtistPage(
            name: name,
            thumbnailUrl: artistThumbnail(header),
            description: parseDescription(response),
            subscriberCountText: b2.o("subscriberCountWithSubscribeText").firstRunText()
                ?? b1.o("longSubscriberCountText").firstRunText() ?? b1.o("shortSubscriberCountText").firstRunText(),
            monthlyListenerCount: immersive.o("monthlyListenerCount").firstRunText(),
            songs: songs,
            moreSongsBrowseId: moreSongs,
            shelves: shelves
        )
    }

    static func artistName(_ header: JSON?) -> String? {
        let r = header.o("musicImmersiveHeaderRenderer") ?? header.o("musicVisualHeaderRenderer")
        let t = r.o("title").runs()
        return t.isEmpty ? nil : t
    }

    static func artistThumbnail(_ header: JSON?) -> String? {
        guard let header else { return nil }
        let immersive = header.o("musicImmersiveHeaderRenderer")
        let visual = header.o("musicVisualHeaderRenderer")
        let renderer = (immersive.o("thumbnail") ?? visual.o("foregroundThumbnail") ?? visual.o("thumbnail")).o("musicThumbnailRenderer")
            ?? header.collect("musicThumbnailRenderer").first
        return renderer.o("thumbnail").a("thumbnails")?.bestThumbnail
    }

    // MARK: Albums and playlists

    public static func parseCollection(_ root: JSON) -> CollectionPage {
        let header = headerRenderers.lazy.compactMap { root.collect($0).first }.first
        let title = header.o("title").runs()
        let subtitle = headerCreditLines.map { header.o($0).runs() }.filter { !$0.isEmpty }.uniqued().joined(separator: " • ")
        let songs = playlistShelfSongs(root) ?? collectSongsDeep(root)
        return CollectionPage(
            title: title,
            subtitle: subtitle,
            thumbnailUrl: header.map { $0.collect("musicThumbnailRenderer").first.o("thumbnail").a("thumbnails")?.bestThumbnail } ?? nil,
            description: parseDescription(root),
            songs: songs
        )
    }

    /** A playlist page's own tracks, without the "Suggestions" YouTube adds under them. */
    static func playlistShelfSongs(_ root: JSON) -> [Song]? {
        guard let scope = root.o("continuationContents") ?? root.o("contents").o("twoColumnBrowseResultsRenderer").o("secondaryContents") else { return nil }
        guard let playlist = scope.o("musicPlaylistShelfContinuation") ?? scope.collect("musicPlaylistShelfRenderer").first else { return nil }
        let credit = pageCredit(root)
        var seen = Set<String>()
        let songs = playlist.collect("musicResponsiveListItemRenderer").compactMap { responsiveListItem($0, fallback: credit) }
            .filter { seen.insert($0.videoId).inserted }
        return songs.isEmpty ? nil : songs
    }

    /** Every track row anywhere in the response; survives layout differences between page types. */
    public static func collectSongsDeep(_ root: JSON) -> [Song] {
        let credit = pageCredit(root)
        var seen = Set<String>()
        return root.collect("musicResponsiveListItemRenderer").compactMap { responsiveListItem($0, fallback: credit) }
            .filter { seen.insert($0.videoId).inserted }
    }

    public static func parseDescription(_ root: JSON) -> String? {
        let shelf = root.collect("musicDescriptionShelfRenderer").first.o("description").runs()
        if !shelf.isEmpty { return shelf }
        for name in headerRenderers + ["musicImmersiveHeaderRenderer"] {
            let d = root.collect(name).first.o("description").runs()
            if !d.isEmpty { return d }
        }
        return nil
    }

    // MARK: Watch queue

    /** The tracks of a `next` response: the radio mix after a song. */
    public static func parseWatchQueue(_ root: JSON) -> [Song] {
        var seen = Set<String>()
        return root.collect("playlistPanelVideoRenderer").compactMap { r -> Song? in
            guard let videoId = r.s("videoId"), seen.insert(videoId).inserted else { return nil }
            let title = r.o("title").runs()
            guard !title.isEmpty else { return nil }
            let runs = r.o("longBylineText").a("runs") ?? []
            let byline = runs.map { Optional($0).s("text") ?? "" }
            let artist = byline.prefix { !$0.contains("•") }.joined().trimmingCharacters(in: .whitespaces)
            let credits = creditsOf(runs)
            let length = r.o("lengthText").runs()
            return Song(
                videoId: videoId, title: title, artist: artist,
                thumbnailUrl: r.o("thumbnail").a("thumbnails")?.bestThumbnail,
                durationText: length.isEmpty ? nil : length,
                artistId: credits.artistId, albumId: credits.albumId, albumName: credits.albumName,
                isVideo: byline.contains { $0.localizedCaseInsensitiveContains("views") },
                isExplicit: r.hasExplicitBadge ? true : nil
            )
        }
    }

    // MARK: Renderers

    struct Credits: Sendable {
        var artistId: String?
        var artistName: String?
        var albumId: String?
        var albumName: String?
    }

    static func rowVideoId(_ r: JSON) -> String? {
        r.o("playlistItemData").s("videoId")
            ?? r.o("overlay").o("musicItemThumbnailOverlayRenderer").o("content").o("musicPlayButtonRenderer")
            .o("playNavigationEndpoint").o("watchEndpoint").s("videoId")
    }

    static func responsiveListItem(_ renderer: JSON?, fallback: Credits = Credits()) -> Song? {
        guard let renderer, let videoId = rowVideoId(renderer) else { return nil }
        let columns = renderer.a("flexColumns") ?? []
        let title = columns.first.o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        guard !title.isEmpty else { return nil }
        let subtitle = (columns.count > 1 ? columns[1] : nil).o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        let parts = subtitle.components(separatedBy: " • ").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        let runtime = parts.last.flatMap { full(Self.duration, $0) ? $0 : nil }
            ?? (renderer.a("fixedColumns") ?? []).lazy.compactMap { col -> String? in
                let t = Optional(col).o("musicResponsiveListItemFixedColumnRenderer").o("text").runs()
                return full(Self.duration, t) ? t : nil
            }.first
        let rowType = parts.first { typeWords.contains($0.lowercased()) }?.lowercased()
        let artist = parts.first { !full(Self.duration, $0) && !typeWords.contains($0.lowercased()) && !full(tally, $0) }
        let credits = creditsOf(columns.flatMap { Optional($0).o("musicResponsiveListItemFlexColumnRenderer").o("text").a("runs") ?? [] })
        let thumbs = renderer.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails") ?? []
        return Song(
            videoId: videoId,
            title: title,
            artist: credits.artistName.flatMap { $0.isEmpty ? nil : $0 } ?? artist ?? fallback.artistName ?? "Unknown artist",
            thumbnailUrl: thumbs.bestThumbnail,
            durationText: runtime,
            artistId: credits.artistId ?? fallback.artistId,
            albumId: credits.albumId ?? fallback.albumId,
            albumName: credits.albumName ?? fallback.albumName,
            isVideo: rowType == "video" || thumbs.isNotSquare,
            isExplicit: renderer.hasExplicitBadge ? true : nil
        )
    }

    static func browseItem(_ renderer: JSON) -> BrowseItem? {
        guard let endpoint = renderer.o("navigationEndpoint").o("browseEndpoint"), let browseId = endpoint.s("browseId") else { return nil }
        let pageType = endpoint.o("browseEndpointContextSupportedConfigs").o("browseEndpointContextMusicConfig").s("pageType") ?? ""
        let columns = renderer.a("flexColumns") ?? []
        let title = columns.first.o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        guard !title.isEmpty else { return nil }
        let subtitle = (columns.count > 1 ? columns[1] : nil).o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        if matches(videoWord, title) || matches(videoWord, subtitle) { return nil }
        return BrowseItem(
            browseId: browseId, title: title, subtitle: subtitle,
            thumbnailUrl: renderer.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails")?.bestThumbnail,
            type: pageTypeOf(pageType), explicit: renderer.hasExplicitBadge
        )
    }

    static func cardShelfSong(_ r: JSON) -> Song? {
        guard let videoId = r.o("onTap").o("watchEndpoint").s("videoId") else { return nil }
        let title = r.o("title").runs()
        guard !title.isEmpty else { return nil }
        let runs = r.o("subtitle").a("runs") ?? []
        let subtitle = runs.map { Optional($0).s("text") ?? "" }.joined()
        let parts = subtitle.components(separatedBy: " • ").filter { !$0.isEmpty }
        let rowType = parts.first { typeWords.contains($0.lowercased()) }?.lowercased()
        let credits = creditsOf(runs)
        let artist = parts.first { !full(duration, $0) && !typeWords.contains($0.lowercased()) && !full(tally, $0) }
        let thumbs = r.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails") ?? []
        return Song(
            videoId: videoId, title: title,
            artist: credits.artistName.flatMap { $0.isEmpty ? nil : $0 } ?? artist ?? "Unknown artist",
            thumbnailUrl: thumbs.bestThumbnail,
            durationText: parts.last.flatMap { full(duration, $0) ? $0 : nil },
            artistId: credits.artistId, albumId: credits.albumId, albumName: credits.albumName,
            isVideo: rowType == "video" || thumbs.isNotSquare,
            isExplicit: (r["subtitleBadges"]?.hasExplicitBadge ?? false) ? true : nil
        )
    }

    static func cardShelfCredit(_ card: JSON) -> Credits? {
        guard let endpoint = card.o("onTap").o("browseEndpoint") else { return nil }
        let pageType = endpoint.o("browseEndpointContextSupportedConfigs").o("browseEndpointContextMusicConfig").s("pageType") ?? ""
        guard pageType.contains("ARTIST") else { return nil }
        let name = card.o("title").runs()
        return name.isEmpty ? nil : Credits(artistId: endpoint.s("browseId"), artistName: name)
    }

    static func cardShelfBrowse(_ r: JSON) -> BrowseItem? {
        guard let endpoint = r.o("onTap").o("browseEndpoint"), let browseId = endpoint.s("browseId") else { return nil }
        let pageType = endpoint.o("browseEndpointContextSupportedConfigs").o("browseEndpointContextMusicConfig").s("pageType") ?? ""
        let title = r.o("title").runs()
        guard !title.isEmpty else { return nil }
        let subtitle = r.o("subtitle").runs()
        if matches(videoWord, title) || matches(videoWord, subtitle) { return nil }
        return BrowseItem(
            browseId: browseId, title: title, subtitle: subtitle,
            thumbnailUrl: r.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails")?.bestThumbnail,
            type: pageTypeOf(pageType), explicit: r["subtitleBadges"]?.hasExplicitBadge ?? false
        )
    }

    static func artistRow(_ renderer: JSON?) -> ShelfItem? {
        guard let renderer else { return nil }
        let endpoint = renderer.o("navigationEndpoint").o("browseEndpoint")
        let pageType = endpoint.o("browseEndpointContextSupportedConfigs").o("browseEndpointContextMusicConfig").s("pageType") ?? ""
        guard pageType.contains("ARTIST"), let browseId = endpoint.s("browseId") else { return nil }
        let columns = renderer.a("flexColumns") ?? []
        let title = columns.first.o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        guard !title.isEmpty else { return nil }
        let subtitle = (columns.count > 1 ? columns[1] : nil).o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        return ShelfItem(title: title, subtitle: subtitle,
                         thumbnailUrl: renderer.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails")?.bestThumbnail,
                         videoId: nil, browseId: browseId, explicit: false)
    }

    static func twoRowItem(_ renderer: JSON?) -> ShelfItem? {
        guard let renderer else { return nil }
        let title = renderer.o("title").runs()
        guard !title.isEmpty else { return nil }
        let endpoint = renderer.o("navigationEndpoint")
        let browseId = endpoint.o("browseEndpoint").s("browseId")
        // Tracks YouTube never catalogued open an "MPED<videoId>" page; that's the video id.
        let videoId = endpoint.o("watchEndpoint").s("videoId") ?? browseId.flatMap { $0.hasPrefix("MPED") ? String($0.dropFirst(4)) : nil }
        let resolved = browseId.flatMap { $0.hasPrefix("MPED") ? nil : $0 }
        let thumbs = renderer.o("thumbnailRenderer").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails") ?? []
        let subtitle = renderer.o("subtitle").runs()
        if resolved == nil, videoId != nil, thumbs.isNotSquare { return nil }
        if let r = resolved, !["VL", "PL", "MPRE", "UC"].contains(where: r.hasPrefix), matches(videoWord, title) || matches(videoWord, subtitle) { return nil }
        if resolved == nil && videoId == nil { return nil }
        return ShelfItem(title: title, subtitle: subtitle, thumbnailUrl: thumbs.bestThumbnail, videoId: videoId, browseId: resolved,
                         explicit: renderer["subtitleBadges"]?.hasExplicitBadge ?? false)
    }

    static func creditsOf(_ runs: [JSON]) -> Credits {
        var c = Credits()
        for run in runs {
            let browse = Optional(run).o("navigationEndpoint").o("browseEndpoint")
            guard let id = browse.s("browseId") else { continue }
            let pageType = browse.o("browseEndpointContextSupportedConfigs").o("browseEndpointContextMusicConfig").s("pageType") ?? ""
            if pageType.contains("ARTIST"), c.artistId == nil { c.artistId = id; c.artistName = Optional(run).s("text") }
            else if pageType.contains("ALBUM"), c.albumId == nil { c.albumId = id; c.albumName = Optional(run).s("text") }
        }
        return c
    }

    /** Who a release page is billed to, off its own header; playlists say who made them, so they aren't. */
    static func pageCredit(_ root: JSON) -> Credits {
        guard let header = headerRenderers.lazy.compactMap({ root.collect($0).first }).first else { return Credits() }
        // The current header hangs the artist off a strapline; the older one
        // packs it into the subtitle, "Album • Artist • 2024". Split per line.
        let lines = headerCreditLines.map { header.o($0).a("runs") ?? [] }
        let parts = lines.flatMap { line in
            line.map { Optional($0).s("text") ?? "" }.joined().components(separatedBy: " • ").map { $0.trimmingCharacters(in: .whitespaces) }
        }
        guard parts.contains(where: { releaseWords.contains($0.lowercased()) }) else { return Credits() }
        var credits = creditsOf(lines.flatMap { $0 })
        if credits.artistName?.isEmpty == false { return credits }
        credits.artistName = parts.first {
            !$0.isEmpty && !typeWords.contains($0.lowercased()) && !full(tally, $0) && !full(year, $0) && !full(duration, $0)
        }
        return credits
    }

    /** The credit out of "Song • Artist": drops the type word, counts and runtimes. */
    public static func artistFromSubtitle(_ subtitle: String) -> String {
        let parts = subtitle.components(separatedBy: " • ").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        return parts.first { !typeWords.contains($0.lowercased()) && !full(tally, $0) && !full(duration, $0) } ?? subtitle
    }

    static func pageTypeOf(_ pageType: String) -> BrowseType {
        if pageType.contains("ALBUM") { return .album }
        if pageType.contains("ARTIST") { return .artist }
        if pageType.contains("PLAYLIST") { return .playlist }
        return .other
    }

    // MARK: Patterns

    static let duration = try! NSRegularExpression(pattern: #"\d+:\d{2}"#)
    static let year = try! NSRegularExpression(pattern: #"\d{4}"#)
    /** A counted quantity ("12.4M plays", "13 songs"), narrow enough to leave "21 Savage" alone. */
    static let tally = try! NSRegularExpression(pattern: #"[\d.,]+\s*[KMB]?\s+(plays|views|likes|songs|tracks|subscribers|hours?|minutes?|seconds?)\b.*"#, options: [.caseInsensitive])
    static let videoWord = try! NSRegularExpression(pattern: #"\bvideos?\b"#, options: [.caseInsensitive])
    static let releaseWords: Set<String> = ["album", "single", "ep"]
    static let typeWords: Set<String> = ["song", "video", "album", "single", "ep", "artist", "playlist", "podcast", "episode"]
    static let headerRenderers = ["musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer"]
    static let headerCreditLines = ["straplineTextOne", "subtitle"]

    static func full(_ re: NSRegularExpression, _ s: String) -> Bool {
        let range = NSRange(s.startIndex..., in: s)
        guard let m = re.firstMatch(in: s, options: [.anchored], range: range) else { return false }
        return m.range.length == range.length
    }

    static func matches(_ re: NSRegularExpression, _ s: String) -> Bool {
        re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }
}

extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
