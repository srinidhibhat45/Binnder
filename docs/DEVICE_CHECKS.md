# Android device checks

Run on disposable fixture files first. These checks exercise Android/FUSE, SQLite recovery, WorkManager, file viewing, and installer behavior that local JVM tests cannot establish.

1. Build with `./gradlew testDebugUnitTest lintDebug assembleDebug`. Confirm the same pinned dependencies compile in CI.
2. Deny all-files access: verify the explanation and settings link. Grant it, return, and verify the first scan. Revoke it during use and verify operations stop safely.
3. Seed known files across Download, DCIM, Documents, Android/media, and an SD card. Include duplicate filenames in different directories, hidden files, zero-byte files, Unicode filenames, and files over 4 GB. Confirm largest-first order and category filtering.

   Also move fixtures to Samsung Gallery/My Files trash (or the device’s equivalent), a nested `.Trash`/`.RecycleBin` folder, and Binnder’s own bin. None should appear in Review. Check a MediaStore-trashed file that retains a normal filename. Background Binnder, trash a visible fixture in another app, and return: it should leave the review queue. Binnder’s own Recycle bin screen must still preview and restore its entries.
4. Preview a portrait EXIF-rotated image, multipage PDF, encrypted/corrupt PDF, playable and unsupported videos, audio, text, and an archive. Check zoom, PDF paging, playback controls, external viewer grants, and no-viewer errors. Backgrounding or leaving a preview must stop/pause playback.
5. Swipe left, force-stop/reopen, and verify that the file remains hidden. Advance a test clock past seven days. Change the kept file’s contents/mtime and verify it returns on a new scan.
6. Swipe right: confirm the original remains during the five-second grace period. Undo just before expiry. Repeat with several rapid deletions and undo them independently. Commit, check byte equality in `.Binnder-trash`, restore, and verify original path, size, and bytes.
7. Swipe up: cancel the dialog. Confirm another request and undo within five seconds. Confirm without Undo and verify permanent removal, accurate deletion accounting, and removal from system media listings.
8. Create another file at a binned file’s original path. Restore must fail while preserving both files. Move the conflicting file away and retry.
9. Replace/change a file after it appears in review and while deletion is pending. Verify that the new identity is never deleted. Remove and reinsert the SD card during pending actions and trash restore.
10. Kill the process during each journal state: pending, moving, restoring, deleting, purging. Reopen and run cleanup. Verify no duplicate actions, overwrite, or unjournaled loss. Reboot with pending actions and overdue trash.
11. Use a test-only database/time fixture for 30-day expiry. Confirm that only expired files are deleted, and that permission denial and volume removal retain records until recovery. Test cleanup after app force-stop/relaunch.
12. Test light/dark Dynamic Color and Android 11 fallback colors, font scale 1.3 and 2.0, TalkBack actions, system Back, screen rotation, phone landscape, and a tablet navigation rail. Capture native screenshots.
13. Install a signed release A, publish a higher-code release B, configure the public repository, and test automatic download/check-now, optional notification, install-source permission, and Android confirmation. Verify files, keep history, and bin persist across the update.
14. Reject wrong-signature, wrong-package, wrong-version, corrupt-checksum, missing-manifest, and truncated APK releases. Test offline mode, API rate limiting, low storage, repository changes during download, and declined installation.
