// Veyra media detector. Read-only: it inspects the DOM and reports media URLs to the app.
// It never modifies the page, injects CSS, or contacts any server.
(function () {
  "use strict";
  var APP = "veyraMedia";
  var MEDIA_EXT = /\.(mp4|m4v|webm|mkv|mov|3gp|ogv|mp3|m4a|aac|ogg|oga|opus|wav|flac|m3u8|mpd)(\?|#|$)/i;
  var lastSent = "";
  var timer = null;

  function abs(u) {
    try { return new URL(u, document.baseURI).href; } catch (e) { return ""; }
  }

  function collect() {
    var items = [];
    var blobs = 0;
    function add(u, tag, title) {
      if (!u) return;
      if (u.indexOf("blob:") === 0 || u.indexOf("mediastream:") === 0) { blobs++; return; }
      var a = abs(u);
      if (a) items.push({ url: a, tag: tag, title: title || "" });
    }
    var title = (document.title || "").slice(0, 120);
    var els = document.querySelectorAll("video, audio");
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var tag = el.tagName.toLowerCase();
      add(el.currentSrc || el.getAttribute("src"), tag, title);
      var sources = el.querySelectorAll("source[src]");
      for (var j = 0; j < sources.length; j++) add(sources[j].getAttribute("src"), tag, title);
    }
    var metas = document.querySelectorAll(
      'meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[property="og:audio"]');
    for (var k = 0; k < metas.length; k++) add(metas[k].getAttribute("content"), "meta", title);
    var links = document.querySelectorAll("a[href]");
    for (var m = 0; m < links.length && m < 400; m++) {
      var h = links[m].getAttribute("href");
      if (h && MEDIA_EXT.test(h)) add(h, "link", (links[m].textContent || "").trim().slice(0, 80) || title);
    }
    return { page: location.href, items: items.slice(0, 40), blobs: blobs };
  }

  function report() {
    timer = null;
    var payload = collect();
    var key = JSON.stringify(payload);
    if (key === lastSent) return;
    lastSent = key;
    try { browser.runtime.sendNativeMessage(APP, payload); } catch (e) { /* app side not ready */ }
  }

  function schedule() {
    if (timer !== null) return;
    timer = setTimeout(report, 1500);
  }

  schedule();
  document.addEventListener("play", schedule, true);
  document.addEventListener("loadedmetadata", schedule, true);
  try {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) { /* ignore */ }
})();
