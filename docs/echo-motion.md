# Echo motion

Three settings have an Echo option, each chosen on its own:

| Setting | Echo option |
| --- | --- |
| Player → Player background | Echo glow: two drifting radial lights with a 20-second colour cycle |
| Player → Player buttons | Echo: an accent-coloured nine-lobed button with an 8-second rotation |
| Motion → Player opening | Echo: a soft spring without overshoot, on opening and closing |

The glow and rotation hold their positions while paused and when the app is not
resumed. Reduce animation freezes them; Android's disabled animator setting also
stops the decorative clocks. The glow uses the current light/dark palette to
preserve contrast.

These selected effects are adapted from Echo Music, rather than its entire UI.
They make no network calls and use no downloaded animation assets.
Source revision and licence are listed in [third-party notices](../THIRD_PARTY_NOTICES.md).
