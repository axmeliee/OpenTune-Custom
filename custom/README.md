# OpenTune Custom source

Based on OpenTune by its upstream contributors. Original copyright notices and GPL LICENSE are retained. This exact source tree produced the APK for this tag. Custom changes add persistent New songs first playlist insertion, a separate updater channel and custom package/version metadata.

See provenance.json and app.patch here. Rebuild with Java 21, Android SDK platforms;android-37.0 and build-tools;36.0.0: `./gradlew -PuniversalOnly=true assembleRelease`. Supply the original signing keystore and OPENTUNE signing environment variables; the private key is never included in source. Debug builds use `./gradlew assembleDebug`. gradle.properties pins the tested lint version; compatibility evidence is in lint-compatibility.md when supplied with this source.
