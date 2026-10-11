import Foundation

/** A playable YouTube Music track. */
public struct Song: Codable, Hashable, Identifiable, Sendable {
    public var videoId: String
    public var title: String
    public var artist: String
    public var thumbnailUrl: String?
    public var durationText: String?
    public var artistId: String?
    public var albumId: String?
    public var albumName: String?
    /** A music-video upload rather than the catalogue track. */
    public var isVideo: Bool
    /** YouTube Music's "E" mark; nil where the source doesn't say. */
    public var isExplicit: Bool?

    public var id: String { videoId }

    public init(
        videoId: String, title: String, artist: String, thumbnailUrl: String? = nil, durationText: String? = nil,
        artistId: String? = nil, albumId: String? = nil, albumName: String? = nil, isVideo: Bool = false, isExplicit: Bool? = nil
    ) {
        self.videoId = videoId
        self.title = title
        self.artist = artist
        self.thumbnailUrl = thumbnailUrl
        self.durationText = durationText
        self.artistId = artistId
        self.albumId = albumId
        self.albumName = albumName
        self.isVideo = isVideo
        self.isExplicit = isExplicit
    }

    /** "3:20" as milliseconds, or 0. */
    public var durationMs: Int64 {
        guard let durationText else { return 0 }
        let parts = durationText.split(separator: ":").compactMap { Int64($0) }
        return parts.reduce(0) { $0 * 60 + $1 } * 1000
    }
}

public enum BrowseType: String, Codable, Sendable {
    case album, artist, playlist, other

    /** What kind of page a browse id opens, from its prefix. */
    public static func of(_ browseId: String) -> BrowseType {
        if browseId.hasPrefix("MPRE") || browseId.hasPrefix("VLOLAK") || browseId.hasPrefix("OLAK") { return .album }
        if browseId.hasPrefix("UC") || browseId.hasPrefix("FEmusic_library_privately_owned_artist") { return .artist }
        if browseId.hasPrefix("VL") || browseId.hasPrefix("PL") || browseId.hasPrefix("RD") { return .playlist }
        return .other
    }
}

/** A card in a home or artist shelf: a track (videoId) or a page (browseId). */
public struct ShelfItem: Codable, Hashable, Identifiable, Sendable {
    public var title: String
    public var subtitle: String
    public var thumbnailUrl: String?
    public var videoId: String?
    public var browseId: String?
    public var explicit: Bool

    public var id: String { videoId.map { "v:\($0)" } ?? browseId.map { "b:\($0)" } ?? "t:\(title)" }

    public var type: BrowseType? { browseId.map(BrowseType.of) }

    /** The card as a song, when it is one. */
    public var song: Song? {
        videoId.map { Song(videoId: $0, title: title, artist: InnertubeParser.artistFromSubtitle(subtitle), thumbnailUrl: thumbnailUrl, isExplicit: explicit ? true : nil) }
    }
}

public struct HomeShelf: Hashable, Identifiable, Sendable {
    public var title: String
    public var items: [ShelfItem]
    public var strapline: String = ""
    public var moreBrowseId: String?
    public var moreParams: String?
    public var id: String { title + (items.first?.id ?? "") }
}

/** An album, artist or playlist row in search. */
public struct BrowseItem: Hashable, Identifiable, Sendable {
    public var browseId: String
    public var title: String
    public var subtitle: String
    public var thumbnailUrl: String?
    public var type: BrowseType
    public var explicit: Bool
    public var id: String { browseId }
}

public enum SearchResult: Hashable, Identifiable, Sendable {
    case topTrack(Song)
    case track(Song)
    case browse(BrowseItem)

    public var id: String {
        switch self {
        case let .topTrack(s): return "top:\(s.videoId)"
        case let .track(s): return "v:\(s.videoId)"
        case let .browse(b): return "b:\(b.browseId)"
        }
    }
}

public struct ArtistPage: Sendable {
    public var name: String?
    public var thumbnailUrl: String?
    public var description: String?
    public var subscriberCountText: String?
    public var monthlyListenerCount: String?
    public var songs: [Song]
    /** The playlist with all of the artist's songs, when there is one. */
    public var moreSongsBrowseId: String?
    public var shelves: [HomeShelf]
}

/** An album or playlist page. */
public struct CollectionPage: Sendable {
    public var title: String
    public var subtitle: String
    public var thumbnailUrl: String?
    public var description: String?
    public var songs: [Song]
}

/** Sizes the app asks YouTube's image server for. */
public enum Art {
    public static let row = 160
    public static let card = 480
    public static let header = 720
    public static let player = 1200
}

public extension Optional where Wrapped == String {
    /** The same cover at [px] square: YouTube's image URLs carry the size as `w…-h…`. */
    func artworkAt(_ px: Int) -> URL? {
        guard let self else { return nil }
        let resized = self.replacingOccurrences(of: #"w\d+-h\d+"#, with: "w\(px)-h\(px)", options: .regularExpression)
        return URL(string: resized)
    }
}
