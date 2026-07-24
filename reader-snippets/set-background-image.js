// Pick an image file, use it as the reader background, and persist it in localStorage.
// Requires Advanced settings: novelWebViewDevTools ON (exercises onShowFileChooser).
// The chosen image is downscaled to fit localStorage, then painted onto a fixed full-viewport layer
// behind the text. Restored on every run, so it survives chapter loads and infinite-scroll appends.
(function() {
    var STORAGE_KEY = 'tsundoku:customBg';   // fixed key: overwritten, never accumulates
    var LAYER_ID = 'tsundoku-custom-bg-layer';
    var MAX_DIM = 1280;                       // longest edge after downscale
    var JPEG_QUALITY = 0.82;

    function ensureLayer() {
        var layer = document.getElementById(LAYER_ID);
        if (!layer) {
            layer = document.createElement('div');
            layer.id = LAYER_ID;
            layer.style.cssText = 'position:fixed;inset:0;z-index:-1;background-size:cover;' +
                'background-position:center;background-repeat:no-repeat;pointer-events:none;';
            document.body.insertBefore(layer, document.body.firstChild);
        }
        return layer;
    }

    function applyDataUrl(dataUrl) {
        ensureLayer().style.backgroundImage = 'url("' + dataUrl + '")';
        // Let the picture read through the text background so it isn't fully hidden by the theme.
        document.documentElement.style.background = 'transparent';
        if (document.body) document.body.style.background = 'transparent';
    }

    function restore() {
        try {
            var saved = localStorage.getItem(STORAGE_KEY);
            if (saved) applyDataUrl(saved);
            return !!saved;
        } catch (e) { return false; }
    }

    function clearBg() {
        try { localStorage.removeItem(STORAGE_KEY); } catch (e) {}
        var layer = document.getElementById(LAYER_ID);
        if (layer) layer.remove();
    }

    // Downscale + re-encode so a multi-MB photo doesn't blow the localStorage quota.
    function downscaleToDataUrl(srcDataUrl, cb) {
        var img = new Image();
        img.onload = function() {
            var w = img.naturalWidth, h = img.naturalHeight;
            var scale = Math.min(1, MAX_DIM / Math.max(w, h));
            var cw = Math.max(1, Math.round(w * scale)), ch = Math.max(1, Math.round(h * scale));
            var canvas = document.createElement('canvas');
            canvas.width = cw; canvas.height = ch;
            canvas.getContext('2d').drawImage(img, 0, 0, cw, ch);
            try {
                cb(canvas.toDataURL('image/jpeg', JPEG_QUALITY));
            } catch (e) {
                cb(srcDataUrl); // tainted canvas or encode failure: fall back to original
            }
        };
        img.onerror = function() { cb(srcDataUrl); };
        img.src = srcDataUrl;
    }

    function persist(dataUrl) {
        try {
            localStorage.setItem(STORAGE_KEY, dataUrl);
            return true;
        } catch (e) {
            alert('Could not save background: ' + (e && e.name === 'QuotaExceededError'
                ? 'image too large for localStorage even after downscale.'
                : (e && e.message) || e));
            return false;
        }
    }

    function pickImage() {
        var input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.style.display = 'none';
        input.onchange = function() {
            var file = input.files && input.files[0];
            input.remove();
            if (!file) return; // chooser cancelled -> filePathCallback resolved with no uri natively
            var reader = new FileReader();
            reader.onload = function() {
                downscaleToDataUrl(reader.result, function(finalUrl) {
                    applyDataUrl(finalUrl);
                    persist(finalUrl);
                });
            };
            reader.readAsDataURL(file);
        };
        document.body.appendChild(input);
        input.click(); // triggers native onShowFileChooser
    }

    var BAR_ID = 'tsundoku-bg-bar';
    var old = document.getElementById(BAR_ID);
    if (old) old.remove();

    var bar = document.createElement('div');
    bar.id = BAR_ID;
    bar.style.cssText = 'position:fixed;right:8px;z-index:100001;display:flex;gap:6px;' +
        'bottom:calc(8px + var(--tsundoku-safe-bottom, 0px));' +
        'background:rgba(0,0,0,0.8);padding:6px;border-radius:8px;font-family:monospace;';

    var pickBtn = document.createElement('button');
    pickBtn.textContent = 'Pick background';
    pickBtn.style.cssText = 'padding:6px 10px;border:none;border-radius:5px;background:#0f0;color:#000;font-weight:bold;cursor:pointer;';
    pickBtn.onclick = pickImage;
    bar.appendChild(pickBtn);

    var clearBtn = document.createElement('button');
    clearBtn.textContent = 'Clear';
    clearBtn.style.cssText = 'padding:6px 10px;border:none;border-radius:5px;background:#ffb300;color:#000;font-weight:bold;cursor:pointer;';
    clearBtn.onclick = clearBg;
    bar.appendChild(clearBtn);

    var close = document.createElement('button');
    close.textContent = 'x';
    close.style.cssText = 'padding:6px 9px;border:none;border-radius:5px;background:#f00;color:#fff;font-weight:bold;cursor:pointer;';
    close.onclick = function() { bar.remove(); };
    bar.appendChild(close);

    document.body.appendChild(bar);

    restore(); // re-apply a previously chosen background on load / after appends
})();
