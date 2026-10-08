(function () {
  'use strict';
  if (window.__mangaBrowserV1 && window.__mangaBrowserV1.version === 1) return true;

  const MAX_IMAGES = 200, MAX_NODES = 5000, MAX_PIXELS = 4000000;
  const MAX_DATA_CHARS = 12 * 1024 * 1024, MAX_TRANSLATED_CHARS = 48 * 1024 * 1024;
  const records = new Map(), byNode = new WeakMap(), retiredImageSources = new WeakMap();
  let nextId = 1, translatedChars = 0, rasterChars = 0, showTranslations = true;
  let protectedIds = new Set();
  const imageAttrs = ['src', 'srcset', 'sizes', 'width', 'height'];
  const sourceAttrs = ['srcset', 'sizes'];
  const backgroundAttrs = ['background-image', 'background-size'];
  const backgroundProbes = new WeakMap();
  let overlayFrame = 0;

  function parentOf(node) { return node.parentElement || (node.getRootNode && node.getRootNode().host) || null; }
  function canvasCapture(node) { return node.ownerDocument.defaultView.__mangaCaptureV1; }
  function singleBackground(node) {
    const css = node.ownerDocument.defaultView.getComputedStyle(node);
    const match = /^url\(\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([^"'()]*))\s*\)$/i.exec(css.backgroundImage);
    if (!match) return null;
    const url = (match[1] || match[2] || match[3] || '').replace(/\\([0-9a-f]{1,6})\s?|\\(.)/gi,
      (_, hex, char) => hex ? String.fromCodePoint(parseInt(hex, 16)) : char);
    return usableUrl(url) ? {url, css} : null;
  }
  function styleValues(node, names) {
    return names.map(name => [name, node.style.getPropertyValue(name), node.style.getPropertyPriority(name)]);
  }
  function putStyles(node, values) {
    for (const [name, value, priority] of values) {
      if (value) node.style.setProperty(name, value, priority); else node.style.removeProperty(name);
    }
  }
  function backgroundOriginal(record) {
    if (!record.showing) return singleBackground(record.node);
    const current = styleValues(record.node, ['background-image']);
    putStyles(record.node, record.saved.background.filter(value => value[0] === 'background-image'));
    const background = singleBackground(record.node);
    putStyles(record.node, current);
    return background;
  }
  function restoreBackground(record) {
    if (record.node.style.getPropertyValue('background-image') === record.displayed) {
      putStyles(record.node, record.saved.background.filter(value => value[0] === 'background-image'));
    }
    if (record.ownedBackgroundSize && record.node.style.getPropertyValue('background-size') === record.ownedBackgroundSize) {
      putStyles(record.node, record.saved.background.filter(value => value[0] === 'background-size'));
    }
    record.ownedBackgroundSize = null;
  }
  function axisAligned(node) {
    for (let ancestor = node; ancestor; ancestor = parentOf(ancestor)) {
      const css = node.ownerDocument.defaultView.getComputedStyle(ancestor);
      if (css.transform && css.transform !== 'none') {
        try {
          const m = new node.ownerDocument.defaultView.DOMMatrix(css.transform);
          if (!m.is2D || m.b !== 0 || m.c !== 0 || m.a <= 0 || m.d <= 0) return false;
        } catch (_) { return false; }
      }
      if ((css.rotate && css.rotate !== 'none' && css.rotate !== '0deg')
          || (css.perspective && css.perspective !== 'none')) return false;
    }
    return true;
  }
  function removeOverlay(record) {
    if (record.overlay) { record.overlay.removeAttribute('src'); record.overlay.remove(); record.overlay = null; }
  }
  function placeOverlay(record) {
    const overlay = record.overlay, node = record.node;
    if (!overlay) return;
    const position = geometry(node);
    if (!position || !axisAligned(node)) { overlay.style.visibility = 'hidden'; return; }
    const css = node.ownerDocument.defaultView.getComputedStyle(node), rect = node.getBoundingClientRect();
    const sx = node.offsetWidth ? rect.width / node.offsetWidth : 1, sy = node.offsetHeight ? rect.height / node.offsetHeight : 1;
    const px = parseFloat(css.paddingLeft) || 0, py = parseFloat(css.paddingTop) || 0;
    const contentWidth = Math.max(0, node.clientWidth - px - (parseFloat(css.paddingRight) || 0));
    const contentHeight = Math.max(0, node.clientHeight - py - (parseFloat(css.paddingBottom) || 0));
    const left = rect.left + (node.clientLeft + px) * sx, top = rect.top + (node.clientTop + py) * sy;
    overlay.style.width = contentWidth + 'px'; overlay.style.height = contentHeight + 'px';
    overlay.style.transform = css.transform === 'none' ? 'none' : css.transform;
    overlay.style.transformOrigin = css.transformOrigin;
    overlay.style.zIndex = css.zIndex;
    // Sibling positioning keeps the reader's stacking context and overflow clipping intact.
    overlay.style.left = '0px'; overlay.style.top = '0px';
    let own = overlay.getBoundingClientRect();
    const parent = overlay.offsetParent, parentRect = parent && parent.getBoundingClientRect();
    const parentScaleX = parent && parent.offsetWidth ? parentRect.width / parent.offsetWidth : 1;
    const parentScaleY = parent && parent.offsetHeight ? parentRect.height / parent.offsetHeight : 1;
    overlay.style.left = (left - own.left) / parentScaleX + 'px';
    overlay.style.top = (top - own.top) / parentScaleY + 'px';
    overlay.style.visibility = 'visible';
  }
  function animateOverlays() {
    overlayFrame = 0;
    let active = false;
    for (const record of records.values()) {
      if (!record.overlay && !record.requestBadge) continue;
      if (!fresh(record)) { discard(record); continue; }
      if (record.overlay) placeOverlay(record);
      if (record.requestBadge) {
        const rect = record.node.getBoundingClientRect(), view = record.node.ownerDocument.defaultView;
        Object.assign(record.requestBadge.style, {left: Math.max(4, rect.left + 8) + 'px', top: Math.max(4, rect.top + 8) + 'px',
          visibility: rect.bottom > 0 && rect.top < view.innerHeight ? 'visible' : 'hidden'});
      }
      active = true;
    }
    if (active) overlayFrame = requestAnimationFrame(animateOverlays);
  }

  function attrs(node, names) {
    return names.map(name => [name, node.getAttribute(name)]);
  }
  function putAttrs(node, saved) {
    for (const [name, value] of saved) {
      if (value === null) node.removeAttribute(name); else node.setAttribute(name, value);
    }
  }
  function sources(node) {
    const parent = node.parentElement;
    return parent && parent.tagName === 'PICTURE' ? Array.from(parent.querySelectorAll('source')) : [];
  }
  function restoreImage(record) {
    if (!record.ownedImage) return;
    const node = record.node, currentSources = sources(node);
    // Do not resurrect an old srcset over a website's newly selected image.
    const sourceChanged = node.getAttribute('src') !== record.displayed
      || node.getAttribute('srcset') !== null
      || currentSources.some(source => source.getAttribute('srcset') !== null);
    if (record.displayed !== record.rawURL) retiredImageSources.set(node, record.displayed);
    else retiredImageSources.delete(node);
    if (!sourceChanged) {
      for (const source of record.saved.sources) {
        if (!currentSources.includes(source.node)) continue;
        putAttrs(source.node, source.attrs.filter(([name]) => source.node.getAttribute(name) === null));
      }
    }
    const owned = new Map(record.ownedImage);
    putAttrs(node, record.saved.image.filter(([name]) => node.getAttribute(name) === owned.get(name)
      && (!sourceChanged || (name !== 'srcset' && name !== 'sizes'))));
    record.ownedImage = null;
  }
  function signature(node) {
    return JSON.stringify([attrs(node, imageAttrs), sources(node).map(source => [
      attrs(source, ['srcset', 'sizes', 'media', 'type', 'src'])
    ])]);
  }
  function currentDocument(node) {
    if (!node.isConnected) return false;
    let doc = node.ownerDocument;
    for (let depth = 0; depth < 16; depth++) {
      if (doc === document) return true;
      try {
        const frame = doc.defaultView && doc.defaultView.frameElement;
        if (!frame || !frame.isConnected || frame.contentDocument !== doc) return false;
        doc = frame.ownerDocument;
      } catch (_) { return false; }
    }
    return false;
  }
  function usableUrl(url) {
    return /^https?:\/\//i.test(url) || /^blob:/i.test(url)
      || /^data:image\/(?:png|jpe?g|webp|gif|bmp|avif)(?:;|,)/i.test(url);
  }
  function fresh(record) {
    if (!record || !currentDocument(record.node)) return false;
    if (record.kind === 'canvas') {
      const capture = canvasCapture(record.node), info = capture && capture.inspect(record.node);
      return !!info && info.revision === record.revision && info.width === record.width && info.height === record.height;
    }
    if (record.kind === 'background') {
      if (record.showing && record.node.style.getPropertyValue('background-image') !== record.displayed) return false;
      const original = backgroundOriginal(record);
      return !!original && original.url === record.rawURL;
    }
    if (signature(record.node) !== record.signature) return false;
    // currentSrc can lag behind src while a new image decodes; the attribute signature catches that first.
    if (record.showing) return record.node.getAttribute('src') === record.displayed;
    return !record.node.complete || (record.node.currentSrc || record.node.src) === record.rawURL;
  }
  function cleanupLoad(record) {
    if (!record.load) return;
    const {target, loaded, failed} = record.load;
    target.removeEventListener('load', loaded); target.removeEventListener('error', failed);
    if (target !== record.node && target !== record.overlay) target.removeAttribute('src');
    if (record.load.prepared && record.load.prepared !== target) record.load.prepared.removeAttribute('src');
    record.load = null;
  }
  function discard(record) {
    if (!record) return;
    cleanupLoad(record);
    removeOverlay(record);
    if (record.requestBadge) { record.requestBadge.remove(); record.requestBadge = null; }
    if (record.unsubscribe) record.unsubscribe();
    if (record.kind === 'background' && record.showing && record.saved) {
      // Restore only properties we still own; a website's new inline image always wins.
      restoreBackground(record);
    }
    if (record.kind === 'img' && record.showing && record.saved) restoreImage(record);
    translatedChars -= record.translated ? record.translated.length : 0;
    rasterChars -= record.raster ? record.raster.length : 0;
    records.delete(record.id);
    if (byNode.get(record.node) === record) byNode.delete(record.node);
  }
  function lookup(id) {
    const record = records.get(String(id));
    if (!fresh(record)) { discard(record); return null; }
    return record;
  }
  function geometry(node, styleCache) {
    let rect = node.getBoundingClientRect();
    let top = rect.top, bottom = rect.bottom, left = rect.left, right = rect.right;
    const width = rect.width, height = rect.height;
    let doc = node.ownerDocument, current = node, clippedVisible = true, above = false;
    for (let depth = 0; depth < 16; depth++) {
      for (let ancestor = current; ancestor; ancestor = parentOf(ancestor)) {
        let css = styleCache && styleCache.get(ancestor);
        if (!css) { css = doc.defaultView.getComputedStyle(ancestor); if (styleCache) styleCache.set(ancestor, css); }
        if (css.display === 'none' || css.visibility === 'hidden' || css.visibility === 'collapse'
            || Number(css.opacity) === 0 || css.contentVisibility === 'hidden') return null;
        if (ancestor !== current && ancestor !== doc.body && ancestor !== doc.documentElement
            && /(auto|scroll|hidden|clip)/.test(css.overflowY + css.overflowX)) {
          const clip = ancestor.getBoundingClientRect();
          if (right <= clip.left || left >= clip.right) return null;
          if (bottom <= clip.top || top >= clip.bottom) clippedVisible = false;
          if (bottom <= clip.top) above = true;
        }
      }
      if (doc === document) return {top, bottom, left, right, width, height, above: above || bottom <= 0,
        visible: clippedVisible && bottom > 0 && top < innerHeight && right > 0 && left < innerWidth};
      const frame = doc.defaultView.frameElement;
      if (!frame) return null;
      if (right <= 0 || left >= doc.defaultView.innerWidth) return null;
      if (bottom <= 0 || top >= doc.defaultView.innerHeight) clippedVisible = false;
      if (bottom <= 0) above = true;
      rect = frame.getBoundingClientRect();
      const sx = frame.offsetWidth ? rect.width / frame.offsetWidth : 1;
      const sy = frame.offsetHeight ? rect.height / frame.offsetHeight : 1;
      top = rect.top + (top + frame.clientTop) * sy;
      bottom = rect.top + (bottom + frame.clientTop) * sy;
      left = rect.left + (left + frame.clientLeft) * sx;
      right = rect.left + (right + frame.clientLeft) * sx;
      doc = frame.ownerDocument; current = frame;
    }
    return null;
  }
  function candidate(node) {
    if (node.hasAttribute('data-manga-overlay')) return null;
    let record = byNode.get(node);
    if (record && !fresh(record)) { discard(record); record = null; }
    if (record) {
      if (record.kind === 'background' && record.probe.image.naturalWidth) {
        record.width = record.probe.image.naturalWidth; record.height = record.probe.image.naturalHeight;
      }
      return record;
    }
    let kind, width, height, rawURL, revision = 0, probe = null;
    if (node.tagName === 'IMG') {
      kind = 'img'; width = node.naturalWidth; height = node.naturalHeight;
      rawURL = node.currentSrc || node.src;
      if (!node.complete) return null;
      // Restoring src does not synchronously replace currentSrc or the decoded pixels.
      if (retiredImageSources.get(node) === rawURL) return null;
      retiredImageSources.delete(node);
    } else if (node.tagName === 'CANVAS') {
      kind = 'canvas'; width = node.width; height = node.height;
      const capture = canvasCapture(node);
      if (!capture || capture.inspect(node).unsupported || !axisAligned(node)) return null;
      revision = capture.inspect(node).revision;
    } else {
      if (node.tagName === 'HTML' || node.tagName === 'BODY' || node.clientWidth < 100 || node.clientHeight < 100) return null;
      const background = singleBackground(node);
      if (!background) return null;
      kind = 'background'; rawURL = background.url;
      probe = backgroundProbes.get(node);
      if (!probe || probe.url !== rawURL) {
        const image = node.ownerDocument.createElement('img');
        probe = {url: rawURL, image, failed: false}; backgroundProbes.set(node, probe);
        image.onerror = () => { probe.failed = true; };
        image.src = rawURL;
      }
      if (probe.failed) return null;
      width = probe.image.naturalWidth || node.clientWidth; height = probe.image.naturalHeight || node.clientHeight;
    }
    if (width < 100 || height < 100 || (kind !== 'canvas' && (!usableUrl(rawURL)
        || (/^data:/i.test(rawURL) && rawURL.length > MAX_DATA_CHARS)))) return null;
    const id = (kind === 'img' ? 'img_' : kind + '_') + nextId++;
    record = {id, node, kind, rawURL, width, height, revision, probe,
      url: kind === 'canvas' ? 'manga-canvas:' + id + ':' + revision : /^data:/i.test(rawURL) ? 'manga-data:' + id : rawURL,
      signature: signature(node), saved: null, translated: null, showing: false, raster: null,
      state: 'stale', load: null, overlay: null};
    records.set(id, record); byNode.set(node, record);
    if (kind === 'canvas') record.unsubscribe = canvasCapture(node).subscribe(node, () => discard(record));
    return record;
  }
  function metadata(record) {
    const image = {id: record.id, url: record.url, width: record.width, height: record.height,
      alt: (record.node.alt || record.node.getAttribute('aria-label') || '').slice(0, 300)};
    if (record.kind !== 'img') image.kind = record.kind;
    return image;
  }
  function scan(options) {
    const auto = !!options, styleCache = auto ? new Map() : null;
    for (const record of records.values()) if (!fresh(record)) discard(record);
    const images = [], queue = [document];
    let scanned = 0, total = 0, inaccessibleFrames = 0, unsupported = 0, canvasElements = 0,
      backgroundElements = 0, capped = false, documents = 0;
    while (queue.length && !capped) {
      if (++documents > 64 && !auto) { capped = true; break; }
      const root = queue.shift(), doc = root.ownerDocument || root;
      try { if (window.__mangaCaptureV1) window.__mangaCaptureV1.install(doc.defaultView); } catch (_) {}
      for (const node of root.querySelectorAll('*')) {
        if (node.hasAttribute('data-manga-overlay')) continue;
        if (node.shadowRoot) queue.push(node.shadowRoot);
        if (node.tagName === 'IFRAME' || node.tagName === 'FRAME') {
          try {
            const child = node.contentDocument;
            if (child && child.documentElement) queue.push(child); else inaccessibleFrames++;
          } catch (_) { inaccessibleFrames++; }
        }
        const known = node.tagName === 'IMG' || node.tagName === 'CANVAS';
        if (!known && (node.clientWidth < 100 || node.clientHeight < 100 || !singleBackground(node))) continue;
        if (++scanned > MAX_NODES && !auto) { capped = true; break; }
        if (node.tagName === 'CANVAS') canvasElements++;
        const record = candidate(node);
        if (!record) { if (node.tagName === 'CANVAS') unsupported++; continue; }
        if (record.kind === 'background') backgroundElements++;
        const {width, height} = record;
        total++;
        const position = auto ? geometry(node, styleCache) : null;
        if (auto && (width < 240 || height < 180 || !position || position.width < 120 || position.height < 90
            || position.right <= 0 || position.left >= innerWidth)) continue;
        if (!auto && images.length >= MAX_IMAGES) continue;
        const image = metadata(record);
        if (auto) Object.assign(image, {top: position.top, bottom: position.bottom, visible: position.visible,
          nearViewport: position.bottom > -innerHeight && position.top < innerHeight * 2,
          ahead: !position.visible && !position.above, order: scanned - 1, state: record.state,
          translated: record.state === 'applied' && !!record.translated});
        images.push(image);
      }
    }
    if (auto) {
      const rank = image => image.visible ? 0 : image.ahead ? 1 : 2;
      images.sort((a, b) => rank(a) - rank(b) || (rank(a) === 2 ? b.top - a.top : a.top - b.top) || a.order - b.order);
      protectedIds = new Set(images.filter(image => image.visible).map(image => image.id));
    }
    return {images, pageUrl: location.href, scanned: auto ? scanned : Math.min(scanned, MAX_NODES), total,
      truncated: capped || (!auto && total > MAX_IMAGES), inaccessibleFrames, unsupported, canvasElements, backgroundElements};
  }
  function scanAuto() { return scan({}); }
  function selectAt(x, y) {
    if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0 || x > 1 || y > 1) return null;
    const viewport = window.visualViewport;
    let root = document, doc = document, px = viewport ? viewport.offsetLeft + x * viewport.width : x * innerWidth,
      py = viewport ? viewport.offsetTop + y * viewport.height : y * innerHeight, node = null;
    for (let depth = 0; depth < 32; depth++) {
      node = root.elementFromPoint(px, py);
      if (!node) return null;
      if (node.shadowRoot) { root = node.shadowRoot; continue; }
      if (node.tagName !== 'IFRAME' && node.tagName !== 'FRAME') break;
      try {
        const child = node.contentDocument, r = node.getBoundingClientRect();
        if (!child || !child.documentElement || !r.width || !r.height) return null;
        px = (px - r.left) * node.offsetWidth / r.width - node.clientLeft;
        py = (py - r.top) * node.offsetHeight / r.height - node.clientTop;
        doc = child; root = child;
        if (window.__mangaCaptureV1) window.__mangaCaptureV1.install(doc.defaultView);
      } catch (_) { return null; }
    }
    // Text and controls may sit above a CSS background. Select its nearest owning element.
    for (; node; node = parentOf(node)) {
      const record = candidate(node);
      if (record) return metadata(record);
    }
    return null;
  }
  function getOriginal(id) {
    const record = lookup(id);
    if (!record) return null;
    const original = {url: record.url, width: record.width, height: record.height};
    if (record.kind !== 'img') original.kind = record.kind;
    return original;
  }
  function getCapturePlan(id) {
    const record = lookup(id);
    if (!record || record.kind !== 'canvas') return null;
    const plan = canvasCapture(record.node).plan(record.node);
    if (plan.revision !== record.revision) return null;
    if (JSON.stringify(plan).length > MAX_DATA_CHARS) return {kind: 'canvas', error: '画布绘制记录超过读取上限，请使用截图翻译'};
    return plan;
  }
  function dump(id) {
    const record = lookup(id);
    if (!record) return null;
    if (record.width * record.height > MAX_PIXELS || record.width > 4096 || record.height > 4096) return null;
    if (/^data:/i.test(record.rawURL)) return record.rawURL.length <= MAX_DATA_CHARS ? record.rawURL : null;
    if (record.raster) return record.raster;
    if (record.showing && record.kind === 'img') return null;
    try {
      if (record.kind === 'canvas') {
        const data = record.node.toDataURL('image/png');
        return data.length <= MAX_DATA_CHARS ? data : null;
      }
      const canvas = record.node.ownerDocument.createElement('canvas');
      canvas.width = record.width; canvas.height = record.height;
      const source = record.kind === 'background' ? record.probe.image : record.node;
      if (record.kind === 'background' && (!source.complete || !source.naturalWidth)) return null;
      canvas.getContext('2d').drawImage(source, 0, 0);
      const data = canvas.toDataURL('image/png');
      canvas.width = canvas.height = 1;
      return data.length <= MAX_DATA_CHARS ? data : null;
    } catch (_) { return null; } // Cross-origin images without CORS are intentionally not read.
  }
  function saveOriginal(record) {
    if (record.saved) return;
    if (record.kind === 'canvas') { record.saved = {}; return; }
    if (record.kind === 'background') {
      record.saved = {background: styleValues(record.node, backgroundAttrs)}; return;
    }
    record.saved = {image: attrs(record.node, imageAttrs),
      sources: sources(record.node).map(node => ({node, attrs: attrs(node, sourceAttrs)}))};
    // Preserve a readable blob before replacing its sole loaded DOM image. Normal HTTP images stay URLs.
    if (/^blob:/i.test(record.rawURL)) {
      const raster = dump(record.id);
      if (raster && rasterChars + raster.length <= 24 * 1024 * 1024) {
        record.raster = raster; rasterChars += raster.length;
      }
    }
  }
  function apply(record, show) {
    if (record.kind === 'canvas') {
      removeOverlay(record);
      if (show) {
        const overlay = record.node.ownerDocument.createElement('img');
        overlay.setAttribute('data-manga-overlay', record.id); overlay.setAttribute('aria-hidden', 'true');
        overlay.style.cssText = 'position:absolute!important;display:block!important;pointer-events:none!important;'
          + 'margin:0!important;padding:0!important;border:0!important;max-width:none!important;max-height:none!important;'
          + 'min-width:0!important;min-height:0!important;box-sizing:content-box!important;object-fit:fill!important;';
        overlay.src = record.translated;
        record.node.insertAdjacentElement('afterend', overlay); record.overlay = overlay;
        placeOverlay(record);
        if (!overlayFrame) overlayFrame = requestAnimationFrame(animateOverlays);
      }
      record.showing = show; record.displayed = show ? record.translated : null;
      return;
    }
    if (record.kind === 'background') {
      if (show) {
        const css = record.node.ownerDocument.defaultView.getComputedStyle(record.node);
        if (/^auto(?:\s+auto)?$/.test(css.backgroundSize)) {
          record.node.style.setProperty('background-size', record.probe.image.naturalWidth + 'px '
            + record.probe.image.naturalHeight + 'px', 'important');
          record.ownedBackgroundSize = record.node.style.getPropertyValue('background-size');
        }
        record.node.style.setProperty('background-image', 'url("' + record.translated + '")', 'important');
      } else restoreBackground(record);
      record.showing = show;
      record.displayed = show ? record.node.style.getPropertyValue('background-image') : null;
      return;
    }
    if (show) {
      for (const source of record.saved.sources) {
        source.node.removeAttribute('srcset'); source.node.removeAttribute('sizes');
      }
      const node = record.node;
      node.removeAttribute('srcset'); node.removeAttribute('sizes');
      // Keep intrinsic layout even if the returned translated bitmap was downsampled by Android.
      const oldWidth = node.getAttribute('width'), oldHeight = node.getAttribute('height');
      if (oldWidth === null && oldHeight === null) {
        node.setAttribute('width', String(record.width)); node.setAttribute('height', String(record.height));
      } else if (oldHeight === null && /^\d+$/.test(oldWidth || '')) {
        node.setAttribute('height', String(Math.round(Number(oldWidth) * record.height / record.width)));
      } else if (oldWidth === null && /^\d+$/.test(oldHeight || '')) {
        node.setAttribute('width', String(Math.round(Number(oldHeight) * record.width / record.height)));
      }
      node.setAttribute('src', record.translated);
      record.ownedImage = attrs(node, imageAttrs);
    } else {
      restoreImage(record);
    }
    record.showing = show;
    record.displayed = show ? record.translated : null;
    record.signature = signature(record.node);
  }
  function replace(id, expectedOriginalUrl, dataUrl, attemptId) {
    const record = lookup(id);
    if (!record || record.url !== expectedOriginalUrl || typeof dataUrl !== 'string'
        || dataUrl.length > MAX_DATA_CHARS
        || !/^data:image\/(?:png|jpe?g|webp);base64,[a-zA-Z0-9+/=\r\n]+$/.test(dataUrl)) return false;
    dataUrl = dataUrl.replace(/[\r\n]/g, '');
    const oldLength = record.translated ? record.translated.length : 0;
    if (translatedChars - oldLength + dataUrl.length > MAX_TRANSLATED_CHARS) {
      const distant = [];
      for (const other of records.values()) {
        if (!fresh(other)) { discard(other); continue; }
        if (other === record || !other.translated || other.state === 'pending' || protectedIds.has(other.id)) continue;
        const position = geometry(other.node);
        if (position && position.visible) continue;
        distant.push({record: other, distance: position ? Math.max(-position.bottom, position.top - innerHeight) : Infinity});
      }
      distant.sort((a, b) => b.distance - a.distance);
      for (const item of distant) {
        if (translatedChars - oldLength + dataUrl.length <= MAX_TRANSLATED_CHARS) break;
        const other = item.record;
        cleanupLoad(other); if (other.showing) apply(other, false);
        translatedChars -= other.translated.length; rasterChars -= other.raster ? other.raster.length : 0;
        other.translated = null; other.raster = null; other.state = 'stale';
      }
      if (translatedChars - oldLength + dataUrl.length > MAX_TRANSLATED_CHARS) { record.state = 'cachefull'; return false; }
    }
    saveOriginal(record);
    cleanupLoad(record);
    translatedChars += dataUrl.length - oldLength;
    record.translated = dataUrl;
    record.attemptId = typeof attemptId === 'string' ? attemptId : null;
    startReplacement(record, showTranslations);
    return true;
  }
  function failReplacement(record) {
    cleanupLoad(record);
    if (record.showing) apply(record, false);
    translatedChars -= record.translated ? record.translated.length : 0;
    record.translated = null;
    record.state = 'failed';
  }
  function startReplacement(record, show) {
    cleanupLoad(record);
    record.state = 'pending';
    const candidate = record.translated;
    // Decode in the owning document first: keep the readable original until its replacement is ready.
    const prepared = record.node.ownerDocument.createElement('img');
    const attempt = {target: prepared, prepared, loaded: null, failed: null};
    const ownsAttempt = () => record.load === attempt && records.get(record.id) === record
      && fresh(record) && record.translated === candidate;
    const staleAttempt = () => {
      if (record.load === attempt) { cleanupLoad(record); record.state = 'stale'; }
    };
    const loaded = async () => {
      if (!ownsAttempt()) { staleAttempt(); return; }
      const target = attempt.target;
      if (target.currentSrc !== candidate) return;
      if (!target.complete || !target.naturalWidth || !target.naturalHeight) { failReplacement(record); return; }
      if (target === prepared) {
        // Match BrowserImageLoader's bitmap budget; dump() has a separate smaller canvas budget.
        if (target.naturalWidth > 6000 || target.naturalHeight > 6000
            || target.naturalWidth * target.naturalHeight > 8000000) { failReplacement(record); return; }
        try { if (typeof prepared.decode === 'function') await prepared.decode(); }
        catch (_) { if (ownsAttempt()) failReplacement(record); else staleAttempt(); return; }
        if (record.kind === 'background') {
          try { await record.probe.image.decode(); }
          catch (_) { if (ownsAttempt()) failReplacement(record); else staleAttempt(); return; }
        }
        if (!ownsAttempt()) { staleAttempt(); return; }
        if (record.kind === 'background' || record.kind === 'canvas') {
          if (show) apply(record, true);
          // Decoded pixels are ready. Canvas overlays get a separate real-element load check.
          if (show && record.kind === 'canvas') {
            prepared.removeEventListener('load', loaded); prepared.removeEventListener('error', failed);
            attempt.target = record.overlay;
            record.overlay.addEventListener('load', loaded); record.overlay.addEventListener('error', failed);
            if (record.overlay.complete && record.overlay.naturalWidth) Promise.resolve().then(loaded);
            return;
          }
          cleanupLoad(record); record.state = 'applied'; return;
        }
        if (show) {
          prepared.removeEventListener('load', loaded); prepared.removeEventListener('error', failed);
          attempt.target = record.node;
          record.node.addEventListener('load', loaded); record.node.addEventListener('error', failed);
          apply(record, true);
          return; // Only the real image's load event confirms an applied visible replacement.
        }
      }
      cleanupLoad(record); record.state = 'applied';
    };
    const failed = () => {
      if (!ownsAttempt()) { staleAttempt(); return; }
      failReplacement(record);
    };
    attempt.loaded = loaded; attempt.failed = failed; record.load = attempt;
    prepared.addEventListener('load', loaded); prepared.addEventListener('error', failed);
    prepared.setAttribute('src', candidate);
  }
  function getReplacementState(id) {
    const record = lookup(id);
    return record ? record.state : 'stale';
  }
  function cancelReplacement(id, expectedOriginalUrl, attemptId) {
    const record = lookup(id);
    if (!record || record.url !== expectedOriginalUrl || record.state !== 'pending') return false;
    if (record.attemptId !== null && record.attemptId !== undefined && record.attemptId !== attemptId) return false;
    failReplacement(record);
    return true;
  }
  function toggleTranslations(show) {
    showTranslations = !!show;
    let shown = 0, skipped = 0;
    for (const record of records.values()) {
      if (!fresh(record)) { discard(record); skipped++; continue; }
      if (!record.translated) continue;
      if (showTranslations && !(record.showing && (record.state === 'applied' || record.state === 'pending'))) startReplacement(record, true);
      else if (showTranslations) { shown++; continue; }
      else if (record.state === 'pending') failReplacement(record);
      else apply(record, false);
      shown++;
    }
    return {shown, skipped};
  }
  function restoreAll() {
    let restored = 0, skipped = 0;
    for (const record of records.values()) {
      if (!fresh(record)) { discard(record); skipped++; continue; }
      if (!record.saved) continue;
      cleanupLoad(record);
      apply(record, false);
      translatedChars -= record.translated ? record.translated.length : 0;
      rasterChars -= record.raster ? record.raster.length : 0;
      record.translated = null; record.saved = null; record.raster = null;
      record.state = 'stale';
      restored++;
    }
    showTranslations = true;
    return {restored, skipped};
  }
  function setRequestState(id, url, label) {
    const record = lookup(id);
    if (!record || record.url !== url) return false;
    if (!label) { if (record.requestBadge) record.requestBadge.remove(); record.requestBadge = null; return true; }
    if (!record.requestBadge) {
      const badge = record.node.ownerDocument.createElement('div');
      badge.setAttribute('data-manga-overlay', 'request'); badge.setAttribute('role', 'status');
      Object.assign(badge.style, {position: 'fixed', zIndex: '2147483646', pointerEvents: 'none', background: '#e8f0fe',
        color: '#174ea6', padding: '6px 10px', borderRadius: '12px', font: '14px sans-serif', maxWidth: '80vw'});
      record.node.ownerDocument.body.appendChild(badge); record.requestBadge = badge;
    }
    record.requestBadge.textContent = String(label);
    if (!overlayFrame) overlayFrame = requestAnimationFrame(animateOverlays);
    return true;
  }
  function clearRequestStates() {
    for (const record of records.values()) if (record.requestBadge) { record.requestBadge.remove(); record.requestBadge = null; }
  }
  Object.defineProperty(window, '__mangaBrowserV1', {configurable: true,
    value: Object.freeze({version: 1, scan, scanAuto, selectAt, getOriginal, replace, dump, restoreAll, toggleTranslations,
      getReplacementState, cancelReplacement, getCapturePlan, setRequestState, clearRequestStates})});
  return true;
})()
