# Binnder

A native Android storage-review app built with Kotlin, Jetpack Compose, and Material 3. Android 11 (API 30) or newer.

## What it does

Version 0.1.1 excludes Android-reported trashed items and known recycle folders from review, refreshes exclusions on resume, and uses a larger preview with fixed action buttons. Recycle bin and Settings are in the top bar; there is no bottom navigation or tablet rail.

| Gesture | Action |
| --- | --- |
| Left | Keep the file and hide that exact file from review for seven days. |
| Right | Queue a move to the recycle bin, retained for thirty days. |
| Up | Ask for confirmation, then queue permanent deletion. |

All deletion actions have an individual five-second Undo control. The destructive operation starts **after** that window. Pending operations survive app restarts. Matching buttons and accessibility actions are available for all three gestures.

Files appear largest first. Review images, all pages of PDFs, videos, audio, and text; use a compatible installed app for formats Android cannot render. Images and PDFs support pinch zoom. The recycle bin supports preview, restore to the original location, and confirmed permanent deletion with Undo.

## Build and install

1. Open this directory in Android Studio. Install JDK 17, Android SDK Platform 36, and current Android build tools.
2. Let Gradle sync, then run the `app` configuration on a device or emulator running Android 11+.
3. On first launch, tap **Allow file access** and enable Binnder under Android’s all-files access setting. Return to the app to scan.

From a terminal with the SDK configured:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper pins Gradle 8.14.3. AGP is 8.13.2, Kotlin 2.2.21, and Compose BOM 2025.10.00.

## GitHub automatic updates

GitHub-built releases automatically embed their repository from `GITHUB_REPOSITORY`; automatic OTA checks are enabled by default. On APKs 0.1.0/0.1.1 already installed from this chat, enter the public `owner/repository` once under **Settings → GitHub updates** and tap **Check for updates** after the first GitHub release is published. This implementation uses public GitHub Releases, without a server, account, or GitHub token on the phone.

### One-time release setup

To update an APK delivered from this workspace, reuse its existing release key rather than generating a different one. The private key and its credentials remain in the original workspace’s `.signing` directory and are excluded from the source download. The key-generation command below is for a new signing identity; changing identities requires a fresh install and will not update the existing app.

Create a public GitHub repository and push this source to its `main` branch. Generate and securely retain a release signing key:

```sh
keytool -genkeypair -v -keystore binnder-release.jks -alias binnder \
  -keyalg RSA -keysize 3072 -validity 10000
```

Use a password manager for the passwords. Keep the keystore outside the repository. Add these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | Base64-encoded bytes of the keystore. On macOS: `base64 -i /path/to/binnder-release.jks | pbcopy`. |
| `SIGNING_STORE_PASSWORD` | Keystore password. |
| `SIGNING_KEY_ALIAS` | `binnder`, if using the command above. |
| `SIGNING_KEY_PASSWORD` | Key password. |

Every push to `main` runs tests and lint, builds a signed APK, and publishes `binnder.apk` plus `update.json` to a new stable release. The first release fails with setup instructions until all four secrets exist; after configuration, rerun the release workflow or push another commit.

**Install the first release APK from GitHub**, then save `owner/repository` in Binnder Settings. Keep using the same signing key. Debug APKs use a different certificate and intentionally refuse release-signed OTA updates; a debug installation must be removed before installing the release build. Restore its bin first.

From 0.1.2, automatic background checks are scheduled every 15 minutes, subject to Android’s scheduling restrictions. Returning to the app also checks, throttled to once per five minutes. **Check for updates** bypasses the throttle. Older installed versions use the earlier six-hour schedule until updated. Downloads are automatic; Settings and an optional notification offer installation. Android requires permission to install apps from Binnder and may require installation confirmation. This is an APK updater, not JavaScript hot reload or an instantaneous GitHub push receiver.

### Publish the prepared project

With the original workspace and signing key present, an authenticated GitHub CLI, and GitHub network access:

```sh
python3 tools/publish_github.py --dry-run
python3 tools/publish_github.py --create
# Or target an existing repository: --repository OWNER/REPOSITORY
```

With `--create`, the publisher creates a public `Binnder` repository under the authenticated account if it is absent. It stops on connectivity and authentication errors. The publisher checks repository ownership and visibility, retains remote branch history, stages an explicit source allowlist, checks push access, installs the original signing key as encrypted Actions secrets, pushes to `main` without force, and verifies the remote commit. It does not change existing repository visibility, include keys in Git, or claim that a queued workflow has already published a release. Monitor the resulting Actions run for build completion. `python3 tools/test_publish_github.py` checks the source exclusion guards without network access.

The app verifies the release SHA-256, package name, strictly increasing version code, and exact signing certificate before offering installation. Downloads are bounded and partial files are discarded after failure. The workflow derives version codes from its run number; when migrating repositories or recreating the workflow, ensure the new code exceeds all installed releases.

## Storage behavior

- Scans mounted shared-storage volumes, including accessible `Android/media`, with explicit all-files access. Android’s private app storage, `Android/data`, and `Android/obb` remain excluded. Unreadable directories are reported.
- Files stay on-device. Internet access is used only for GitHub update checks and downloads. There is no analytics or file-upload code.
- Trash uses `.Binnder-trash` at the root of each source volume. Same-volume moves preserve file bytes and avoid temporary duplicate storage. Binnder’s scan excludes these folders.
- Binned files **still occupy storage**. The app reports the bytes it successfully permanently deleted, rather than pretending that moving into the bin freed space.
- File identity includes path, length, modification time, device, and inode. A changed identity invalidates keep history and prevents a stale deletion from targeting a replacement file.
- SQLite journals moves, restores, pending actions, and deletes. Cleanup reconciles interrupted operations before proceeding. No recursive directory deletion is used.
- Restore refuses to replace an existing destination. If the volume is removed, permission is revoked, or a move fails, the operation preserves the original or binned file and reports the problem.
- Trash expires thirty days after the successful move. WorkManager checks hourly, and the app also checks on resume. Android battery restrictions, a force-stop, or an unmounted SD card can delay cleanup; a powered-off phone cannot run cleanup.
- **Before uninstalling or clearing app data, restore your bin.** The shared-storage trash survives uninstall, but its private filename index does not. Individual trash files can be recovered manually from `.Binnder-trash`, though their stored names are UUIDs.
- Keep and expiry deadlines use device wall-clock time. Changing the clock affects those deadlines. Undo uses monotonic time and a persisted boot counter, so a clock change does not shorten its five-second window.

## Project map

- `Storage.kt`: volume scan, file identity, SQLite journal, keep history, trash/restore/expiry.
- `SafeDisk.kt`: checked file moves and deletion without overwrite or recursive fallbacks.
- `ReviewPolicy.kt`: seven-day, thirty-day, five-second, and repository-validation policies.
- `MainViewModel.kt`: review queue, deferred operations, foreground countdown, update state.
- `MainActivity.kt`: Material 3 review, bin, settings, swipe controls, confirmation, Undo.
- `Previews.kt`: bounded image/PDF rendering, audio/video playback, compatible-app handoff.
- `Updates.kt`: GitHub release checks, download validation, notification, installer handoff.
- `BinnderApplication.kt`: WorkManager cleanup and update workers.
- `.github/workflows/`: pull-request checks and signed release publishing.

## Verification status

Kotlin and Compose sources were compiled with the locally cached compiler and Android SDK. Twenty-three local JUnit tests cover timing boundaries, repository validation, exact-byte bin/restore, destination collisions, stale-file guards, failed moves, checked deletion, recycle-bin exclusions, repository defaults, check throttling, and trusted release URLs. Six separate publisher tests check exclusion of signing credentials and symlinks, repository creation, and connection failure handling. The fallback checker is `python3 tools/check_cached_compiler.py`; it requires an existing local dependency cache and is a developer diagnostic, not the standard build path.

The environment blocks the sockets Gradle and ADB need. An installable APK is built with the cached Kotlin compiler and official SDK tools (manifest merger, AAPT2, D8, zipalign, apksigner) using `tools/build_cached_apk.py`. The downloadable 0.1.1 APK uses the same local release key as 0.1.0; install it over the earlier version to retain history and the bin index. Full Gradle/lint checks, emulator screenshots, and device testing remain unverified. The standard GitHub/Android Studio Gradle build is still the authoritative build path. See `docs/DEVICE_CHECKS.md` for the remaining device checks.
