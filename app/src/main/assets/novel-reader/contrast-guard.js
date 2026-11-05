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

    var fg = parse(getComputedStyle(document.body).color);
    var bg = parse(getComputedStyle(document.body).backgroundColor);
    if (bg[3] === 0) {
        bg = parse(getComputedStyle(document.documentElement).backgroundColor);
        if (bg[3] === 0) bg = hex('__BG__');
    }
    if (contrast(fg, bg) >= 3) return;

    var style = document.createElement('style');
    style.id = '__GUARD_ID__';
    style.textContent = 'html, body { color: __TEXT__ !important; background-color: __BG__ !important; }';
    document.head.appendChild(style);
})();
