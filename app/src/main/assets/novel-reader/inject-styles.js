// Inject / refresh the inline `<style>` element in the WebView document.
//
// Replaces:
//   __STYLE_ID__  — DOM id of the <style> element to inject/replace
//   __CSS__       — JSON-quoted string (produced by quoteForJson) of the CSS body
//   __KEEP_POSITION__ — true to keep the element at the top of the viewport in place
//   __CONTAINER_ID__, __CHAPTER_TAG__, __DIVIDER_CLASS__ — reader wrappers that can't anchor the position

(function () {
    var style = document.getElementById('__STYLE_ID__');
    if (!style) {
        style = document.createElement('style');
        style.id = '__STYLE_ID__';
        document.head.appendChild(style);
    }
    var anchor = null;
    var anchorTop = 0;
    if (__KEEP_POSITION__) {
        // Wrappers and dividers don't move with the text when the dividers change size.
        function movesWithText(el) {
            return el && el !== document.body && el !== document.documentElement &&
                el.id !== '__CONTAINER_ID__' && el.tagName.toLowerCase() !== '__CHAPTER_TAG__' &&
                !el.classList.contains('__DIVIDER_CLASS__');
        }
        var x = window.innerWidth / 2;
        var probes = [1, window.innerHeight / 4, window.innerHeight / 2];
        for (var i = 0; i < probes.length && !anchor; i++) {
            var el = document.elementFromPoint(x, probes[i]);
            if (movesWithText(el)) {
                anchor = el;
            } else if (document.caretRangeFromPoint) {
                var range = document.caretRangeFromPoint(x, probes[i]);
                if (range && range.startContainer.nodeType === 3 && range.getClientRects().length) anchor = range;
            }
            if (anchor) anchorTop = anchor.getBoundingClientRect().top;
        }
    }
    style.textContent = __CSS__;
    if (anchor) {
        // Reading the rect forces the new layout, so the shift is exact
        var shift = anchor.getBoundingClientRect().top - anchorTop;
        if (shift !== 0) window.scrollBy(0, shift);
        // Chapter tracking reads the boundaries, which moved with the dividers.
        if (typeof window.updateChapterBoundaries === 'function') window.updateChapterBoundaries();
    }
})();
