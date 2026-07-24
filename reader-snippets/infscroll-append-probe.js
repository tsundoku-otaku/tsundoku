// Infinite-scroll append probe: verifies the append dedup fix.
// Fires loadNextChapter() several times in quick succession (the pathological case), then reports
// whether any chapter id appears more than once in the DOM. Requires infinite scroll ON.
(function() {
    function nodeIds() {
        return Array.prototype.slice.call(document.querySelectorAll('tsundoku-chapter'))
            .map(function(n) { return n.getAttribute('data-chapter-id'); })
            .filter(Boolean);
    }

    function duplicates(ids) {
        var seen = {}, dups = [];
        ids.forEach(function(id) {
            seen[id] = (seen[id] || 0) + 1;
            if (seen[id] === 2) dups.push(id);
        });
        return dups;
    }

    var before = nodeIds();
    var runtime = (window.Tsundoku && Tsundoku.runtime) || {};
    if (!runtime.isInfScroll) {
        alert('Infinite scroll is OFF (Tsundoku.runtime.isInfScroll=false) — nothing to probe.');
        return;
    }
    if (!(window.Android && typeof window.Android.loadNextChapter === 'function')) {
        alert('Android.loadNextChapter bridge not present.');
        return;
    }

    // Hammer the append trigger; the native side must coalesce these into at most one real append.
    for (var i = 0; i < 5; i++) window.Android.loadNextChapter();

    // Give the append coroutine time to run, then compare.
    setTimeout(function() {
        var after = nodeIds();
        var dups = duplicates(after);
        alert(
            'Append probe\n' +
            'nodes before: ' + before.length + '\n' +
            'nodes after: ' + after.length + '\n' +
            'net added: ' + (after.length - before.length) + ' (expect 0 or 1, never per-call)\n' +
            'duplicate ids: ' + (dups.length ? dups.join(', ') : 'none (PASS)')
        );
    }, 1500);
})();
