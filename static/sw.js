const CACHE_NAME = "minddeck-shell-v38";
const SHELL = [
  "/",
  "/static/app.js?v=33",
  "/static/mobile-reference.css?v=12",
  "/static/cbse-syllabus.js?v=1",
  "/static/smart-study.js",
  "/static/minddeck-icon.svg?v=2",
  "/static/manifest.webmanifest?v=2",
  "/static/offline.html",
  "/static/offline.css",
  "/static/offline.js",
  "/static/vendor/pdf-4.10.38.min.mjs",
  "/static/vendor/pdf-4.10.38.worker.min.mjs",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
      .then(() => self.clients.matchAll({ type: "window", includeUncontrolled: true }))
      .then((clients) => Promise.all(clients.map((client) => client.navigate(client.url).catch(() => {}))))
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/")) {
    return;
  }
  if (event.request.mode === "navigate") {
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          const isCleanHome = url.pathname === "/" && !url.search;
          if (response.ok && isCleanHome) {
            const copy = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put("/", copy)).catch(() => {});
          }
          return response;
        })
        .catch(async () => (await caches.match("/")) || caches.match("/static/offline.html"))
    );
    return;
  }
  event.respondWith(
    caches.match(event.request).then((cached) => {
      const refreshed = fetch(event.request).then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy)).catch(() => {});
        }
        return response;
      });
      if (cached) {
        event.waitUntil(refreshed.catch(() => {}));
        return cached;
      }
      return refreshed;
    })
  );
});
