// Inject / refresh the inline `<style>` element in the WebView document.
//
// Replaces:
//   __STYLE_ID__  — DOM id of the <style> element to inject/replace
//   __CSS__       — JSON-quoted string (produced by quoteForJson) of the CSS body

(function () {
    var style = document.getElementById('__STYLE_ID__');
    if (!style) {
        style = document.createElement('style');
        style.id = '__STYLE_ID__';
        document.head.appendChild(style);
    }
    style.textContent = __CSS__;
})();
