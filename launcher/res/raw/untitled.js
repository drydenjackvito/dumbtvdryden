// Untitled in Tomboy (see Radio.java): reports what the page plays to Android, and takes the
// remote's buttons and the music ceiling. Safe to run more than once on a page.
(function () {
  if (window.__tomboy) return;
  window.__tomboy = true;
  var T = window.TomboyRadio;
  if (!T) return;

  // Every audio or video element that has played, in the page or not (new Audio()).
  var players = [];
  var cap = 1;
  var proto = HTMLMediaElement.prototype;
  var vol = Object.getOwnPropertyDescriptor(proto, 'volume');
  function remember(el) {
    if (players.indexOf(el) < 0) {
      players.push(el);
      if (el.__want === undefined) el.__want = vol.get.call(el);
      vol.set.call(el, Math.max(0, Math.min(1, el.__want * cap)));
      ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'durationchange'].forEach(function (n) {
        el.addEventListener(n, function () { report(); });
      });
    }
  }
  // The site's own volume is kept as asked; what plays is that times the ceiling.
  Object.defineProperty(proto, 'volume', {
    configurable: true,
    get: function () { return this.__want === undefined ? vol.get.call(this) : this.__want; },
    set: function (v) {
      this.__want = v;
      vol.set.call(this, Math.max(0, Math.min(1, v * cap)));
    }
  });
  var play = proto.play;
  proto.play = function () {
    remember(this);
    return play.apply(this, arguments);
  };
  Array.prototype.forEach.call(document.querySelectorAll('audio,video'), remember);

  // The site's Media Session: its song and its next / previous buttons. Made up when the web view
  // lacks one, so the site still hands them over.
  var handlers = {};
  var ms = navigator.mediaSession;
  if (!window.MediaMetadata) {
    window.MediaMetadata = function (m) {
      m = m || {};
      this.title = m.title || '';
      this.artist = m.artist || '';
      this.album = m.album || '';
      this.artwork = m.artwork || [];
    };
  }
  if (!ms) {
    ms = { metadata: null, playbackState: 'none', setPositionState: function () {} };
    try { Object.defineProperty(navigator, 'mediaSession', { value: ms, configurable: true }); } catch (e) {}
  }
  var setHandler = ms.setActionHandler;
  ms.setActionHandler = function (action, fn) {
    handlers[action] = fn;
    if (setHandler) {
      try { return setHandler.apply(ms, arguments); } catch (e) {}
    }
  };

  function current() {
    var best = null;
    for (var i = 0; i < players.length; i++) {
      var p = players[i];
      if (!p.paused && !p.ended) return p;
      if (!best && p.currentSrc && p.isConnected !== false) best = p;
    }
    return best || players[players.length - 1] || null;
  }

  function art(md) {
    var list = (md && md.artwork) || [];
    var pick = null, size = 0;
    for (var i = 0; i < list.length; i++) {
      var s = parseInt(String(list[i].sizes || '0').split('x')[0], 10) || 1;
      if (!pick || s > size) { pick = list[i]; size = s; }
    }
    if (pick && pick.src) return new URL(pick.src, location.href).href;
    return '';
  }

  // Without a Media Session song: whatever the page shows as playing.
  function guess() {
    var t = document.title || '';
    t = t.replace(/\s*[|–—-]\s*untitled.*$/i, '').trim();
    return { title: t, artist: '', album: '', artwork: [] };
  }

  var last = '';
  function report() {
    var p = current();
    var md = ms.metadata || (p ? guess() : null);
    var state = p ? (p.ended ? 'ended' : p.paused ? 'paused' : 'playing') : 'none';
    var msg = [
      md ? md.title || '' : '',
      md ? md.artist || '' : '',
      md ? md.album || '' : '',
      art(md),
      state,
      p && isFinite(p.duration) ? Math.round(p.duration * 1000) : 0,
      p ? Math.round(p.currentTime * 1000) : 0
    ];
    var key = msg.slice(0, 5).join('\n') + Math.round(msg[6] / 5000);
    if (key === last) return;
    last = key;
    T.state(msg[0], msg[1], msg[2], msg[3], msg[4], msg[5], msg[6]);
  }
  setInterval(report, 1000);

  // A button on the page whose label reads like the action, for a site with no handler for it.
  function press(words) {
    var all = document.querySelectorAll('button,[role=button],[aria-label]');
    for (var i = 0; i < all.length; i++) {
      var l = (all[i].getAttribute('aria-label') || all[i].title || '').toLowerCase();
      for (var j = 0; j < words.length; j++) {
        if (l.indexOf(words[j]) >= 0) { all[i].click(); return true; }
      }
    }
    return false;
  }

  window.__tomboyDo = function (what, arg) {
    var p = current();
    if (what === 'cap') {
      cap = arg;
      players.forEach(function (el) {
        vol.set.call(el, Math.max(0, Math.min(1, (el.__want === undefined ? 1 : el.__want) * cap)));
      });
      return;
    }
    if (what === 'play' || what === 'pause') {
      if (handlers[what]) { handlers[what]({ action: what }); return; }
      if (p) { if (what === 'play') p.play(); else p.pause(); return; }
      press([what]);
      return;
    }
    if (what === 'next') {
      if (handlers.nexttrack) handlers.nexttrack({ action: 'nexttrack' });
      else press(['next', 'skip forward']);
      return;
    }
    if (what === 'previous') {
      if (handlers.previoustrack) handlers.previoustrack({ action: 'previoustrack' });
      else if (p && p.currentTime > 3) p.currentTime = 0;
      else press(['previous', 'prev', 'back']);
      return;
    }
    if (what === 'seek' && p) {
      p.currentTime = Math.max(0, arg / 1000);
    }
  };
  report();
})();
