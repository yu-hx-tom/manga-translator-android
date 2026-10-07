(function () {
  'use strict';
  // Install at document start. A late installation can still read an origin-clean canvas,
  // but never guesses the missing draw history of a tainted canvas.
  function install(win) {
    if (!win || win.__mangaCaptureV1) return;
    const states = new WeakMap(), bitmapSources = new WeakMap(), knownMutations = new WeakMap();
    const existing = new WeakSet(win.document.querySelectorAll('canvas'));
    const MAX_OPS = 512, MAX_SOURCE_CHARS = 10 * 1024 * 1024;
    const retainedPlans = new Map();
    let retainedChars = 0, retainedOps = 0;
    function state(canvas) {
      let value = states.get(canvas);
      if (!value) {
        value = {revision: 0, width: canvas.width, height: canvas.height, ops: [], chars: 0,
          blocked: existing.has(canvas) ? '画布在追踪开始前已存在，无法确认完整绘制过程' : '', listeners: new Set()};
        states.set(canvas, value);
      }
      return value;
    }
    function changed(canvas, reset) {
      const value = state(canvas);
      value.revision++;
      if (reset) { clearPlan(value); value.blocked = value.contextError || ''; }
      value.width = canvas.width; value.height = canvas.height;
      for (const listener of value.listeners) { try { listener(); } catch (_) {} }
      return value;
    }
    function clearPlan(value) {
      retainedChars -= value.chars; retainedOps -= value.ops.length;
      value.chars = 0; value.ops = []; retainedPlans.delete(value);
    }
    function block(value, reason) { value.blocked = reason; clearPlan(value); }
    function rememberAttribute(canvas) {
      if (canvas.isConnected && canvas.getRootNode() === win.document) knownMutations.set(canvas, (knownMutations.get(canvas) || 0) + 1);
    }
    function imageSource(source) {
      if (source && source.tagName === 'IMG' && source.complete && source.naturalWidth) {
        return {url: source.currentSrc || source.src, sourceWidth: source.naturalWidth, sourceHeight: source.naturalHeight,
          width: source.naturalWidth, height: source.naturalHeight, cropX: 0, cropY: 0};
      }
      return source && bitmapSources.get(source);
    }
    if (win.createImageBitmap) {
      const create = win.createImageBitmap;
      win.createImageBitmap = function (source) {
        const args = Array.prototype.slice.call(arguments), base = imageSource(source);
        let mapped = base && Object.assign({}, base), options = args.length > 2 ? args[5] : args[1];
        if (options && ((options.imageOrientation && options.imageOrientation !== 'from-image')
            || (options.premultiplyAlpha && options.premultiplyAlpha !== 'default')
            || (options.colorSpaceConversion && options.colorSpaceConversion !== 'default')
            || options.resizeWidth !== undefined || options.resizeHeight !== undefined)) mapped = null;
        if (mapped && args.length > 2) {
          const values = args.slice(1, 5);
          if (values.length !== 4 || !values.every(Number.isSafeInteger) || values[0] < 0 || values[1] < 0
              || values[2] <= 0 || values[3] <= 0 || values[0] + values[2] > mapped.width
              || values[1] + values[3] > mapped.height) mapped = null;
          else {
            mapped.cropX += values[0]; mapped.cropY += values[1]; mapped.width = values[2]; mapped.height = values[3];
          }
        }
        const promise = create.apply(this, arguments);
        return promise.then(bitmap => {
          if (mapped && bitmap.width === mapped.width && bitmap.height === mapped.height) bitmapSources.set(bitmap, mapped);
          return bitmap;
        });
      };
    }
    const proto = win.CanvasRenderingContext2D && win.CanvasRenderingContext2D.prototype;
    if (proto) {
      const drawImage = proto.drawImage;
      proto.drawImage = function () {
        const result = drawImage.apply(this, arguments), value = changed(this.canvas);
        if (value.blocked) return result;
        const source = imageSource(arguments[0]), args = Array.prototype.slice.call(arguments, 1);
        if (!source) {
          block(value, '画布包含视频、其他画布或非静态图片来源'); return result;
        }
        const url = source.url;
        if (!/^https?:\/\//i.test(url) && !/^data:image\/(?:png|jpe?g|webp|gif|bmp|avif)(?:;|,)/i.test(url)) {
          block(value, '画布图片来源无法由本机重新读取'); return result;
        }
        if (url.length + value.chars > MAX_SOURCE_CHARS || value.ops.length >= MAX_OPS) {
          block(value, '画布绘制记录超过内存上限'); return result;
        }
        if (this.globalAlpha !== 1 || this.globalCompositeOperation !== 'source-over'
            || (this.filter && this.filter !== 'none') || this.shadowBlur || this.shadowOffsetX || this.shadowOffsetY) {
          block(value, '画布使用透明混合、滤镜或阴影，无法精确重建'); return result;
        }
        let sx = 0, sy = 0, sw = source.width, sh = source.height, dx, dy, dw, dh;
        if (args.length === 2) { [dx, dy] = args; dw = sw; dh = sh; }
        else if (args.length === 4) { [dx, dy, dw, dh] = args; }
        else if (args.length === 8) [sx, sy, sw, sh, dx, dy, dw, dh] = args;
        else { block(value, '画布绘制参数不受支持'); return result; }
        const matrix = this.getTransform(), transform = [matrix.a, matrix.b, matrix.c, matrix.d, matrix.e, matrix.f];
        if (![sx, sy, sw, sh, dx, dy, dw, dh, ...transform].every(Number.isFinite)
            || sx < 0 || sy < 0 || sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0
            || sx + sw > source.width || sy + sh > source.height) {
          block(value, '画布含越界或反向裁切，无法精确重建'); return result;
        }
        while (retainedPlans.size && (retainedChars + url.length > 24 * 1024 * 1024 || retainedOps >= 4096)) {
          block(retainedPlans.keys().next().value, '较早的画布绘制记录已超过内存预算，需要重新绘制或截图');
        }
        if (value.blocked) return result;
        value.ops.push({url, sourceWidth: source.sourceWidth, sourceHeight: source.sourceHeight,
          sx: sx + source.cropX, sy: sy + source.cropY, sw, sh, dx, dy, dw, dh, matrix: transform, smoothing: this.imageSmoothingEnabled});
        value.chars += url.length; retainedChars += url.length; retainedOps++;
        retainedPlans.delete(value); retainedPlans.set(value, true);
        return result;
      };
      const clearRect = proto.clearRect;
      proto.clearRect = function (x, y, width, height) {
        const result = clearRect.apply(this, arguments), value = changed(this.canvas), m = this.getTransform();
        // A clip may survive clearRect; only resizing/reset() proves a clean drawing state.
        // Accept numeric primitives only: native coercion may have side effects and must not be repeated.
        if (!value.blocked && [x, y, width, height].every(Number.isFinite)
            && m.a === 1 && m.b === 0 && m.c === 0 && m.d === 1 && m.e === 0 && m.f === 0
            && x <= 0 && y <= 0 && width + x >= this.canvas.width && height + y >= this.canvas.height) clearPlan(value);
        else block(value, '画布使用局部清除或无法确认的剪裁');
        return result;
      };
      for (const name of ['fillRect', 'strokeRect', 'fill', 'stroke', 'fillText', 'strokeText', 'putImageData', 'clip']) {
        const original = proto[name];
        if (!original) continue;
        proto[name] = function () {
          const result = original.apply(this, arguments);
          block(changed(this.canvas), '画布包含文字、路径、像素修改或剪裁，需要读取像素或截图');
          return result;
        };
      }
      if (proto.reset) {
        const reset = proto.reset;
        proto.reset = function () { const result = reset.apply(this, arguments); changed(this.canvas, true); return result; };
      }
    }
    const canvasProto = win.HTMLCanvasElement && win.HTMLCanvasElement.prototype;
    if (canvasProto) {
      for (const name of ['width', 'height']) {
        const descriptor = Object.getOwnPropertyDescriptor(canvasProto, name);
        if (!descriptor || !descriptor.set || !descriptor.configurable) continue;
        Object.defineProperty(canvasProto, name, Object.assign({}, descriptor, {set: function (value) {
          descriptor.set.call(this, value); rememberAttribute(this); changed(this, true);
        }}));
      }
      const getContext = canvasProto.getContext;
      canvasProto.getContext = function (type) {
        const context = getContext.apply(this, arguments);
        if (context && type !== '2d') {
          state(this).unsupported = true;
          block(state(this), '该画布使用 WebGL 或其他非二维绘制，需要截图');
        } else if (context && typeof context.getContextAttributes === 'function') {
          const attributes = context.getContextAttributes();
          if (!attributes.alpha || (attributes.colorSpace && attributes.colorSpace !== 'srgb')) {
            const value = state(this);
            value.contextError = '画布使用不透明或特殊色彩空间，需要读取完整像素或截图';
            block(value, value.contextError);
          }
        }
        return context;
      };
      if (canvasProto.transferControlToOffscreen) {
        const transfer = canvasProto.transferControlToOffscreen;
        canvasProto.transferControlToOffscreen = function () {
          const result = transfer.apply(this, arguments), value = changed(this);
          value.unsupported = true; block(value, '画布交由后台线程绘制，需要截图'); return result;
        };
      }
    }
    for (const name of ['setAttribute', 'removeAttribute']) {
      const original = win.Element.prototype[name];
      win.Element.prototype[name] = function (key) {
        const hadAttribute = this instanceof win.HTMLCanvasElement && this.hasAttribute(key);
        const result = original.apply(this, arguments);
        if (this instanceof win.HTMLCanvasElement && /^(width|height)$/i.test(String(key))
            && (name === 'setAttribute' || hadAttribute)) { rememberAttribute(this); changed(this, true); }
        return result;
      };
    }
    // WebGL pixels may be readable but cannot be kept fresh by the 2D draw hook.
    for (const webgl of [win.WebGLRenderingContext, win.WebGL2RenderingContext]) {
      if (!webgl) continue;
      for (const name of ['clear', 'drawArrays', 'drawElements', 'drawArraysInstanced', 'drawElementsInstanced', 'blitFramebuffer']) {
        const original = webgl.prototype[name];
        if (typeof original !== 'function') continue;
        webgl.prototype[name] = function () {
          const result = original.apply(this, arguments);
          if (this.canvas) {
            const value = changed(this.canvas); value.unsupported = true;
            block(value, '该画布使用 WebGL 绘制，需要截图');
          }
          return result;
        };
      }
    }
    // setAttribute('width', ...) does not go through the property setter.
    const observer = new win.MutationObserver(mutations => {
      for (const mutation of mutations) if (mutation.target instanceof win.HTMLCanvasElement) {
        const remaining = knownMutations.get(mutation.target) || 0;
        if (remaining) { knownMutations.set(mutation.target, remaining - 1); continue; }
        block(changed(mutation.target), '画布尺寸通过未追踪的属性修改，完整绘制历史需要重新采集');
      }
    });
    observer.observe(win.document, {subtree: true, attributes: true, attributeFilter: ['width', 'height']});
    Object.defineProperty(win, '__mangaCaptureV1', {configurable: true, value: Object.freeze({
      install,
      inspect(canvas) {
        const value = state(canvas);
        return {revision: value.revision, width: canvas.width, height: canvas.height,
          unsupported: !!value.unsupported,
          error: value.blocked || (!value.ops.length ? '没有可重建的静态图片绘制记录' : '')};
      },
      plan(canvas) {
        const value = state(canvas), info = this.inspect(canvas);
        return Object.assign({kind: 'canvas'}, info, info.error ? {} : {ops: value.ops.map(op => Object.assign({}, op))});
      },
      subscribe(canvas, listener) { state(canvas).listeners.add(listener); return () => state(canvas).listeners.delete(listener); }
    })});
  }
  install(window);
  return true;
})()
