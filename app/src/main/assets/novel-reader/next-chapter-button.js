// Inject a "Next Chapter →" button at the bottom of the WebView content.
//
// Replaces:
//   __BTN_CONTAINER_ID__ — DOM id of the wrapping div

(function () {
    var existing = document.getElementById('__BTN_CONTAINER_ID__');
    if (existing) existing.remove();

    var container = document.createElement('div');
    container.id = '__BTN_CONTAINER_ID__';
    container.style.cssText = 'padding: 32px 16px; text-align: center;';

    var bg = getComputedStyle(document.body).backgroundColor || 'transparent';
    var fg = getComputedStyle(document.body).color || '#000000';

    var btn = document.createElement('button');
    btn.textContent = 'Next Chapter →';
    btn.style.cssText = 'width: 100%; padding: 12px 24px; font-size: 16px; ' +
        'background-color: ' + bg + '; color: ' + fg + '; ' +
        'border: 2px solid ' + fg + '; border-radius: 8px; ' +
        'cursor: pointer; text-transform: none; opacity: 0.8;';
    btn.onclick = function () {
        Android.loadNextChapter();
    };
    container.appendChild(btn);
    document.body.appendChild(container);
})();
