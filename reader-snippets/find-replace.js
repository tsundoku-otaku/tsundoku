// In-chapter Find & Replace with a bottom overlay bar (Next / Prev match navigation).
// The bar is fixed to the bottom and clears the reader menu via --tsundoku-safe-bottom, so it never
// hides under the bottom app bar. Matching is literal + case-insensitive, scoped to chapter content.
//
// Find highlights every match and steps through them with Next / Prev; Replace swaps the current
// match, Replace all swaps them all. Edits are in-page only (lost on reload), like any DOM snippet.
(function () {
    var BAR_ID = 'tsundoku-fr-bar';
    var HIT_CLASS = 'tsundoku-fr-hit';
    var CURRENT_CLASS = 'tsundoku-fr-current';

    var old = document.getElementById(BAR_ID);
    if (old) old.remove();

    var hits = [];
    var index = -1;

    function chapterRoots() {
        var roots = document.querySelectorAll('tsundoku-chapter');
        return roots.length ? Array.prototype.slice.call(roots) : [document.body];
    }

    // Unwrap previous <mark> hits back into plain text so a new search starts clean.
    function clearHits() {
        var marks = document.querySelectorAll('.' + HIT_CLASS);
        for (var i = 0; i < marks.length; i++) {
            var m = marks[i];
            var parent = m.parentNode;
            if (!parent) continue;
            parent.replaceChild(document.createTextNode(m.textContent), m);
            parent.normalize();
        }
        hits = [];
        index = -1;
    }

    function textNodesIn(root) {
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: function (node) {
                if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                var p = node.parentNode;
                // Skip our own UI and non-content elements.
                while (p && p !== root) {
                    var tag = p.nodeName;
                    if (p.id === BAR_ID || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'MARK') {
                        return NodeFilter.FILTER_REJECT;
                    }
                    p = p.parentNode;
                }
                return NodeFilter.FILTER_ACCEPT;
            },
        });
        var nodes = [];
        var n;
        while ((n = walker.nextNode())) nodes.push(n);
        return nodes;
    }

    function highlightInNode(node, term) {
        var text = node.nodeValue;
        var lower = text.toLowerCase();
        var needle = term.toLowerCase();
        var from = 0, at;
        var frag = null, last = 0;
        while ((at = lower.indexOf(needle, from)) !== -1) {
            if (!frag) frag = document.createDocumentFragment();
            if (at > last) frag.appendChild(document.createTextNode(text.slice(last, at)));
            var mark = document.createElement('mark');
            mark.className = HIT_CLASS;
            mark.textContent = text.slice(at, at + term.length);
            frag.appendChild(mark);
            hits.push(mark);
            last = at + term.length;
            from = last;
        }
        if (frag) {
            if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
            node.parentNode.replaceChild(frag, node);
        }
    }

    function doFind(term) {
        clearHits();
        if (!term) { updateCount(); return; }
        chapterRoots().forEach(function (root) {
            textNodesIn(root).forEach(function (node) { highlightInNode(node, term); });
        });
        if (hits.length) focus(0);
        updateCount();
    }

    function focus(i) {
        if (!hits.length) return;
        if (index >= 0 && hits[index]) hits[index].classList.remove(CURRENT_CLASS);
        index = (i + hits.length) % hits.length;
        var el = hits[index];
        el.classList.add(CURRENT_CLASS);
        el.scrollIntoView({ block: 'center', behavior: 'smooth' });
        updateCount();
    }

    function replaceCurrent(rep) {
        if (index < 0 || !hits[index]) return;
        var mark = hits[index];
        var parent = mark.parentNode;
        parent.replaceChild(document.createTextNode(rep), mark);
        parent.normalize();
        hits.splice(index, 1);
        if (hits.length) focus(index); else { index = -1; updateCount(); }
    }

    function replaceAll(rep) {
        for (var i = 0; i < hits.length; i++) {
            var mark = hits[i];
            var parent = mark.parentNode;
            if (!parent) continue;
            parent.replaceChild(document.createTextNode(rep), mark);
            parent.normalize();
        }
        hits = [];
        index = -1;
        updateCount();
    }

    // UI
    var bar = document.createElement('div');
    bar.id = BAR_ID;
    bar.style.cssText = 'position:fixed;left:8px;right:8px;z-index:100001;display:flex;flex-wrap:wrap;' +
        'gap:6px;align-items:center;' +
        'bottom:calc(8px + var(--tsundoku-safe-bottom, 0px));' +
        'background:rgba(0,0,0,0.85);padding:8px;border-radius:8px;font-family:monospace;';

    function field(placeholder) {
        var i = document.createElement('input');
        i.type = 'text';
        i.placeholder = placeholder;
        i.style.cssText = 'flex:1 1 120px;min-width:90px;padding:6px 8px;border:none;border-radius:5px;' +
            'font-family:monospace;font-size:14px;';
        return i;
    }

    function button(label, bg, onClick) {
        var b = document.createElement('button');
        b.textContent = label;
        b.style.cssText = 'padding:6px 10px;border:none;border-radius:5px;background:' + bg + ';' +
            'color:#000;font-weight:bold;cursor:pointer;';
        b.onclick = onClick;
        return b;
    }

    var findInput = field('Find');
    var replaceInput = field('Replace with');
    var count = document.createElement('span');
    count.style.cssText = 'color:#0f0;font-size:13px;min-width:56px;text-align:center;';

    function updateCount() {
        count.textContent = hits.length ? ((index + 1) + '/' + hits.length) : '0/0';
    }

    findInput.addEventListener('input', function () { doFind(findInput.value); });
    findInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); focus(index + 1); }
    });

    bar.appendChild(findInput);
    bar.appendChild(button('◀', '#0f0', function () { focus(index - 1); }));
    bar.appendChild(count);
    bar.appendChild(button('▶', '#0f0', function () { focus(index + 1); }));
    bar.appendChild(replaceInput);
    bar.appendChild(button('Replace', '#ffb300', function () { replaceCurrent(replaceInput.value); }));
    bar.appendChild(button('All', '#ffb300', function () { replaceAll(replaceInput.value); }));
    bar.appendChild(button('x', '#f00', function () { clearHits(); bar.remove(); }));

    // Highlight style, injected once.
    if (!document.getElementById('tsundoku-fr-style')) {
        var style = document.createElement('style');
        style.id = 'tsundoku-fr-style';
        style.textContent =
            '.' + HIT_CLASS + '{background:#ff0;color:#000;}' +
            '.' + CURRENT_CLASS + '{background:#ff9800;outline:2px solid #ff5722;}';
        document.head.appendChild(style);
    }

    document.body.appendChild(bar);
    updateCount();
})();
