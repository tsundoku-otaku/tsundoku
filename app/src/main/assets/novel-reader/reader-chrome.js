// Push reader-chrome state into the page: safe-area CSS vars + reader-menu visibility.
//
// Replaces:
//   __SAFE_TOP_VAR__ / __SAFE_BOTTOM_VAR__ - CSS custom property names
//   __SAFE_TOP__ / __SAFE_BOTTOM__         - px values (numbers)
//   __OBJECT__                             - global object name (Tsundoku)
//   __MENU_KEY__                           - runtime menu-visible key
//   __MENU_VISIBLE__                       - true / false
//   __EVENT__                              - menu-visibility CustomEvent name
//
// Writes the vars only when they change: setting a custom property invalidates style for its whole
// subtree, an avoidable recalc on a long document during menu toggles.
(function () {
    var s = document.documentElement.style;
    var top = '__SAFE_TOP__px', bottom = '__SAFE_BOTTOM__px';
    if (s.getPropertyValue('__SAFE_TOP_VAR__') !== top) s.setProperty('__SAFE_TOP_VAR__', top);
    if (s.getPropertyValue('__SAFE_BOTTOM_VAR__') !== bottom) s.setProperty('__SAFE_BOTTOM_VAR__', bottom);

    window.__OBJECT__ = window.__OBJECT__ || {};
    window.__OBJECT__.runtime = window.__OBJECT__.runtime || {};
    var was = window.__OBJECT__.runtime.__MENU_KEY__;
    window.__OBJECT__.runtime.__MENU_KEY__ = __MENU_VISIBLE__;
    if (was !== __MENU_VISIBLE__) {
        window.dispatchEvent(new CustomEvent('__EVENT__', { detail: { visible: __MENU_VISIBLE__ } }));
    }
})();
