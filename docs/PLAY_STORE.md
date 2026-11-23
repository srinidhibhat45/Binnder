# Google Play listing and policy answers

Copy these into Play Console. Build the upload file with **Actions → Build Google Play bundle → Run workflow**, then download the `binnder-play-aab` artifact.

## Store listing

- **App name:** Binnder
- **Short description (≤80):** Swipe through your largest files to keep, bin, or delete them safely.
- **Category:** Tools
- **Full description:**

> Binnder helps you free up storage on your phone, one file at a time, starting with the largest.
>
> Swipe left to keep a file, right to move it to a 30-day recycle bin, or up to delete it permanently after confirming. Every deletion waits five seconds with an Undo button, so a slip of the finger never costs you a file.
>
> • Previews for images, every page of PDFs, videos, audio, and text, with pinch zoom
> • Recycle bin with preview, restore to the original folder, and automatic cleanup after 30 days
> • Skips files already in Android's or other apps' trash
> • Shows the space you have actually freed, not just what you moved
> • Works on internal storage and SD cards
> • Follows your system light/dark theme and wallpaper colors
>
> Private by design: no accounts, no ads, no analytics, and no internet access. Your files never leave your phone.

- **Privacy policy URL:** https://github.com/srinidhibhat45/Binnder/blob/main/docs/PRIVACY.md

## App content answers

- **Ads:** No ads.
- **App access:** All functionality is available without special access (no login). Reviewers must grant "All files access" when prompted.
- **Content rating:** Utility app; no violence, sexual content, gambling, user interaction, or location sharing.
- **Target audience:** 18+ (not designed for children).
- **News app:** No. **Government app:** No. **Financial features:** None. **Health:** No.
- **Data safety:** The app collects no data and shares no data. (Files are processed on-device only; the Play build has no network permission.)

## All files access (MANAGE_EXTERNAL_STORAGE) declaration

- **Core feature:** File management / storage cleanup.
- **Justification:** Binnder is a storage-review tool. Its core and only purpose is to let the user browse every file on shared storage and removable volumes (largest first), preview it, and then keep, move to a recycle bin, restore, or permanently delete it. This requires listing and modifying files of all types (documents, archives, APKs, downloads, media) across all directories, including the app's own `.Binnder-trash` folders at the root of each volume. MediaStore and the Storage Access Framework cannot enumerate non-media files across the whole volume or move files within the same volume without copying, so all-files access is required.
- **Demo video:** Record a 30-second screen capture: open app → grant All files access → swipe a file left/right/up → open Recycle bin → restore. Upload to YouTube as unlisted and paste the link.
