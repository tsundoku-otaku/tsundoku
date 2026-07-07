// Page-side scroll listener for the novel WebView reader.
//
// Installed once per WebView load via NovelWebViewStyler.injectScrollTracking().
// Replaces the following tokens at install time (see NovelWebViewJsAssets):
//   __TSUNDOKU_OBJECT_NAME__     — `Tsundoku` (the global window key)
//   __CHAPTER_DIVIDER_CLASS__    — CSS class on per-chapter divider divs
//   __CHAPTER_ID_ATTR__          — HTML attribute holding the chapter id
//   __INFINITE_SCROLL_ENABLED__  — `true` / `false` JS literal
//   __LOAD_THRESHOLD__           — 0.0 - 1.0 JS literal
//
// Reports to the Android side via the `Android` JS interface:
//   Android.onChapterScrollUpdate(chapterIdx, progress)
//   Android.onScrollUpdate(progress)
//   Android.onScrollProgress(progress)
//   Android.loadNextChapter()

(function () {
    window.__TSUNDOKU_OBJECT_NAME__ = window.__TSUNDOKU_OBJECT_NAME__ || {};
    window.__TSUNDOKU_OBJECT_NAME__.runtime = window.__TSUNDOKU_OBJECT_NAME__.runtime || {};
    var runtime = window.__TSUNDOKU_OBJECT_NAME__.runtime;

    if (runtime.infiniteScrollInstalled) {
        return;
    }
    runtime.infiniteScrollInstalled = true;

    var lastProgress = 0;
    var saveTimeout = null;
    var lastChapterUpdate = 0;   // throttle gate for onChapterScrollUpdate
    var lastScrollUpdate = 0;    // throttle gate for onScrollUpdate
    runtime.loadingNext = runtime.loadingNext || false;
    runtime.setLoadingNext = function (v) { runtime.loadingNext = !!v; };
    var infiniteScrollEnabled = __INFINITE_SCROLL_ENABLED__;
    var loadThreshold = __LOAD_THRESHOLD__;

    window.chapterBoundaries = window.chapterBoundaries || [];
    runtime.lastBoundaryUpdate = runtime.lastBoundaryUpdate || 0;
    runtime.knownDividerCount = runtime.knownDividerCount || 0;
    runtime.knownDocHeight = runtime.knownDocHeight || 0;
    runtime.lastBoundaryCheck = runtime.lastBoundaryCheck || 0;

    window.addEventListener('scroll', function () {
        if (infiniteScrollEnabled && typeof window.updateChapterBoundaries === 'function') {
            // Re-query when the DOM changed: a new divider (count) OR a height drift from a
            // reflow (image/font load) that leaves the divider count intact but moves every
            // startOffset. Time-gate the check: reading scrollHeight forces layout, so probing it
            // every frame at 60 fps is wasteful. Appends re-query directly via rAF, so a lagged
            // opportunistic check here only delays reflow adjustments by at most one interval.
            var boundaryNow = Date.now();
            if (boundaryNow - runtime.lastBoundaryCheck > 120) {
                runtime.lastBoundaryCheck = boundaryNow;
                var domDividerCount = document.querySelectorAll('.__CHAPTER_DIVIDER_CLASS__').length;
                var domDocHeight = document.body ? document.body.scrollHeight : 0;
                if (domDividerCount !== runtime.knownDividerCount ||
                    Math.abs(domDocHeight - runtime.knownDocHeight) > 2) {
                    window.updateChapterBoundaries();
                }
            }
        }

        var scrollTop = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
        // Use the real content height and the VISUAL viewport (window.innerHeight).
        // documentElement.clientHeight is the layout viewport, which diverges from the
        // visual viewport under useWideViewPort/loadWithOverviewMode (and any scaling),
        // making scrollHeight - clientHeight larger than the reachable scroll range, so
        // the true bottom computed to ~0.92 and never hit 100%.
        var docHeight = Math.max(
            document.documentElement.scrollHeight,
            document.body ? document.body.scrollHeight : 0
        );
        var viewport = window.innerHeight || document.documentElement.clientHeight;
        var scrollable = docHeight - viewport;
        var progress = scrollable > 0 ? scrollTop / scrollable : 1;
        // Snap to 100% at the real bottom: sub-pixel rounding and fractional DPI leave a
        // few px of slack that scrollTop can never close.
        if (scrollable > 0 && scrollTop >= scrollable - 2) progress = 1.0;
        if (progress >= __DONE_THRESHOLD__) progress = 1.0;
        if (progress < 0) progress = 0;

        var currentChapterProgress = progress;
        var currentChapterIdx = 0;
        if (infiniteScrollEnabled && window.chapterBoundaries.length > 1) {
            // Resolve the visible chapter as the last one whose start is at or above scrollTop,
            // then compute progress within THAT chapter only. Always attributing to a chapter
            // (never the whole-document ratio) keeps the reported percent per-chapter even when
            // scrollTop sits past the last cached boundary after a reflow.
            var idx = 0;
            for (var i = 0; i < window.chapterBoundaries.length; i++) {
                if (scrollTop >= window.chapterBoundaries[i].startOffset) {
                    idx = i;
                } else {
                    break;
                }
            }
            var boundary = window.chapterBoundaries[idx];
            currentChapterIdx = idx;
            var chapterScrollY = Math.max(scrollTop - boundary.startOffset, 0);
            // A middle chapter is fully read once scrollTop reaches the next divider, so its
            // scrollable range is the full height. Only the last loaded chapter has an unreachable
            // trailing viewport (nothing below it), so subtract innerHeight there. Subtracting it
            // for every chapter reported 100% a full screen early and forced short (sub-viewport)
            // middle chapters to 100% the instant they were entered.
            var isLastChapter = idx === window.chapterBoundaries.length - 1;
            var effectiveHeight = Math.max(boundary.height - (isLastChapter ? window.innerHeight : 0), 1);
            currentChapterProgress = Math.min(chapterScrollY / effectiveHeight, 1.0);
            // Snap so a fully-read chapter reports 100% and gets marked read instead of ~0.98.
            if (currentChapterProgress >= __DONE_THRESHOLD__) currentChapterProgress = 1.0;

            // Throttle onChapterScrollUpdate to 50 ms — same cadence as onScrollUpdate.
            // Without the gate this fires at 60 fps and floods the Android JS bridge.
            var now = Date.now();
            if (now - lastChapterUpdate > 50) {
                lastChapterUpdate = now;
                Android.onChapterScrollUpdate(currentChapterIdx, currentChapterProgress);
            }
        }

        // Fire on any >=1% change, and also the moment we first reach 100% even if that final
        // step is under 1% — otherwise the dead-zone gate swallows the tail and 100% never saves.
        var reachedEnd = currentChapterProgress >= 1.0 && lastProgress < 1.0;
        if (Math.abs(currentChapterProgress - lastProgress) > 0.01 || reachedEnd) {
            lastProgress = currentChapterProgress;

            var now = Date.now();
            if (now - lastScrollUpdate > 50) {
                lastScrollUpdate = now;
                Android.onScrollUpdate(currentChapterProgress);
            }

            clearTimeout(saveTimeout);
            if (currentChapterProgress >= 1.0) {
                // Persist the finished state immediately rather than after the debounce.
                Android.onScrollProgress(currentChapterProgress);
            } else {
                saveTimeout = setTimeout(function () {
                    Android.onScrollProgress(currentChapterProgress);
                }, 500);
            }
        }

        var shouldLoadNext = false;
        if (infiniteScrollEnabled) {
            if (window.chapterBoundaries.length > 1) {
                shouldLoadNext = (currentChapterIdx === (window.chapterBoundaries.length - 1)) && (currentChapterProgress >= loadThreshold);
            } else {
                shouldLoadNext = progress >= loadThreshold;
            }
        }
        if (shouldLoadNext && !runtime.loadingNext) {
            runtime.loadingNext = true;
            try {
                Android.loadNextChapter();
            } catch (e) {
                console.error('Infinite scroll: Error calling loadNextChapter:', e);
                runtime.loadingNext = false;
            }
        }
    });

    // Use getBoundingClientRect() + scrollY rather than offsetTop so boundaries
    // are correct even when dividers sit inside a positioned container.
    window.addChapterBoundary = function (chapterId, startOffset, height) {
        window.chapterBoundaries.push({
            chapterId: chapterId,
            startOffset: startOffset,
            height: height
        });
    };

    window.updateChapterBoundaries = function () {
        var dividers = document.querySelectorAll('.__CHAPTER_DIVIDER_CLASS__');
        var scrollY = window.scrollY || window.pageYOffset || 0;
        var boundaries = [];
        dividers.forEach(function (divider, index) {
            var chapterId = divider.getAttribute('__CHAPTER_ID_ATTR__');
            var rect = divider.getBoundingClientRect();
            var startOffset = rect.top + scrollY;
            var nextDivider = dividers[index + 1];
            var endOffset = nextDivider
                ? nextDivider.getBoundingClientRect().top + scrollY
                : document.body.scrollHeight;
            boundaries.push({
                chapterId: chapterId,
                startOffset: startOffset,
                height: endOffset - startOffset
            });
        });
        window.chapterBoundaries = boundaries;
        runtime.knownDividerCount = dividers.length;
        runtime.knownDocHeight = document.body ? document.body.scrollHeight : 0;
        runtime.lastBoundaryUpdate = Date.now();
    };

    setTimeout(function () {
        if (typeof window.updateChapterBoundaries === 'function') {
            window.updateChapterBoundaries();
        }
    }, 0);
})();
