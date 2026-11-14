// Planted into every page before its own scripts run: the bridge the web app looks for (window.pleos, version 1).
// Android's @JavascriptInterface is synchronous and string-only, so each call hands a request id to the native side,
// which answers later through window.__pleosResolve / window.__pleosReject.
(function () {
  if (window.pleos) return;
  var pending = {}, seq = 0;
  function ask(kind, args) {
    return new Promise(function (resolve, reject) {
      var id = String(++seq);
      pending[id] = { resolve: resolve, reject: reject };
      try { PleosNative.request(kind, id, JSON.stringify(args || null)); }
      catch (error) { delete pending[id]; reject(error); }
    });
  }
  window.__pleosResolve = function (id, json) { var p = pending[id]; if (!p) return; delete pending[id]; p.resolve(JSON.parse(json)); };
  window.__pleosReject = function (id, message) { var p = pending[id]; if (!p) return; delete pending[id]; p.reject(new Error(message)); };
  window.pleos = {
    version: 1,
    vehicle: function () { return ask("vehicle"); },
    // 2026-11-02 (still version 1, only additions): the car's whole trip-relevant state, and the car's own voice.
    car: function () { return ask("car"); },
    speak: function (text) { return ask("speak", { text: text }); },
    // the car's speech recognition: the final words ({ text }); partial words come as window "pleos-stt" events
    listen: function () { return ask("listen"); },
    stopListening: function () { return ask("listen-stop"); },
    // the trip's stops watched by the car (FusedLocation proximity): window "pleos-arrive" events { id, transition }
    watchStops: function (stops, radius) { return ask("watch-stops", { stops: stops, radius: radius || 300 }); },
    unwatchStops: function () { return ask("unwatch-stops"); },
    location: function () { return ask("location"); },
    navigate: function (stops) { return ask("navigate", stops); }
  };
})();
