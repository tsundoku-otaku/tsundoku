// With source CSS priority on, the site's colors win over the reader theme. If the result is
// unreadable (e.g. dark site text on a dark reader theme), fall back to the theme colors.
//
// Replaces:
//   __GUARD_ID__ - DOM id of the <style> element holding the fallback
//   __BG__       - reader background hex
//   __TEXT__     - reader text hex

(function () {
    var old = document.getElementById('__GUARD_ID__');
    if (old) old.remove();
    if (!document.body) return;

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

    // Paragraphs spread through the chapter; one unreadable block early on shouldn't decide alone,
    // and readable text up top shouldn't hide unreadable text further down.
    var paragraphs = Array.prototype.filter.call(
        document.querySelectorAll('tsundoku-chapter p'),
        function (p) { return p.textContent.trim(); }
    );
    var samples = [];
    var step = Math.max(1, Math.floor(paragraphs.length / 10));
    for (var i = 0; i < paragraphs.length && samples.length < 10; i += step) samples.push(paragraphs[i]);
    if (!samples.length) samples.push(document.body);
    var unreadable = samples.filter(function (el) {
        return contrast(parse(getComputedStyle(el).color), effectiveBackground(el)) < 3;
    }).length;
    if (unreadable * 2 <= samples.length) return;

    var style = document.createElement('style');
    style.id = '__GUARD_ID__';
    style.textContent = 'html, body { color: __TEXT__ !important; background-color: __BG__ !important; } ' +
        'tsundoku-chapter * { color: inherit !important; }';
    document.head.appendChild(style);
})();
