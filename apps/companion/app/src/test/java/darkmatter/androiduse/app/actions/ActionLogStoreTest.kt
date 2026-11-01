package darkmatter.androiduse.app.actions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import darkmatter.androiduse.app.network.RpcErrorCode
import darkmatter.androiduse.app.network.UiBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLogStoreTest {

    private fun entry(
        id: String,
        sensitive: Boolean = false,
        timestamp: Long = 1_700_000_000_000,
    ) = ActionLogEntry(
        actionId = id,
        action = "click",
        selectorKind = "text",
        targetResourceId = "com.example:id/submit",
        targetBounds = UiBounds(10, 20, 210, 120),
        accepted = true,
        verified = null,
        revisionBefore = 4,
        revisionAfter = 5,
        timestampMillis = timestamp,
        sensitive = sensitive,
    )

    @Test
    fun `recent returns newest first and respects limit`() {
        val store = ActionLogStore(capacity = 10)
        store.append(entry("one", timestamp = 1))
        store.append(entry("two", timestamp = 2))
        store.append(entry("three", timestamp = 3))

        assertEquals(listOf("three", "two"), store.recent(2).map { it.actionId })
        assertEquals(3, store.recent(100).size)
        assertTrue(store.recent(0).isEmpty())
        assertTrue(store.recent(-1).isEmpty())
    }

    @Test
    fun `capacity evicts the oldest entries`() {
        val store = ActionLogStore(capacity = 3)
        repeat(5) { store.append(entry("action-$it", timestamp = it.toLong())) }

        assertEquals(listOf("action-4", "action-3", "action-2"), store.recent(10).map { it.actionId })
    }

    @Test
    fun `safe target metadata is preserved for non-sensitive actions`() {
        val store = ActionLogStore(capacity = 10)
        val stored = store.append(entry("one"))

        assertEquals("com.example:id/submit", stored.targetResourceId)
        assertEquals(UiBounds(10, 20, 210, 120), stored.targetBounds)
        assertEquals("text", stored.selectorKind)
        assertEquals(4, stored.revisionBefore)
        assertEquals(5, stored.revisionAfter)
        assertNull(stored.verified)
    }

    @Test
    fun `sensitive entries keep only safe structured metadata`() {
        val secret = "correct-horse-battery-staple"
        val store = ActionLogStore(capacity = 10)
        val stored = store.append(entry("secure", sensitive = true).copy(errorMessage = secret))

        assertNull(stored.errorMessage)
        assertFalse(store.recent(10).toString().contains(secret))
        assertFalse(store.snapshotJson().contains(secret))
        assertEquals("com.example:id/submit", stored.targetResourceId)
    }

    @Test
    fun `sensitive error messages are dropped but error codes remain`() {
        val secret = "token=abc123"
        val store = ActionLogStore(capacity = 10)
        val stored = store.append(
            ActionLogEntry(
                actionId = "failed",
                action = "fill",
                selectorKind = "ref",
                targetResourceId = null,
                targetBounds = null,
                accepted = false,
                verified = false,
                revisionBefore = 1,
                revisionAfter = 1,
                timestampMillis = 5,
                errorCode = RpcErrorCode.NOT_ACTIONABLE,
                errorMessage = "cannot fill $secret",
                sensitive = true,
            ),
        )

        assertNull(stored.errorMessage)
        assertEquals(RpcErrorCode.NOT_ACTIONABLE, stored.errorCode)
        assertFalse(store.snapshotJson().contains(secret))
    }

    @Test
    fun `empty values normalize to null`() {
        val store = ActionLogStore(capacity = 10)
        val stored = store.append(
            entry("empty").copy(
                targetResourceId = "   ",
                errorMessage = "",
                selectorKind = "",
            ),
        )

        assertNull(stored.targetResourceId)
        assertNull(stored.errorMessage)
        assertNull(stored.selectorKind)
    }

    @Test
    fun `public log schema never accepts free-form target text`() {
        val fieldNames = ActionLogEntry::class.java.declaredFields.map { it.name }
        val forbidden = listOf("targetText", "inputText", "text", "value", "password", "secret")

        assertTrue(
            "ActionLogEntry exposes free-form sensitive fields: ${forbidden.filter { it in fieldNames }}",
            forbidden.none { it in fieldNames },
        )
    }

    @Test
    fun `concurrent appends stay bounded and lose no unique entries`() {
        val store = ActionLogStore(capacity = 500)
        val threads = 4
        val perThread = 100
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)

            repeat(threads) { thread ->
                executor.execute {
                    start.await()
                    repeat(perThread) { index ->
                        store.append(entry("t$thread-$index", timestamp = index.toLong()))
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))

            assertEquals(threads * perThread, store.recent(Int.MAX_VALUE).size)
            assertEquals(threads * perThread, store.recent(Int.MAX_VALUE).map { it.actionId }.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `clear removes all entries`() {
        val store = ActionLogStore(capacity = 10)
        store.append(entry("one"))

        store.clear()

        assertTrue(store.recent(10).isEmpty())
    }
}
