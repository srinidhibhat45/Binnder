# Binnder design

Native Material 3 is the user-pinned visual authority. Follow system light/dark and Dynamic Color, with teal fallback. Roboto and the Material type scale. No decorative dashboard, gamified deletion counts, or remote imagery.

Review centers on one large preview card with filename, exact location, size, and modification date. A horizontal category filter precedes it. Explicit Keep, Delete forever, and Bin buttons mirror swipe gestures; direction labels appear during dragging. The permanent action is visually secondary but clearly dangerous. The five-second pending-action strip remains visible across navigation and supports each pending operation independently.

Review is the primary surface. Recycle bin and Settings open from top-bar icons and have a Back button. No bottom navigation or side rail. Review cards use 12 dp horizontal margins and a 760 dp width cap, with the preview taking the remaining height above its metadata. Keep, Delete forever, and Bin controls stay below the scrollable card, visible without scrolling. Destructive dialogs identify the file and the consequence. Bin rows name expiry and offer restore. Error messages preserve context and provide recovery. No file operation is reported as successful before it succeeds.

Signature interaction: the card follows a deliberate drag, tilts slightly horizontally, and reveals its action. Material spring returns a subthreshold swipe; commit shows the next file. Explicit buttons provide the same actions to users who cannot swipe. Do not use haptics as the sole confirmation.

Refinements: preload the current and next two media previews into a bounded 24 MiB cache. Crossfade between reviewed files and use spring settling that cancels when a new drag starts. Hold a committed gesture while saving; failed choices settle back rather than leaving an offscreen card. Threshold haptics supplement visible feedback. Tapping the preview opens it; a compact corner control mirrors that action. Keep metadata quiet and offer a native Details bottom sheet with the full selectable path and modification date. A compact horizontal Undo strip shows each action with its own countdown and progress line, keeping multiple actions from repeatedly shrinking the preview.

Mode: Operate. First viewport: app bar, category chips, largest-first queue summary, dominant file preview, file metadata, and three labeled actions. Safe area insets and scrolling support larger fonts. Preview opens a separate full-screen destination and never routes touches into swipe handling.
