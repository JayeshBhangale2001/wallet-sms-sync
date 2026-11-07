package io.github.jayeshb.walletsmssync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewSectionsTest {
    @Test fun eachStatusBelongsToOnlyOneSection() {
        listOf("SYNCED", "IGNORED", "LINKED").forEach { assertEquals(ReviewSection.HISTORY, reviewSection(it)) }
        listOf("REVIEW", "UNCERTAIN").forEach { assertEquals(ReviewSection.NEEDS_REVIEW, reviewSection(it)) }
        listOf("QUEUED", "SENDING").forEach { assertEquals(ReviewSection.UPLOAD_PENDING, reviewSection(it)) }
        assertNull(reviewSection("OTHER"))
    }
}
