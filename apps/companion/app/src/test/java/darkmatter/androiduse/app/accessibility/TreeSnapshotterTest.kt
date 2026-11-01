package darkmatter.androiduse.app.accessibility

import darkmatter.androiduse.app.network.UiBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeSnapshotterTest {

    // ------------------------------------------------------------ fake adapter

    private class FakeNode(
        override val className: String? = "android.widget.FrameLayout",
        override val role: String? = null,
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val resourceId: String? = null,
        override val bounds: UiBounds = UiBounds(0, 0, 1080, 2400),
        override val clickable: Boolean = false,
        override val scrollable: Boolean = false,
        override val editable: Boolean = false,
        override val enabled: Boolean = true,
        override val focused: Boolean = false,
        override val visible: Boolean = true,
        override val checked: Boolean? = null,
        override val supportedActions: List<String> = emptyList(),
        override val isPassword: Boolean = false,
        private val children: List<SnapshotNode?> = emptyList(),
        private val reportedChildCount: Int? = null,
        private val accessor: ((Int) -> SnapshotNode?)? = null,
    ) : SnapshotNode {
        override val childCount: Int get() = reportedChildCount ?: children.size
        override fun childAt(index: Int): SnapshotNode? =
            accessor?.invoke(index) ?: children.getOrNull(index)
    }

    private fun node(
        className: String? = "android.widget.FrameLayout",
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        bounds: UiBounds = UiBounds(0, 0, 1080, 2400),
        clickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        focused: Boolean = false,
        visible: Boolean = true,
        checked: Boolean? = null,
        actions: List<String> = emptyList(),
        password: Boolean = false,
        children: List<SnapshotNode?> = emptyList(),
        reportedChildCount: Int? = null,
        accessor: ((Int) -> SnapshotNode?)? = null,
    ): FakeNode = FakeNode(
        className = className,
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        bounds = bounds,
        clickable = clickable,
        scrollable = scrollable,
        editable = editable,
        enabled = enabled,
        focused = focused,
        visible = visible,
        checked = checked,
        supportedActions = actions,
        isPassword = password,
        children = children,
        reportedChildCount = reportedChildCount,
        accessor = accessor,
    )

    private fun snapshot(root: SnapshotNode, limits: SnapshotLimits = SnapshotLimits()) =
        TreeSnapshotter.serialize(
            root = root,
            deviceId = "device-1",
            windowId = "window-1",
            revision = 7,
            limits = limits,
        )

    // ------------------------------------------------------------------ tests

    @Test
    fun `flattens nested actionable nodes in stable pre-order`() {
        val submit = node(
            className = "android.widget.Button",
            text = "Submit",
            resourceId = "com.example:id/submit",
            clickable = true,
            bounds = UiBounds(10, 20, 210, 120),
            actions = listOf("click"),
        )
        val email = node(
            className = "android.widget.EditText",
            text = "Email",
            editable = true,
            bounds = UiBounds(10, 140, 500, 240),
            actions = listOf("setText"),
        )
        val form = node(
            className = "android.widget.LinearLayout",
            children = listOf(email, submit),
        )

        val result = snapshot(form)

        assertEquals("device-1", result.deviceId)
        assertEquals("window-1", result.windowId)
        assertEquals(7, result.revision)
        assertEquals(1080, result.width)
        assertEquals(2400, result.height)
        assertFalse(result.truncated)
        assertEquals(3, result.elements.size)
        assertEquals(listOf("android.widget.LinearLayout", "android.widget.EditText", "android.widget.Button"),
            result.elements.map { it.className })
        assertEquals(listOf(TreeSnapshotter.refFor(7, 0), TreeSnapshotter.refFor(7, 1), TreeSnapshotter.refFor(7, 2)),
            result.elements.map { it.ref })
        assertEquals("Submit", result.elements[2].text)
        assertEquals("com.example:id/submit", result.elements[2].resourceId)
        assertEquals(UiBounds(10, 20, 210, 120), result.elements[2].bounds)
    }

    @Test
    fun `keeps repeated labels distinct via revision-scoped refs`() {
        val first = node(className = "android.widget.Button", text = "OK", clickable = true)
        val second = node(className = "android.widget.Button", text = "OK", clickable = true)
        val result = snapshot(node(children = listOf(first, second)))

        val buttons = result.elements.filter { it.text == "OK" }
        assertEquals(2, buttons.size)
        assertNotEquals(buttons[0].ref, buttons[1].ref)
        assertTrue(buttons.all { it.ref.contains("7") })
    }

    @Test
    fun `normalizes missing and empty text ids`() {
        val result = snapshot(
            node(
                className = null,
                text = "",
                contentDescription = null,
                resourceId = "",
                bounds = UiBounds(0, 0, 100, 100),
            ),
        )

        val element = result.elements.single()
        assertNull(element.className)
        assertNull(element.text)
        assertNull(element.contentDescription)
        assertNull(element.resourceId)
    }

    @Test
    fun `serializes a cyclic graph once and marks it truncated`() {
        lateinit var cyclic: FakeNode
        cyclic = node(
            className = "android.widget.FrameLayout",
            text = "Cycle",
            accessor = { cyclic },
            reportedChildCount = 1,
        )

        val result = snapshot(cyclic)

        assertEquals(1, result.elements.size)
        assertEquals("Cycle", result.elements.single().text)
        assertTrue(result.truncated)
    }

    @Test
    fun `tolerates malformed child counts and throwing child accessors`() {
        val throwing = node(
            className = "android.widget.Button",
            text = "Broken",
            reportedChildCount = 2,
            accessor = { throw IllegalStateException("stale node") },
        )
        val negative = node(
            className = "android.widget.FrameLayout",
            text = "Negative",
            reportedChildCount = -4,
        )

        val result = snapshot(node(children = listOf(throwing, negative)))

        assertEquals(3, result.elements.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `enforces max depth and reports truncation`() {
        val grandchild = node(text = "grandchild")
        val child = node(text = "child", children = listOf(grandchild))
        val root = node(text = "root", children = listOf(child))

        val result = snapshot(root, SnapshotLimits(maxDepth = 1))

        assertEquals(listOf("root", "child"), result.elements.map { it.text })
        assertTrue(result.truncated)
    }

    @Test
    fun `does not report truncation when the tree exactly fits the depth limit`() {
        val child = node(text = "child")
        val root = node(text = "root", children = listOf(child))

        val result = snapshot(root, SnapshotLimits(maxDepth = 1))

        assertEquals(2, result.elements.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `enforces max nodes and reports truncation`() {
        var current = node(text = "leaf")
        repeat(9) { index ->
            current = node(text = "level-$index", children = listOf(current))
        }

        val result = snapshot(current, SnapshotLimits(maxNodes = 4))

        assertEquals(4, result.elements.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `does not report truncation when the tree exactly fits the node limit`() {
        var current = node(text = "leaf")
        repeat(3) { index ->
            current = node(text = "level-$index", children = listOf(current))
        }

        val result = snapshot(current, SnapshotLimits(maxNodes = 4))

        assertEquals(4, result.elements.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `is deterministic across repeated serialization`() {
        val root = node(
            className = "android.widget.ScrollView",
            scrollable = true,
            children = listOf(
                node(className = "android.widget.Button", text = "One", clickable = true),
                node(className = "android.widget.Button", text = "Two", clickable = true),
                node(className = "android.widget.TextView", text = "Label"),
            ),
        )

        val first = snapshot(root)
        val second = snapshot(root)

        assertEquals(first, second)
        assertEquals(first.compactText, second.compactText)
    }

    @Test
    fun `compact text contains only actionable elements`() {
        val label = node(className = "android.widget.TextView", text = "Non-actionable label")
        val button = node(
            className = "android.widget.Button",
            text = "Continue",
            clickable = true,
            bounds = UiBounds(10, 10, 200, 100),
            actions = listOf("click"),
        )
        val root = node(className = "android.widget.FrameLayout", children = listOf(label, button))

        val result = snapshot(root)

        assertTrue(result.compactText!!.contains("Continue"))
        assertTrue(result.compactText!!.contains(TreeSnapshotter.refFor(7, 2)))
        assertFalse(result.compactText!!.contains("Non-actionable label"))
        assertEquals(3, result.elements.size)
    }

    @Test
    fun `compact mode keeps only actionable structured elements`() {
        val label = node(className = "android.widget.TextView", text = "Label")
        val button = node(className = "android.widget.Button", text = "Go", clickable = true)
        val root = node(children = listOf(label, button))

        val compact = TreeSnapshotter.serialize(
            root = root,
            deviceId = "device-1",
            windowId = "window-1",
            revision = 7,
            limits = SnapshotLimits(),
            mode = SnapshotMode.COMPACT,
        )
        val actionableOnly = TreeSnapshotter.serialize(
            root = root,
            deviceId = "device-1",
            windowId = "window-1",
            revision = 7,
            limits = SnapshotLimits(),
            mode = SnapshotMode.ACTIONABLE_ONLY,
        )

        assertEquals(listOf("Go"), compact.elements.map { it.text })
        assertTrue(compact.compactText!!.contains("Go"))
        assertEquals(listOf("Go"), actionableOnly.elements.map { it.text })
        assertNull(actionableOnly.compactText)
    }

    @Test
    fun `preserves element state metadata`() {
        val button = node(
            className = "android.widget.CheckBox",
            text = "Remember me",
            clickable = true,
            enabled = false,
            focused = true,
            visible = false,
            checked = true,
            actions = listOf("click", "click", ""),
        )

        val element = snapshot(button).elements.single()

        assertTrue(element.clickable)
        assertFalse(element.enabled)
        assertTrue(element.focused)
        assertFalse(element.visible)
        assertEquals(true, element.checked)
        assertEquals(listOf("click"), element.supportedActions)
    }
}
