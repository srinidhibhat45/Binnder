# Changes

## 0.1.2 — prepared; GitHub publication pending

- GitHub release builds embed their own update repository automatically. Manual repository selection remains available.
- Schedule automatic checks every 15 minutes and on return to the app, with a five-minute foreground throttle. Explicit Check for updates always checks immediately.
- Migrate the existing periodic worker to its new interval, skip unconfigured workers, avoid duplicate notifications for the same downloaded release, and accept GitHub’s canonical owner/repository casing.
- Add a publisher that retains remote history, excludes signing material from source, configures encrypted Actions secrets with the original key, and verifies a non-forced push to main.
- Publication is not enabled until an actual repository and GitHub access are supplied, the push succeeds, and the release workflow publishes its APK and update manifest.

## 0.1.1

- Exclude files marked as trashed by Android MediaStore, plus known recycle folders at any depth (including Samsung My Files trash, Gallery trash, and Binnder trash). Exclude both original and trash paths recorded in Binnder’s journal.
- Refresh exclusions when returning to the app. Check again before keeping, queueing deletion, or committing a pending review deletion. A failed trash-status check holds pending deletions rather than deleting unknown items.
- Remove the bottom navigation and tablet rail. Open Recycle bin and Settings from top-bar icons; return with the Back button or system gesture.
- Enlarge preview cards and thumbnails. Keep the three file actions outside the scrollable card so they remain reachable.
- Preload upcoming media previews into a bounded cache, crossfade between files, cancel settling animations when a new swipe starts, and provide haptic threshold feedback. Open previews by tapping the image; move full metadata into a Details sheet. Compact the independent Undo controls into a horizontal strip with countdowns.
- Version code 2, signed with the same release key as 0.1.0 so it can be installed over the previous APK without clearing keep history or the bin index.

Verification: 19 local JVM tests; Android/Compose compilation, native resource packaging, signing, and package checks. Device UI and manufacturer-specific MediaStore behavior still require phone testing.
