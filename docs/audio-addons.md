# HTTP audio add-ons

This branch replaces the fixed SpotiFLAC sources, verification grants and registry imports with the [BitChord HTTP add-on protocol](https://bitchord.kushagrasingh.in/docs). The client implementation lives in `core/data/addons`; it does not execute extension packages or downloaded code.

## Use

Open **Settings → Audio → Audio add-ons**, paste a service root or its `manifest.json` URL, and select **Add URL**. Enable sources and use **Move up / Move down** to set the lookup order. Private path/query parameters are preserved locally and excluded from Android backup. Remove deletes the configuration, not saved music.

Use **Max** playback quality, or enable **Lossless below Max**, to check add-ons in the background. Normal playback starts first. Max quality compares eligible matching lossless copies and selects the highest bit-depth/sample-rate score; equal quality preserves source order. Direct FLAC and HLS initialization headers provide measured metadata where available. Signal path shows lookup failures and the format reported by the decoder.

For downloads, choose **Max** in Download quality. The same priority order is used. A source must permit downloads and provide a direct audio file; segmented HLS/DASH sources are skipped for file downloads. If no eligible file is available, normal download sources remain available. A FLAC label cannot guarantee a lossless Bluetooth output.

## Protocol and failure behavior

- HTTPS manifest, `search?q=…`, and `stream/{opaque-id}` requests. Manifest setting defaults are sent with the negotiated lossless quality.
- Search results must match title, credited performers and duration. Unrelated results are never selected merely because they come first.
- Successful manifests/searches are cached for ten minutes and stream responses for five minutes. Concurrent requests are coalesced; errors are not cached. HTTP 429 applies a bounded cooldown.
- Search/resolve has a ten-second budget per source. Playback continues while it runs. A failed source moves to the next eligible source; a decoder failure temporarily skips that source for that recording.
- `allowDownloads=false` excludes a source from downloads. `checkValidLossless=true` gates subsequent requests on a supported wired/USB/HDMI output. Bluetooth codec detection is conservative and currently does not satisfy that gate.
- Direct FLAC headers supply bit depth/sample rate and verify recording duration. Unencrypted HLS/DASH playback is delegated to Media3, with lossless audio preferred and lossy selections rejected. Encrypted responses are unsupported.
- Add-on media uses a separate HTTPS transport without ambient authentication/cookies. Responses and download sizes are bounded; private network destinations are rejected. Diagnostic messages omit signed media URLs and configured URL tokens.

## Platform scope and validation

The client is integrated with the shared Android playback/download engine and phone/TV settings. The desktop JavaFX backend remains unchanged and does not gain FLAC support from this integration. Watch playback shares the engine but has no new add-on editor.

`AddonProtocolTest`, `AddonStoreTest`, and `AddonResolverTest` cover URL handling, quality negotiation, cache/cooldown behavior, persistence, priority/fallback, strict matching and provider restrictions. `scripts/probe-audio-addons.py` performs optional live compatibility checks of the three public example services and redacts URLs. Endpoint success does not establish availability for all recordings or verify playback on a physical device.

Protocol reference: [BitChord](https://github.com/kushagrasinghx/BitChord), GPL-3.0. OpenTune's HTTP adapter is implemented here using that documented contract; the browser preview adapts the GPL-3.0 BitChord web player; Android does not run downloaded addon code.
