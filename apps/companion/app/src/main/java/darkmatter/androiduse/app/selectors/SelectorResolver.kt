package darkmatter.androiduse.app.selectors

import darkmatter.androiduse.app.network.UiBounds
import darkmatter.androiduse.app.network.UiElement
import darkmatter.androiduse.app.network.UiSnapshot

/**
 * One snapshot revision plus its flat, stable pre-order element list.
 *
 * The tree is a value copy taken for the current call. It never contains an
 * Android node handle; resolution returns a [NodePath] that the action engine
 * reacquires from a fresh live traversal.
 */
data class CurrentTree(val snapshot: UiSnapshot) {
    val revision: Int get() = snapshot.revision
    val elements: List<UiElement> get() = snapshot.elements
}

/**
 * A revision-scoped pointer to one snapshot element.
 *
 * [index] is the stable pre-order traversal index used by
 * `TreeSnapshotter.refFor`; [ref] repeats the wire ref so callers can log it
 * without re-deriving it. [promotedFromRef] is set when click resolution safely
 * promoted a non-clickable label to its unique clickable ancestor.
 */
data class NodePath(
    val revision: Int,
    val index: Int,
    val ref: String,
    val promotedFromRef: String? = null,
) {
    init {
        require(revision >= 0) { "revision must be non-negative" }
        require(index >= 0) { "index must be non-negative" }
        require(ref.isNotEmpty()) { "ref must not be empty" }
    }
}

/** The live tree revision no longer matches the resolved path. */
class NodePathStaleException(message: String = "resolved node path is stale") : Exception(message)

/** The live traversal no longer contains the resolved path. */
class NodePathMissingException(message: String = "resolved node path is missing") : Exception(message)

/**
 * Optional actionability filters. A `null` field means "do not care", while
 * `true`/`false` require that exact state.
 */
data class ElementFilters(
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val enabled: Boolean? = null,
    val visible: Boolean? = null,
) {
    fun matches(element: UiElement): Boolean {
        clickable?.let { if (element.clickable != it) return false }
        editable?.let { if (element.editable != it) return false }
        enabled?.let { if (element.enabled != it) return false }
        visible?.let { if (element.visible != it) return false }
        return true
    }
}

/**
 * Explicit selection of one match from a multi-match result. There is no
 * implicit "first": callers must ask for [first] or [at].
 */
data class Selection(val index: Int?, val first: Boolean) {
    init {
        require(index == null || index >= 0) { "index must be non-negative" }
        require(!(index != null && first)) { "index and first are mutually exclusive" }
    }

    companion object {
        val Unique: Selection = Selection(index = null, first = false)

        fun first(): Selection = Selection(index = null, first = true)

        fun at(index: Int): Selection = Selection(index = index, first = false)
    }
}

/** Result of resolving one selector against one current snapshot revision. */
sealed interface Resolution {
    data class Found(val nodePath: NodePath) : Resolution

    data object Missing : Resolution

    data class Ambiguous(val count: Int) : Resolution

    data object StaleRef : Resolution

    data object NotActionable : Resolution
}

/**
 * Pure matching layer over snapshot DTOs.
 *
 * Matching rules:
 * - text: exact equality when `exact = true`, otherwise case-sensitive substring
 * - resourceId / description: exact equality
 * - role: exact role, plus the optional accessible name (text or description)
 * - ref: exact ref plus the snapshot revision; a mismatched revision is stale
 *
 * Duplicate semantic matches are [Resolution.Ambiguous] unless the caller
 * explicitly asks for [Selection.first] or [Selection.at]. Filters are applied
 * only after a single explicit selection, so a disabled duplicate can never be
 * silently chosen as "the actionable one".
 *
 * Click resolution may promote a non-clickable label to exactly one enclosing
 * clickable ancestor. The flat wire snapshot has no parent pointers, so
 * ancestry is inferred conservatively: a candidate must appear earlier in
 * pre-order, contain the target bounds, and be the unique smallest-area
 * clickable container. If that inference is not unique, no node is returned.
 */
object SelectorResolver {

    fun resolve(
        selector: Selector,
        tree: CurrentTree,
        filters: ElementFilters = ElementFilters(),
        selection: Selection = Selection.Unique,
        promoteClickableAncestor: Boolean = false,
    ): Resolution {
        val stale = checkRefRevision(selector, tree)
        if (stale != null) {
            return stale
        }

        val matches = tree.elements.withIndex().filter { (_, element) ->
            matches(selector, element)
        }
        if (matches.isEmpty()) {
            return Resolution.Missing
        }

        val selected = when {
            selection.index != null -> matches.getOrNull(selection.index) ?: return Resolution.Missing
            selection.first -> matches.first()
            matches.size == 1 -> matches.single()
            else -> return Resolution.Ambiguous(matches.size)
        }

        val element = selected.value
        if (filters.matches(element)) {
            return Resolution.Found(pathFor(tree, selected.index, element))
        }

        if (promoteClickableAncestor && filters.clickable == true && !element.clickable) {
            val withoutClickable = filters.copy(clickable = null)
            if (withoutClickable.matches(element)) {
                val ancestor = uniqueClickableAncestor(tree, selected.index, element, filters)
                if (ancestor != null) {
                    return Resolution.Found(
                        pathFor(
                            tree = tree,
                            index = ancestor.index,
                            element = ancestor.value,
                            promotedFromRef = element.ref,
                        ),
                    )
                }
            }
        }

        return Resolution.NotActionable
    }

    /** Counts semantic matches after applying [filters]. Never selects a node. */
    fun count(
        selector: Selector,
        tree: CurrentTree,
        filters: ElementFilters = ElementFilters(),
    ): Int {
        if (checkRefRevision(selector, tree) != null) {
            return 0
        }
        return tree.elements.count { element ->
            matches(selector, element) && filters.matches(element)
        }
    }

    fun exists(
        selector: Selector,
        tree: CurrentTree,
        filters: ElementFilters = ElementFilters(),
    ): Boolean = count(selector, tree, filters) > 0

    // ------------------------------------------------------------- internals

    private fun matches(selector: Selector, element: UiElement): Boolean = when (selector) {
        is Selector.Text -> {
            val text = element.text ?: return false
            if (selector.exact == true) text == selector.value else text.contains(selector.value)
        }

        is Selector.ResourceId -> element.resourceId == selector.value
        is Selector.Description -> element.contentDescription == selector.value
        is Selector.Role -> {
            if (element.role != selector.value) {
                false
            } else {
                selector.name == null ||
                    element.text == selector.name ||
                    element.contentDescription == selector.name
            }
        }

        is Selector.Ref -> element.ref == selector.value
    }

    private fun checkRefRevision(selector: Selector, tree: CurrentTree): Resolution? {
        if (selector !is Selector.Ref) {
            return null
        }
        if (selector.revision != tree.revision) {
            return Resolution.StaleRef
        }
        val embeddedRevision = REF_PATTERN.matchEntire(selector.value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return null
        return if (embeddedRevision == selector.revision) null else Resolution.StaleRef
    }

    private fun uniqueClickableAncestor(
        tree: CurrentTree,
        targetIndex: Int,
        target: UiElement,
        filters: ElementFilters,
    ): IndexedValue<UiElement>? {
        if (target.bounds.width() <= 0 || target.bounds.height() <= 0) {
            return null
        }
        val ancestorFilters = filters.copy(clickable = true, editable = null)
        val candidates = tree.elements.withIndex()
            .filter { (index, candidate) ->
                index < targetIndex &&
                    candidate.bounds.contains(target.bounds) &&
                    ancestorFilters.matches(candidate)
            }
        if (candidates.isEmpty()) {
            return null
        }

        val smallestArea = candidates.minOf { it.value.bounds.area() }
        val nearest = candidates.filter { it.value.bounds.area() == smallestArea }
        return nearest.singleOrNull()
    }

    private fun pathFor(
        tree: CurrentTree,
        index: Int,
        element: UiElement,
        promotedFromRef: String? = null,
    ): NodePath = NodePath(
        revision = tree.revision,
        index = index,
        ref = element.ref,
        promotedFromRef = promotedFromRef,
    )

    private fun UiBounds.contains(inner: UiBounds): Boolean =
        left <= inner.left &&
            top <= inner.top &&
            right >= inner.right &&
            bottom >= inner.bottom

    private fun UiBounds.width(): Int = (right - left).coerceAtLeast(0)

    private fun UiBounds.height(): Int = (bottom - top).coerceAtLeast(0)

    private fun UiBounds.area(): Long = width().toLong() * height().toLong()

    private val REF_PATTERN = Regex("^r(-?\\d+)-e(\\d+)$")
}
