package cc.futurvo.ideadock.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a write gets a review notification, ported from
 * `dsh-vsdock/src/review.ts`. Every branch is a real user complaint: the IDE
 * saving, the buffer being dirty, an unpaired workspace, a build directory, a
 * missing baseline.
 */
class ReviewDecisionTest {

    private fun input(
        path: String = "/work/src/A.kt",
        paired: List<String> = listOf("/work"),
        selfSavedAt: Long? = null,
        dirty: Boolean = false,
        hasBefore: Boolean = true,
        bytes: Int = 100,
        now: Long = 10_000
    ) = ReviewDecision.Input(
        path = path,
        folders = listOf("/work"),
        paired = paired,
        selfSavedAt = selfSavedAt,
        dirty = dirty,
        hasBefore = hasBefore,
        bytes = bytes,
        now = now,
        selfSaveGraceMs = 2_000,
        maxBytes = 512 * 1024
    )

    @Test
    fun `a change inside a paired folder is reviewed`() {
        assertEquals(ReviewDecision.Verdict.Review, ReviewDecision.decide(input(), windows = false))
    }

    @Test
    fun `a save the ide just made is ignored`() {
        val verdict = ReviewDecision.decide(input(selfSavedAt = 9_000, now = 10_000), windows = false)
        assertTrue(verdict is ReviewDecision.Verdict.Skip)
        assertEquals("the IDE saved it", (verdict as ReviewDecision.Verdict.Skip).reason)
    }

    @Test
    fun `an older save no longer suppresses the review`() {
        assertEquals(
            ReviewDecision.Verdict.Review,
            ReviewDecision.decide(input(selfSavedAt = 1_000, now = 10_000), windows = false)
        )
    }

    @Test
    fun `a dirty buffer is ignored`() {
        assertTrue(ReviewDecision.decide(input(dirty = true), windows = false) is ReviewDecision.Verdict.Skip)
    }

    @Test
    fun `an unpaired workspace is silent`() {
        val verdict = ReviewDecision.decide(input(paired = emptyList()), windows = false)
        assertEquals("no folder is registered with DSH", (verdict as ReviewDecision.Verdict.Skip).reason)
    }

    @Test
    fun `a file outside the paired folders is ignored`() {
        assertTrue(
            ReviewDecision.decide(input(path = "/other/A.kt"), windows = false) is ReviewDecision.Verdict.Skip
        )
    }

    @Test
    fun `a file without a baseline is ignored`() {
        val verdict = ReviewDecision.decide(input(hasBefore = false), windows = false)
        assertEquals("no earlier version to compare with", (verdict as ReviewDecision.Verdict.Skip).reason)
    }

    @Test
    fun `a file over the size cap is ignored`() {
        val verdict = ReviewDecision.decide(input(bytes = 600 * 1024), windows = false)
        assertEquals("file is too large", (verdict as ReviewDecision.Verdict.Skip).reason)
    }

    @Test
    fun `generated and binary paths are recognised`() {
        assertTrue(ReviewDecision.isIgnoredPath("node_modules/lib/index.js"))
        assertTrue(ReviewDecision.isIgnoredPath("build/out/A.class"))
        assertTrue(ReviewDecision.isIgnoredPath(".git/HEAD"))
        assertTrue(ReviewDecision.isIgnoredPath("web/app.min.js"))
        assertTrue(ReviewDecision.isIgnoredPath("img/logo.png"))
        assertTrue(ReviewDecision.isIgnoredPath("pom.xml.lock"))
        assertFalse(ReviewDecision.isIgnoredPath("src/main/kotlin/A.kt"))
        assertFalse(ReviewDecision.isIgnoredPath("README.md"))
        assertFalse(ReviewDecision.isIgnoredPath("compose.yaml"))
    }

    @Test
    fun `windows paths compare case-insensitively`() {
        val verdict = ReviewDecision.decide(
            input(path = "C:\\Work\\Src\\A.kt", paired = listOf("c:/work")),
            windows = true
        )
        assertEquals(ReviewDecision.Verdict.Review, verdict)
    }

    @Test
    fun `insideAny understands the folder itself`() {
        assertTrue(ReviewDecision.insideAny("/work", listOf("/work"), windows = false))
        assertTrue(ReviewDecision.insideAny("/work/src/A.kt", listOf("/work"), windows = false))
        assertFalse(ReviewDecision.insideAny("/workshop/A.kt", listOf("/work"), windows = false))
    }
}
