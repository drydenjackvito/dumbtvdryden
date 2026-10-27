// Runs first in YouTube's embed page inside Tomboy's video window (see VideoWindow.java).
// The TV's hardware decoder draws on the video plane the HDMI input holds, so the page's own
// video shows black over a game. This catches the compressed video YouTube hands its player,
// decodes it in software (WebCodecs) and draws it on a canvas, which shows like any other UI.
// The page's video keeps playing, unseen, for the sound and the clock.
(function () {
  if (window.__tb) return;
  window.__tb = 1;
  var START = __START__, VOL = __VOL__, MAX_W = 640, MAX_H = 360;
  var stats = { dec: 0, drawn: 0, late: 0, restarts: 0, skips: 0, ms: 0 };
  function log(s) { try { Tomboy.log(s); } catch (e) {} }

  // 1. Steer YouTube to VP9 (the software decoder's codec) at 360p or less: the TV's processor
  // decodes 480p at about 20 frames a second, short of 30.
  function ok(t) {
    t = String(t || '');
    if (/av01|avc1|avc3|hev1|hvc1/i.test(t)) return false;
    var m = /width=(\d+)/.exec(t);
    if (m && +m[1] > MAX_W) return false;
    m = /height=(\d+)/.exec(t);
    return !(m && +m[1] > MAX_H);
  }
  ['MediaSource', 'ManagedMediaSource', 'WebKitMediaSource'].forEach(function (n) {
    var M = window[n];
    if (!M || !M.isTypeSupported) return;
    var o = M.isTypeSupported.bind(M);
    M.isTypeSupported = function (t) { return /^video/i.test(t) && !ok(t) ? false : o(t); };
  });
  var cpt = HTMLMediaElement.prototype.canPlayType;
  HTMLMediaElement.prototype.canPlayType = function (t) {
    return /^video/i.test(t) && !ok(t) ? '' : cpt.call(this, t);
  };
  if (navigator.mediaCapabilities && navigator.mediaCapabilities.decodingInfo) {
    var di = navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
    navigator.mediaCapabilities.decodingInfo = function (c) {
      var v = c && c.video;
      if (v && (!ok(v.contentType) || v.width > MAX_W || v.height > MAX_H)) {
        return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
      }
      return di(c);
    };
  }

  // YouTube lowers the quality when the player drops frames. Its own (unseen) player drops none,
  // so it is told the frames this one could not show in time.
  var gq = HTMLVideoElement.prototype.getVideoPlaybackQuality;
  if (gq) {
    HTMLVideoElement.prototype.getVideoPlaybackQuality = function () {
      var q = gq.call(this);
      if (!codec) return q;
      var total = Math.max(q.totalVideoFrames, stats.drawn + stats.late);
      return { totalVideoFrames: total, droppedVideoFrames: stats.late, corruptedVideoFrames: 0,
        creationTime: q.creationTime, totalFrameDelay: q.totalFrameDelay || 0 };
    };
  }

  // 2. The video bytes as YouTube appends them: WebM, split into frames.
  var chunks = [], seen = {}, codec = null;
  var pend = new Uint8Array(0), clusterTime = 0, tick = 1000; // µs per timecode tick

  function vint(b, p, raw) {
    var f = b[p];
    if (f === undefined || f === 0) return null;
    var len = 1, m = 0x80;
    while (!(f & m)) { m >>= 1; len++; }
    if (p + len > b.length) return null;
    var v = raw ? f : (f & (m - 1));
    for (var i = 1; i < len; i++) v = v * 256 + b[p + i];
    return { v: v, len: len };
  }
  function uint(b, s, n) { var t = 0; for (var i = 0; i < n; i++) t = t * 256 + b[s + i]; return t; }
  function vp9Key(d) { return d.length > 0 && ((d[0] >> 2) & 1) === 0; }

  function feed(u8, off) {
    var b = new Uint8Array(pend.length + u8.length);
    b.set(pend);
    b.set(u8, pend.length);
    var p = 0;
    for (;;) {
      var id = vint(b, p, true);
      if (!id) break;
      var sz = vint(b, p + id.len, false);
      if (!sz) break;
      var h = id.len + sz.len, ID = id.v;
      // Segment, Cluster, BlockGroup: step inside rather than over.
      if (ID === 0x18538067 || ID === 0x1F43B675 || ID === 0xA0) { p += h; continue; }
      if (p + h + sz.v > b.length) break; // the rest comes with the next append
      var s = p + h;
      if (ID === 0xE7) clusterTime = uint(b, s, sz.v);
      else if (ID === 0x2AD7B1) tick = uint(b, s, sz.v) / 1000;
      else if (ID === 0xA3 || ID === 0xA1) {
        var tn = vint(b, s, false), q = s + tn.len;
        var rel = (b[q] << 8) | b[q + 1];
        if (rel & 0x8000) rel -= 0x10000;
        var data = b.slice(q + 3, s + sz.v);
        var key = ID === 0xA3 ? !!(b[q + 2] & 0x80) : vp9Key(data);
        var t = Math.round((clusterTime + rel) * tick) + off;
        if (!seen[t]) {
          seen[t] = 1;
          insert({ t: t, key: key, data: data });
        }
      }
      p = s + sz.v;
    }
    pend = b.slice(p);
  }

  // In time order. YouTube sometimes appends a piece out of order: it goes in its place, and
  // the decoder carries on (starting it again would decode seconds of video a second time).
  function insert(c) {
    var n = chunks.length;
    if (!n || c.t > chunks[n - 1].t) { chunks.push(c); return; }
    var lo = 0, hi = n;
    while (lo < hi) { var mid = (lo + hi) >> 1; if (chunks[mid].t < c.t) lo = mid + 1; else hi = mid; }
    chunks.splice(lo, 0, c);
    if (lo < fed) fed++; // before what was fed already: behind the clock, not needed
  }

  function setCodec(type) {
    var m = /codecs="?([^",]+)/.exec(type);
    var c = m ? m[1] : 'vp9';
    if (c === 'vp9') c = 'vp09.00.10.08';
    if (c === codec) return;
    codec = c;
    dec = null; // configure again
    log('video track ' + type);
    if (window.VideoDecoder) {
      VideoDecoder.isConfigSupported({ codec: codec, hardwareAcceleration: 'prefer-software' })
        .then(function (r) { log('software decoder for ' + codec + ': ' + r.supported); },
              function (e) { log('software decoder check ' + e); });
    } else {
      log('no WebCodecs');
    }
  }
  var asb = MediaSource.prototype.addSourceBuffer;
  MediaSource.prototype.addSourceBuffer = function (t) {
    var sb = asb.call(this, t);
    if (/^video\/webm/i.test(t)) { sb.__tbv = 1; setCodec(t); }
    else if (/^video/i.test(t)) log('not webm: ' + t);
    return sb;
  };
  var ab = SourceBuffer.prototype.appendBuffer;
  SourceBuffer.prototype.appendBuffer = function (d) {
    if (this.__tbv) {
      try {
        var u = d instanceof ArrayBuffer ? new Uint8Array(d) : new Uint8Array(d.buffer, d.byteOffset, d.byteLength);
        feed(u.slice(), Math.round((this.timestampOffset || 0) * 1e6));
      } catch (e) { log('parse ' + e); }
    }
    return ab.call(this, d);
  };
  if (SourceBuffer.prototype.changeType) {
    var ct = SourceBuffer.prototype.changeType;
    SourceBuffer.prototype.changeType = function (t) {
      if (this.__tbv && /^video\/webm/i.test(t)) setCodec(t);
      return ct.call(this, t);
    };
  }

  // 3. Decoding just ahead of the clock, and drawing the frame that is due.
  var dec = null, frames = [], fed = 0, needKey = true, target = 0, lastNow = -1, shown = null;
  var lastOut = -1, lastSkip = 0;
  function restartAt(now) {
    stats.restarts++;
    if (dec) { try { dec.close(); } catch (e) {} }
    frames.forEach(function (f) { f.close(); });
    frames = [];
    if (!codec || !window.VideoDecoder) { dec = null; return; }
    dec = new VideoDecoder({
      output: function (f) {
        lastOut = f.timestamp;
        if (f.timestamp < target - 40000) { f.close(); stats.late++; return; } // only to reach the target
        frames.push(f);
        stats.dec++;
      },
      error: function (e) { log('decode ' + e); dec = null; }
    });
    dec.configure({ codec: codec, hardwareAcceleration: 'prefer-software', optimizeForLatency: true });
    needKey = true;
    target = now;
    lastOut = -1;
    var k = -1, i;
    for (i = 0; i < chunks.length; i++) {
      if (chunks[i].t > now) break;
      if (chunks[i].key) k = i;
    }
    if (k < 0) for (i = 0; i < chunks.length; i++) if (chunks[i].key && chunks[i].t >= now) { k = i; break; }
    fed = k < 0 ? chunks.length : k;
  }

  function pump(now) {
    if (!codec || !window.VideoDecoder) return;
    if (!dec || dec.state === 'closed' || Math.abs(now - lastNow) > 2e6) restartAt(now);
    lastNow = now;
    // Fallen behind the sound: feed from the next keyframe past the clock, with the same decoder
    // (a new one takes a while to get going, and would only fall behind again).
    var n = performance.now();
    if (lastOut >= 0 && lastOut < now - 700000 && n - lastSkip > 2000) {
      for (var i = fed; i < chunks.length; i++) {
        if (chunks[i].key && chunks[i].t > now) { fed = i; target = chunks[i].t; lastSkip = n; stats.skips++; break; }
      }
    }
    while (dec && fed < chunks.length && chunks[fed].t < now + 1000000 && dec.decodeQueueSize < 6 && frames.length < 16) {
      var c = chunks[fed++];
      if (needKey && !c.key) continue;
      needKey = false;
      try {
        dec.decode(new EncodedVideoChunk({ type: c.key ? 'key' : 'delta', timestamp: c.t, data: c.data }));
      } catch (e) { log('feed ' + e); dec = null; break; }
    }
  }

  // Forget frames well behind the clock, keeping the last keyframe before it for small seeks back.
  function prune(now) {
    var k = -1;
    for (var i = 0; i < chunks.length && chunks[i].t < now - 3e6; i++) if (chunks[i].key) k = i;
    if (k > 0 && k <= fed) {
      chunks.splice(0, k).forEach(function (c) { delete seen[c.t]; });
      fed -= k;
    }
  }

  var canvas, ctx, video, told = false, lastLog = 0, lastPrune = 0, err = false;
  function setup() {
    var css = document.createElement('style');
    css.textContent = '.ytp-chrome-top,.ytp-chrome-bottom,.ytp-pause-overlay,.ytp-gradient-top,'
      + '.ytp-gradient-bottom,.ytp-watermark,.ytp-large-play-button,.ytp-spinner,'
      + '.ytp-cued-thumbnail-overlay,.ytp-paid-content-overlay{display:none!important}'
      + 'html,body{background:#000!important}';
    document.head.appendChild(css);
    canvas = document.createElement('canvas');
    canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;object-fit:contain;'
      + 'z-index:2147483647;pointer-events:none;background:#000';
    document.body.appendChild(canvas);
    ctx = canvas.getContext('2d');
  }

  function frame() {
    if (!canvas && document.body) setup();
    var v = document.querySelector('video');
    if (v && canvas) {
      window.__v = v;
      if (!told) {
        told = true;
        v.volume = VOL / 100;
        v.play();
        Tomboy.ready();
        v.addEventListener('loadedmetadata', function () {
          if (START > 3 && v.currentTime < START - 3) v.currentTime = START;
        });
      }
      var now = v.currentTime * 1e6;
      pump(now);
      var pick = null;
      while (frames.length && frames[0].timestamp <= now + 20000) {
        if (pick) { pick.close(); stats.late++; } // came too late to be shown
        pick = frames.shift();
      }
      if (pick) {
        var t0 = performance.now();
        // The canvas at the video's own size, stretched by the page: every decoded pixel kept.
        var fw = pick.displayWidth, fh = pick.displayHeight;
        if (canvas.width !== fw || canvas.height !== fh) { canvas.width = fw; canvas.height = fh; }
        ctx.drawImage(pick, 0, 0, fw, fh);
        if (shown) shown.close();
        shown = pick;
        stats.drawn++;
        stats.ms += performance.now() - t0;
      }
      var n = Date.now();
      if (n - lastPrune > 1000) { lastPrune = n; prune(now); }
      if (n - lastLog > 3000) {
        lastLog = n;
        log('t=' + v.currentTime.toFixed(1) + ' paused=' + v.paused + ' codec=' + codec + ' chunks=' + chunks.length
          + ' decoded=' + stats.dec + ' drawn=' + stats.drawn + ' late=' + stats.late + ' restarts=' + stats.restarts
          + ' skips=' + stats.skips
          + ' queue=' + (dec ? dec.decodeQueueSize : '-') + '/' + frames.length
          + ' drawMs=' + (stats.drawn ? (stats.ms / stats.drawn).toFixed(1) : '-')
          + ' size=' + (shown ? shown.displayWidth + 'x' + shown.displayHeight : '-')
          + ' canvas=' + canvas.width + 'x' + canvas.height + ' dpr=' + devicePixelRatio);
      }
    }
    var er = document.querySelector('.ytp-error');
    if (er && er.offsetParent && !err) { err = true; log('error: ' + er.innerText.slice(0, 80)); Tomboy.error(150); }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
})();
