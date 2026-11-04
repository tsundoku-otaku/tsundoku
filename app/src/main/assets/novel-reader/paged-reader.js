// Paged-mode engine for the novel WebView reader (webview only). Installed via
// NovelWebViewStyler.injectPagedReader().
//
// CSS multi-column on #__CHAPTERS_CONTAINER_ID__; page turns move it via
// transform: translateX (not scrollLeft), so a page flip never fires a scroll event.
// Progress persists through the same Android bridge calls scroll-tracking.js uses
// (onScrollUpdate/onScrollProgress).
//
// p.lastRatio is the authoritative reading position; pageIndex is always re-derived
// from it against the current pageCount, never the reverse - an early pageCount
// measurement (fonts still loading) can be wrong, and deriving from a stale pageIndex
// would compound that error.
//
// Crossing a page edge always does a full chapter switch (no in-DOM append), with
// Kotlin prefetching the next/prev chapter near an edge.
//
// JS API: actions.nextPage()/prevPage()/goToPage(n)/setPagedConfig({...});
// runtime.pagingEnabled/pageIndex/pageCount/progress/chapterProgress/currentChapterId
// (same fields scroll-tracking.js publishes); config.paged.{dragCommitFraction,
// edgeCommitFraction, edgeMaxDampedFraction, chapterTransitionHoldMs} - live-read,
// snippet-overridable; window events __PAGE_EVENT__ { pageIndex, pageCount } and
// __PROGRESS_EVENT__ (same shape as scroll-tracking.js).

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
        dragCommitFraction: __PAGED_DRAG_COMMIT_FRACTION__,
        // Fraction of the WebView's width a drag past the first/last page must cross to commit a
        // chapter switch. Defaults to the same value as dragCommitFraction (the "Page-turn swipe
        // distance" pref drives both), but stays a separate key so a snippet can diverge them.
        edgeCommitFraction: __PAGED_EDGE_COMMIT_FRACTION__,
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
        // A fractional body box wider than the whole-px column lets the next column's first pixel
        // show at the clip edge, so pin the box to the column width.
        document.body.style.boxSizing = 'border-box';
        document.body.style.width = '';
        document.body.style.width = Math.floor(document.body.getBoundingClientRect().width) + 'px';
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
        // Explicit floored px height, not `calc(100vh - Npx)`: fractional rounding there let the
        // last line's descenders clip past the overflow:hidden edge. 1px safety buffer for the rest.
        var innerH = window.innerHeight || document.documentElement.clientHeight;
        var available = innerH - vMargin - 1;
        // Snap to whole lines so the column break never lands mid-line. Read line-height from the
        // content, not body (source CSS may override it).
        var lineHeight = parseFloat(contentLineHeight());
        if (lineHeight > 0) {
            var lines = Math.floor(available / lineHeight);
            if (lines > 0) available = Math.ceil(lines * lineHeight);
        }
        document.body.style.height = Math.floor(available) + 'px';
    }

    function contentLineHeight() {
        var el = container();
        var sample = el.querySelector('p, div, li, blockquote') || el;
        return window.getComputedStyle(sample).lineHeight;
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
        reportProgress(p.lastRatio);
        publishProgress(p.lastRatio, idx, pc);
    }

    // Recomputes page count/width against the current layout and lands on the page nearest
    // p.lastRatio (the authoritative position - see file header). `ratio`, when passed, ALSO
    // updates lastRatio first (an explicit restore/seek); omit it to just re-derive against a
    // possibly-changed pageCount (reflow) without changing the intended position.
    // Deferred when a touch drag is in progress (see below) so a resize/ResizeObserver firing
    // mid-swipe doesn't null out `drag` and snap the page under the user's finger; re-run once
    // the drag ends.
    var pendingRepaginateRatio;
    var hasPendingRepaginate = false;

    function repaginate(ratio) {
        if (!enabled) return;
        if (drag) {
            hasPendingRepaginate = true;
            pendingRepaginateRatio = ratio;
            return;
        }
        if (typeof ratio === 'number') p.lastRatio = ratio;
        applyColumnLayout();
        var pc = computePageCount();
        var idx = pc > 1 ? Math.round(p.lastRatio * pc) - 1 : 0;
        landOnPage(idx, pc, false);
        drag = null;
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
            // pageWidth()/container() force a layout read - cache both for the gesture's
            // duration instead of on every ACTION_MOVE (width doesn't change mid-touch).
            drag = {
                baseIndex: p.pageIndex,
                pageCount: p.pageCount || computePageCount(),
                width: pageWidth(),
                el: container(),
            };
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
        var w = drag.width;
        var el = drag.el;
        el.classList.add(NO_TRANSITION_CLASS);
        el.style.transform = 'translateX(' + (fraction * w - drag.baseIndex * w) + 'px)';

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

        function runPendingRepaginate() {
            if (!hasPendingRepaginate) return;
            hasPendingRepaginate = false;
            repaginate(pendingRepaginateRatio);
            pendingRepaginateRatio = undefined;
        }

        container().classList.remove(NO_TRANSITION_CLASS);
        var t = chapterTransitionEl();
        t.style.transition = '';
        t.style.opacity = '';

        if (atStart && rawFraction > cfg.edgeCommitFraction) { goToPage(-1); runPendingRepaginate(); return; }
        if (atEnd && rawFraction < -cfg.edgeCommitFraction) { goToPage(pc); runPendingRepaginate(); return; }

        t.classList.remove(TRANSITION_VISIBLE_CLASS);
        if (!atEnd && rawFraction < -cfg.dragCommitFraction) { goToPage(baseIndex + 1); runPendingRepaginate(); return; }
        if (!atStart && rawFraction > cfg.dragCommitFraction) { goToPage(baseIndex - 1); runPendingRepaginate(); return; }
        landOnPage(baseIndex, pc, true);
        runPendingRepaginate();
    };

    // target < 0 or >= pageCount always does a full chapter switch, after a brief
    // title-announce transition. edgeTransitionPending latches so a rapid repeated
    // trigger can't stack multiple switch calls; cleared only by the next document load.
    var edgeTransitionPending = false;

    function goToPage(target) {
        if (!p.restored) return;
        var pc = p.pageCount || computePageCount();
        if (target < 0) {
            if (edgeTransitionPending) return;
            edgeTransitionPending = true;
            showChapterTransition('start', function () {
                try { Android.requestPrevChapter(); } catch (e) { edgeTransitionPending = false; hideChapterTransition(); }
            });
            return;
        }
        if (target >= pc) {
            if (edgeTransitionPending) return;
            edgeTransitionPending = true;
            showChapterTransition('end', function () {
                try { Android.loadNextChapter(); } catch (e) { edgeTransitionPending = false; hideChapterTransition(); }
            });
            return;
        }
        p.lastRatio = (target + 1) / pc;
        landOnPage(target, pc, true);
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

    function isSuspended() {
        return !document.documentElement.classList.contains(PAGED_CLASS);
    }

    // Edit mode has its own keyboard-clearance bottom padding on body that assumes normal
    // document flow (see NovelWebViewViewer's toggleEditMode script) - that has no room to work
    // within paged mode's fixed 100vh clip, so pagination is suspended (plain scrollable flow,
    // same as continuous mode) for the duration of editing rather than trying to reconcile both.
    function setSuspended(suspended) {
        if (!enabled) return;
        if (suspended) {
            document.documentElement.classList.remove(PAGED_CLASS);
            container().style.transform = '';
            document.body.style.width = '';
            document.body.style.height = '';
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
        landOnPage(p.pageIndex, p.pageCount, true);
    };

    runtime.pagingEnabled = enabled;
    window.__tdPagedSetSuspended = setSuspended;
    // Guarded like goToPage/dragTo/goToElement - a seek before restore lands would get clobbered.
    window.__tdPagedRepaginate = function (ratio) {
        if (!p.restored) return;
        repaginate(ratio);
    };
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
        var settled = false;
        var ro = null;
        function done() {
            if (window.Android && window.Android.onScrollRestoreComplete) {
                window.Android.onScrollRestoreComplete(token);
            }
        }
        function disconnectRo() {
            if (ro) { ro.disconnect(); ro = null; }
        }
        function attempt() {
            if (settled) return true;
            if (!enabled) { settled = true; disconnectRo(); done(); return true; }
            var w = pageWidth();
            if (w > 0 && container().scrollWidth > 0) {
                settled = true;
                disconnectRo();
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
            ro = new ResizeObserver(function () {
                if (attempt()) disconnectRo();
            });
            ro.observe(document.body);
        }
        // Bounded rAF retry: force-settle at the deadline rather than leaving p.restored false forever.
        var rafRetries = 0;
        var MAX_RAF_RETRIES = 30;
        (function rafLoop() {
            if (attempt()) return;
            rafRetries++;
            if (rafRetries < MAX_RAF_RETRIES) {
                requestAnimationFrame(rafLoop);
                return;
            }
            settled = true;
            disconnectRo();
            repaginate(ratio);
            p.restored = true;
            done();
        })();
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
            if (!p.restored || isSuspended()) return;
            repaginate();
        });
        containerResizeObserver.observe(container());
        p.resizeObserver = containerResizeObserver;
    }

    var resizeDebounce = null;
    window.addEventListener('resize', function () {
        if (!p.restored || isSuspended()) return;
        clearTimeout(resizeDebounce);
        resizeDebounce = setTimeout(function () { repaginate(); }, 100);
    });

    // Added content (snippets, late images, fonts) overflows into more columns without resizing the
    // container, so the ResizeObserver above never sees it. Event-driven, debounced like resize;
    // attribute changes (TTS highlight, this script's transforms) are deliberately not observed.
    // Content can grow before or after the reader (late images, snippets), so re-derive the page
    // from the text that was at the top of the screen; the ratio alone would jump to other text.
    // A font swap reflows everything, where the ratio is the better guess.
    function textAtPageStart() {
        if (!document.caretRangeFromPoint) return null;
        var el = container();
        var x = Math.floor(window.innerWidth / 2);
        var h = window.innerHeight || document.documentElement.clientHeight;
        for (var y = 8; y < h; y += 24) {
            var range = document.caretRangeFromPoint(x, y);
            if (range && el.contains(range.startContainer)) return range;
        }
        return null;
    }

    function offsetInContainer(range) {
        var rect = range.getClientRects()[0];
        if (!rect) {
            var node = range.startContainer;
            if (node.nodeType !== 1) node = node.parentElement;
            else if (node.childNodes[range.startOffset]) node = node.childNodes[range.startOffset];
            if (!node || node.nodeType !== 1) return null;
            rect = node.getBoundingClientRect();
        }
        return rect.left - container().getBoundingClientRect().left;
    }

    function reflowKeepingText() {
        var anchor = textAtPageStart();
        applyColumnLayout();
        var pc = computePageCount();
        var idx = p.pageIndex || 0;
        var offset = anchor ? offsetInContainer(anchor) : null;
        if (offset !== null) idx = Math.floor((offset + 1) / pageWidth());
        idx = Math.max(0, Math.min(idx, pc - 1));
        p.lastRatio = pc > 0 ? (idx + 1) / pc : 0;
        landOnPage(idx, pc, false);
    }

    var reflowDebounce = null;
    var reflowKeepsText = true;
    function scheduleReflowCheck(keepText) {
        if (!p.restored) return;
        reflowKeepsText = reflowKeepsText && keepText;
        clearTimeout(reflowDebounce);
        reflowDebounce = setTimeout(function run() {
            if (!enabled || isSuspended()) { reflowKeepsText = true; return; }
            // Mid-swipe the deferred repaginate would land by ratio, so wait for the release
            if (drag) { reflowDebounce = setTimeout(run, 150); return; }
            var keep = reflowKeepsText;
            reflowKeepsText = true;
            if (computePageCount() === p.pageCount) return;
            if (keep) reflowKeepingText(); else repaginate();
        }, 100);
    }
    function onContentReflow() { scheduleReflowCheck(true); }
    if (typeof MutationObserver === 'function') {
        new MutationObserver(onContentReflow)
            .observe(container(), { childList: true, characterData: true, subtree: true });
    }
    container().addEventListener('load', onContentReflow, true);
    container().addEventListener('error', onContentReflow, true);
    if (document.fonts && document.fonts.addEventListener) {
        document.fonts.addEventListener('loadingdone', function () { scheduleReflowCheck(false); });
    }
})();
