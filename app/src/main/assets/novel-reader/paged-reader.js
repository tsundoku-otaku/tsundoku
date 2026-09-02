// Paged-mode engine for the novel WebView reader (webview only, v1).
// Installed once per load via NovelWebViewStyler.injectPagedReader(), which substitutes the
// __TSUNDOKU_OBJECT_NAME__ / __CHAPTERS_CONTAINER_ID__ / __PAGED_BODY_CLASS__ / __PAGE_EVENT__ /
// __PROGRESS_EVENT__ / __PAGED_ENABLED__ / __SAFE_TOP_VAR__ / __SAFE_BOTTOM_VAR__ tokens.
//
// Mechanism: CSS multi-column on #__CHAPTERS_CONTAINER_ID__, column-width set inline from
// document.body.clientWidth (accounts for the user's margins - body keeps its configured margin
// in paged mode, only html is zeroed). Page turns move the container with
// `transform: translateX`, not scrollLeft, so a page flip never fires a `scroll` event. The
// container has a CSS transition on transform for an animated turn, suppressed for any
// non-user-driven transform change (restore, resize/rotation repagination).
//
// Progress is reported through the SAME Android bridge calls scroll-tracking.js already uses
// (onScrollUpdate / onScrollProgress), as a percent ratio - so it round-trips through the
// existing `last_page_read` percent column with no schema change.
//
// The authoritative reading position is `p.lastRatio`, not pageIndex/pageCount - pageIndex is
// always RE-DERIVED from lastRatio against the current pageCount, never the other way around.
// This matters because pageCount can be measured wrong on a transient/early layout pass (fonts
// still loading, etc); if a later, correct repagination derived its target from the PREVIOUS
// (possibly wrong) pageIndex/pageCount instead of the original intended ratio, an early
// mis-measurement would compound into landing on the wrong page (this caused a real bug: a
// chapter opening on an early pageCount=1 read, followed by a corrective re-layout jumping straight
// to the last page). Every user-driven move (a page turn, a slider seek) updates lastRatio too, so
// it always reflects where the reader actually chose to be, not a stale derived value.
//
// Unlike an earlier draft, paged mode does NOT append the next chapter into this document for
// infinite scroll - crossing a page edge always does a full chapter switch (matching how a
// backward crossing already worked), with the next/previous chapter prefetched by Kotlin
// (ReaderActivity.requestPreloadChapter, the same generic preload path used elsewhere) once the
// reader nears the first/last page, so the switch is fast without the append-position bugs.
//
// Reports to the Android JS interface:
//   onPageInfoChanged(pageIndex, pageCount)   status bar / jump-to-page display, and triggers
//                                              Kotlin-side next/prev chapter prefetch near an edge
//   onScrollUpdate(ratio) / onScrollProgress(ratio)   same persist pipeline as scroll mode
//
// Publishes for snippets/plugins:
//   runtime.pagingEnabled / runtime.pageIndex / runtime.pageCount
//   runtime.progress / runtime.chapterProgress / runtime.currentChapterId   same fields
//     scroll-tracking.js publishes in continuous mode, kept in sync here so a snippet reading them
//     doesn't need a paged-mode branch (in paged mode progress === chapterProgress, since only one
//     chapter is ever in the DOM - see the full-chapter-switch note above).
//   actions.nextPage() / actions.prevPage() / actions.goToPage(n)
//   actions.setPagedConfig({...})   merges into config.paged, takes effect on the next gesture
//   config.paged.{dragCommitFraction, edgeCommitFraction, edgeMaxDampedFraction,
//                 chapterTransitionHoldMs}   live-read tunables, see DEFAULT_PAGED_CONFIG below -
//                 a snippet may write these directly (config.paged.dragCommitFraction = 0.05) or
//                 through actions.setPagedConfig; either way every read site below pulls from this
//                 object fresh, so a change applies to the very next swipe/edge-cross without a
//                 reload. Values already present on window.Tsundoku.config.paged when this script
//                 evaluates (a customJs snippet ran first, same load) win over the defaults.
//   window event __PAGE_EVENT__  { pageIndex, pageCount }
//   window event __PROGRESS_EVENT__  { progress, chapterProgress, chapterId, isLast }   same shape
//     scroll-tracking.js dispatches, so a snippet can bind one listener for both modes
//
// Chapter-edge overflow briefly shows a pull-to-refresh-style edge chevron before calling the
// same Android bridge methods the non-paged "Next/Previous Chapter" actions use.

(function () {
    window.__TSUNDOKU_OBJECT_NAME__ = window.__TSUNDOKU_OBJECT_NAME__ || {};
    var T = window.__TSUNDOKU_OBJECT_NAME__;
    T.runtime = T.runtime || {};
    var runtime = T.runtime;

    if (runtime.pagedReaderInstalled) {
        return;
    }
    runtime.pagedReaderInstalled = true;

    var PAGED_CLASS = '__PAGED_BODY_CLASS__';
    var PAGE_EVENT = '__PAGE_EVENT__';
    var PROGRESS_EVENT = '__PROGRESS_EVENT__';
    var CONTAINER_ID = '__CHAPTERS_CONTAINER_ID__';
    var NO_TRANSITION_CLASS = 'tsundoku-paged-no-transition';
    var TRANSITION_ID = 'tsundoku-paged-chapter-transition';
    var TRANSITION_VISIBLE_CLASS = 'tsundoku-visible';
    var enabled = __PAGED_ENABLED__;

    // User/snippet-tunable behavior constants - read live from T.config.paged everywhere below
    // (never cached into a local var) so a change made at any point, by a snippet running before
    // this script or one appended later from the console/dev-tools, takes effect on the very next
    // gesture. Defaults fill in only the keys not already set (e.g. by a customJs snippet that ran
    // earlier in this same page load, before injectPagedReader()).
    var DEFAULT_PAGED_CONFIG = {
        // Fraction of the WebView's width a drag must cross to commit a same-chapter page turn.
        dragCommitFraction: 0.15,
        // Fraction of the WebView's width a drag past the first/last page must cross to commit a
        // chapter switch (independent of dragCommitFraction - crossing a chapter boundary is a
        // deliberately bigger gesture than turning a page).
        edgeCommitFraction: 0.35,
        // Cap (as a fraction of width) on how far the rubber-band edge drag can visually travel,
        // however far past the edge the finger actually goes.
        edgeMaxDampedFraction: 0.45,
        // How long the edge chevron/title-announce transition holds before the chapter switch
        // actually fires (swipe-past-edge and the bottom-bar arrow at the first/last page both
        // go through this).
        chapterTransitionHoldMs: 260,
    };
    T.config = T.config || {};
    T.config.paged = (function (overrides) {
        var merged = {};
        for (var k in DEFAULT_PAGED_CONFIG) merged[k] = DEFAULT_PAGED_CONFIG[k];
        for (var k2 in overrides) {
            if (Object.prototype.hasOwnProperty.call(overrides, k2)) merged[k2] = overrides[k2];
        }
        return merged;
    })(T.config.paged || {});
    var cfg = T.config.paged;

    var p = window.__tdPaged || (window.__tdPaged = {});
    p.pageIndex = p.pageIndex || 0;
    p.pageCount = p.pageCount || 1;
    p.lastRatio = (typeof p.lastRatio === 'number') ? p.lastRatio : 0;
    // Guards the ResizeObserver/window-resize auto-repaginate below from firing before
    // restoreScrollPosition's first __tdPagedRestoreRatio call has set the real lastRatio.
    p.restored = p.restored || false;

    function container() {
        var el = document.getElementById(CONTAINER_ID);
        if (el) return el;
        el = document.createElement('div');
        el.id = CONTAINER_ID;
        while (document.body.firstChild) {
            el.appendChild(document.body.firstChild);
        }
        document.body.appendChild(el);
        return el;
    }

    // The stable "one page" width: read from body, never from the multicol container itself
    // (whose own clientWidth is the full multi-column box, not one column).
    function pageWidth() {
        return document.body.clientWidth || window.innerWidth || 1;
    }

    function applyColumnLayout() {
        var el = container();
        var w = pageWidth();
        el.style.columnWidth = w + 'px';
        el.style.MozColumnWidth = w + 'px';
        el.style.webkitColumnWidth = w + 'px';
        // body needs a definite height (not just html's) for its own overflow:hidden to actually
        // clip the container - reduce by body's own margin only. Deliberately NOT by
        // __SAFE_TOP_VAR__/__SAFE_BOTTOM_VAR__ (transient menu-bar overlays) - sizing the page to
        // those turned every menu toggle into a repagination + ResizeObserver feedback loop.
        var cs = window.getComputedStyle(document.body);
        var vMargin = (parseFloat(cs.marginTop) || 0) + (parseFloat(cs.marginBottom) || 0);
        document.body.style.boxSizing = 'border-box';
        // Explicit floored px height, not `calc(100vh - Npx)`: fractional rounding there let the
        // last line's descenders clip past the overflow:hidden edge. 1px safety buffer for the rest.
        var innerH = window.innerHeight || document.documentElement.clientHeight;
        document.body.style.height = Math.floor(innerH - vMargin - 1) + 'px';
    }

    function computePageCount() {
        var el = container();
        var w = pageWidth();
        if (!w) return 1;
        // ceil, not round: columns are uniform width (only the last one is partially filled with
        // text, never narrower), so rounding down on a sub-pixel-under measurement would strand
        // the last page's tail content past the end of the computed count.
        return Math.max(1, Math.ceil(el.scrollWidth / w));
    }

    // Layout-based (offsetLeft/offsetParent chain), not paint-based (getBoundingClientRect):
    // unaffected by the container's own `transform`.
    function offsetLeftWithin(el, root) {
        var x = 0;
        var node = el;
        while (node && node !== root) {
            x += node.offsetLeft || 0;
            node = node.offsetParent;
        }
        return x;
    }

    function pageOfElement(el) {
        if (!el) return null;
        var left = offsetLeftWithin(el, container());
        var w = pageWidth();
        return w ? Math.floor(left / w) : 0;
    }

    // animate=false (default) suppresses the CSS transition for restore/reflow landings;
    // animate=true lets the container's own `transition: transform` play for a real page turn.
    function setTransform(pageIndex, animate) {
        var el = container();
        var value = 'translateX(-' + (pageIndex * pageWidth()) + 'px)';
        if (animate) {
            el.style.transform = value;
            return;
        }
        el.classList.add(NO_TRANSITION_CLASS);
        el.style.transform = value;
        void el.offsetHeight; // force layout before the transition is lifted next frame
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                el.classList.remove(NO_TRANSITION_CLASS);
            });
        });
    }

    function dispatchPageChange(pageIndex, pageCount) {
        try {
            window.dispatchEvent(new CustomEvent(PAGE_EVENT, {
                detail: { pageIndex: pageIndex, pageCount: pageCount },
            }));
        } catch (e) {}
    }

    function reportProgress(ratio) {
        try { Android.onScrollUpdate(ratio); } catch (e) {}
        try { Android.onScrollProgress(ratio); } catch (e) {}
    }

    function reportPageInfo(pageIndex, pageCount) {
        try { Android.onPageInfoChanged(pageIndex, pageCount); } catch (e) {}
    }

    // Mirrors scroll-tracking.js's publishProgress/dispatchProgress so a snippet listening for
    // __PROGRESS_EVENT__ / reading runtime.progress doesn't need a separate paged-mode code path.
    // ratio (0-1, same value persisted via reportProgress) stands in for both progress and
    // chapterProgress - paged mode never has more than one chapter in the DOM (see file header),
    // so there's no separate whole-document-vs-current-chapter distinction to make.
    function publishProgress(ratio, pageIndex, pageCount) {
        var chapterId = (T.currentChapter && T.currentChapter.id != null) ? T.currentChapter.id : null;
        var isLast = pageCount <= 1 || pageIndex === pageCount - 1;
        runtime.progress = ratio;
        runtime.chapterProgress = ratio;
        runtime.currentChapterId = chapterId;
        try {
            window.dispatchEvent(new CustomEvent(PROGRESS_EVENT, {
                detail: {
                    progress: ratio,
                    chapterProgress: ratio,
                    chapterId: chapterId,
                    isLast: isLast,
                },
            }));
        } catch (e) {}
    }

    function hideChapterTransition() {
        var el = document.getElementById(TRANSITION_ID);
        if (el) el.classList.remove(TRANSITION_VISIBLE_CLASS);
    }

    function landOnPage(idx, pc, animate) {
        idx = Math.max(0, Math.min(idx, pc - 1));
        p.pageCount = pc;
        p.pageIndex = idx;
        hideChapterTransition();
        setTransform(idx, !!animate);
        runtime.pageIndex = idx;
        runtime.pageCount = pc;
        dispatchPageChange(idx, pc);
        reportPageInfo(idx, pc);
        publishProgress(p.lastRatio, idx, pc);
    }

    // Recomputes page count/width against the current layout and lands on the page nearest
    // p.lastRatio (the authoritative position - see file header). `ratio`, when passed, ALSO
    // updates lastRatio first (an explicit restore/seek); omit it to just re-derive against a
    // possibly-changed pageCount (reflow) without changing the intended position.
    function repaginate(ratio) {
        if (!enabled) return;
        if (typeof ratio === 'number') p.lastRatio = ratio;
        applyColumnLayout();
        var pc = computePageCount();
        var idx = pc > 1 ? Math.round(p.lastRatio * pc) - 1 : 0;
        landOnPage(idx, pc, false);
    }

    // Pull-to-refresh-style edge badge (chevron icon, no chapter title text) rather than a
    // full-screen text overlay.
    var CHEVRON_SVG = '<svg viewBox="0 0 24 24" width="20" height="20">' +
        '<path d="M15 4l-8 8 8 8" fill="none" stroke="currentColor" stroke-width="2.5" ' +
        'stroke-linecap="round" stroke-linejoin="round"/></svg>';

    function chapterTransitionEl() {
        var el = document.getElementById(TRANSITION_ID);
        if (!el) {
            el = document.createElement('div');
            el.id = TRANSITION_ID;
            el.className = 'tsundoku-paged-chapter-transition';
            var badge = document.createElement('div');
            badge.className = 'tsundoku-paged-transition-badge';
            badge.innerHTML = CHEVRON_SVG;
            el.appendChild(badge);
            document.body.appendChild(el);
        }
        return el;
    }

    // side: 'start' (revealing the previous chapter, chevron points left) or 'end' (next
    // chapter, chevron flipped to point right).
    function setChapterTransitionSide(el, side) {
        el.setAttribute('data-side', side);
        el.firstElementChild.style.transform = side === 'end' ? 'scaleX(-1)' : '';
    }

    function showChapterTransition(side, thenCall) {
        var el = chapterTransitionEl();
        el.style.opacity = '';
        setChapterTransitionSide(el, side);
        void el.offsetHeight;
        el.classList.add(TRANSITION_VISIBLE_CLASS);
        setTimeout(thenCall, cfg.chapterTransitionHoldMs);
    }

    // Live drag-follow (finger tracking, not fling-triggered): Kotlin forwards raw touch deltas
    // as a fraction of the WebView's own width via __tdPagedDragTo/__tdPagedDragRelease, called
    // on every ACTION_MOVE / on ACTION_UP.
    var drag = null;

    // Diminishing-returns rubber band: x in [0, inf) -> [0, cfg.edgeMaxDampedFraction).
    function dampenEdgeDrag(x) {
        return cfg.edgeMaxDampedFraction * (1 - 1 / (1 + 3 * x));
    }

    window.__tdPagedDragTo = function (rawFraction) {
        if (!enabled || !p.restored) return;
        if (!drag) {
            drag = { baseIndex: p.pageIndex, pageCount: p.pageCount || computePageCount() };
        }
        var atStart = drag.baseIndex === 0;
        var atEnd = drag.baseIndex === drag.pageCount - 1;
        var fraction = rawFraction;
        var edgeProgress = 0;
        if (atStart && fraction > 0) {
            edgeProgress = dampenEdgeDrag(fraction);
            fraction = edgeProgress;
        } else if (atEnd && fraction < 0) {
            edgeProgress = dampenEdgeDrag(-fraction);
            fraction = -edgeProgress;
        } else {
            fraction = Math.max(-1, Math.min(1, fraction));
        }
        var w = pageWidth();
        var el = container();
        el.classList.add(NO_TRANSITION_CLASS);
        el.style.transform = 'translateX(-' + ((drag.baseIndex * w) - fraction * w) + 'px)';

        var t = chapterTransitionEl();
        if (edgeProgress > 0) {
            setChapterTransitionSide(t, fraction > 0 ? 'start' : 'end');
            t.style.transition = 'none';
            t.style.opacity = String(Math.min(1, edgeProgress / cfg.edgeCommitFraction));
            t.classList.add(TRANSITION_VISIBLE_CLASS);
        } else {
            t.classList.remove(TRANSITION_VISIBLE_CLASS);
        }
    };

    window.__tdPagedDragRelease = function (rawFraction) {
        if (!drag) return;
        var baseIndex = drag.baseIndex;
        var pc = drag.pageCount;
        var atStart = baseIndex === 0;
        var atEnd = baseIndex === pc - 1;
        drag = null;

        container().classList.remove(NO_TRANSITION_CLASS);
        var t = chapterTransitionEl();
        t.style.transition = '';
        t.style.opacity = '';

        if (atStart && rawFraction > cfg.edgeCommitFraction) { goToPage(-1); return; }
        if (atEnd && rawFraction < -cfg.edgeCommitFraction) { goToPage(pc); return; }

        t.classList.remove(TRANSITION_VISIBLE_CLASS);
        if (rawFraction < -cfg.dragCommitFraction) { goToPage(baseIndex + 1); return; }
        if (rawFraction > cfg.dragCommitFraction) { goToPage(baseIndex - 1); return; }
        landOnPage(baseIndex, pc, true);
    };

    // target < 0 or >= pageCount always does a full chapter switch (never an in-DOM append -
    // Kotlin's loadNextChapter() bridge method skips its infinite-scroll append branch while
    // paged mode is active), after a brief title-announce transition.
    //
    // edgeTransitionPending latches once a switch is triggered so a fast repeated trigger (held
    // key, rapid taps/swipes) can't stack multiple showChapterTransition timeouts each firing
    // their own Android.loadNextChapter()/requestPrevChapter() call. It only ever gets cleared by
    // a fresh document load (new chapter's script re-running from scratch), which is always the
    // eventual outcome of a committed edge crossing.
    var edgeTransitionPending = false;

    function goToPage(target) {
        var pc = p.pageCount || computePageCount();
        if (target < 0) {
            if (edgeTransitionPending) return;
            edgeTransitionPending = true;
            showChapterTransition('start', function () {
                try { Android.requestPrevChapter(); } catch (e) {}
            });
            return;
        }
        if (target >= pc) {
            if (edgeTransitionPending) return;
            edgeTransitionPending = true;
            showChapterTransition('end', function () {
                try { Android.loadNextChapter(); } catch (e) {}
            });
            return;
        }
        p.lastRatio = (target + 1) / pc;
        landOnPage(target, pc, true);
        reportProgress(p.lastRatio);
    }

    function nextPage() { goToPage((p.pageIndex || 0) + 1); }
    function prevPage() { goToPage((p.pageIndex || 0) - 1); }

    T.actions = T.actions || {};
    T.actions.nextPage = nextPage;
    T.actions.prevPage = prevPage;
    T.actions.goToPage = function (n) { goToPage(n); };
    // Merges into config.paged in place (direct property writes on config.paged work too - this
    // is just a one-call convenience for setting several at once). Takes effect on the next
    // swipe/edge-cross; no reload needed.
    T.actions.setPagedConfig = function (partial) {
        if (!partial) return;
        for (var k in partial) {
            if (Object.prototype.hasOwnProperty.call(partial, k)) cfg[k] = partial[k];
        }
    };

    // Edit mode has its own keyboard-clearance bottom padding on body that assumes normal
    // document flow (see NovelWebViewViewer's toggleEditMode script) - that has no room to work
    // within paged mode's fixed 100vh clip, so pagination is suspended (plain scrollable flow,
    // same as continuous mode) for the duration of editing rather than trying to reconcile both.
    function setSuspended(suspended) {
        if (!enabled) return;
        if (suspended) {
            document.documentElement.classList.remove(PAGED_CLASS);
            container().style.transform = '';
        } else {
            document.documentElement.classList.add(PAGED_CLASS);
            repaginate();
        }
    }

    // Called from Kotlin when an edge crossing found no adjacent chapter to switch to (already at
    // the first/last chapter) - releases the latch and hides the chevron since no document reload
    // is coming to clear them on its own.
    window.__tdPagedReleaseEdgeTransition = function () {
        edgeTransitionPending = false;
        hideChapterTransition();
    };

    runtime.pagingEnabled = enabled;
    window.__tdPagedSetSuspended = setSuspended;
    window.__tdPagedRepaginate = repaginate;
    // Called from Kotlin's TTS highlight-follow with the highlighted element: turns to the page
    // containing it (animated), clamped within the current pageCount so it can't itself trigger a
    // chapter switch (TTS's own chapter handoff in Kotlin already owns that).
    window.__tdPagedGoToElement = function (el) {
        if (!enabled || !p.restored) return;
        var idx = pageOfElement(el);
        if (idx === null) return;
        var pc = p.pageCount || computePageCount();
        idx = Math.max(0, Math.min(idx, pc - 1));
        if (idx === p.pageIndex) return;
        p.lastRatio = pc > 0 ? (idx + 1) / pc : 0;
        landOnPage(idx, pc, true);
    };
    // Called from Kotlin's restoreScrollPosition when paged mode is active, with the saved
    // percent (0-1) - waits for the container to have real width before landing on the target
    // page, then signals completion the same way the scroll-mode restore does.
    window.__tdPagedRestoreRatio = function (ratio, token) {
        function done() {
            if (window.Android && window.Android.onScrollRestoreComplete) {
                window.Android.onScrollRestoreComplete(token);
            }
        }
        function attempt() {
            if (!enabled) { done(); return true; }
            var w = pageWidth();
            if (w > 0 && container().scrollWidth > 0) {
                repaginate(ratio);
                p.restored = true;
                done();
                // One settle pass for layout that finishes shortly after restore (e.g. a native
                // status-bar height correcting from its initial estimate) - same ratio, just
                // re-measured against final geometry.
                setTimeout(function () { if (enabled) repaginate(); }, 400);
                return true;
            }
            return false;
        }
        if (attempt()) return;
        if (typeof ResizeObserver === 'function') {
            var ro = new ResizeObserver(function () {
                if (attempt()) ro.disconnect();
            });
            ro.observe(document.body);
        }
        requestAnimationFrame(function () { if (!attempt()) done(); });
    };

    if (!enabled) {
        return;
    }

    document.documentElement.classList.add(PAGED_CLASS);
    // Structure only - no repagination here. The initial page position is always driven by
    // Kotlin's restoreScrollPosition() calling __tdPagedRestoreRatio() right after this script
    // evaluates; self-repaginating here too would race that call and stomp the restored position.
    container();
    applyColumnLayout();

    if (typeof ResizeObserver === 'function') {
        var containerResizeObserver = new ResizeObserver(function () {
            if (!p.restored) return;
            repaginate();
        });
        containerResizeObserver.observe(container());
        p.resizeObserver = containerResizeObserver;
    }

    var resizeDebounce = null;
    window.addEventListener('resize', function () {
        if (!p.restored) return;
        clearTimeout(resizeDebounce);
        resizeDebounce = setTimeout(function () { repaginate(); }, 100);
    });
})();
