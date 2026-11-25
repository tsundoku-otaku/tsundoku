// With source CSS priority on, the site's colors win over the reader theme. If the result is
// unreadable (e.g. dark site text on a dark reader theme), fall back to the theme colors.
//
// Replaces:
//   __GUARD_ID__ - DOM id of the <style> element holding the fallback
//   __BG__       - reader background hex
//   __TEXT__     - reader text hex

(function () {
    if (!document.body) return;
    var old = document.getElementById('__GUARD_ID__');
    if (old) old.remove();

    function parse(c) {
        var m = c.match(/[\d.]+/g);
        return m ? m.map(Number) : [0, 0, 0, 1];
    }
    function lum(c) {
        var ch = [c[0], c[1], c[2]].map(function (v) {
            v /= 255;
            return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
    }
    function contrast(a, b) {
        var la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
    function hex(h) {
        return [parseInt(h.substr(1, 2), 16), parseInt(h.substr(3, 2), 16), parseInt(h.substr(5, 2), 16), 1];
    }

    // A partly transparent text color shows mixed with what is behind it.
    function blend(fg, bg) {
        var a = fg.length < 4 ? 1 : fg[3];
        if (a >= 1) return fg;
        return [0, 1, 2].map(function (k) { return fg[k] * a + bg[k] * (1 - a); }).concat([1]);
    }

    // The background text is actually drawn on: a site's own box counts, not just the page. A partly
    // transparent box is blended over what is behind it, ending on the reader background.
    function effectiveBackground(el) {
        var layers = [];
        for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
            var c = parse(getComputedStyle(n).backgroundColor);
            var a = c.length < 4 ? 1 : c[3];
            if (a <= 0) continue;
            layers.push([c[0], c[1], c[2], a]);
            if (a >= 1) break;
        }
        var out = hex('__BG__');
        for (var i = layers.length - 1; i >= 0; i--) {
            var l = layers[i];
            out = [0, 1, 2].map(function (k) { return l[k] * l[3] + out[k] * (1 - l[3]); }).concat([1]);
        }
        return out;
    }

    // Only text that is unreadable where it is drawn gets the fallback, each on its own, so a readable
    // block (light text on a site's dark box) keeps its colors next to an unreadable one.
    function readableColor(bg) {
        var text = hex('__TEXT__');
        if (contrast(text, bg) >= 3) return '__TEXT__';
        return contrast([0, 0, 0, 1], bg) >= contrast([255, 255, 255, 1], bg) ? '#000000' : '#ffffff';
    }
    function hasOwnText(el) {
        for (var c = el.firstChild; c; c = c.nextSibling) {
            if (c.nodeType === 3 && c.nodeValue.trim()) return true;
        }
        return false;
    }
    // Every run starts from the site's own colors: an inserted chapter can change the background of
    // text that was checked before, so everything is rechecked.
    Array.prototype.forEach.call(document.querySelectorAll('[data-tsundoku-contrast]'), function (el) {
        var saved = el.getAttribute('data-tsundoku-contrast');
        if (saved) el.style.setProperty('color', saved, 'important');
        el.removeAttribute('data-tsundoku-contrast');
        el.style.removeProperty('--td-contrast-color');
    });
    // A stylesheet rule rather than an inline color, so the TTS highlight can still set its own. It is in
    // place before the scan, so text inside a corrected block is checked against the corrected color. The
    // :not(#id) parts only raise specificity above the site's own !important rules.
    var style = document.createElement('style');
    style.id = '__GUARD_ID__';
    style.textContent =
        '[data-tsundoku-contrast]:not(#td-c):not(#td-c):not(#td-c):not(.td-tts-highlight-bg, .td-tts-highlight-bg *){color:var(--td-contrast-color)!important}';
    document.head.appendChild(style);
    var root = document.querySelector('tsundoku-chapter') ? 'tsundoku-chapter' : 'body';
    var fixed = 0;
    Array.prototype.forEach.call(document.querySelectorAll(root + ', ' + root + ' *'), function (el) {
        if (!hasOwnText(el)) return;
        // Spoilers hide text by matching its color to the background on purpose.
        if (el.closest('[class*="spoiler" i]')) return;
        var bg = effectiveBackground(el);
        if (contrast(blend(parse(getComputedStyle(el).color), bg), bg) >= 3) return;
        // An inline !important color would beat any stylesheet rule, so it is set aside until the next run.
        var inline = el.style.getPropertyPriority('color') === 'important' ? el.style.getPropertyValue('color') : '';
        if (inline) el.style.removeProperty('color');
        el.setAttribute('data-tsundoku-contrast', inline);
        el.style.setProperty('--td-contrast-color', readableColor(bg));
        fixed++;
    });
    if (!fixed) style.remove();
})();
