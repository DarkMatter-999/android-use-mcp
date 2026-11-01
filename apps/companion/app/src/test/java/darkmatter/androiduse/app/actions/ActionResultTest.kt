package darkmatter.androiduse.app.actions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import darkmatter.androiduse.app.network.ActionResult
import darkmatter.androiduse.app.network.RpcErrorCode
import darkmatter.androiduse.app.network.UiBounds
import darkmatter.androiduse.app.network.UiElement
import darkmatter.androiduse.app.network.UiSnapshot
import darkmatter.androiduse.app.selectors.CurrentTree
import darkmatter.androiduse.app.selectors.NodePath
import darkmatter.androiduse.app.selectors.NodePathMissingException
import darkmatter.androiduse.app.selectors.NodePathStaleException
import darkmatter.androiduse.app.selectors.Selector

class ActionResultTest {

    private lateinit var backend: RecordingBackend
    private lateinit var logStore: ActionLogStore
    private lateinit var workers: java.util.concurrent.ExecutorService
    private lateinit var executor: ActionExecutor

    @Before
    fun setUp() {
        backend = RecordingBackend(sampleTree())
        logStore = ActionLogStore(capacity = 50)
        workers = Executors.newSingleThreadExecutor()
        executor = ActionExecutor(
            backend = backend,
            logStore = logStore,
            worker = workers,
            timestamp = { 1_700_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        workers.shutdownNow()
    }

    // --------------------------------------------------------------- fixtures

    private fun element(
        ref: String,
        text: String? = null,
        resourceId: String? = null,
        bounds: UiBounds = UiBounds(0, 0, 400, 100),
        clickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
    ) = UiElement(
        ref = ref,
        className = null,
        role = null,
        text = text,
        contentDescription = null,
        resourceId = resourceId,
        bounds = bounds,
        clickable = clickable,
        scrollable = scrollable,
        editable = editable,
        enabled = enabled,
        focused = false,
        visible = visible,
        checked = null,
        supportedActions = emptyList(),
        children = null,
    )

    private fun sampleTree() = CurrentTree(
        UiSnapshot(
            deviceId = "device",
            windowId = "window",
            revision = 7,
            width = 1080,
            height = 1920,
            elements = listOf(
                element("r7-e0", text = "Save", resourceId = "com.example:id/save", clickable = true),
                element("r7-e1", text = "Input", resourceId = "com.example:id/input", editable = true),
                element(
                    "r7-e2",
                    text = "Row",
                    bounds = UiBounds(0, 400, 400, 500),
                    clickable = true,
                ),
                element(
                    "r7-e3",
                    text = "Label",
                    bounds = UiBounds(10, 410, 200, 490),
                ),
                element(
                    "r7-e4",
                    text = "List",
                    bounds = UiBounds(0, 600, 400, 900),
                    scrollable = true,
                ),
                element(
                    "r7-e5",
                    text = "Dup",
                    bounds = UiBounds(0, 1000, 200, 1100),
                    clickable = true,
                ),
                element(
                    "r7-e6",
                    text = "Dup",
                    bounds = UiBounds(200, 1000, 400, 1100),
                    clickable = true,
                ),
                element(
                    "r7-e7",
                    text = "Disabled",
                    bounds = UiBounds(0, 1200, 200, 1300),
                    clickable = true,
                    enabled = false,
                ),
            ),
            truncated = false,
        ),
    )

    private fun click(selector: Selector, first: Boolean = false, index: Int? = null): ActionRequest =
        ActionRequest(
            actionId = "action-1",
            action = "click",
            selector = selector,
            first = first,
            index = index,
            timeoutMillis = 2_000,
        )

    private fun resultError(result: ActionResult): RpcErrorCode? = result.error?.code

    // ------------------------------------------------------------- node actions

    @Test
    fun `an accepted click returns an unverified result and safe target metadata`() {
        val result = executor.execute(click(Selector.Text("Save", exact = true)))

        assertTrue(result.accepted)
        assertNull(result.verified)
        assertNull(result.verification)
        assertEquals("click", result.action)
        assertEquals("r7-e0", result.target?.ref)
        assertNull("target text must never echo arbitrary label text", result.target?.text)
        assertEquals(listOf(0, 0, 400, 100), result.target?.bounds)
        assertEquals(7, result.revisionBefore)
        assertEquals(7, result.revisionAfter)
        assertEquals(listOf("with:r7-e0", "click:r7-e0"), backend.calls)
        assertEquals(1, backend.acquired.get())
        assertEquals(1, backend.released.get())
        assertEquals(0, backend.active.get())

        val entry = logStore.recent(1).single()
        assertEquals("click", entry.action)
        assertEquals("text", entry.selectorKind)
        assertEquals("com.example:id/save", entry.targetResourceId)
        assertEquals(UiBounds(0, 0, 400, 100), entry.targetBounds)
        assertTrue(entry.accepted)
        assertNull(entry.verified)
        assertNull(entry.errorCode)
        assertFalse(entry.sensitive)
    }

    @Test
    fun `a rejected click is reported and logged without claiming success`() {
        backend.clickResult = false

        val result = executor.execute(click(Selector.Text("Save", exact = true)))

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.NOT_ACTIONABLE, resultError(result))
        assertNull(result.verified)
        assertEquals(1, backend.released.get())

        val entry = logStore.recent(1).single()
        assertFalse(entry.accepted)
        assertEquals(RpcErrorCode.NOT_ACTIONABLE, entry.errorCode)
    }

    @Test
    fun `long click uses the long-click action`() {
        val result = executor.execute(
            ActionRequest(
                actionId = "action-2",
                action = "longClick",
                selector = Selector.Text("Save", exact = true),
                timeoutMillis = 2_000,
            ),
        )

        assertTrue(result.accepted)
        assertEquals(listOf("with:r7-e0", "longClick:r7-e0"), backend.calls)
    }

    @Test
    fun `fill sets text but never logs or returns the input value`() {
        val secret = "s3cret-value-do-not-log"

        val result = executor.execute(
            ActionRequest(
                actionId = "action-3",
                action = "fill",
                selector = Selector.Text("Input", exact = true),
                value = secret,
                timeoutMillis = 2_000,
            ),
        )

        assertTrue(result.accepted)
        assertEquals(secret, backend.lastSetText)
        assertFalse(result.toString().contains(secret))
        assertFalse(logStore.snapshotJson().contains(secret))
        assertNull(result.target?.text)
        assertNull(result.error)

        val entry = logStore.recent(1).single()
        assertTrue(entry.sensitive)
        assertEquals("fill", entry.action)
        assertNull(entry.errorMessage)
    }

    @Test
    fun `a rejected fill still suppresses error metadata and input text`() {
        val secret = "another-secret"
        backend.setTextResult = false

        val result = executor.execute(
            ActionRequest(
                actionId = "action-4",
                action = "fill",
                selector = Selector.Text("Input", exact = true),
                value = secret,
                timeoutMillis = 2_000,
            ),
        )

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.NOT_ACTIONABLE, resultError(result))
        assertFalse(result.toString().contains(secret))
        assertFalse(logStore.snapshotJson().contains(secret))
        val entry = logStore.recent(1).single()
        assertTrue(entry.sensitive)
        assertNull(entry.errorMessage)
    }

    @Test
    fun `clear sets an empty string`() {
        val result = executor.execute(
            ActionRequest(
                actionId = "action-5",
                action = "clear",
                selector = Selector.Text("Input", exact = true),
                timeoutMillis = 2_000,
            ),
        )

        assertTrue(result.accepted)
        assertEquals("", backend.lastSetText)
        assertTrue(logStore.recent(1).single().sensitive)
    }

    @Test
    fun `scroll maps all four directions to native scroll actions`() {
        for ((direction, expected) in listOf(
            ScrollDirection.UP to "up",
            ScrollDirection.DOWN to "down",
            ScrollDirection.LEFT to "left",
            ScrollDirection.RIGHT to "right",
            ScrollDirection.FORWARD to "forward",
            ScrollDirection.BACKWARD to "backward",
        )) {
            backend.calls.clear()
            backend.lastScrollDirection = null
            val result = executor.execute(
                ActionRequest(
                    actionId = "scroll-$expected",
                    action = "scroll",
                    selector = Selector.Text("List", exact = true),
                    direction = direction,
                    timeoutMillis = 2_000,
                ),
            )

            assertTrue(result.accepted)
            assertEquals(direction, backend.lastScrollDirection)
            assertEquals(listOf("with:r7-e4", "scroll:r7-e4:$expected"), backend.calls)
        }
    }

    @Test
    fun `ambiguous selectors never dispatch an action`() {
        val result = executor.execute(click(Selector.Text("Dup", exact = true)))

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.AMBIGUOUS_TARGET, resultError(result))
        assertTrue(backend.calls.isEmpty())
        assertEquals(0, backend.acquired.get())
        assertEquals(0, backend.released.get())
    }

    @Test
    fun `first and index require an explicit choice for duplicate matches`() {
        val first = executor.execute(click(Selector.Text("Dup", exact = true), first = true))
        assertEquals("r7-e5", first.target?.ref)

        backend.calls.clear()
        val second = executor.execute(click(Selector.Text("Dup", exact = true), index = 1))
        assertEquals("r7-e6", second.target?.ref)

        backend.calls.clear()
        val missing = executor.execute(click(Selector.Text("Dup", exact = true), index = 2))
        assertFalse(missing.accepted)
        assertEquals(RpcErrorCode.NOT_FOUND, resultError(missing))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `click promotes to a unique clickable ancestor but not to an ambiguous one`() {
        val result = executor.execute(click(Selector.Text("Label", exact = true)))

        assertTrue(result.accepted)
        assertEquals("r7-e2", result.target?.ref)
        assertEquals(listOf("with:r7-e2", "click:r7-e2"), backend.calls)
    }

    @Test
    fun `disabled targets are not actionable and are never promoted`() {
        val result = executor.execute(click(Selector.Text("Disabled", exact = true)))

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.NOT_ACTIONABLE, resultError(result))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `missing targets return NOT_FOUND and are logged`() {
        val result = executor.execute(click(Selector.Text("Absent", exact = true)))

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.NOT_FOUND, resultError(result))
        val entry = logStore.recent(1).single()
        assertEquals(RpcErrorCode.NOT_FOUND, entry.errorCode)
        assertNull(entry.targetResourceId)
    }

    // ---------------------------------------------------------------- revisions

    @Test
    fun `a stale ref revision is rejected before node acquisition`() {
        val result = executor.execute(click(Selector.Ref(value = "r6-e0", revision = 6)))

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.STALE_REF, resultError(result))
        assertTrue(backend.calls.isEmpty())

        val expectedMismatch = executor.execute(
            ActionRequest(
                actionId = "action-6",
                action = "click",
                selector = Selector.Text("Save", exact = true),
                expectedRevision = 6,
                timeoutMillis = 2_000,
            ),
        )
        assertFalse(expectedMismatch.accepted)
        assertEquals(RpcErrorCode.STALE_REF, resultError(expectedMismatch))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `revision after can advance while an accepted action remains unverified`() {
        backend.bumpRevisionAfterAction = true

        val result = executor.execute(click(Selector.Text("Save", exact = true)))

        assertTrue(result.accepted)
        assertNull(result.verified)
        assertEquals(7, result.revisionBefore)
        assertEquals(8, result.revisionAfter)
        assertNull(result.error)
    }

    @Test
    fun `a separate postcondition supplies verification`() {
        val passed = executor.execute(
            ActionRequest(
                actionId = "action-7",
                action = "click",
                selector = Selector.Text("Save", exact = true),
                postcondition = ActionPostcondition(
                    selector = Selector.Text("Save", exact = true),
                    exists = true,
                ),
                timeoutMillis = 2_000,
            ),
        )
        assertTrue(passed.accepted)
        assertEquals(true, passed.verified)
        assertEquals(true, passed.verification?.passed)

        val failed = executor.execute(
            ActionRequest(
                actionId = "action-8",
                action = "click",
                selector = Selector.Text("Save", exact = true),
                postcondition = ActionPostcondition(
                    selector = Selector.Text("Absent", exact = true),
                    exists = true,
                ),
                timeoutMillis = 2_000,
            ),
        )
        assertTrue(failed.accepted)
        assertEquals(false, failed.verified)
        assertEquals(false, failed.verification?.passed)
    }

    @Test
    fun `a stale ref postcondition is unverifiable even when absence is requested`() {
        val result = executor.execute(
            ActionRequest(
                actionId = "action-stale-postcondition",
                action = "click",
                selector = Selector.Text("Save", exact = true),
                postcondition = ActionPostcondition(
                    selector = Selector.Ref(value = "r6-e0", revision = 6),
                    exists = false,
                ),
                timeoutMillis = 2_000,
            ),
        )

        assertTrue(result.accepted)
        assertEquals(false, result.verified)
        assertEquals(false, result.verification?.passed)
        assertEquals("Postcondition could not be evaluated", result.verification?.message)
    }

    // ------------------------------------------------------- cancellation/timeout

    @Test
    fun `pre-cancelled actions never acquire a node`() {
        val cancellation = ActionCancellation()
        cancellation.cancel()

        val result = executor.execute(click(Selector.Text("Save", exact = true)), cancellation)

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.CANCELLED, resultError(result))
        assertTrue(backend.calls.isEmpty())
        assertEquals(RpcErrorCode.CANCELLED, logStore.recent(1).single().errorCode)
    }

    @Test
    fun `cancellation during node acquisition returns CANCELLED and releases acquired nodes`() {
        backend.nodeDelayMillis = 500
        backend.releaseLatch = CountDownLatch(1)
        val cancellation = ActionCancellation()
        var result: ActionResult? = null

        val runner = thread {
            result = executor.execute(click(Selector.Text("Save", exact = true)), cancellation)
        }
        assertTrue("action did not enter node acquisition", backend.nodeEntered.await(2, TimeUnit.SECONDS))
        cancellation.cancel()
        runner.join(2_000)

        assertEquals(RpcErrorCode.CANCELLED, resultError(requireNotNull(result)))
        assertFalse(requireNotNull(result).accepted)
        assertTrue("acquired node was not released", backend.releaseLatch.await(2, TimeUnit.SECONDS))
        assertEquals(0, backend.active.get())
    }

    @Test
    fun `cancellation after dispatch reports accepted with an unknown-outcome message`() {
        val cancellation = ActionCancellation()
        val clickLatch = backend.blockClickUntilCancelled()
        var result: ActionResult? = null

        val runner = thread {
            result = executor.execute(click(Selector.Text("Save", exact = true)), cancellation)
        }
        assertTrue("action did not enter dispatch", backend.clickEntered.await(2, TimeUnit.SECONDS))
        cancellation.cancel()
        runner.join(2_000)

        val cancelled = requireNotNull(result)
        assertEquals(RpcErrorCode.CANCELLED, resultError(cancelled))
        assertTrue("a dispatched action remains accepted", cancelled.accepted)
        assertTrue(
            "cancellation after dispatch must not claim a known outcome",
            cancelled.error?.message?.contains("outcome may be unknown") == true,
        )
        clickLatch.countDown()

        val entries = logStore.recent(10)
        assertEquals(1, entries.size)
        assertEquals(RpcErrorCode.CANCELLED, entries.single().errorCode)
        assertTrue(entries.single().accepted)
    }

    @Test
    fun `timeout after dispatch reports accepted with an unknown-outcome message`() {
        val clickLatch = backend.blockClickUntilCancelled()

        val timedOut = executor.execute(
            ActionRequest(
                actionId = "action-post-timeout",
                action = "click",
                selector = Selector.Text("Save", exact = true),
                timeoutMillis = 50,
            ),
        )
        clickLatch.countDown()

        assertEquals(RpcErrorCode.TIMEOUT, resultError(timedOut))
        assertTrue("a dispatched action remains accepted", timedOut.accepted)
        assertTrue(
            "timeout after dispatch must not claim a known outcome",
            timedOut.error?.message?.contains("outcome may be unknown") == true,
        )
        assertEquals(1, logStore.recent(10).size)
    }

    @Test
    fun `timeout aborts a stalled action and releases acquired nodes`() {
        backend.nodeDelayMillis = 500
        backend.releaseLatch = CountDownLatch(1)
        var result: ActionResult? = null

        val runner = thread {
            result = executor.execute(
                ActionRequest(
                    actionId = "action-9",
                    action = "click",
                    selector = Selector.Text("Save", exact = true),
                    timeoutMillis = 50,
                ),
            )
        }
        assertTrue("action did not enter node acquisition", backend.nodeEntered.await(2, TimeUnit.SECONDS))
        runner.join(2_000)

        assertEquals(RpcErrorCode.TIMEOUT, resultError(requireNotNull(result)))
        assertFalse(requireNotNull(result).accepted)
        assertTrue("acquired node was not released", backend.releaseLatch.await(2, TimeUnit.SECONDS))
        assertEquals(0, backend.active.get())
    }

    // ----------------------------------------------------------------- gestures

    @Test
    fun `bounded coordinate gestures dispatch explicit gesture specs`() {
        val tap = executor.execute(
            ActionRequest(
                actionId = "gesture-1",
                action = "tap",
                x = 100,
                y = 200,
                timeoutMillis = 2_000,
            ),
        )
        assertTrue(tap.accepted)
        assertEquals(GestureKind.TAP, backend.lastGesture?.kind)
        assertEquals(100, backend.lastGesture?.startX)
        assertEquals(200, backend.lastGesture?.startY)

        val longPress = executor.execute(
            ActionRequest(
                actionId = "gesture-2",
                action = "longPress",
                x = 100,
                y = 200,
                durationMillis = 700,
                timeoutMillis = 2_000,
            ),
        )
        assertTrue(longPress.accepted)
        assertEquals(GestureKind.LONG_PRESS, backend.lastGesture?.kind)
        assertEquals(700L, backend.lastGesture?.durationMillis)

        val swipe = executor.execute(
            ActionRequest(
                actionId = "gesture-3",
                action = "swipe",
                x = 100,
                y = 200,
                endX = 500,
                endY = 800,
                durationMillis = 300,
                timeoutMillis = 2_000,
            ),
        )
        assertTrue(swipe.accepted)
        assertEquals(GestureKind.SWIPE, backend.lastGesture?.kind)
        assertEquals(500, backend.lastGesture?.endX)
        assertEquals(800, backend.lastGesture?.endY)
        val loggedGesture = logStore.recent(1).single()
        assertEquals(100, loggedGesture.gestureX)
        assertEquals(200, loggedGesture.gestureY)
    }

    @Test
    fun `out-of-bounds and malformed gestures fail with INVALID_MESSAGE`() {
        val outside = executor.execute(
            ActionRequest(
                actionId = "gesture-4",
                action = "tap",
                x = 1080,
                y = 100,
                timeoutMillis = 2_000,
            ),
        )
        assertFalse(outside.accepted)
        assertEquals(RpcErrorCode.INVALID_MESSAGE, resultError(outside))
        assertNull(backend.lastGesture)

        val missing = executor.execute(
            ActionRequest(
                actionId = "gesture-5",
                action = "swipe",
                x = 10,
                y = 10,
                timeoutMillis = 2_000,
            ),
        )
        assertFalse(missing.accepted)
        assertEquals(RpcErrorCode.INVALID_MESSAGE, resultError(missing))

        val samePoint = executor.execute(
            ActionRequest(
                actionId = "gesture-6",
                action = "swipe",
                x = 10,
                y = 10,
                endX = 10,
                endY = 10,
                durationMillis = 300,
                timeoutMillis = 2_000,
            ),
        )
        assertFalse(samePoint.accepted)
        assertEquals(RpcErrorCode.INVALID_MESSAGE, resultError(samePoint))

        val tooLongForTap = executor.execute(
            ActionRequest(
                actionId = "gesture-10",
                action = "tap",
                x = 10,
                y = 10,
                durationMillis = 1_000,
                timeoutMillis = 2_000,
            ),
        )
        assertFalse(tooLongForTap.accepted)
        assertEquals(RpcErrorCode.INVALID_MESSAGE, resultError(tooLongForTap))

        val tooShortForLongPress = executor.execute(
            ActionRequest(
                actionId = "gesture-11",
                action = "longPress",
                x = 10,
                y = 10,
                durationMillis = 100,
                timeoutMillis = 2_000,
            ),
        )
        assertFalse(tooShortForLongPress.accepted)
        assertEquals(RpcErrorCode.INVALID_MESSAGE, resultError(tooShortForLongPress))
    }

    @Test
    fun `gesture rejection and unsupported results are explicit`() {
        backend.gestureResult = GestureResult(success = false)
        val rejected = executor.execute(
            ActionRequest(
                actionId = "gesture-7",
                action = "tap",
                x = 10,
                y = 10,
                timeoutMillis = 2_000,
            ),
        )
        assertFalse(rejected.accepted)
        assertEquals(RpcErrorCode.NOT_ACTIONABLE, resultError(rejected))

        backend.gestureResult = GestureResult(success = false, unsupported = true)
        val unsupported = executor.execute(
            ActionRequest(
                actionId = "gesture-8",
                action = "tap",
                x = 10,
                y = 10,
                timeoutMillis = 2_000,
            ),
        )
        assertFalse(unsupported.accepted)
        assertEquals(RpcErrorCode.UNSUPPORTED, resultError(unsupported))

        backend.gestureResult = GestureResult(success = false, timedOut = true)
        val timedOut = executor.execute(
            ActionRequest(
                actionId = "gesture-9",
                action = "tap",
                x = 10,
                y = 10,
                timeoutMillis = 2_000,
            ),
        )
        assertTrue("a dispatched gesture may still complete after the wait times out", timedOut.accepted)
        assertEquals(RpcErrorCode.TIMEOUT, resultError(timedOut))
        assertTrue(timedOut.error?.message?.contains("outcome may be unknown") == true)
    }

    @Test
    fun `expected revision is rechecked immediately before global dispatch`() {
        backend.bumpRevisionOnCurrentRevisionCall = 2

        val result = executor.execute(
            ActionRequest(
                actionId = "global-stale-revision",
                action = "back",
                expectedRevision = 7,
                timeoutMillis = 2_000,
            ),
        )

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.STALE_REF, resultError(result))
        assertTrue("stale global action must not dispatch", backend.calls.isEmpty())
    }

    @Test
    fun `node revision is checked again inside the live-node action window`() {
        backend.bumpRevisionBeforeNodeBlock = true

        val result = executor.execute(
            ActionRequest(
                actionId = "node-stale-revision",
                action = "click",
                selector = Selector.Text("Save", exact = true),
                expectedRevision = 7,
                timeoutMillis = 2_000,
            ),
        )

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.STALE_REF, resultError(result))
        assertFalse("stale node must not receive a click", backend.calls.any { it.startsWith("click:") })
    }

    // ------------------------------------------------------------------ globals

    @Test
    fun `supported global actions dispatch and unsupported ones fail explicitly`() {
        for (action in listOf("back", "home", "recents", "notifications")) {
            backend.calls.clear()
            val result = executor.execute(
                ActionRequest(
                    actionId = "global-$action",
                    action = action,
                    timeoutMillis = 2_000,
                ),
            )
            assertTrue("$action should be accepted", result.accepted)
            assertEquals(listOf("global:$action"), backend.calls)
        }

        backend.globalResult = false
        val unsupported = executor.execute(
            ActionRequest(
                actionId = "global-back",
                action = "back",
                timeoutMillis = 2_000,
            ),
        )
        assertFalse(unsupported.accepted)
        assertEquals(RpcErrorCode.UNSUPPORTED, resultError(unsupported))
    }

    @Test
    fun `unknown actions fail with UNSUPPORTED without touching a node`() {
        val result = executor.execute(
            ActionRequest(
                actionId = "action-10",
                action = "explode",
                selector = Selector.Text("Save", exact = true),
                timeoutMillis = 2_000,
            ),
        )

        assertFalse(result.accepted)
        assertEquals(RpcErrorCode.UNSUPPORTED, resultError(result))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `every failure path appends exactly one safe log record`() {
        executor.execute(click(Selector.Text("Absent", exact = true)))
        executor.execute(click(Selector.Text("Dup", exact = true)))
        executor.execute(click(Selector.Ref(value = "r6-e0", revision = 6)))
        executor.execute(click(Selector.Text("Disabled", exact = true)))
        executor.execute(
            ActionRequest(
                actionId = "action-11",
                action = "explode",
                selector = Selector.Text("Save", exact = true),
                timeoutMillis = 2_000,
            ),
        )

        val entries = logStore.recent(10)
        assertEquals(5, entries.size)
        assertEquals(
            listOf(
                RpcErrorCode.UNSUPPORTED,
                RpcErrorCode.NOT_ACTIONABLE,
                RpcErrorCode.STALE_REF,
                RpcErrorCode.AMBIGUOUS_TARGET,
                RpcErrorCode.NOT_FOUND,
            ),
            entries.map { it.errorCode },
        )
        assertTrue(entries.none { it.accepted })
        assertTrue(entries.all { it.targetResourceId == null })
    }

    // ---------------------------------------------------------------- fakes

    private class RecordingBackend(var tree: CurrentTree) : ActionBackend {
        val calls = mutableListOf<String>()
        val nodeEntered = CountDownLatch(1)
        val clickEntered = CountDownLatch(1)
        @Volatile
        var releaseLatch = CountDownLatch(1)

        @Volatile
        private var clickLatch: CountDownLatch? = null

        @Volatile
        var nodeDelayMillis: Long = 0

        fun blockClickUntilCancelled(): CountDownLatch =
            CountDownLatch(1).also { clickLatch = it }

        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val active = AtomicInteger()

        var clickResult = true
        var longClickResult = true
        var setTextResult = true
        var scrollResult = true
        var globalResult = true
        var gestureResult = GestureResult(success = true)
        var bumpRevisionAfterAction = false
        var bumpRevisionOnCurrentRevisionCall: Int? = null
        private var currentRevisionCalls = 0
        var bumpRevisionBeforeNodeBlock = false

        @Volatile
        var lastSetText: String? = null

        @Volatile
        var lastScrollDirection: ScrollDirection? = null

        @Volatile
        var lastGesture: GestureSpec? = null

        override fun currentRevision(): Int {
            currentRevisionCalls++
            if (currentRevisionCalls == bumpRevisionOnCurrentRevisionCall) {
                tree = CurrentTree(tree.snapshot.copy(revision = tree.revision + 1))
            }
            return tree.revision
        }

        override fun currentTree(): CurrentTree = tree

        override fun <T> withNode(path: NodePath, block: (ActionNode) -> T): T {
            acquired.incrementAndGet()
            active.incrementAndGet()
            try {
                calls += "with:${path.ref}"
                nodeEntered.countDown()
                if (nodeDelayMillis > 0) {
                    Thread.sleep(nodeDelayMillis)
                }
                if (path.revision != tree.revision) {
                    throw NodePathStaleException()
                }
                val element = tree.snapshot.elements.getOrNull(path.index)
                    ?: throw NodePathMissingException()
                if (bumpRevisionBeforeNodeBlock) {
                    tree = CurrentTree(tree.snapshot.copy(revision = tree.revision + 1))
                }
                val result = block(FakeNode(element))
                if (bumpRevisionAfterAction) {
                    tree = CurrentTree(tree.snapshot.copy(revision = tree.revision + 1))
                }
                return result
            } finally {
                active.decrementAndGet()
                released.incrementAndGet()
                releaseLatch.countDown()
            }
        }

        override fun dispatchGesture(spec: GestureSpec): GestureResult {
            lastGesture = spec
            calls += "gesture:${spec.kind}"
            return gestureResult
        }

        override fun performGlobalAction(action: GlobalAction): Boolean {
            calls += "global:${action.name.lowercase()}"
            return globalResult
        }

        private inner class FakeNode(private val element: UiElement) : ActionNode {
            override val clickable: Boolean get() = element.clickable
            override val editable: Boolean get() = element.editable
            override val enabled: Boolean get() = element.enabled
            override val visible: Boolean get() = element.visible
            override val scrollable: Boolean get() = element.scrollable
            override val bounds: UiBounds get() = element.bounds
            override val resourceId: String? get() = element.resourceId

            override fun click(): Boolean {
                calls += "click:${element.ref}"
                clickEntered.countDown()
                clickLatch?.await(2, TimeUnit.SECONDS)
                return clickResult
            }

            override fun longClick(): Boolean {
                calls += "longClick:${element.ref}"
                return longClickResult
            }

            override fun setText(value: String): Boolean {
                calls += "setText:${element.ref}"
                lastSetText = value
                return setTextResult
            }

            override fun scroll(direction: ScrollDirection): Boolean {
                calls += "scroll:${element.ref}:${direction.name.lowercase()}"
                lastScrollDirection = direction
                return scrollResult
            }
        }
    }
}
