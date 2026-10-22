package com.binnder.app

import org.junit.Assert.*
import org.junit.Test

class ScanPolicyTest {
    @Test fun excludesBinnderTrashAtAnyDepth() {
        listOf(".Binnder-trash/id", "Android/.Binnder-trash/id", "nested/.BINNDER-TRASH/id").forEach {
            assertTrue(it,ScanPolicy.isBinPath(it))
        }
    }
    @Test fun excludesManufacturerAndFileManagerBins() {
        listOf("Android/.Trash/com.sec.android.app.myfiles/photo.jpg", "DCIM/.recycle/clip.mp4",
            "Pictures/.RecycleBin/image.jpg", ".Trashes/501/file", ".Trash-1000/files/file",
            "\$RECYCLE.BIN/id/file", "MIUI/.globalTrash/file", "Recycle bin/file").forEach {
            assertTrue(it,ScanPolicy.isBinPath(it))
        }
    }
    @Test fun excludesMediaStoreRenamedFilesCaseInsensitively() {
        assertTrue(ScanPolicy.isBinPath("DCIM/.trashed-123-photo.jpg"))
        assertTrue(ScanPolicy.isBinPath("Download/.TRASHED-456-report.pdf"))
    }
    @Test fun mediaStoreTrashFlagExcludesNormalLookingPaths() {
        val path="/storage/emulated/0/DCIM/photo.jpg"
        assertFalse(ScanPolicy.isReviewable(path,"DCIM/photo.jpg",setOf(path),emptySet()))
        assertTrue(ScanPolicy.isReviewable(path,"DCIM/photo.jpg",emptySet(),emptySet()))
    }
    @Test fun excludesBothPendingOriginalAndJournalledTrashPaths() {
        val original="/storage/emulated/0/Download/a.pdf"
        val binned="/storage/emulated/0/custom-bin/id"
        val active=setOf(original,binned)
        assertFalse(ScanPolicy.isReviewable(original,"Download/a.pdf",emptySet(),active))
        assertFalse(ScanPolicy.isReviewable(binned,"custom-bin/id",emptySet(),active))
    }
    @Test fun doesNotHideFilesMerelyMentioningTrash() {
        listOf("Download/trash-notes.txt","Pictures/recycling-day.jpg","Documents/Trash collection/invoice.pdf",
            "Download/Trash.pdf","DCIM/recyclebin-photo.jpg","Projects/.trash-tools/source.kt").forEach {
            assertFalse(it,ScanPolicy.isBinPath(it))
        }
    }
}
