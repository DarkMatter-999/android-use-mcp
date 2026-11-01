package darkmatter.androiduse.app.accessibility

import darkmatter.androiduse.app.actions.ActionLogEntry
import darkmatter.androiduse.app.actions.ActionLogStore
import darkmatter.androiduse.app.network.RpcProtocol
import darkmatter.androiduse.app.network.UiBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionTest {

    private val secret = "hunter2-correct-horse"

    private fun passwordNode(children: List<SnapshotNode> = emptyList()) = FakeNode(
        className = "android.widget.EditText",
        text = secret,
        contentDescription = "Password $secret",
        resourceId = "com.example:id/password",
        bounds = UiBounds(0, 100, 500, 200),
        editable = true,
        supportedActions = listOf("setText"),
        isPassword = true,
        children = children,
    )

    private fun screen(vararg children: SnapshotNode) = FakeNode(
        className = "android.widget.LinearLayout",
        text = "Sign in",
        children = children.toList(),
    )

    private fun serialize(root: SnapshotNode): darkmatter.androiduse.app.network.UiSnapshot =
        TreeSnapshotter.serialize(
            root = root,
            deviceId = "device-1",
            windowId = "window-1",
            revision = 3,
            limits = SnapshotLimits(),
        )

    @Test
    fun `password text never enters structured elements`() {
        val snapshot = serialize(screen(passwordNode()))

        val password = snapshot.elements.single { it.resourceId == "com.example:id/password" }
        assertNull(password.text)
        assertNull(password.contentDescription)
        assertTrue(password.editable)
        assertFalse(snapshot.elements.any { it.text?.contains(secret) == true })
        assertFalse(snapshot.elements.any { it.contentDescription?.contains(secret) == true })
    }

    @Test
    fun `password text never enters compact text`() {
        val snapshot = serialize(screen(passwordNode()))

        assertTrue(snapshot.compactText!!.contains("com.example:id/password"))
        assertFalse(snapshot.compactText!!.contains(secret))
        assertFalse(snapshot.compactText!!.contains("Password $secret"))
    }

    @Test
    fun `password text never reaches serialized JSON`() {
        val snapshot = serialize(screen(passwordNode()))

        val json = RpcProtocol.encodeSnapshot(snapshot).toString()
        assertFalse(json.contains(secret))
        assertTrue(json.contains("com.example:id/password"))
    }

    @Test
    fun `deeply nested password values are redacted while siblings survive`() {
        val email = FakeNode(
            className = "android.widget.EditText",
            text = "person@example.com",
            editable = true,
        )
        val nested = screen(email, passwordNode())
        val snapshot = serialize(nested)

        assertTrue(snapshot.elements.any { it.text == "person@example.com" })
        assertFalse(snapshot.elements.any { it.text == secret })
        assertFalse(snapshot.compactText!!.contains(secret))
    }

    @Test
    fun `password descendants are redacted as part of the protected subtree`() {
        val secretChild = FakeNode(
            className = "android.widget.TextView",
            text = secret,
            contentDescription = secret,
        )
        val snapshot = serialize(screen(passwordNode(children = listOf(secretChild))))

        assertFalse(snapshot.elements.any { it.text?.contains(secret) == true })
        assertFalse(snapshot.elements.any { it.contentDescription?.contains(secret) == true })
        assertFalse(snapshot.compactText!!.contains(secret))
    }

    @Test
    fun `sensitive action log entries drop error metadata from memory and JSON`() {
        val store = ActionLogStore(capacity = 10)
        store.append(
            ActionLogEntry(
                actionId = "action-1",
                action = "fill",
                selectorKind = "resourceId",
                targetResourceId = "com.example:id/password",
                targetBounds = UiBounds(0, 100, 500, 200),
                accepted = true,
                verified = null,
                revisionBefore = 3,
                revisionAfter = 3,
                timestampMillis = 1_700_000_000_000,
                errorMessage = "failed to fill $secret",
                sensitive = true,
            ),
        )

        val entry = store.recent(1).single()
        assertNull(entry.errorMessage)
        assertFalse(entry.toString().contains(secret))
        assertFalse(store.snapshotJson().contains(secret))
    }

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
        private val children: List<SnapshotNode> = emptyList(),
    ) : SnapshotNode {
        override val childCount: Int get() = children.size
        override fun childAt(index: Int): SnapshotNode? = children.getOrNull(index)
    }
}
