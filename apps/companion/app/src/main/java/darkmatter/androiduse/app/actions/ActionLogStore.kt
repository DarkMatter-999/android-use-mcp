package darkmatter.androiduse.app.actions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import darkmatter.androiduse.app.network.RpcErrorCode
import darkmatter.androiduse.app.network.RpcProtocol
import darkmatter.androiduse.app.network.UiBounds

/**
 * One safe, serializable action record for the companion UI.
 *
 * The type deliberately has no free-form target text or input-value field:
 * arbitrary action text, fill/type input, and password contents cannot be
 * captured through this API. Records expose only structured metadata (action
 * name, selector kind, resource ID, bounds, revisions, timestamp, status).
 * [sensitive] is retained to suppress optional error metadata when a producer
 * marks a protected failure.
 */
@Serializable
data class ActionLogEntry(
    val actionId: String,
    val action: String,
    val selectorKind: String? = null,
    val targetResourceId: String? = null,
    val targetBounds: UiBounds? = null,
    val accepted: Boolean,
    /** `null` means dispatched/accepted but not independently verified. */
    val verified: Boolean?,
    val revisionBefore: Int,
    val revisionAfter: Int,
    val timestampMillis: Long,
    val errorCode: RpcErrorCode? = null,
    val errorMessage: String? = null,
    /** Suppresses optional error metadata; never needed to redact target text. */
    val sensitive: Boolean,
) {
    fun sanitized(): ActionLogEntry = copy(
        actionId = actionId.trim(),
        action = action.trim(),
        selectorKind = selectorKind.clean(),
        targetResourceId = targetResourceId.clean(),
        errorMessage = if (sensitive) null else errorMessage.clean(),
    )

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Bounded, thread-safe, in-memory action log. It is intentionally not persisted:
 * the POC only needs recent session observability, and keeping the log in memory
 * avoids writing sensitive metadata to disk.
 */
class ActionLogStore(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val entries = ArrayDeque<ActionLogEntry>()
    private val mutableRecent = MutableStateFlow<List<ActionLogEntry>>(emptyList())

    /** Newest-first observable view for the home screen. */
    val recentEntries: StateFlow<List<ActionLogEntry>> = mutableRecent.asStateFlow()

    /**
     * Appends a sanitized copy and returns the stored value. Sensitive entries
     * drop optional error metadata. There is no free-form target text field to
     * sanitize, so safety does not depend on caller redaction.
     */
    fun append(entry: ActionLogEntry): ActionLogEntry {
        val safe = entry.sanitized()
        synchronized(lock) {
            entries.addFirst(safe)
            while (entries.size > capacity) {
                entries.removeLast()
            }
            mutableRecent.value = entries.toList()
        }
        return safe
    }

    /** Newest-first records, capped at [limit]. Non-positive limits return empty. */
    fun recent(limit: Int): List<ActionLogEntry> = synchronized(lock) {
        if (limit <= 0) emptyList() else entries.take(limit)
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            mutableRecent.value = emptyList()
        }
    }

    /** JSON view used by tests/diagnostics; contains only sanitized records. */
    fun snapshotJson(): String = RpcProtocol.json.encodeToString(recent(Int.MAX_VALUE))

    companion object {
        const val DEFAULT_CAPACITY: Int = 200

        /** Process-wide log shared by the service, action executor, and UI. */
        val shared: ActionLogStore = ActionLogStore()
    }
}
