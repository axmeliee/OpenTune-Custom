# Custom stable APK pipeline

The default `custom` branch holds the automation and `app.patch`. Every six hours,
or via Actions → Custom stable release → Run workflow, the workflow resolves the
latest **published stable** `justshivv/Opentune` release to an exact commit and
prepares a separate clean source checkout. The patch must pass `git apply --check`;
there is no fuzzy fallback or automated conflict resolution.

Preparation pins `android.experimental.lint.version=9.3.3` in `gradle.properties`.
This tested lint update fixes Android 37 inheritance analysis without suppressing
manifest checks. `custom/lint-compatibility.md`, when present, is copied into each
generated source tag as evidence of the diagnosis and validation.

Preparation changes the Android application ID to `com.opentune.custom`, computes
`upstream versionCode * 100 + 1`, and uses `<upstream>-custom.1` as versionName.
It retains the upstream changelog and original copyright/license files. Exact
prepared source, custom tooling and provenance are published in the custom source
tag. Upstream workflow files are removed from generated source tags to prevent
unintended upstream publication jobs.

Repository secrets (all four required): `OPENTUNE_KEYSTORE_BASE64`,
`OPENTUNE_KEYSTORE_PASSWORD`, `OPENTUNE_KEY_ALIAS`, `OPENTUNE_KEY_PASSWORD`.
Use the **existing custom APK's key**. Never replace it with a runner/debug key.
The key exists only in runner temporary storage and is removed in an always step.
Before any upstream build code runs, the key's certificate must match the original
custom APK SHA-256 `e8ce6f3b61626581556e2b245f98b9e4aa4c8e0d59dd457d6f5fd3a75c9f9483`.
The workflow verifies APK signature, certificate against that key, package and
version before publication. Secrets are never copied into the source tag.

The workflow publishes through a draft release only after behavior tests and a
signed release build pass. Assets: `OpenTune-v<version>-custom.1-universal.apk`,
`SHA256SUMS.txt`, `provenance.json`. A rerun verifies downloaded assets and source
provenance before skipping an existing release. An incomplete public release or
incompatible patch fails for manual inspection; it is never silently overwritten.
An interrupted draft can be resumed when prepared source is unchanged.

Failures retain test reports for 14 days and open/update one issue per custom tag.
Android still asks the user to approve installing an update on the phone.
The monitor commits only `.github/monitor-state.json` every 28 days to keep public
repository scheduling active during long gaps between upstream releases. This
also runs when there is no new release or signing secrets are still missing.
It uses a normal push to the default branch and never overwrites concurrent
external changes; a keepalive error is visible separately and does not stop the
release check. This state-only commit does not trigger another workflow run.
GitHub outages, delayed schedules and manually disabled Actions can still interrupt
monitoring; the keepalive cannot run while Actions itself is disabled.

Local pipeline tests (no Android SDK or Python packages required):
`python -m unittest discover -s custom/tests -v`.

Maintaining a new patch: start from the pinned upstream release, port the smallest
feature/updater changes, run Android tests, then regenerate `custom/app.patch` for
app sources/tests/resources only. Dynamic Gradle identity, signing and SDK metadata
belong to `pipeline.py`, not the patch. Changes in upstream Gradle conventions fail
closed and require review before a new stable release can be published.
