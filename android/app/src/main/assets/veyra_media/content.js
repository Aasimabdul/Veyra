// Veyra media detector. Inspects DOM/resource timing and reports candidate URLs to the app.
// On YouTube it clicks visible skip-ad controls and hides ad overlays; it does not rip protected streams or contact a server.
(function () {
  "use strict";
  var APP = "veyraMedia";
  var MEDIA_EXT = /\.(mp4|m4v|webm|mkv|mov|3gp|ogv|mp3|m4a|aac|ogg|oga|opus|wav|flac|m3u8|mpd)(\?|#|$)/i;
  var lastSent = "";
  var timer = null;
  var known = Object.create(null);
  var seenBlobs = Object.create(null);
  var adMediaSkipped = 0;

  function abs(u) {
    try { return new URL(u, document.baseURI).href; } catch (e) { return ""; }
  }

  function collect() {
    var items = [];
    var blobs = 0;
    adMediaSkipped = 0;
    function add(u, tag, title, mime) {
      if (!u) return;
      if (u.indexOf("blob:") === 0 || u.indexOf("mediastream:") === 0) {
        if (!seenBlobs[u]) { seenBlobs[u] = true; blobs++; }
        return;
      }
      var a = abs(u);
      if (a && !known[a]) known[a] = { url: a, tag: tag, title: title || "", mime: mime || "" };
      else if (a && known[a]) {
        if (!known[a].title && title) known[a].title = title;
        if (!known[a].mime && mime) known[a].mime = mime;
      }
    }
    function isYoutubeAdShowing() {
      if (!/youtube\.com$|youtube-nocookie\.com$/.test(location.hostname)) return false;
      return !!document.querySelector(".html5-video-player.ad-showing, .ytp-ad-player-overlay, .video-ads");
    }
    function isAdVideo(el) {
      return isYoutubeAdShowing() && !!el.closest(".html5-video-player.ad-showing, .ytp-ad-player-overlay, .video-ads");
    }
    var title = (document.title || "").slice(0, 120);
    var els = document.querySelectorAll("video, audio");
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (isAdVideo(el)) { adMediaSkipped++; continue; }
      var tag = el.tagName.toLowerCase();
      add(el.currentSrc || el.getAttribute("src"), tag, title, el.getAttribute("type") || "");
      var sources = el.querySelectorAll("source[src]");
      for (var j = 0; j < sources.length; j++) {
        add(sources[j].getAttribute("src"), tag, title, sources[j].getAttribute("type") || "");
      }
      ["data-src", "data-video-src", "data-url"].forEach(function (attr) {
        add(el.getAttribute(attr), tag, title);
      });
    }
    try {
      var resources = performance.getEntriesByType("resource");
      if (!isYoutubeAdShowing()) {
        for (var ri = Math.max(0, resources.length - 250); ri < resources.length; ri++) {
          var ru = resources[ri].name || "";
          if (/\.(mp4|m4v|webm|mkv|mov|3gp|ogv|mp3|m4a|aac|ogg|oga|opus|wav|flac|m3u8|mpd)(\?|#|$)/i.test(ru)) {
            add(ru, /\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)(\?|#|$)/i.test(ru) ? "audio" : "video", title);
          }
        }
      }
    } catch (e) { /* timing entries are optional */ }
    var metas = document.querySelectorAll(
      'meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[property="og:audio"]');
    for (var k = 0; k < metas.length; k++) add(metas[k].getAttribute("content"), "meta", title);
    var links = document.querySelectorAll("a[href]");
    for (var m = 0; m < links.length && m < 400; m++) {
      var h = links[m].getAttribute("href");
      if (h && MEDIA_EXT.test(h)) add(h, "link", (links[m].textContent || "").trim().slice(0, 80) || title);
    }
    var all = Object.keys(known).map(function (key) { return known[key]; });
    return { page: location.href, items: all.slice(-60), blobs: Object.keys(seenBlobs).length, adMediaSkipped: adMediaSkipped };
  }

  function handleSkippableYouTubeAd() {
    if (!/youtube\.com$|youtube-nocookie\.com$/.test(location.hostname)) return;
    try {
      var skip = document.querySelector(".ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern");
      if (skip && !skip.disabled && skip.getClientRects().length) skip.click();
      var overlays = document.querySelectorAll(".ytp-ad-overlay-container, .ytp-ad-text-overlay, .ytp-ad-player-overlay");
      for (var i = 0; i < overlays.length; i++) overlays[i].style.setProperty("display", "none", "important");
    } catch (e) { /* site UI changes are expected */ }
  }

  function report() {
    timer = null;
    handleSkippableYouTubeAd();
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
  document.addEventListener("playing", schedule, true);
  document.addEventListener("timeupdate", schedule, true);
  try {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) { /* ignore */ }
})();
