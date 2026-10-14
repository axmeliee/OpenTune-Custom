# Publishing OpenTune

The store-package workflow **prepares artifacts only**. It never creates a release, uploads to a store, or publishes the website. Keep the current work on the draft branch until the owner approves publication.

## Available routes

| Channel | What to use | Remaining step |
| --- | --- | --- |
| GitHub Releases / GitHub Store clients | Signed APKs, source repository and stable version tags | Publish an approved non-prerelease GitHub release. Clients discover compatible APK assets; there is no universal separate “GitHub Store” submission API. |
| Obtainium | `https://github.com/justshivv/Opentune` | Add the repository as a GitHub source in Obtainium. Stable signed APK releases are already the supported format. |
| IzzyOnDroid | Signed release APK plus existing Fastlane metadata and screenshots | Request inclusion at https://apt.izzysoft.de/fdroid/ and complete their review. Do not add an availability badge until a listing exists. |
| Official F-Droid | Source code, exported `fdroid/com.opentune.yml`, Fastlane metadata | Complete source-build/scanner review and submit via https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/. The recipe is a submission draft, not proof of acceptance or reproducibility. |
| Your own F-Droid-compatible repository | Existing signed APKs | Use `fdroid init`, place APKs in its `repo/` folder, then `fdroid update`. Publish the signed repository using HTTPS and retain the repository signing key for future indexes. See https://f-droid.org/docs/Setup_an_F-Droid_App_Repo/. |
| Aurora Store | Google Play listing | Aurora is a client for Google Play, not an independent store. Create an approved Play Console listing first. An AAB package is available for review; packaging alone does not establish Play eligibility. |
| Other APK stores | Signed universal APK, descriptions, screenshots and privacy URL | Submit through the store's publisher process and satisfy its rules. |

## Prepare a package

After approval puts the workflow on the default branch, open **Actions → Prepare store package (no publishing) → Run workflow**. Select the reviewed branch/tag and:

- `signed-apk`: signed APKs for GitHub, Obtainium, IzzyOnDroid or other APK stores.
- `fdroid-unsigned`: one unsigned APK, plus a source-pinned F-Droid recipe for review. F-Droid builds from source and signs its own packages; the unsigned APK itself is not an official-store submission.
- `play-bundle`: signed Android App Bundle for Play review. The present app includes an in-app APK updater and `REQUEST_INSTALL_PACKAGES`; prepare a Play-specific distribution that removes those before Play submission. Music-service usage and the listing must also meet Play's policies.

Download the artifact: it includes APK/AAB, SHA256SUMS.txt, Fastlane listing files/images, a version manifest, the exact source commit and publishing instructions. Signed options require the existing `OPENTUNE_KEYSTORE_BASE64`, `OPENTUNE_KEYSTORE_PASSWORD`, `OPENTUNE_KEY_ALIAS`, and `OPENTUNE_KEY_PASSWORD` secrets. No signing key is included in artifacts. Never substitute a new signing key for an already-distributed app.

Local equivalents:

```sh
python scripts/prepare-store-package.py --commit FULL_40_CHARACTER_COMMIT_SHA
./gradlew assembleRelease
./gradlew assembleRelease -PunsignedRelease=true -PuniversalOnly=true
./gradlew bundleRelease
```

The first command validates listing lengths and exports metadata under `build/store-package`. The APK and AAB commands place binaries in Gradle's normal output directories. Configure the release signing environment for signed builds.

## F-Droid review still required

- The app uses YouTube Music/JioSaavn and other external services (`NonFreeNet`). Optional usage counting is enabled by default and uses a random installation identifier (`Tracking`); both are disclosed in the draft metadata.
- The build uses JitPack dependencies, including InnerTubeX and NewPipeExtractor. F-Droid maintainers must verify source provenance and scanner compatibility, potentially requiring source-library recipes. No scanner exclusions or prebuilt-binary exceptions are added here.
- The recipe pins the exported source commit and disables automatic version updates until a maintainer validates the build. Run `fdroid lint` and `fdroid build com.opentune` in an F-Droid build environment, resolve findings, then enable tag-based updates with the maintainer.
- The build currently requires JDK 21, Gradle 8.11.1 and Android compile SDK 37. Confirm these are supported by the chosen F-Droid builder.
- An F-Droid signing certificate normally differs from GitHub's. Cross-store updates require matching signatures, or an approved reproducible-build arrangement. Keep store channels clear for users.

## Listing and website

Canonical app page: https://justshivv.github.io/Opentune/

Privacy policy (available after the website changes are approved and deployed): https://justshivv.github.io/Opentune/privacy/

Metadata source: `fastlane/metadata/android/en-US`. Update the changelog matching `versionCode`, keep the app ID `com.opentune`, increment versionCode for releases and describe only shipped features. Review each store's privacy/data declarations against the actual code, including optional connected services.

The website includes canonical URLs, app structured data, crawlable FAQs and `sitemap.xml`. Once published, submit `https://justshivv.github.io/Opentune/sitemap.xml` in Google Search Console and Bing Webmaster Tools after verifying the site. Indexing/ranking is controlled by search engines. A project-level `/Opentune/robots.txt` would not govern this host: robots.txt belongs at `https://justshivv.github.io/robots.txt`, so this project uses page-level robots metadata and omits private dashboard/invite URLs from the sitemap.
