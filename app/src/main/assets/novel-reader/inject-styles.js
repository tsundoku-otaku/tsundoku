// Inject / refresh the inline `<style>` element in the WebView document.
//
// Replaces:
//   __STYLE_ID__  — DOM id of the <style> element to inject/replace
//   __CSS__       — JSON-quoted string (produced by quoteForJson) of the CSS body
//   __KEEP_POSITION__ — true to keep the element at the top of the viewport in place

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
        var probes = [1, window.innerHeight / 4, window.innerHeight / 2];
        for (var i = 0; i < probes.length && !anchor; i++) {
            var el = document.elementFromPoint(window.innerWidth / 2, probes[i]);
            if (el && el !== document.body && el !== document.documentElement) {
                anchor = el;
                anchorTop = el.getBoundingClientRect().top;
            }
        }
    }
    style.textContent = __CSS__;
    if (anchor) {
        // Reading the rect forces the new layout, so the shift is exact
        var shift = anchor.getBoundingClientRect().top - anchorTop;
        if (shift !== 0) window.scrollBy(0, shift);
    }
})();
