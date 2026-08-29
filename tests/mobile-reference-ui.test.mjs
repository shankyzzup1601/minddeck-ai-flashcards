import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const root = new URL("../", import.meta.url);
const [html, app, css, serviceWorker] = await Promise.all([
  readFile(new URL("templates/index.html", root), "utf8"),
  readFile(new URL("static/app.js", root), "utf8"),
  readFile(new URL("static/mobile-reference.css", root), "utf8"),
  readFile(new URL("static/sw.js", root), "utf8"),
]);

test("mobile launch starts with account choices", () => {
  assert.match(html, /id="begin-text"[^>]*>GET STARTED</);
  assert.match(html, /id="splash-sign-in"[^>]*>I ALREADY HAVE AN ACCOUNT</);
  assert.match(html, /id="authFullName"/);
  assert.match(html, /id="authEmail"/);
  assert.match(html, /id="authPassword"/);
  assert.match(html, /id="authReferral"/);
  assert.match(app, /minddeck:first-run/);
  assert.match(app, /openAuthFlow\(event\.detail\?\.mode \|\| "signup"\)/);
});

test("mobile shell includes the five reference destinations", () => {
  for (const id of ["navDeck", "navGenerate", "navHome", "navStudy", "navOverall"]) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
  for (const view of ["mobileHomeView", "mobileProgressView", "mobileSettingsView"]) {
    assert.match(html, new RegExp(`id="${view}"`));
  }
  assert.match(html, /<body data-workspace="home">/);
});

test("Science and Commerce shelves include complete subject shortcuts", () => {
  assert.match(html, /aria-label="Science subjects"/);
  assert.match(html, /aria-label="Commerce subjects"/);
  for (const subject of [
    "physics",
    "physical-chemistry",
    "mathematics",
    "biology",
    "accountancy",
    "business-studies",
    "economics",
    "entrepreneurship",
  ]) {
    assert.match(html, new RegExp(`data-subject-filter="${subject}"`));
    assert.ok(app.includes(subject), `${subject} should be wired in app.js`);
  }
});

test("mobile UI assets are versioned and available offline", () => {
  assert.match(html, /mobile-reference\.css\?v=2/);
  assert.match(html, /app\.js\?v=17/);
  assert.match(serviceWorker, /minddeck-shell-v21/);
  assert.match(serviceWorker, /mobile-reference\.css\?v=2/);
  assert.match(serviceWorker, /app\.js\?v=17/);
  assert.match(css, /@media \(max-width: 760px\)/);
});

test("document IDs remain unique", () => {
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
  const duplicates = [...new Set(ids.filter((id, index) => ids.indexOf(id) !== index))];
  assert.deepEqual(duplicates, []);
});
