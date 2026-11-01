package darkmatter.androiduse.app.actions

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import darkmatter.androiduse.app.network.ActionResult
import darkmatter.androiduse.app.network.ActionResultError
import darkmatter.androiduse.app.network.ActionResultTarget
import darkmatter.androiduse.app.network.ActionResultVerification
import darkmatter.androiduse.app.network.RpcErrorCode
import darkmatter.androiduse.app.network.UiBounds
import darkmatter.androiduse.app.selectors.CurrentTree
import darkmatter.androiduse.app.selectors.ElementFilters
import darkmatter.androiduse.app.selectors.NodePath
import darkmatter.androiduse.app.selectors.NodePathMissingException
import darkmatter.androiduse.app.selectors.NodePathStaleException
import darkmatter.androiduse.app.selectors.Resolution
import darkmatter.androiduse.app.selectors.Selection
import darkmatter.androiduse.app.selectors.Selector
import darkmatter.androiduse.app.selectors.SelectorResolver

/** Cooperative cancellation token shared by the caller and the worker. */
class ActionCancellation {
    @Volatile
    private var cancelled: Boolean = false

    fun cancel() {
        cancelled = true
    }

    fun isCancelled(): Boolean = cancelled
}

enum class GestureKind { TAP, LONG_PRESS, SWIPE }

/** Bounded coordinate gesture handed to the service for dispatch. */
data class GestureSpec(
    val kind: GestureKind,
    val startX: Int,
    val startY: Int,
    val endX: Int? = null,
    val endY: Int? = null,
    val durationMillis: Long,
    val timeoutMillis: Long,
)

/**
 * Outcome of a gesture dispatch. `success` means the system reported completion;
 * `unsupported` and `timedOut` stay distinct so callers never conflate an
 * unavailable capability with a completed gesture.
 */
data class GestureResult(
    val success: Boolean,
    val unsupported: Boolean = false,
    val timedOut: Boolean = false,
    val message: String? = null,
)

enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS }

/**
 * Short-lived native node view. The backend keeps the underlying
 * `AccessibilityNodeInfo` alive only inside [ActionBackend.withNode].
 */
interface ActionNode {
    val clickable: Boolean
    val editable: Boolean
    val enabled: Boolean
    val visible: Boolean
    val scrollable: Boolean
    val bounds: UiBounds
    val resourceId: String?

    fun click(): Boolean

    fun longClick(): Boolean

    fun setText(value: String): Boolean

    fun scroll(direction: ScrollDirection): Boolean
}

/**
 * Platform boundary for the executor. `withNode` resolves [path] against a fresh
 * live traversal, invokes the block, and releases every acquired node before
 * returning (including on exceptions/interruption).
 */
interface ActionBackend {
    fun currentRevision(): Int

    fun currentTree(): CurrentTree

    fun <T> withNode(path: NodePath, block: (ActionNode) -> T): T

    fun dispatchGesture(spec: GestureSpec): GestureResult

    fun performGlobalAction(action: GlobalAction): Boolean
}

/**
 * Blocking native action engine. [execute] returns only after an outcome,
 * timeout, or cancellation, and appends exactly one safe [ActionLogStore]
 * record for every path.
 */
class ActionExecutor(
    private val backend: ActionBackend,
    private val logStore: ActionLogStore = ActionLogStore.shared,
    private val timestamp: () -> Long = System::currentTimeMillis,
    private val worker: ExecutorService = DefaultWorker.executor,
) {

    fun execute(
        request: ActionRequest,
        cancellation: ActionCancellation = ActionCancellation(),
    ): ActionResult {
        val basicError = validate(request)
        if (basicError != null) {
            val revision = safeRevision()
            return logAndReturn(
                failureOutcome(
                    request = request,
                    code = basicError.code,
                    message = basicError.message,
                    revisionBefore = revision,
                    revisionAfter = revision,
                    target = null,
                    sensitive = request.isSensitive,
                ),
            )
        }

        val initialRevision = safeRevision()
        if (cancellation.isCancelled()) {
            return logAndReturn(
                abortedOutcome(
                    request = request,
                    code = RpcErrorCode.CANCELLED,
                    dispatched = false,
                    target = null,
                    revisionBefore = initialRevision,
                    revisionAfter = initialRevision,
                    message = "Action was cancelled before dispatch",
                ),
            )
        }

        val dispatched = AtomicBoolean(false)
        val target = AtomicReference<TargetMeta?>(null)
        val revisionBefore = AtomicInteger(initialRevision)
        val future = worker.submit(
            Callable {
                executeInternal(
                    request = request,
                    cancellation = cancellation,
                    dispatched = dispatched,
                    target = target,
                    revisionBefore = revisionBefore,
                )
            },
        )

        val startedNanos = System.nanoTime()
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis)
        while (true) {
            if (cancellation.isCancelled()) {
                if (future.cancel(true)) {
                    return logAndReturn(
                        abortedOutcome(
                            request = request,
                            code = RpcErrorCode.CANCELLED,
                            dispatched = dispatched.get(),
                            target = target.get(),
                            revisionBefore = revisionBefore.get(),
                            revisionAfter = safeRevision(),
                            message = cancellationMessage(dispatched.get(), "cancelled"),
                        ),
                    )
                }
                return logAndReturn(finishAlreadyDone(future, request, dispatched, target, revisionBefore))
            }

            val remainingNanos = timeoutNanos - (System.nanoTime() - startedNanos)
            if (remainingNanos <= 0) {
                if (future.cancel(true)) {
                    return logAndReturn(
                        abortedOutcome(
                            request = request,
                            code = RpcErrorCode.TIMEOUT,
                            dispatched = dispatched.get(),
                            target = target.get(),
                            revisionBefore = revisionBefore.get(),
                            revisionAfter = safeRevision(),
                            message = cancellationMessage(dispatched.get(), "timed out"),
                        ),
                    )
                }
                return logAndReturn(finishAlreadyDone(future, request, dispatched, target, revisionBefore))
            }

            val pollMillis = minOf(
                TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                CANCELLATION_POLL_MILLIS,
            )
            try {
                return logAndReturn(future.get(pollMillis, TimeUnit.MILLISECONDS))
            } catch (_: TimeoutException) {
                // Poll again so cancellation is observed promptly.
            } catch (_: CancellationException) {
                // The worker was cancelled by another path; fall through.
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                if (future.cancel(true)) {
                    return logAndReturn(
                        abortedOutcome(
                            request = request,
                            code = RpcErrorCode.CANCELLED,
                            dispatched = dispatched.get(),
                            target = target.get(),
                            revisionBefore = revisionBefore.get(),
                            revisionAfter = safeRevision(),
                            message = "Action wait was interrupted",
                        ),
                    )
                }
                return logAndReturn(finishAlreadyDone(future, request, dispatched, target, revisionBefore))
            } catch (_: ExecutionException) {
                return logAndReturn(
                    failureOutcome(
                        request = request,
                        code = RpcErrorCode.INTERNAL,
                        message = "Action execution failed",
                        revisionBefore = revisionBefore.get(),
                        revisionAfter = safeRevision(),
                        target = target.get(),
                        sensitive = request.isSensitive,
                    ),
                )
            }
        }
    }

    // ----------------------------------------------------------- execution

    private fun executeInternal(
        request: ActionRequest,
        cancellation: ActionCancellation,
        dispatched: AtomicBoolean,
        target: AtomicReference<TargetMeta?>,
        revisionBefore: AtomicInteger,
    ): ExecutionOutcome {
        var before = revisionBefore.get()
        try {
            val tree = backend.currentTree()
            before = tree.revision
            revisionBefore.set(before)
            if (cancellation.isCancelled()) {
                return abortedOutcome(
                    request = request,
                    code = RpcErrorCode.CANCELLED,
                    dispatched = false,
                    target = null,
                    revisionBefore = before,
                    revisionAfter = safeRevision(),
                    message = "Action was cancelled before dispatch",
                )
            }

            val expectedRevision = request.expectedRevision
            if (expectedRevision != null && expectedRevision != tree.revision) {
                return failureOutcome(
                    request = request,
                    code = RpcErrorCode.STALE_REF,
                    message = "Snapshot revision is no longer current",
                    revisionBefore = before,
                    revisionAfter = safeRevision(),
                    target = null,
                    sensitive = request.isSensitive,
                )
            }

            return when (request.action) {
                in ActionNames.nodeActions -> executeNodeAction(
                    request = request,
                    tree = tree,
                    cancellation = cancellation,
                    dispatched = dispatched,
                    target = target,
                    revisionBefore = before,
                )

                in ActionNames.gestureActions -> executeGesture(
                    request = request,
                    tree = tree,
                    cancellation = cancellation,
                    dispatched = dispatched,
                    revisionBefore = before,
                )

                in ActionNames.globalActions -> executeGlobal(
                    request = request,
                    cancellation = cancellation,
                    dispatched = dispatched,
                    revisionBefore = before,
                )

                else -> failureOutcome(
                    request = request,
                    code = RpcErrorCode.UNSUPPORTED,
                    message = "Action is not supported",
                    revisionBefore = before,
                    revisionAfter = safeRevision(),
                    target = null,
                    sensitive = request.isSensitive,
                )
            }
        } catch (_: NodePathStaleException) {
            return failureOutcome(
                request = request,
                code = RpcErrorCode.STALE_REF,
                message = "Snapshot revision is no longer current",
                revisionBefore = before,
                revisionAfter = safeRevision(),
                target = target.get(),
                sensitive = request.isSensitive,
            )
        } catch (_: NodePathMissingException) {
            return failureOutcome(
                request = request,
                code = RpcErrorCode.NOT_FOUND,
                message = "No matching node exists in the current tree",
                revisionBefore = before,
                revisionAfter = safeRevision(),
                target = target.get(),
                sensitive = request.isSensitive,
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return abortedOutcome(
                request = request,
                code = RpcErrorCode.CANCELLED,
                dispatched = dispatched.get(),
                target = target.get(),
                revisionBefore = before,
                revisionAfter = safeRevision(),
                message = cancellationMessage(dispatched.get(), "cancelled"),
            )
        } catch (_: RuntimeException) {
            return failureOutcome(
                request = request,
                code = RpcErrorCode.INTERNAL,
                message = "Action execution failed",
                revisionBefore = before,
                revisionAfter = safeRevision(),
                target = target.get(),
                sensitive = request.isSensitive,
            )
        }
    }

    private fun executeNodeAction(
        request: ActionRequest,
        tree: CurrentTree,
        cancellation: ActionCancellation,
        dispatched: AtomicBoolean,
        target: AtomicReference<TargetMeta?>,
        revisionBefore: Int,
    ): ExecutionOutcome {
        val selector = request.selector
            ?: return failureOutcome(
                request = request,
                code = RpcErrorCode.INVALID_MESSAGE,
                message = "A selector is required for this action",
                revisionBefore = revisionBefore,
                revisionAfter = safeRevision(),
                target = null,
                sensitive = request.isSensitive,
            )

        val filters = filtersFor(request.action)
        val selection = when {
            request.index != null -> Selection.at(request.index)
            request.first -> Selection.first()
            else -> Selection.Unique
        }
        val promote = request.action == ActionNames.CLICK || request.action == ActionNames.LONG_CLICK
        val resolution = SelectorResolver.resolve(
            selector = selector,
            tree = tree,
            filters = filters,
            selection = selection,
            promoteClickableAncestor = promote,
        )

        val path = when (resolution) {
            is Resolution.Found -> resolution.nodePath
            Resolution.Missing -> return failureOutcome(
                request, RpcErrorCode.NOT_FOUND, "No element matched the selector",
                revisionBefore, safeRevision(), null, request.isSensitive,
            )

            is Resolution.Ambiguous -> return failureOutcome(
                request,
                RpcErrorCode.AMBIGUOUS_TARGET,
                "Selector matched ${resolution.count} elements; use first or index",
                revisionBefore,
                safeRevision(),
                null,
                request.isSensitive,
            )

            Resolution.StaleRef -> return failureOutcome(
                request, RpcErrorCode.STALE_REF, "Snapshot revision is no longer current",
                revisionBefore, safeRevision(), null, request.isSensitive,
            )

            Resolution.NotActionable -> return failureOutcome(
                request, RpcErrorCode.NOT_ACTIONABLE, "Target is not actionable",
                revisionBefore, safeRevision(), null, request.isSensitive,
            )
        }

        val element = tree.elements.getOrNull(path.index)
            ?: return failureOutcome(
                request, RpcErrorCode.NOT_FOUND, "No matching node exists in the current tree",
                revisionBefore, safeRevision(), null, request.isSensitive,
            )
        val meta = TargetMeta(
            ref = path.ref,
            resourceId = element.resourceId,
            bounds = element.bounds,
            selectorKind = selectorKind(selector),
        )
        target.set(meta)

        if (request.action == ActionNames.SCROLL && !element.scrollable) {
            return failureOutcome(
                request, RpcErrorCode.NOT_ACTIONABLE, "Target is not scrollable",
                revisionBefore, safeRevision(), meta, request.isSensitive,
            )
        }
        if (cancellation.isCancelled()) {
            return abortedOutcome(
                request = request,
                code = RpcErrorCode.CANCELLED,
                dispatched = false,
                target = meta,
                revisionBefore = revisionBefore,
                revisionAfter = safeRevision(),
                message = "Action was cancelled before dispatch",
            )
        }

        val dispatch = backend.withNode(path) { node ->
            // The snapshot may become stale after resolution but before live-node
            // dispatch. Recheck within the backend's short-lived node window.
            if (backend.currentRevision() != path.revision) {
                throw NodePathStaleException()
            }
            when (request.action) {
                ActionNames.CLICK -> {
                    dispatched.set(true)
                    NodeDispatch(dispatched = true, success = node.click())
                }

                ActionNames.LONG_CLICK -> {
                    dispatched.set(true)
                    NodeDispatch(dispatched = true, success = node.longClick())
                }

                ActionNames.FILL -> {
                    dispatched.set(true)
                    NodeDispatch(dispatched = true, success = node.setText(request.value.orEmpty()))
                }

                ActionNames.CLEAR -> {
                    dispatched.set(true)
                    NodeDispatch(dispatched = true, success = node.setText(""))
                }

                ActionNames.SCROLL -> {
                    val direction = request.direction
                    if (!node.scrollable || direction == null) {
                        NodeDispatch(dispatched = false, success = false)
                    } else {
                        dispatched.set(true)
                        NodeDispatch(dispatched = true, success = node.scroll(direction))
                    }
                }

                else -> NodeDispatch(dispatched = false, success = false)
            }
        }

        val after = safeRevision()
        if (!dispatch.dispatched) {
            return failureOutcome(
                request, RpcErrorCode.NOT_ACTIONABLE, "Target is not actionable",
                revisionBefore, after, meta, request.isSensitive,
            )
        }
        if (!dispatch.success) {
            return failureOutcome(
                request, RpcErrorCode.NOT_ACTIONABLE, "The target rejected the action",
                revisionBefore, after, meta, request.isSensitive,
            )
        }

        val (verified, verification) = verification(request, accepted = true)
        return ExecutionOutcome(
            result = ActionResult(
                actionId = request.actionId,
                action = request.action,
                accepted = true,
                verified = verified,
                verification = verification,
                target = targetFor(meta),
                revisionBefore = revisionBefore,
                revisionAfter = after,
                error = null,
            ),
            selectorKind = meta.selectorKind,
            targetResourceId = meta.resourceId,
            targetBounds = meta.bounds,
            sensitive = request.isSensitive,
        )
    }

    private fun executeGesture(
        request: ActionRequest,
        tree: CurrentTree,
        cancellation: ActionCancellation,
        dispatched: AtomicBoolean,
        revisionBefore: Int,
    ): ExecutionOutcome {
        val spec = gestureSpec(request)
            ?: return failureOutcome(
                request,
                RpcErrorCode.INVALID_MESSAGE,
                "Gesture coordinates or duration are invalid",
                revisionBefore,
                safeRevision(),
                null,
                request.isSensitive,
            )

        if (!inBounds(tree, spec.startX, spec.startY) ||
            (spec.kind == GestureKind.SWIPE && !inBounds(tree, spec.endX!!, spec.endY!!))
        ) {
            return failureOutcome(
                request,
                RpcErrorCode.INVALID_MESSAGE,
                "Gesture coordinates are outside the current window",
                revisionBefore,
                safeRevision(),
                null,
                request.isSensitive,
            )
        }

        if (cancellation.isCancelled()) {
            return abortedOutcome(
                request = request,
                code = RpcErrorCode.CANCELLED,
                dispatched = false,
                target = null,
                revisionBefore = revisionBefore,
                revisionAfter = safeRevision(),
                message = "Action was cancelled before dispatch",
            )
        }

        expectedRevisionFailure(request, revisionBefore)?.let { return it }

        dispatched.set(true)
        val result = backend.dispatchGesture(spec)
        val after = safeRevision()
        return when {
            result.unsupported -> failureOutcome(
                request, RpcErrorCode.UNSUPPORTED, "Gesture dispatch is not supported",
                revisionBefore, after, null, request.isSensitive,
            )

            result.timedOut -> abortedOutcome(
                request = request,
                code = RpcErrorCode.TIMEOUT,
                dispatched = true,
                target = null,
                revisionBefore = revisionBefore,
                revisionAfter = after,
                message = "Gesture timed out after dispatch; outcome may be unknown",
            )

            !result.success -> failureOutcome(
                request, RpcErrorCode.NOT_ACTIONABLE, "Gesture was rejected",
                revisionBefore, after, null, request.isSensitive,
            )

            else -> {
                val (verified, verification) = verification(request, accepted = true)
                ExecutionOutcome(
                    result = ActionResult(
                        actionId = request.actionId,
                        action = request.action,
                        accepted = true,
                        verified = verified,
                        verification = verification,
                        target = null,
                        revisionBefore = revisionBefore,
                        revisionAfter = after,
                        error = null,
                    ),
                    selectorKind = null,
                    targetResourceId = null,
                    targetBounds = null,
                    gestureX = spec.startX,
                    gestureY = spec.startY,
                    sensitive = request.isSensitive,
                )
            }
        }
    }

    private fun executeGlobal(
        request: ActionRequest,
        cancellation: ActionCancellation,
        dispatched: AtomicBoolean,
        revisionBefore: Int,
    ): ExecutionOutcome {
        if (cancellation.isCancelled()) {
            return abortedOutcome(
                request = request,
                code = RpcErrorCode.CANCELLED,
                dispatched = false,
                target = null,
                revisionBefore = revisionBefore,
                revisionAfter = safeRevision(),
                message = "Action was cancelled before dispatch",
            )
        }

        expectedRevisionFailure(request, revisionBefore)?.let { return it }

        val global = when (request.action) {
            ActionNames.BACK -> GlobalAction.BACK
            ActionNames.HOME -> GlobalAction.HOME
            ActionNames.RECENTS -> GlobalAction.RECENTS
            ActionNames.NOTIFICATIONS -> GlobalAction.NOTIFICATIONS
            else -> return failureOutcome(
                request, RpcErrorCode.UNSUPPORTED, "Action is not supported",
                revisionBefore, safeRevision(), null, request.isSensitive,
            )
        }

        dispatched.set(true)
        val success = backend.performGlobalAction(global)
        val after = safeRevision()
        if (!success) {
            return failureOutcome(
                request, RpcErrorCode.UNSUPPORTED, "Global action is not supported",
                revisionBefore, after, null, request.isSensitive,
            )
        }

        val (verified, verification) = verification(request, accepted = true)
        return ExecutionOutcome(
            result = ActionResult(
                actionId = request.actionId,
                action = request.action,
                accepted = true,
                verified = verified,
                verification = verification,
                target = null,
                revisionBefore = revisionBefore,
                revisionAfter = after,
                error = null,
            ),
            selectorKind = null,
            targetResourceId = null,
            targetBounds = null,
            sensitive = request.isSensitive,
        )
    }

    // ------------------------------------------------------------ verification

    private fun verification(
        request: ActionRequest,
        accepted: Boolean,
    ): Pair<Boolean?, ActionResultVerification?> {
        val postcondition = request.postcondition ?: return null to null
        if (!accepted) {
            return false to ActionResultVerification(false, "Action was not accepted")
        }

        return try {
            val tree = backend.currentTree()
            val postconditionSelector = postcondition.selector
            if (postconditionSelector is Selector.Ref && postconditionSelector.revision != tree.revision) {
                // A stale ref denotes "unknown", not "absent": an exists=false
                // postcondition must never pass merely because the revision moved.
                return false to ActionResultVerification(
                    passed = false,
                    message = "Postcondition could not be evaluated",
                )
            }
            val filters = ElementFilters(
                clickable = postcondition.clickable,
                editable = postcondition.editable,
                enabled = postcondition.enabled,
                visible = postcondition.visible,
            )
            val count = SelectorResolver.count(postconditionSelector, tree, filters)
            val passed = if (postcondition.exists) count > 0 else count == 0
            val message = if (passed) {
                "Postcondition satisfied"
            } else {
                "Postcondition not satisfied"
            }
            passed to ActionResultVerification(passed, message)
        } catch (_: RuntimeException) {
            false to ActionResultVerification(false, "Postcondition could not be evaluated")
        }
    }

    // --------------------------------------------------------------- outcomes

    private fun failureOutcome(
        request: ActionRequest,
        code: RpcErrorCode,
        message: String,
        revisionBefore: Int,
        revisionAfter: Int,
        target: TargetMeta?,
        sensitive: Boolean,
    ): ExecutionOutcome = ExecutionOutcome(
        result = ActionResult(
            actionId = request.actionId,
            action = request.action,
            accepted = false,
            verified = null,
            verification = null,
            target = targetFor(target),
            revisionBefore = revisionBefore,
            revisionAfter = revisionAfter,
            error = ActionResultError(code = code, message = message),
        ),
        selectorKind = selectorKind(request.selector),
        targetResourceId = target?.resourceId,
        targetBounds = target?.bounds,
        sensitive = sensitive,
    )

    private fun abortedOutcome(
        request: ActionRequest,
        code: RpcErrorCode,
        dispatched: Boolean,
        target: TargetMeta?,
        revisionBefore: Int,
        revisionAfter: Int,
        message: String,
    ): ExecutionOutcome = ExecutionOutcome(
        result = ActionResult(
            actionId = request.actionId,
            action = request.action,
            accepted = dispatched,
            verified = null,
            verification = null,
            target = targetFor(target),
            revisionBefore = revisionBefore,
            revisionAfter = revisionAfter,
            error = ActionResultError(code = code, message = message),
        ),
        selectorKind = selectorKind(request.selector),
        targetResourceId = target?.resourceId,
        targetBounds = target?.bounds,
        sensitive = request.isSensitive,
    )

    private fun finishAlreadyDone(
        future: java.util.concurrent.Future<ExecutionOutcome>,
        request: ActionRequest,
        dispatched: AtomicBoolean,
        target: AtomicReference<TargetMeta?>,
        revisionBefore: AtomicInteger,
    ): ExecutionOutcome = try {
        future.get()
    } catch (_: CancellationException) {
        abortedOutcome(
            request = request,
            code = RpcErrorCode.CANCELLED,
            dispatched = dispatched.get(),
            target = target.get(),
            revisionBefore = revisionBefore.get(),
            revisionAfter = safeRevision(),
            message = cancellationMessage(dispatched.get(), "cancelled"),
        )
    } catch (_: ExecutionException) {
        failureOutcome(
            request, RpcErrorCode.INTERNAL, "Action execution failed",
            revisionBefore.get(), safeRevision(), target.get(), request.isSensitive,
        )
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        abortedOutcome(
            request = request,
            code = RpcErrorCode.CANCELLED,
            dispatched = dispatched.get(),
            target = target.get(),
            revisionBefore = revisionBefore.get(),
            revisionAfter = safeRevision(),
            message = "Action wait was interrupted",
        )
    }

    private fun logAndReturn(outcome: ExecutionOutcome): ActionResult {
        val result = outcome.result
        logStore.append(
            ActionLogEntry(
                actionId = result.actionId,
                action = result.action,
                selectorKind = outcome.selectorKind,
                targetResourceId = outcome.targetResourceId,
                targetBounds = outcome.targetBounds,
                gestureX = outcome.gestureX,
                gestureY = outcome.gestureY,
                accepted = result.accepted,
                verified = result.verified,
                revisionBefore = result.revisionBefore,
                revisionAfter = result.revisionAfter,
                timestampMillis = timestamp(),
                errorCode = result.error?.code,
                errorMessage = result.error?.message,
                sensitive = outcome.sensitive,
            ),
        )
        return result
    }

    // -------------------------------------------------------------- validation

    private data class RequestError(val code: RpcErrorCode, val message: String)

    private fun validate(request: ActionRequest): RequestError? {
        if (request.actionId.isBlank()) {
            return RequestError(RpcErrorCode.INVALID_MESSAGE, "actionId must not be empty")
        }
        if (request.action.isBlank()) {
            return RequestError(RpcErrorCode.INVALID_MESSAGE, "action must not be empty")
        }
        if (request.action !in ActionNames.supported) {
            return RequestError(RpcErrorCode.UNSUPPORTED, "Action is not supported")
        }
        if (request.timeoutMillis !in 1..ActionRequest.MAX_TIMEOUT_MILLIS) {
            return RequestError(
                RpcErrorCode.INVALID_MESSAGE,
                "timeoutMillis must be between 1 and ${ActionRequest.MAX_TIMEOUT_MILLIS}",
            )
        }
        if (request.index != null && request.index < 0) {
            return RequestError(RpcErrorCode.INVALID_MESSAGE, "index must be non-negative")
        }
        if (request.index != null && request.first) {
            return RequestError(RpcErrorCode.INVALID_MESSAGE, "index and first are mutually exclusive")
        }
        if (request.expectedRevision != null && request.expectedRevision < 0) {
            return RequestError(RpcErrorCode.INVALID_MESSAGE, "expectedRevision must be non-negative")
        }

        return when (request.action) {
            in ActionNames.nodeActions -> validateNodeAction(request)
            in ActionNames.gestureActions -> validateGesture(request)
            else -> null
        }
    }

    private fun validateNodeAction(request: ActionRequest): RequestError? {
        if (request.selector == null) {
            return RequestError(RpcErrorCode.INVALID_MESSAGE, "A selector is required for this action")
        }
        if (request.action == ActionNames.FILL) {
            val value = request.value
                ?: return RequestError(RpcErrorCode.INVALID_MESSAGE, "fill requires a value")
            if (value.length > ActionRequest.MAX_INPUT_CHARS) {
                return RequestError(
                    RpcErrorCode.INVALID_MESSAGE,
                    "fill value is too long; the maximum is ${ActionRequest.MAX_INPUT_CHARS} characters",
                )
            }
        }
        if (request.action == ActionNames.SCROLL && request.direction == null) {
            return RequestError(RpcErrorCode.INVALID_MESSAGE, "scroll requires a direction")
        }
        return null
    }

    private fun validateGesture(request: ActionRequest): RequestError? {
        val x = request.x
        val y = request.y
        if (x == null || y == null) {
            return RequestError(RpcErrorCode.INVALID_MESSAGE, "Gesture start coordinates are required")
        }
        if (x !in 0..ActionRequest.MAX_COORDINATE || y !in 0..ActionRequest.MAX_COORDINATE) {
            return RequestError(RpcErrorCode.INVALID_MESSAGE, "Gesture coordinates are out of bounds")
        }

        val duration = request.durationMillis ?: when (request.action) {
            ActionNames.TAP -> TAP_DURATION_MILLIS
            ActionNames.LONG_PRESS -> LONG_PRESS_DURATION_MILLIS
            else -> SWIPE_DURATION_MILLIS
        }
        val minimum = when (request.action) {
            ActionNames.LONG_PRESS -> LONG_PRESS_MIN_MILLIS
            else -> 1L
        }
        val maximum = when (request.action) {
            ActionNames.TAP -> TAP_MAX_MILLIS
            else -> ActionRequest.MAX_GESTURE_MILLIS
        }
        if (duration !in minimum..maximum) {
            return RequestError(
                RpcErrorCode.INVALID_MESSAGE,
                "Gesture duration must be between $minimum and $maximum milliseconds",
            )
        }

        if (request.action == ActionNames.SWIPE) {
            val endX = request.endX ?: return RequestError(
                RpcErrorCode.INVALID_MESSAGE,
                "swipe requires end coordinates",
            )
            val endY = request.endY ?: return RequestError(
                RpcErrorCode.INVALID_MESSAGE,
                "swipe requires end coordinates",
            )
            if (endX !in 0..ActionRequest.MAX_COORDINATE || endY !in 0..ActionRequest.MAX_COORDINATE) {
                return RequestError(RpcErrorCode.INVALID_MESSAGE, "Gesture coordinates are out of bounds")
            }
            if (endX == x && endY == y) {
                return RequestError(RpcErrorCode.INVALID_MESSAGE, "swipe must move between two points")
            }
        }
        return null
    }

    private fun gestureSpec(request: ActionRequest): GestureSpec? {
        val x = request.x ?: return null
        val y = request.y ?: return null
        val duration = request.durationMillis ?: when (request.action) {
            ActionNames.TAP -> TAP_DURATION_MILLIS
            ActionNames.LONG_PRESS -> LONG_PRESS_DURATION_MILLIS
            else -> SWIPE_DURATION_MILLIS
        }
        return when (request.action) {
            ActionNames.TAP -> GestureSpec(
                kind = GestureKind.TAP,
                startX = x,
                startY = y,
                durationMillis = duration,
                timeoutMillis = request.timeoutMillis,
            )

            ActionNames.LONG_PRESS -> GestureSpec(
                kind = GestureKind.LONG_PRESS,
                startX = x,
                startY = y,
                durationMillis = duration,
                timeoutMillis = request.timeoutMillis,
            )

            ActionNames.SWIPE -> GestureSpec(
                kind = GestureKind.SWIPE,
                startX = x,
                startY = y,
                endX = request.endX ?: return null,
                endY = request.endY ?: return null,
                durationMillis = duration,
                timeoutMillis = request.timeoutMillis,
            )

            else -> null
        }
    }

    private fun inBounds(tree: CurrentTree, x: Int, y: Int): Boolean =
        x in 0 until tree.snapshot.width && y in 0 until tree.snapshot.height

    // --------------------------------------------------------------- metadata

    private data class TargetMeta(
        val ref: String,
        val resourceId: String?,
        val bounds: UiBounds,
        val selectorKind: String?,
    )

    private data class NodeDispatch(val dispatched: Boolean, val success: Boolean)

    private data class ExecutionOutcome(
        val result: ActionResult,
        val selectorKind: String?,
        val targetResourceId: String?,
        val targetBounds: UiBounds?,
        val sensitive: Boolean,
        val gestureX: Int? = null,
        val gestureY: Int? = null,
    )

    private fun targetFor(meta: TargetMeta?): ActionResultTarget? = meta?.let {
        ActionResultTarget(
            ref = it.ref,
            text = null,
            bounds = listOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom),
        )
    }

    private fun selectorKind(selector: Selector?): String? = when (selector) {
        is Selector.Text -> "text"
        is Selector.ResourceId -> "resourceId"
        is Selector.Description -> "description"
        is Selector.Role -> "role"
        is Selector.Ref -> "ref"
        null -> null
    }

    private fun filtersFor(action: String): ElementFilters = when (action) {
        ActionNames.CLICK, ActionNames.LONG_CLICK ->
            ElementFilters(clickable = true, enabled = true, visible = true)

        ActionNames.FILL, ActionNames.CLEAR ->
            ElementFilters(editable = true, enabled = true, visible = true)

        ActionNames.SCROLL -> ElementFilters(enabled = true, visible = true)
        else -> ElementFilters()
    }

    private fun safeRevision(): Int = try {
        backend.currentRevision()
    } catch (_: RuntimeException) {
        0
    }

    private fun expectedRevisionFailure(request: ActionRequest, revisionBefore: Int): ExecutionOutcome? {
        val expected = request.expectedRevision ?: return null
        val current = safeRevision()
        if (current == expected) return null
        return failureOutcome(
            request = request,
            code = RpcErrorCode.STALE_REF,
            message = "Snapshot revision is no longer current",
            revisionBefore = revisionBefore,
            revisionAfter = current,
            target = null,
            sensitive = request.isSensitive,
        )
    }

    private fun cancellationMessage(dispatched: Boolean, reason: String): String =
        if (dispatched) {
            "Action $reason after dispatch; outcome may be unknown"
        } else {
            "Action $reason before dispatch"
        }

    private object DefaultWorker {
        val executor: ExecutorService = Executors.newCachedThreadPool(
            ThreadFactory { runnable ->
                Thread(runnable, "android-use-action").apply { isDaemon = true }
            },
        )
    }

    private companion object {
        const val CANCELLATION_POLL_MILLIS: Long = 10
        const val TAP_DURATION_MILLIS: Long = 50
        const val TAP_MAX_MILLIS: Long = 500
        const val LONG_PRESS_MIN_MILLIS: Long = 500
        const val LONG_PRESS_DURATION_MILLIS: Long = 600
        const val SWIPE_DURATION_MILLIS: Long = 300
    }
}
