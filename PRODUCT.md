# Binnder

<!-- impeccable:product-schema 1 -->

## Platform
android

## Stack
Confirmed: Kotlin, Jetpack Compose, Material 3. Android 11+; GitHub-distributed signed APKs.

## Users
Phone owners reviewing files to clear storage without accidental loss.

## Product Purpose
Review accessible shared-storage files one at a time, largest first, with useful previews.

## Capabilities and Constraints
- Left keeps a file out of review for seven days. Right moves it into a 30-day recycle bin. Up requests permanent deletion with a confirmation dialog.
- Every deletion has a five-second undo window before any destructive operation commits.
- View images, PDFs, videos, audio, and open other supported files in installed viewers.
- Kept-file history persists across restarts; file changes invalidate that history.
- Recycle-bin files consume space. Only actual permanent deletion counts as freed storage.
- Scan shared storage and removable volumes with all-files access; Android protects private app storage.
- Exclude Android MediaStore trashed items, other apps’ known recycle-bin folders, and Binnder’s active bin/journal paths. Recheck on returning to the app.
- Maximize file previews; no persistent app navigation. Recycle bin and Settings are top-bar actions.
- GitHub-built APKs automatically embed their repository and enable update checks by default; the initial chat-installed APK needs the repository entered once. Updates use signed releases, checksums, and system installation confirmation.
- From 0.1.2, background checks are scheduled every 15 minutes and foreground resume checks are throttled to five minutes. Android can delay background work; updates are not an instantaneous push receiver.
- Cleanup and update checking run periodically, subject to Android background scheduling. Overdue trash is cleaned when the app resumes too.

## Brand Commitments
Simple, clean, to-the-point native Material interface. Follow system light/dark theme and wallpaper colors. Working title: Binnder, from project directory (assumption).

## Product Principles
1. File identity and preview precede deletion controls.
2. Keep routine swipes fast; interrupt only for irreversible choices.
3. Preserve data if a move, restore, or validation fails.
4. No accounts, analytics, or file uploads.

## Evidence on Hand
Greenfield project; no existing application or GitHub repository configured.
