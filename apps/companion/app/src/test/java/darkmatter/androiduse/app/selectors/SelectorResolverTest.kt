package darkmatter.androiduse.app.selectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import darkmatter.androiduse.app.network.UiBounds
import darkmatter.androiduse.app.network.UiElement
import darkmatter.androiduse.app.network.UiSnapshot

/**
 * Pure selector-resolution tests. Every [UiElement] here is a value copied from a
 * snapshot; the resolver never receives or returns an Android node handle.
 */
class SelectorResolverTest {

    private fun element(
        ref: String,
        text: String? = null,
        resourceId: String? = null,
        description: String? = null,
        role: String? = null,
        bounds: UiBounds = UiBounds(0, 0, 100, 100),
        clickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
    ) = UiElement(
        ref = ref,
        className = null,
        role = role,
        text = text,
        contentDescription = description,
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

    private fun tree(revision: Int = 7, vararg elements: UiElement) = CurrentTree(
        UiSnapshot(
            deviceId = "device",
            windowId = "window",
            revision = revision,
            width = 1080,
            height = 1920,
            elements = elements.toList(),
            truncated = false,
        ),
    )

    private fun found(resolution: Resolution): NodePath {
        assertTrue("expected Found, got $resolution", resolution is Resolution.Found)
        return (resolution as Resolution.Found).nodePath
    }

    @Test
    fun `exact text requires equality and partial text matches a substring`() {
        val current = tree(
            revision = 7,
            element("r7-e0", text = "Save changes"),
            element("r7-e1", text = "Save"),
        )

        assertEquals(
            "r7-e1",
            found(
                SelectorResolver.resolve(
                    Selector.Text(value = "Save", exact = true),
                    current,
                ),
            ).ref,
        )
        assertEquals(
            "r7-e0",
            found(
                SelectorResolver.resolve(
                    Selector.Text(value = "changes"),
                    current,
                ),
            ).ref,
        )
        assertEquals(
            Resolution.Missing,
            SelectorResolver.resolve(Selector.Text(value = "save", exact = true), current),
        )
    }

    @Test
    fun `resource id and description match exactly`() {
        val current = tree(
            revision = 7,
            element(
                "r7-e0",
                resourceId = "com.example:id/save",
                description = "Save changes",
            ),
        )

        assertEquals(
            "r7-e0",
            found(SelectorResolver.resolve(Selector.ResourceId("com.example:id/save"), current)).ref,
        )
        assertEquals(
            "r7-e0",
            found(SelectorResolver.resolve(Selector.Description("Save changes"), current)).ref,
        )
        assertEquals(
            Resolution.Missing,
            SelectorResolver.resolve(Selector.Description("save changes"), current),
        )
    }

    @Test
    fun `role matches with an optional accessible name`() {
        val current = tree(
            revision = 7,
            element("r7-e0", text = "Save", role = "button"),
            element("r7-e1", description = "Close dialog", role = "button"),
        )

        assertEquals(
            "r7-e0",
            found(SelectorResolver.resolve(Selector.Role("button", name = "Save"), current)).ref,
        )
        assertEquals(
            "r7-e1",
            found(SelectorResolver.resolve(Selector.Role("button", name = "Close dialog"), current)).ref,
        )
        assertEquals(
            Resolution.Ambiguous(2),
            SelectorResolver.resolve(Selector.Role("button"), current),
        )
    }

    @Test
    fun `filters reject disabled and invisible targets instead of treating them as missing`() {
        val current = tree(
            revision = 7,
            element("r7-e0", text = "Disabled", clickable = true, enabled = false),
            element("r7-e1", text = "Hidden", clickable = true, visible = false),
            element("r7-e2", text = "Editable", editable = true),
            element("r7-e3", text = "NotEditable"),
        )

        val clickable = ElementFilters(clickable = true, enabled = true, visible = true)
        assertEquals(
            Resolution.NotActionable,
            SelectorResolver.resolve(Selector.Text("Disabled", exact = true), current, clickable),
        )
        assertEquals(
            Resolution.NotActionable,
            SelectorResolver.resolve(Selector.Text("Hidden", exact = true), current, clickable),
        )
        assertEquals(
            "r7-e2",
            found(
                SelectorResolver.resolve(
                    Selector.Text("Editable", exact = true),
                    current,
                    ElementFilters(editable = true, enabled = true, visible = true),
                ),
            ).ref,
        )
        assertEquals(
            Resolution.NotActionable,
            SelectorResolver.resolve(
                Selector.Text("NotEditable", exact = true),
                current,
                ElementFilters(editable = true, enabled = true, visible = true),
            ),
        )
        assertEquals(
            "r7-e1",
            found(
                SelectorResolver.resolve(
                    Selector.Text("Hidden", exact = true),
                    current,
                    ElementFilters(enabled = true),
                ),
            ).ref,
        )
    }

    @Test
    fun `multiple semantic matches are ambiguous unless first or index is explicit`() {
        val current = tree(
            revision = 7,
            element("r7-e0", text = "Dup", clickable = true),
            element("r7-e1", text = "Dup", clickable = true),
        )
        val selector = Selector.Text("Dup", exact = true)

        assertEquals(Resolution.Ambiguous(2), SelectorResolver.resolve(selector, current))
        assertEquals("r7-e0", found(SelectorResolver.resolve(selector, current, selection = Selection.first())).ref)
        assertEquals("r7-e1", found(SelectorResolver.resolve(selector, current, selection = Selection.at(1))).ref)
        assertEquals(Resolution.Missing, SelectorResolver.resolve(selector, current, selection = Selection.at(2)))
    }

    @Test
    fun `count and existence expose matches without selecting a node`() {
        val current = tree(
            revision = 7,
            element("r7-e0", text = "Dup", clickable = true),
            element("r7-e1", text = "Dup", clickable = false),
            element("r7-e2", text = "Other"),
        )
        val selector = Selector.Text("Dup", exact = true)

        assertEquals(2, SelectorResolver.count(selector, current))
        assertEquals(1, SelectorResolver.count(selector, current, ElementFilters(clickable = true)))
        assertTrue(SelectorResolver.exists(selector, current))
        assertFalse(SelectorResolver.exists(Selector.Text("Absent", exact = true), current))
        assertEquals(0, SelectorResolver.count(Selector.Text("Absent", exact = true), current))
        assertEquals(Resolution.Missing, SelectorResolver.resolve(Selector.Text("Absent", exact = true), current))
    }

    @Test
    fun `a stale ref revision is reported before any node lookup`() {
        val current = tree(revision = 7, element("r7-e0", text = "Save"))

        assertEquals(
            Resolution.StaleRef,
            SelectorResolver.resolve(Selector.Ref(value = "r6-e0", revision = 6), current),
        )
        assertEquals(
            Resolution.StaleRef,
            SelectorResolver.resolve(Selector.Ref(value = "r7-e0", revision = 6), current),
        )
        assertEquals(
            Resolution.StaleRef,
            SelectorResolver.resolve(Selector.Ref(value = "r6-e0", revision = 7), current),
        )
        assertEquals(
            Resolution.Missing,
            SelectorResolver.resolve(Selector.Ref(value = "r7-e9", revision = 7), current),
        )
        assertEquals(
            "r7-e0",
            found(SelectorResolver.resolve(Selector.Ref(value = "r7-e0", revision = 7), current)).ref,
        )
    }

    @Test
    fun `a unique clickable ancestor is promoted only when it is unambiguous`() {
        val current = tree(
            revision = 7,
            element(
                "r7-e0",
                text = "Row",
                bounds = UiBounds(0, 0, 400, 120),
                clickable = true,
            ),
            element(
                "r7-e1",
                text = "Label",
                bounds = UiBounds(10, 10, 200, 100),
                clickable = false,
            ),
        )

        val result = SelectorResolver.resolve(
            selector = Selector.Text("Label", exact = true),
            tree = current,
            filters = ElementFilters(clickable = true, enabled = true, visible = true),
            promoteClickableAncestor = true,
        )
        val path = found(result)
        assertEquals(0, path.index)
        assertEquals("r7-e0", path.ref)
        assertEquals("r7-e1", path.promotedFromRef)

        val noPromotion = SelectorResolver.resolve(
            selector = Selector.Text("Label", exact = true),
            tree = current,
            filters = ElementFilters(clickable = true, enabled = true, visible = true),
            promoteClickableAncestor = false,
        )
        assertEquals(Resolution.NotActionable, noPromotion)
    }

    @Test
    fun `the nearest unique clickable ancestor is promoted when several contain the target`() {
        val current = tree(
            revision = 7,
            element(
                "r7-e0",
                text = "Outer row",
                bounds = UiBounds(0, 0, 400, 200),
                clickable = true,
            ),
            element(
                "r7-e1",
                text = "Inner row",
                bounds = UiBounds(0, 0, 400, 120),
                clickable = true,
            ),
            element(
                "r7-e2",
                text = "Label",
                bounds = UiBounds(10, 10, 200, 100),
            ),
        )

        val path = found(
            SelectorResolver.resolve(
                selector = Selector.Text("Label", exact = true),
                tree = current,
                filters = ElementFilters(clickable = true, enabled = true, visible = true),
                promoteClickableAncestor = true,
            ),
        )
        assertEquals(1, path.index)
        assertEquals("r7-e1", path.ref)
        assertEquals("r7-e2", path.promotedFromRef)
    }

    @Test
    fun `two equally close clickable ancestors are not promoted arbitrarily`() {
        val current = tree(
            revision = 7,
            element(
                "r7-e0",
                text = "First wrapper",
                bounds = UiBounds(0, 0, 400, 120),
                clickable = true,
            ),
            element(
                "r7-e1",
                text = "Second wrapper",
                bounds = UiBounds(0, 0, 400, 120),
                clickable = true,
            ),
            element(
                "r7-e2",
                text = "Label",
                bounds = UiBounds(10, 10, 200, 100),
            ),
        )

        assertEquals(
            Resolution.NotActionable,
            SelectorResolver.resolve(
                selector = Selector.Text("Label", exact = true),
                tree = current,
                filters = ElementFilters(clickable = true, enabled = true, visible = true),
                promoteClickableAncestor = true,
            ),
        )
    }

    @Test
    fun `disabled and invisible targets are never promoted to a clickable ancestor`() {
        val current = tree(
            revision = 7,
            element(
                "r7-e0",
                text = "Row",
                bounds = UiBounds(0, 0, 400, 120),
                clickable = true,
            ),
            element(
                "r7-e1",
                text = "Label",
                bounds = UiBounds(10, 10, 200, 100),
                enabled = false,
            ),
            element(
                "r7-e2",
                text = "Hidden",
                bounds = UiBounds(10, 200, 200, 300),
                visible = false,
            ),
        )
        val filters = ElementFilters(clickable = true, enabled = true, visible = true)

        assertEquals(
            Resolution.NotActionable,
            SelectorResolver.resolve(
                Selector.Text("Label", exact = true),
                current,
                filters,
                promoteClickableAncestor = true,
            ),
        )
        assertEquals(
            Resolution.NotActionable,
            SelectorResolver.resolve(
                Selector.Text("Hidden", exact = true),
                current,
                filters,
                promoteClickableAncestor = true,
            ),
        )
    }
}
