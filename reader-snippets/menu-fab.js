// Floating action buttons that slide in/out with the reader menu.
// Uses the menu-visibility signals the WebView exposes:
//   Tsundoku.runtime.menuVisible                 - boolean
//   window 'tsundoku:menuvisibilitychange'       - CustomEvent, detail.visible / detail.menuVisible
//   --tsundoku-safe-top / --tsundoku-safe-bottom - reader menu bar heights (+ system bars) in px
// The column is bounded by both safe-area vars, so it sits in the free band between the top app bar
// and the bottom bar.
(function() {
    var WRAP_ID = 'tsundoku-menu-fab';
    var old = document.getElementById(WRAP_ID);
    if (old) old.remove();

    var wrap = document.createElement('div');
    wrap.id = WRAP_ID;
    wrap.style.cssText = 'position:fixed;right:12px;z-index:100001;display:flex;flex-direction:column;gap:10px;' +
        'justify-content:flex-end;align-items:flex-end;' +
        'top:calc(12px + var(--tsundoku-safe-top, 0px));' +
        'bottom:calc(16px + var(--tsundoku-safe-bottom, 0px));' +
        'pointer-events:none;' +
        'transition:opacity .2s, transform .2s;font-family:monospace;';

    function fab(label, onClick) {
        var b = document.createElement('button');
        b.textContent = label;
        b.style.cssText = 'width:48px;height:48px;border:none;border-radius:50%;background:#0f0;color:#000;' +
            'font-size:18px;font-weight:bold;cursor:pointer;box-shadow:0 2px 6px rgba(0,0,0,0.4);' +
            'pointer-events:auto;';
        b.onclick = onClick;
        return b;
    }

    wrap.appendChild(fab('↑', function() { window.scrollTo({ top: 0, behavior: 'smooth' }); }));
    wrap.appendChild(fab('↓', function() {
        window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' });
    }));
    wrap.appendChild(fab('↻', function() {
        // Trigger a next-chapter append, but only when the app-provided runtime flags say it's
        // worthwhile (infinite scroll on, not already at the last chapter).
        var rt = (window.Tsundoku && Tsundoku.runtime) || {};
        if (!rt.isInfScroll || rt.noMoreChapters) return;
        if (window.Android && typeof window.Android.loadNextChapter === 'function') {
            window.Android.loadNextChapter();
        }
    }));

    document.body.appendChild(wrap);

    function render(visible) {
        wrap.style.opacity = visible ? '1' : '0';
        wrap.style.transform = visible ? 'translateY(0)' : 'translateY(12px)';
        // Hide (not just fade) so an invisible FAB can't be tapped; wrap stays pointer-events:none
        // so the empty band never eats taps.
        wrap.style.visibility = visible ? 'visible' : 'hidden';
    }

    // Initial state from the runtime flag, then follow the event.
    render(!!(window.Tsundoku && Tsundoku.runtime && Tsundoku.runtime.menuVisible));
    // reader-chrome.js emits detail.visible; the reader-event system emits detail.menuVisible.
    // Both fire under the same name, so read either and fall back to the runtime flag.
    window.addEventListener('tsundoku:menuvisibilitychange', function(e) {
        var d = e.detail || {};
        var visible = d.visible;
        if (visible === undefined) visible = d.menuVisible;
        if (visible === undefined) visible = !!(window.Tsundoku && Tsundoku.runtime && Tsundoku.runtime.menuVisible);
        render(!!visible);
    });
})();
