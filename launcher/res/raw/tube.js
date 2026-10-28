// Runs first in YouTube's embed page inside Tomboy's video window (see VideoWindow.java).
// The TV's hardware decoder draws on the video plane the HDMI input holds, so the page's own
// video shows black over a game. This catches the compressed video YouTube hands its player and
// passes each frame, with the player's clock, to Tomboy, which decodes it with Android's own
// software decoder into a view of its own (NativeVideo.java). The page's video keeps playing,
// unseen, for the sound and the clock.
(function () {
  if (window.__tb) return;
  window.__tb = 1;
  var SEND = __SEND__, START = __START__, VOL = __VOL__, MAX_W = __MAXW__, MAX_H = __MAXH__, QUALITY = __QUALITY__;
  var sent = 0;
  function log(s) { try { Tomboy.log(s); } catch (e) {} }

  // 1. The quality. With YouTube's own player (decoded by the TV's hardware), ask it for the
  // quality set in Options: stored as its own preference before it starts, and set again on the
  // player once it is there. For Tomboy's decoder (software), steer YouTube to VP9 at 480p or
  // less: the TV's processor is mostly taken next to an HDMI input.
  var LABEL = { 1080: 'hd1080', 720: 'hd720', 480: 'large', 360: 'medium' }[QUALITY] || 'hd720';
  if (!SEND) {
    try {
      var now0 = Date.now();
      localStorage.setItem('yt-player-quality', JSON.stringify({
        data: JSON.stringify({ quality: QUALITY, previousQuality: QUALITY }),
        expiration: now0 + 30 * 86400000, creation: now0 }));
    } catch (e) { log('quality preference ' + e); }
  } else {
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

  }

  // 2. The video bytes as YouTube appends them: WebM, split into frames, each passed to Tomboy.
  var codec = null, pend = new Uint8Array(0), clusterTime = 0, tick = 1000; // µs per timecode tick

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
  function b64(d) {
    var s = '';
    for (var i = 0; i < d.length; i += 8192) s += String.fromCharCode.apply(null, d.subarray(i, i + 8192));
    return btoa(s);
  }

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
        var data = b.subarray(q + 3, s + sz.v);
        var key = ID === 0xA3 ? !!(b[q + 2] & 0x80) : vp9Key(data);
        if (SEND) Tomboy.chunk(b64(data), Math.round((clusterTime + rel) * tick) + off, key);
        sent++;
      }
      p = s + sz.v;
    }
    pend = b.slice(p);
  }

  var asb = MediaSource.prototype.addSourceBuffer;
  MediaSource.prototype.addSourceBuffer = function (t) {
    var sb = asb.call(this, t);
    if (/^video\/webm/i.test(t)) { sb.__tbv = 1; codec = t; log('video track ' + t); }
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

  // 3. The player: its clock to Tomboy, its overlays hidden, its errors reported.
  var told = false, err = false, lastLog = 0;
  function tellClock(v) { Tomboy.clock(v.currentTime, v.paused || v.seeking); }
  function setup() {
    var css = document.createElement('style');
    css.textContent = '.ytp-chrome-top,.ytp-chrome-bottom,.ytp-pause-overlay,.ytp-gradient-top,'
      + '.ytp-gradient-bottom,.ytp-watermark,.ytp-large-play-button,.ytp-spinner,'
      + '.ytp-cued-thumbnail-overlay,.ytp-paid-content-overlay{display:none!important}'
      + 'html,body{background:#000!important}';
    document.head.appendChild(css);
  }
  var looked = setInterval(function () {
    if (!document.head) return;
    var v = document.querySelector('video');
    if (v && !told) {
      told = true;
      setup();
      window.__v = v;
      v.volume = VOL / 100;
      v.play();
      Tomboy.ready();
      ['timeupdate', 'play', 'playing', 'pause', 'seeking', 'seeked', 'ratechange', 'waiting']
        .forEach(function (e) { v.addEventListener(e, function () { tellClock(v); }); });
      v.addEventListener('loadedmetadata', function () {
        if (START > 3 && v.currentTime < START - 3) v.currentTime = START;
      });
      setInterval(function () { tellClock(v); }, 250);
      if (!SEND) {
        var tries = 0, set = setInterval(function () {
          var p = document.getElementById('movie_player');
          if (p && p.setPlaybackQualityRange) { p.setPlaybackQualityRange(LABEL, LABEL); log('quality asked: ' + LABEL); }
          if (++tries > 10) clearInterval(set);
        }, 1000);
      }
    }
    var er = document.querySelector('.ytp-error');
    if (er && er.offsetParent && !err) { err = true; log('error: ' + er.innerText.slice(0, 80)); Tomboy.error(150); }
    var n = Date.now();
    if (v && n - lastLog > 3000) {
      lastLog = n;
      var mp = document.getElementById('movie_player');
      log('t=' + v.currentTime.toFixed(1) + ' paused=' + v.paused + ' codec=' + codec + ' sent=' + sent
        + ' size=' + v.videoWidth + 'x' + v.videoHeight
        + ' quality=' + (mp && mp.getPlaybackQuality ? mp.getPlaybackQuality() : '-')
        + ' page=' + innerWidth + 'x' + innerHeight + ' dpr=' + devicePixelRatio);
    }
  }, 200);
})();
