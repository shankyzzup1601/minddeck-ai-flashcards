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
  assert.match(html, /minddeck:onboarding-complete-v1/);
  assert.doesNotMatch(html, /id="splash-actions" hidden/);
  assert.doesNotMatch(html, /id="track"/);
  assert.doesNotMatch(html, /window\.setInterval\(\(\) => \{\s*progress/);
  assert.match(app, /const ONBOARDING_STORE = "minddeck:onboarding-complete-v1"/);
  assert.match(app, /localStorage\.setItem\(ONBOARDING_STORE, "1"\)/);
  assert.match(app, /if \(signedIn\) \{\s*markOnboardingComplete\(\);/);
});

test("mobile shell includes the five reference destinations", () => {
  for (const id of ["navDeck", "navGenerate", "navHome", "navStudy", "navOverall"]) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
  for (const view of ["mobileHomeView", "mobileProgressView", "mobileSettingsView"]) {
    assert.match(html, new RegExp(`id="${view}"`));
  }
  assert.match(html, /<body data-workspace="home">/);
  for (const emoji of ["📚", "✨", "🏡", "🧠", "📊"]) assert.ok(html.includes(emoji));
  assert.match(css, /\.navHome \.glyph \{[^}]*border-radius: 50%/s);
  assert.match(css, /width: min\(calc\(100% - 20px\), 430px\)/);
  assert.match(css, /#navDeck \.glyph::before \{ content: "📚"/);
  assert.match(css, /body \.app > \.side \{[^}]*left: 50% !important;[^}]*transform: translate3d\(-50%, 0, 0\) !important;/s);
  assert.match(css, /overflow-x: clip/);
});

test("My Deck has a guided empty state and reliable account fallback", () => {
  for (const id of [
    "deckGenerateAction",
    "deckAddAction",
    "deckMetricTotal",
    "deckMetricDue",
    "deckMetricMastered",
    "accountGuestIcon",
  ]) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(app, /deckEmptyState/);
  assert.match(app, /dataset\.deckEmptyAction/);
  assert.match(app, /accountAvatarImage\.onload/);
  assert.match(app, /updateViaCache: "none"/);
  assert.match(app, /minddeck-shell-v26-refreshed/);
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
  assert.match(html, /mobile-reference\.css\?v=7/);
  assert.match(html, /app\.js\?v=21/);
  assert.match(serviceWorker, /minddeck-shell-v26/);
  assert.match(serviceWorker, /mobile-reference\.css\?v=7/);
  assert.match(serviceWorker, /app\.js\?v=21/);
  assert.match(css, /@media \(max-width: 760px\)/);
});

test("generation presents one built-in MindDeck AI experience", () => {
  assert.match(html, /id="settingsTitle">MindDeck AI</);
  assert.match(html, /id="aiStatusBadge"/);
  assert.match(html, /Notes to cards/);
  assert.match(html, /Photo understanding/);
  assert.match(html, /Smart hints/);
  assert.match(html, /Generate 15 with AI/);
  assert.doesNotMatch(html, /Owner access code/);
  assert.doesNotMatch(html, /Unlock selected AI/);
  assert.doesNotMatch(html, /Secure OpenAI/);
  assert.doesNotMatch(html, /Secure Gemini/);
  assert.match(app, /provider: "minddeck"/);
  assert.match(app, /Sign in once to generate with MindDeck AI/);
  assert.match(css, /\.aiStatusCard/);
});

test("document IDs remain unique", () => {
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
  const duplicates = [...new Set(ids.filter((id, index) => ids.indexOf(id) !== index))];
  assert.deepEqual(duplicates, []);
});
