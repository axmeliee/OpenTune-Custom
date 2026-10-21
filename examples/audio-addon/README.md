# OpenTune add-on starter

A small Cloudflare Worker implementing manifest, search and direct FLAC resolution. It serves JSON only. The user's device retrieves audio from your media host, not through this Worker. No OpenTune account, verification grant or executable extension is needed.

1. Copy this directory and change the manifest ID/name in `worker.mjs`.
2. Populate `CATALOG` in `wrangler.toml` or the Worker environment with a JSON array. The default empty catalog is valid and returns no songs; it does not pretend to provide audio.
3. Run `node --test worker.test.mjs`, then deploy through Cloudflare using `npx wrangler deploy` with your own account.
4. Paste your deployed HTTPS root or `/manifest.json` URL into OpenTune's Audio add-ons settings.

Each catalog entry has this shape (replace all example values):

```json
{
  "id": "my-recording-1",
  "title": "Recording title",
  "artist": "Artist name",
  "album": "Album title",
  "duration": 180.25,
  "artworkURL": "https://your-media-host.example/cover.jpg",
  "url": "https://your-media-host.example/recording.flac",
  "codec": "flac",
  "sampleRate": 96000,
  "bitDepth": 24
}
```

`duration` is seconds; `sampleRate` is Hz. Measure the file instead of guessing from the requested tier:

```sh
ffprobe -v error -show_entries stream=codec_name,sample_rate,bits_per_raw_sample -show_entries format=duration -of json recording.flac
```

The media host should serve correct `Content-Length`, support byte-range requests (`206` with `Content-Range`), and enable CORS for browser playback. Never convert a compressed source to FLAC and describe it as a higher-quality master. Return the precision your file actually contains. OpenTune also checks recording identity and FLAC headers.

The starter has one lossless quality. For a real multi-quality service, add manifest options and map each tier to a corresponding native rendition. Return `404` for a missing recording, `429` plus `Retry-After` for a temporary quota, and a meaningful HTTP error for failures. Do not return an HTML error page or an empty success as a stream response.

Do not put provider credentials in manifests, search results or repository files. Generate expiring media URLs inside your service where needed. An installed URL may have a private path, but that is still a bearer credential: distribute and store it accordingly.

See the [community add-on guide](../../site/addons/index.html) and [Android implementation notes](../../docs/audio-addons.md).
