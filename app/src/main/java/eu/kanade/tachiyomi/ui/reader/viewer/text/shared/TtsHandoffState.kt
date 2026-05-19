package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import kotlinx.coroutines.Job

/**
 * The TTS auto-advance handoff between the chapter that just finished
 * speaking and the next chapter modeled as an explicit state machine.
 *
 * Before this type existed, each reader viewer tracked the same conceptual
 * flow with scattered nullable / boolean fields (`pendingTtsAppend`,
 * `pendingTtsHandoffTimeoutJob`, `cachedNextChapterForTts`,
 * `isFetchingNextChapterForTts`, …) and every state transition was an
 * implicit combination of nullable / boolean checks. That made it easy to
 * land in an invalid combination (e.g. cache populated while a fetch was
 * still in flight) and impossible for the compiler to catch.
 *
 * Legal transitions:
 *
 * ```
 *                ┌──────────────────────────────────┐
 *                ▼                                  │
 *  Idle ──► PreFetching ──► Cached ──► Appending ───┘
 *    │                          ▲
 *    └──────────────────────────┘  (fresh-fetch path: the append code goes
 *                                   straight from Idle to Appending and
 *                                   transitions through Cached internally)
 * ```
 *
 * Invalid transitions (e.g. transitioning to [Cached] while [Appending] is
 * active) become exhaustiveness errors in `when` blocks instead of subtle
 * runtime mis-behavior.
 *
 * @param P the type of the cached payload — viewers cache different things
 *   (WebView caches a `Pair<ReaderChapter, ReaderPage>`; TextView caches a
 *   `LoadedChapter` with view bindings).
 */
sealed class TtsHandoffState<out P> {

    /** No handoff in progress. */
    object Idle : TtsHandoffState<Nothing>()

    /** A background pre-fetch is in flight; do not start another. */
    data class PreFetching(val anchorChapterId: Long?) : TtsHandoffState<Nothing>()

    /**
     * The next chapter has been pre-fetched and is sitting in memory ready
     * to be appended. The payload type [P] is viewer-specific.
     */
    data class Cached<out P>(val payload: P) : TtsHandoffState<P>()

    /**
     * The append has been triggered and we are waiting for the renderer to
     * signal completion (typically via a JS-side callback). [watchdog] is
     * the timeout job that bails us out if the renderer never reports done.
     *
     * **Caller contract:** the watchdog job must be cancelled whenever the
     * state transitions away from [Appending] (both on success and on error).
     * The state machine does not enforce this automatically — the viewer is
     * responsible for calling `handoffState.timeoutJob?.cancel()` before
     * assigning a new state.
     */
    data class Appending(val watchdog: Job) : TtsHandoffState<Nothing>()

    val isIdle: Boolean get() = this is Idle
    val isPreFetching: Boolean get() = this is PreFetching
    val isAppending: Boolean get() = this is Appending
    val cachedOrNull: P? get() = (this as? Cached<P>)?.payload

    /**
     * Returns the timeout job attached to the current state, or `null` if
     * the state has no timeout. Helpful for clean-up paths that need to
     * cancel any pending timeout regardless of which state is active.
     */
    val timeoutJob: Job?
        get() = (this as? Appending)?.watchdog
}
