// Native JS dialogs (alert / confirm / prompt) wired to real reader actions.
// Requires Advanced settings: novelWebViewDevTools ON.
// Adds a floating toolbar whose buttons drive alert/confirm/prompt, verifying they render and
// resolve the pending JsResult (so the JS thread never hangs).
(function() {
    var BAR_ID = 'tsundoku-dialogs-bar';
    var existing = document.getElementById(BAR_ID);
    if (existing) existing.remove();

    function scrollToPercent(pct) {
        var doc = document.documentElement;
        var max = Math.max((doc.scrollHeight || 0) - (doc.clientHeight || window.innerHeight || 0), 0);
        window.scrollTo(0, Math.round(max * (pct / 100)));
    }

    // Read the app's own reading progress (boundary-aware, matches the slider) instead of
    // re-deriving it from scrollTop, which drifts under infinite scroll and wide-viewport modes.
    function currentProgressPercent() {
        var rt = (window.Tsundoku && Tsundoku.runtime) || {};
        var p = (typeof rt.chapterProgress === 'number') ? rt.chapterProgress : rt.progress;
        return Math.round((p || 0) * 100);
    }

    // alert(): read-only status, sourced entirely from the app-provided Tsundoku object.
    function doAlert() {
        var T = window.Tsundoku || {};
        var ch = T.currentChapter || {};
        var rt = T.runtime || {};
        var total = (T.chapters && T.chapters.length) || 0;
        alert(
            'Chapter: ' + (ch.title || '(unknown)') + '\n' +
            'Number: ' + (ch.number >= 0 ? ch.number : '?') + '\n' +
            'Loaded in page: ' + document.querySelectorAll('tsundoku-chapter').length + ' / ' + total + '\n' +
            'Progress: ' + currentProgressPercent() + '% (chapter) / ' +
                Math.round((rt.progress || 0) * 100) + '% (document)\n' +
            'TTS: ' + (rt.ttsState || 'stopped') + '\n' +
            'Loading chapter: ' + !!rt.loadingChapter + '\n' +
            'Menu visible: ' + !!rt.menuVisible
        );
    }

    // confirm(): yes/no gate before loading the next chapter. Gate on the runtime flags the app
    // provides so we don't fire a pointless append.
    function doConfirm() {
        var rt = (window.Tsundoku && Tsundoku.runtime) || {};
        if (!rt.isInfScroll) { alert('Infinite scroll is OFF — append does nothing here.'); return; }
        if (rt.noMoreChapters) { alert('Already at the last chapter (runtime.noMoreChapters=true).'); return; }
        if (confirm('Load the next chapter now (infinite scroll append)?')) {
            if (window.Android && typeof window.Android.loadNextChapter === 'function') {
                window.Android.loadNextChapter();
            } else {
                alert('Android.loadNextChapter bridge not present.');
            }
        }
    }

    // prompt(): text input to jump to a progress %.
    function doPrompt() {
        var raw = prompt('Jump to progress %', String(currentProgressPercent()));
        if (raw === null) return; // Cancel
        var pct = parseFloat(raw);
        if (isNaN(pct)) { alert('Not a number: ' + raw); return; }
        scrollToPercent(Math.max(0, Math.min(100, pct)));
    }

    var bar = document.createElement('div');
    bar.id = BAR_ID;
    bar.style.cssText = 'position:fixed;left:8px;z-index:100001;display:flex;gap:6px;' +
        'bottom:calc(8px + var(--tsundoku-safe-bottom, 0px));' +
        'background:rgba(0,0,0,0.8);padding:6px;border-radius:8px;font-family:monospace;';

    [['alert: status', doAlert],
     ['confirm: next', doConfirm],
     ['prompt: jump %', doPrompt]].forEach(function(pair) {
        var b = document.createElement('button');
        b.textContent = pair[0];
        b.style.cssText = 'padding:6px 10px;border:none;border-radius:5px;background:#0f0;color:#000;font-weight:bold;cursor:pointer;';
        b.onclick = pair[1];
        bar.appendChild(b);
    });

    var close = document.createElement('button');
    close.textContent = 'x';
    close.style.cssText = 'padding:6px 9px;border:none;border-radius:5px;background:#f00;color:#fff;font-weight:bold;cursor:pointer;';
    close.onclick = function() { bar.remove(); };
    bar.appendChild(close);

    document.body.appendChild(bar);
})();
