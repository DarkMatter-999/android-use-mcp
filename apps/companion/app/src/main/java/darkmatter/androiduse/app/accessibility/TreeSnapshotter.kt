package darkmatter.androiduse.app.accessibility

import java.util.Collections
import java.util.IdentityHashMap
import darkmatter.androiduse.app.network.UiBounds
import darkmatter.androiduse.app.network.UiElement
import darkmatter.androiduse.app.network.UiSnapshot

/**
 * Snapshot views exposed by [TreeSnapshotter].
 *
 * - [FULL] keeps every serialized node plus actionable compact text.
 * - [COMPACT] keeps only actionable nodes plus actionable compact text.
 * - [ACTIONABLE_ONLY] keeps only actionable nodes and omits compact text.
 */
enum class SnapshotMode {
    FULL,
    COMPACT,
    ACTIONABLE_ONLY,
}

/** Bounds for one snapshot traversal. The root is depth zero. */
data class SnapshotLimits(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxNodes: Int = DEFAULT_MAX_NODES,
) {
    init {
        require(maxDepth >= 0) { "maxDepth must be non-negative" }
        require(maxNodes >= 1) { "maxNodes must be at least one" }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 30
        const val DEFAULT_MAX_NODES: Int = 500
    }
}

/**
 * Read-only view of one accessibility node. The Android adapter in `UseService`
 * implements this over a short-lived `AccessibilityNodeInfo`; unit tests use an
 * in-memory fake. Nothing in this interface is allowed to hold a node handle
 * beyond the traversal that created it.
 */
interface SnapshotNode {
    val className: String?
    val role: String?
    val text: String?
    val contentDescription: String?
    val resourceId: String?
    val bounds: UiBounds
    val clickable: Boolean
    val scrollable: Boolean
    val editable: Boolean
    val enabled: Boolean
    val focused: Boolean
    val visible: Boolean
    val checked: Boolean?
    val supportedActions: List<String>

    /** True only when the platform marks the field as a password. */
    val isPassword: Boolean

    val childCount: Int

    /** Returns the child at [index], or `null` when the node is stale/malformed. */
    fun childAt(index: Int): SnapshotNode?
}

/**
 * Pure accessibility-tree flattener. It never touches Android framework types,
 * so cycle, depth, node-count, ordering, and redaction behavior are all unit
 * testable on the JVM.
 *
 * Refs are scoped to one snapshot revision: `r<revision>-e<traversalIndex>`.
 * Every element is serialized in stable pre-order (children in adapter order),
 * and [UiElement.children] stays empty because the list is already flattened.
 */
object TreeSnapshotter {

    /** Ref format shared by snapshots and the Task 4 selector resolver. */
    fun refFor(revision: Int, index: Int): String = "r$revision-e$index"

    fun serialize(
        root: SnapshotNode,
        deviceId: String,
        windowId: String,
        revision: Int,
        limits: SnapshotLimits = SnapshotLimits(),
        mode: SnapshotMode = SnapshotMode.FULL,
        packageName: String? = null,
        windowTitle: String? = null,
    ): UiSnapshot {
        val walker = Walker(revision = revision, limits = limits)
        walker.walk(root, depth = 0)

        val elements = when (mode) {
            SnapshotMode.FULL -> walker.elements
            SnapshotMode.COMPACT, SnapshotMode.ACTIONABLE_ONLY -> walker.elements.filter { it.isActionable() }
        }
        val compactText = when (mode) {
            SnapshotMode.ACTIONABLE_ONLY -> null
            SnapshotMode.FULL, SnapshotMode.COMPACT -> walker.compactLines
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
        }

        val bounds = root.bounds
        return UiSnapshot(
            deviceId = deviceId,
            windowId = windowId,
            revision = revision,
            packageName = packageName?.takeIf { it.isNotBlank() },
            windowTitle = windowTitle?.takeIf { it.isNotBlank() },
            width = (bounds.right - bounds.left).coerceAtLeast(0),
            height = (bounds.bottom - bounds.top).coerceAtLeast(0),
            elements = elements,
            compactText = compactText,
            truncated = walker.truncated,
        )
    }

    private class Walker(
        private val revision: Int,
        private val limits: SnapshotLimits,
    ) {
        val elements = mutableListOf<UiElement>()
        val compactLines = mutableListOf<String>()
        var truncated: Boolean = false
            private set

        private val visited: MutableSet<SnapshotNode> =
            Collections.newSetFromMap(IdentityHashMap<SnapshotNode, Boolean>())

        fun walk(node: SnapshotNode, depth: Int, redact: Boolean = false) {
            if (elements.size >= limits.maxNodes) {
                truncated = true
                return
            }
            if (depth > limits.maxDepth) {
                truncated = true
                return
            }
            if (!visited.add(node)) {
                // A repeated object identity is a cycle (or a shared DAG node).
                // Serialize it once and report that the tree was not represented
                // in full instead of looping.
                truncated = true
                return
            }

            // A password flag protects the whole subtree: child labels inside a
            // protected field can contain the same secret.
            val protected = redact || node.isPassword
            val index = elements.size
            val element = node.toElement(
                ref = TreeSnapshotter.refFor(revision, index),
                redact = protected,
            )
            elements += element
            if (element.isActionable()) {
                compactLines += element.toCompactLine(password = protected)
            }

            val count = node.childCount
            if (count < 0) {
                truncated = true
                return
            }

            // Do not loop over an absurd child count reported by a malformed
            // node; the node cap already bounds useful output.
            val iterations = minOf(count, limits.maxNodes + 1)
            if (count > iterations) {
                truncated = true
            }
            for (childIndex in 0 until iterations) {
                if (elements.size >= limits.maxNodes) {
                    truncated = true
                    return
                }
                val child = try {
                    node.childAt(childIndex)
                } catch (_: RuntimeException) {
                    truncated = true
                    null
                }
                if (child == null) {
                    // childCount promised a child that the adapter could not
                    // produce; the snapshot is incomplete.
                    truncated = true
                    continue
                }
                walk(child, depth + 1, protected)
            }
        }

        private fun SnapshotNode.toElement(ref: String, redact: Boolean): UiElement {
            return UiElement(
                ref = ref,
                className = className?.takeIf { it.isNotBlank() },
                role = role?.takeIf { it.isNotBlank() },
                text = if (redact) null else text?.takeIf { it.isNotBlank() },
                contentDescription = if (redact) {
                    null
                } else {
                    contentDescription?.takeIf { it.isNotBlank() }
                },
                resourceId = resourceId?.takeIf { it.isNotBlank() },
                bounds = bounds,
                clickable = clickable,
                scrollable = scrollable,
                editable = editable,
                enabled = enabled,
                focused = focused,
                visible = visible,
                checked = checked,
                supportedActions = supportedActions
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted(),
                children = null,
            )
        }
    }

    private fun UiElement.isActionable(): Boolean =
        clickable || scrollable || editable || supportedActions.isNotEmpty()

    private fun UiElement.toCompactLine(password: Boolean): String {
        val builder = StringBuilder()
        builder.append('[').append(ref).append(']')
        className?.let { builder.append(' ').append(it) }
        role?.let { builder.append(" role=").append(it) }
        text?.let { builder.append(" text=\"").append(escape(it)).append('"') }
        contentDescription?.let { builder.append(" desc=\"").append(escape(it)).append('"') }
        resourceId?.let { builder.append(" id=").append(it) }
        if (password) {
            builder.append(" password=redacted")
        }
        if (clickable) builder.append(" clickable")
        if (scrollable) builder.append(" scrollable")
        if (editable) builder.append(" editable")
        builder.append(if (enabled) " enabled" else " disabled")
        if (focused) builder.append(" focused")
        if (!visible) builder.append(" invisible")
        checked?.let { builder.append(" checked=").append(it) }
        builder.append(" bounds=(")
            .append(bounds.left).append(',')
            .append(bounds.top).append(',')
            .append(bounds.right).append(',')
            .append(bounds.bottom).append(')')
        if (supportedActions.isNotEmpty()) {
            builder.append(" actions=").append(supportedActions.joinToString(","))
        }
        return builder.toString()
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
