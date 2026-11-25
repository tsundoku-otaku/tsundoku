// In-page "#id" links within a chapter.

(function () {
    if (window.__tdFragmentLinksInstalled) return;
    window.__tdFragmentLinksInstalled = true;

    function cssEscape(value) {
        if (window.CSS && CSS.escape) return CSS.escape(value);
        return String(value).replace(/["\\]/g, '\\$&');
    }

    function decode(value) {
        try { return decodeURIComponent(value); } catch (e) { return value; }
    }

    document.addEventListener('click', function (e) {
        var anchor = e.target && e.target.closest ? e.target.closest('a[href^="#"]') : null;
        if (!anchor || anchor.isContentEditable) return;
        var id = decode(anchor.getAttribute('href').substring(1));
        if (!id) return;
        var scope = anchor.closest('__CHAPTER_TAG_NAME__') || document;
        // An id wins over a same-named <a name>, wherever each sits, as browsers resolve fragments.
        var target = scope.querySelector('[id="' + cssEscape(id) + '"]') ||
            scope.querySelector('a[name="' + cssEscape(id) + '"]');
        if (!target) return;
        e.preventDefault();
        e.stopPropagation();
        if (window.Android && window.Android.onFragmentLinkTapped) window.Android.onFragmentLinkTapped();
        var t = window.__TSUNDOKU_OBJECT_NAME__;
        if (t && t.actions && t.actions.goToElement) t.actions.goToElement(target);
        else target.scrollIntoView({ block: 'start' });
    }, true);
})();
