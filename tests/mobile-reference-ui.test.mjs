import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const root = new URL("../", import.meta.url);
const [html, app, css, serviceWorker, aiBridge, packageJson] = await Promise.all([
  readFile(new URL("templates/index.html", root), "utf8"),
  readFile(new URL("static/app.js", root), "utf8"),
  readFile(new URL("static/mobile-reference.css", root), "utf8"),
  readFile(new URL("static/sw.js", root), "utf8"),
  readFile(new URL("api/minddeck-ai.mjs", root), "utf8"),
  readFile(new URL("package.json", root), "utf8"),
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

test("mobile shell includes a dedicated Plan destination", () => {
  for (const id of ["navDeck", "navGenerate", "navHome", "navStudy", "navPlanner", "navOverall"]) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
  for (const view of ["mobileHomeView", "mobileProgressView", "mobileSettingsView"]) {
    assert.match(html, new RegExp(`id="${view}"`));
  }
  assert.match(html, /<body data-workspace="home">/);
  for (const emoji of ["📚", "✨", "🏡", "🧠", "🗓️", "📊"]) assert.ok(html.includes(emoji));
  assert.match(css, /\.navHome \.glyph \{[^}]*border-radius: 50%/s);
  assert.match(css, /width: min\(calc\(100% - 20px\), 430px\)/);
  assert.match(css, /#navDeck \.glyph::before \{ content: "📚"/);
  assert.match(css, /#navPlanner \.glyph::before \{ content: "🗓️"/);
  assert.match(css, /\.side #navOverall \{ display: none !important; \}/);
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
  assert.match(app, /minddeck-shell-v30-refreshed/);
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
  assert.match(html, /mobile-reference\.css\?v=10/);
  assert.match(html, /app\.js\?v=25/);
  assert.match(serviceWorker, /minddeck-shell-v30/);
  assert.match(serviceWorker, /mobile-reference\.css\?v=10/);
  assert.match(serviceWorker, /app\.js\?v=25/);
  assert.match(css, /@media \(max-width: 760px\)/);
});

test("timer and planner are complete working study tools", () => {
  for (const id of [
    "timerWidget",
    "timerTime",
    "timerToggle",
    "plannerWorkspace",
    "plannerForm",
    "plannerTaskInput",
    "plannerSubject",
    "plannerDate",
    "plannerTime",
    "plannerList",
    "plannerProgress",
  ]) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(app, /const MAX_PLANNER_TASKS = 120/);
  assert.match(app, /planner: plannerTasks/);
  assert.match(app, /function normalizePlannerTasks/);
  assert.match(app, /data-planner-toggle/);
  assert.match(app, /data-planner-focus/);
  assert.match(app, /data-planner-delete/);
  assert.match(app, /\.register\("\/static\/sw\.js\?v=30"/);
  assert.match(css, /body\[data-workspace="planner"\] \.plannerWorkspace \{ display: block; \}/);
});

test("mobile account control always shows an icon or Google profile photo", () => {
  assert.match(html, /id="accountGuestIcon"[\s\S]*class="accountGlyph"/);
  assert.match(html, /id="accountAvatarImage"[^>]*decoding="async"/);
  assert.match(css, /#account \.accountGuestIcon:not\(\[hidden\]\)[\s\S]*display: grid !important/);
  assert.match(css, /#account \.accountAvatar:not\(\[hidden\]\)[\s\S]*display: grid !important/);
  assert.match(css, /#accountAvatarFallback:not\(\[hidden\]\)[\s\S]*display: grid !important/);
  assert.match(app, /const showImage = Boolean\(avatarUrl\)/);
  assert.match(app, /if \(avatarUrl\) accountAvatarImage\.src = avatarUrl/);
  assert.doesNotMatch(app, /avatarUrl && !profile\.avatar/);
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
  assert.match(app, /\/api\/minddeck-ai/);
  assert.match(app, /mode: "generate"/);
  assert.match(app, /Sign in once to generate with MindDeck AI/);
  assert.match(css, /\.aiStatusCard/);
  assert.match(css, /#settingsModal \.aiSettingsDialog \{[^}]*overflow-y: auto;/s);
});

test("the Vercel AI bridge keeps identity and credentials server-side", () => {
  assert.match(aiBridge, /getVercelOidcToken/);
  assert.match(aiBridge, /\/auth\/v1\/user/);
  assert.match(aiBridge, /__Host-minddeck_access/);
  assert.match(aiBridge, /__Host-minddeck_csrf/);
  assert.match(aiBridge, /timingSafeEqual/);
  assert.match(aiBridge, /providerOptions/);
  assert.match(aiBridge, /store: false/);
  assert.match(aiBridge, /MAX_IMAGE_BYTES/);
  assert.doesNotMatch(aiBridge, /Bearer [A-Za-z0-9_-]{20,}/);
  assert.equal(JSON.parse(packageJson).dependencies["@vercel/oidc"], "3.2.0");
});

test("document IDs remain unique", () => {
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
  const duplicates = [...new Set(ids.filter((id, index) => ids.indexOf(id) !== index))];
  assert.deepEqual(duplicates, []);
});
