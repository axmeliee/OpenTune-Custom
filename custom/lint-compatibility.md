# Independent lint compatibility pin

The v0.4.18 custom release build reported exactly four `Instantiatable`
inheritance errors with AGP 8.10.1 / Android Lint 31.10.1, Kotlin 2.4.10,
JDK 21.0.12.1 and compile target `android-37.0` (SDK platform revision 2).
The report said the two activities did not extend `android.app.Activity`
and the two services did not extend `android.app.Service`.

## Confirmed class-file evidence

On 2026-10-22, JDK `javap` inspected the compiled release classes in
`app/build/tmp/kotlin-classes/release` and the **exact** dependency JARs
listed in
`app/build/intermediates/incremental/lintVitalAnalyzeRelease/release-artifact-libraries.xml`.
The full inheritance chains are:

```text
com.opentune.MainActivity
  -> androidx.activity.ComponentActivity
  -> androidx.core.app.ComponentActivity
  -> android.app.Activity

com.opentune.ui.spotify.ExportifyActivity
  -> androidx.activity.ComponentActivity
  -> androidx.core.app.ComponentActivity
  -> android.app.Activity

com.opentune.playback.PlaybackService
  -> androidx.media3.session.MediaLibraryService
  -> androidx.media3.session.MediaSessionService
  -> androidx.lifecycle.LifecycleService
  -> android.app.Service

androidx.work.impl.foreground.SystemForegroundService
  -> androidx.lifecycle.LifecycleService
  -> android.app.Service
```

These were the resolved AAR `jars/classes.jar` artifacts, not substitute
stubs or another dependency version:

| Artifact | Verified classes |
| --- | --- |
| `androidx.activity:activity:1.11.0` | `androidx.activity.ComponentActivity` |
| `androidx.core:core:1.16.0` | `androidx.core.app.ComponentActivity` |
| `androidx.media3:media3-session:1.11.0` | `MediaLibraryService`, `MediaSessionService` |
| `androidx.lifecycle:lifecycle-service:2.9.4` | `LifecycleService` |
| `androidx.work:work-runtime:2.10.1` | `SystemForegroundService` |

The lint `module.xml` boot classpath pointed to the existing
`platforms/android-37.0/android.jar`; `javap` also confirmed its
`android.app.Activity` and `android.app.Service` declarations. The app's
compiled activities/services are public and have public no-argument
constructors. The errors therefore contradict the inspected bytecode.

## Verified resolution

No manifest suppression or global lint disable is applied. Release checks
and `abortOnError` remain enabled. On 2026-10-22, the integration run completed
`:app:lintVitalReportRelease` with
`-Pandroid.experimental.lint.version=9.3.3`: `BUILD SUCCESSFUL` in 1 minute
41 seconds, with the manifest unchanged and all four inheritance errors gone.
The selected resolution is an independent lint 9.3.3 pin in
`gradle.properties`:

```properties
android.experimental.lint.version=9.3.3
```

This keeps the upstream AGP and Kotlin versions and requires no component
source change. Google explicitly supports running newer lint with older
AGP using this property:
[Combining AGP With Newer Lint](https://googlesamples.github.io/android-custom-lint-rules/usage/newer-lint.md.html).

## Limits

The precise internal reason lint failed to resolve these inheritance
chains has **not** been proven. Compatibility between this older lint
and the newer SDK/Kotlin/dependencies is a hypothesis, not a confirmed
specific defect. The controlled old/new analyzer experiment confirms that
upgrading lint removes the observed false positives; it does not identify
the responsible internal code path. This lint task alone does not verify
the complete release build, installation or startup on a phone.

The inherited R8 Kotlin metadata warnings are a separate toolchain
compatibility concern; updating lint alone does not address them.
