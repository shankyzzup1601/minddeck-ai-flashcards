import {
  STARTER_DECKS,
  buildOfflineHints,
  clozeBack,
  clozeFront,
  compareExplanation,
  createClozeDrafts,
  decodeDeckShare,
  deleteImageAsset,
  encodeDeckShare,
  extractTextWithBrowserOcr,
  fileToDataUrl,
  loadDeckBackup,
  loadImageAsset,
  normalizeEnhancements,
  renderRichText,
  saveDeckBackup,
  saveImageAsset,
} from "./smart-study.js";

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];

const GUEST_STORE = "minddeck-v2";
const CSRF_TOKEN = document.querySelector('meta[name="csrf-token"]')?.content || "";
const PDF_MODULE = "/static/vendor/pdf-4.10.38.min.mjs";
const PDF_WORKER = "/static/vendor/pdf-4.10.38.worker.min.mjs";
const FOCUS_STORE = "minddeck-focus-timer-v1";
const THEME_STORE = "minddeck-visual-theme-v1";
const CLASS_STORE = "minddeck-class-v1";
const PROFILE_STORE = "minddeck-mobile-profile-v1";
const MOBILE_SETTINGS_STORE = "minddeck-mobile-settings-v1";
const ONBOARDING_STORE = "minddeck:onboarding-complete-v1";
const GENERATED_DECK_SIZE = 15;
const DECK_SCHEMA_VERSION = 6;
const THEMES = Object.freeze([
  { key: "cosmic", label: "Midnight" },
  { key: "aurora", label: "Terminal" },
  { key: "rose", label: "Crimson" },
]);
const TIMER_MODES = Object.freeze({
  focus: { label: "Focus sprint", duration: 25 * 60, isFocus: true },
  break: { label: "Quick reset", duration: 5 * 60, isFocus: false },
  deep: { label: "Deep focus", duration: 50 * 60, isFocus: true },
});
const TEMPLATE_LABELS = Object.freeze({
  basic: "Recall",
  ncert: "NCERT Cloze",
  reaction: "Reaction Mechanism",
  formula: "Formula · Unit · Dimension",
  journal: "Journal Entry",
  graph: "Graph Flip",
  assertion: "Assertion–Reasoning",
  derivation: "Derivation",
});
const SUBJECT_WORKSPACES = Object.freeze({
  physics: { label: "Physics", subject: "Physics", aliases: ["physics"] },
  "physical-chemistry": {
    label: "Chemistry",
    subject: "Chemistry",
    aliases: ["chemistry", "physical chemistry", "organic chemistry", "inorganic chemistry", "thermodynamics", "electrochemistry", "chemical kinetics"],
  },
  mathematics: {
    label: "Mathematics",
    subject: "Mathematics",
    aliases: ["mathematics", "maths", "calculus", "algebra", "trigonometry", "probability", "statistics"],
  },
  biology: { label: "Biology", subject: "Biology", aliases: ["biology", "botany", "zoology"] },
  accountancy: { label: "Accountancy", subject: "Accountancy", aliases: ["accountancy", "accounts"] },
  "business-studies": {
    label: "Business Studies",
    subject: "Business Studies",
    aliases: ["business studies", "business study", "business organisation", "marketing management"],
  },
  economics: {
    label: "Economics",
    subject: "Economics",
    aliases: ["economics", "microeconomics", "macroeconomics", "national income", "demand", "supply"],
  },
  entrepreneurship: {
    label: "Entrepreneurship",
    subject: "Entrepreneurship",
    aliases: ["entrepreneurship", "entrepreneur", "enterprise development", "startup planning"],
  },
});

let cards = [];
let index = 0;
let reviewed = new Set();
let fileText = "";
let photoFile = null;
let photoPreviewUrl = "";
let aiProviders = { minddeck: false, openai: false, gemini: false };
let aiRuntime = "flask";
let deckUpdatedAt = 0;
let authState = { enabled: false, googleEnabled: false, user: null };
let syncTimer = null;
let syncInFlight = false;
let activeStoreKey = GUEST_STORE;
let activeAccountKey = null;
let accountStoreFresh = false;
let removeGuestAfterSync = false;
let studyStats = defaultStudyStats();
let timerState = defaultTimerState();
let timerInterval = null;
let activeTheme = THEMES[0].key;
let currentImageUrl = "";
let hintStep = 0;
let feynmanRecorder = null;
let feynmanStream = null;
let feynmanRecognition = null;
let feynmanTimer = null;
let feynmanAudioUrl = "";
let feynmanStartedAt = 0;
let feynmanChunks = [];
let feynmanCardId = "";
let pendingOcclusionFile = null;
let pendingOcclusions = [];
let pendingOcclusionUrl = "";
let matchTimer = null;
let matchState = null;
let studyQueueIds = [];
let examRevealCount = 0;

function setWorkspace(workspace, scroll = true) {
  const target = ["home", "generate", "study", "deck", "planner", "overall"].includes(workspace)
    ? workspace
    : "home";
  document.body.dataset.workspace = target;
  $$('[data-workspace-target]').forEach((button) => {
    const active = button.dataset.workspaceTarget === target;
    button.classList.toggle("active", active);
    if (active) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  const headings = {
    home: ["MindDeck Home", "Your complete study plan for today."],
    generate: ["Create Flashcards", "Paste your notes and create 15 clear questions."],
    study: ["Study", "Flip the card, recall the answer, then choose a rating."],
    deck: ["My Deck", "View, review, or remove your saved questions."],
    planner: ["Daily Planner", "Choose a study time, start the timer, and track today’s progress."],
    overall: ["Overall Progress", "Track mastery, study rhythm, and memory readiness."],
  };
  const [title, subtitle] = headings[target];
  $(".top h1").textContent = title;
  $(".top p").textContent = subtitle;
  if (scroll) window.scrollTo({ top: 0, behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
}

function spatialMotionEnabled() {
  return window.matchMedia("(pointer: fine)").matches && !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

function bindDepthSurface(surface) {
  if (!surface || !spatialMotionEnabled()) return;
  const reset = () => {
    surface.style.setProperty("--surface-rx", "0deg");
    surface.style.setProperty("--surface-ry", "0deg");
    surface.style.setProperty("--surface-shine-x", "50%");
    surface.style.setProperty("--surface-shine-y", "20%");
  };
  surface.addEventListener("pointermove", (event) => {
    const bounds = surface.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return;
    const x = Math.min(1, Math.max(0, (event.clientX - bounds.left) / bounds.width));
    const y = Math.min(1, Math.max(0, (event.clientY - bounds.top) / bounds.height));
    surface.style.setProperty("--surface-rx", `${((0.5 - y) * 7).toFixed(2)}deg`);
    surface.style.setProperty("--surface-ry", `${((x - 0.5) * 9).toFixed(2)}deg`);
    surface.style.setProperty("--surface-shine-x", `${Math.round(x * 100)}%`);
    surface.style.setProperty("--surface-shine-y", `${Math.round(y * 100)}%`);
  });
  surface.addEventListener("pointerleave", reset);
  surface.addEventListener("pointercancel", reset);
}

function setupSpatialUi() {
  if (!spatialMotionEnabled()) return;
  $$(".subjectChip,.heroCard .stat,.smartTool,.quickAction").forEach(bindDepthSurface);
  const scene = $("#scene");
  if (!scene) return;
  const resetCardDepth = () => {
    scene.style.setProperty("--card-rx", "0deg");
    scene.style.setProperty("--card-ry", "0deg");
    scene.style.setProperty("--card-glare-x", "50%");
    scene.style.setProperty("--card-glare-y", "24%");
  };
  scene.addEventListener("pointermove", (event) => {
    const bounds = scene.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return;
    const x = Math.min(1, Math.max(0, (event.clientX - bounds.left) / bounds.width));
    const y = Math.min(1, Math.max(0, (event.clientY - bounds.top) / bounds.height));
    scene.style.setProperty("--card-rx", `${((0.5 - y) * 8).toFixed(2)}deg`);
    scene.style.setProperty("--card-ry", `${((x - 0.5) * 11).toFixed(2)}deg`);
    scene.style.setProperty("--card-glare-x", `${Math.round(x * 100)}%`);
    scene.style.setProperty("--card-glare-y", `${Math.round(y * 100)}%`);
  });
  scene.addEventListener("pointerleave", resetCardDepth);
  scene.addEventListener("pointercancel", resetCardDepth);
}

function setCardFlipped(flipped) {
  const cardElement = $("#card");
  const scene = $("#scene");
  if (!cardElement) return;
  if (scene) {
    scene.style.setProperty("--card-rx", "0deg");
    scene.style.setProperty("--card-ry", "0deg");
    scene.style.setProperty("--card-glare-x", "50%");
    scene.style.setProperty("--card-glare-y", "24%");
  }
  cardElement.classList.toggle("flip", flipped);
  cardElement.setAttribute("aria-expanded", String(flipped));
  cardElement.setAttribute(
    "aria-label",
    flipped
      ? "Flashcard answer shown. Click or press Space to return to the question."
      : "Flashcard question. Click or press Space to reveal the answer."
  );
}

function toggleCardFlip() {
  if (!cards.length) return;
  setCardFlipped(!$("#card").classList.contains("flip"));
}

function subjectMatches(card, workspaceKey) {
  const config = SUBJECT_WORKSPACES[workspaceKey];
  if (!card || !config) return false;
  const searchable = [card.subject, ...(card.examTags || []), card.front].join(" ").toLowerCase();
  return config.aliases.some((alias) => searchable.includes(alias));
}

function renderSubjectCounts() {
  const now = Date.now();
  for (const [workspaceKey] of Object.entries(SUBJECT_WORKSPACES)) {
    const matching = cards.filter((card) => subjectMatches(card, workspaceKey));
    const due = matching.filter((card) => Date.parse(card.dueDate) <= now).length;
    const label = $(`[data-subject-count="${workspaceKey}"]`);
    if (label) label.textContent = due ? `${due} due · ${matching.length} cards` : `${matching.length} cards`;
  }
}

function selectedGenerationMetadata(cardMode) {
  const classLevel = $("#classSelect").value;
  const stream = $("#streamSelect").value;
  const subject = $("#subjectSelect").value;
  const chapter = $("#chapterTag").value.trim();
  const selectedTags = $$(".examTagInput:checked").map((input) => input.value);
  if (cardMode === "formula" && !selectedTags.includes("Formula Only")) selectedTags.push("Formula Only");
  if (cardMode === "derivation" && !selectedTags.includes("3-Mark Board Derivation")) {
    selectedTags.push("3-Mark Board Derivation");
  }
  return { classLevel, stream, subject, chapter, selectedTags };
}

function applyGenerationMetadata(generated, cardMode) {
  const { classLevel, stream, subject, chapter, selectedTags } = selectedGenerationMetadata(cardMode);
  const requestedTemplate = ["ncert", "formula", "assertion", "reaction", "journal", "derivation"].includes(cardMode)
    ? cardMode
    : "";
  return generated.map((card) => {
    card.subject = card.subject || subject;
    card.examTags = [...new Set([...(card.examTags || []), classLevel, stream, chapter, ...selectedTags].filter(Boolean))].slice(0, 6);
    if (requestedTemplate && card.template === "basic") card.template = requestedTemplate;
    if (selectedTags.includes("NCERT Exception / Trap")) card.trap = true;
    return card;
  });
}

function restoreClassLevel() {
  const classSelect = $("#classSelect");
  if (!classSelect) return;
  try {
    const savedClass = localStorage.getItem(CLASS_STORE);
    if ([...classSelect.options].some((option) => option.value === savedClass)) classSelect.value = savedClass;
  } catch {
    // The selector remains usable if local storage is unavailable.
  }
}

function applyTheme(themeKey, announce = false) {
  const theme = THEMES.find((item) => item.key === themeKey) || THEMES[0];
  activeTheme = theme.key;
  document.documentElement.dataset.theme = theme.key;
  const label = $("#themeName");
  const button = $("#themeToggle");
  if (label) label.textContent = theme.label;
  if (button) button.setAttribute("aria-label", `Visual theme: ${theme.label}. Activate to switch.`);
  try {
    localStorage.setItem(THEME_STORE, theme.key);
  } catch {
    // A private browsing policy may block persistence; the active tab still updates.
  }
  if (announce) toast(`${theme.label} palette active`);
}

function loadTheme() {
  let storedTheme = THEMES[0].key;
  try {
    storedTheme = localStorage.getItem(THEME_STORE) || storedTheme;
  } catch {
    // Use the default palette when storage is unavailable.
  }
  applyTheme(storedTheme);
}

function cycleTheme() {
  const currentIndex = THEMES.findIndex((theme) => theme.key === activeTheme);
  const nextTheme = THEMES[(currentIndex + 1) % THEMES.length];
  applyTheme(nextTheme.key, true);
}

function newCard(front, back, enhancements = {}) {
  return {
    id: Date.now().toString(36) + Math.random().toString(36).slice(2),
    front: front.trim().slice(0, 500),
    back: back.trim().slice(0, 2000),
    interval: 0,
    repetition: 0,
    easeFactor: 2.5,
    dueDate: new Date().toISOString(),
    reviews: 0,
    ...normalizeEnhancements(enhancements),
  };
}

function finiteNumber(value, fallback, minimum, maximum) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.min(maximum, Math.max(minimum, number)) : fallback;
}

function defaultStudyStats() {
  return { totalSeconds: 0, sessions: 0, dailyGoalMinutes: 25, dailyFocus: [], dailyReviews: [] };
}

function normalizeStudyStats(value) {
  if (!value || typeof value !== "object") return defaultStudyStats();
  const dailyByDate = new Map();
  const rawDaily = Array.isArray(value.dailyFocus) ? value.dailyFocus.slice(-90) : [];
  for (const item of rawDaily) {
    if (!item || typeof item !== "object" || typeof item.date !== "string") continue;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(item.date) || !Number.isFinite(Date.parse(`${item.date}T00:00:00Z`))) continue;
    const seconds = Math.round(finiteNumber(item.seconds, 0, 0, 86_400));
    dailyByDate.set(item.date, seconds);
  }
  const reviewsByDate = new Map();
  const rawReviews = Array.isArray(value.dailyReviews) ? value.dailyReviews.slice(-180) : [];
  for (const item of rawReviews) {
    if (!item || typeof item !== "object" || typeof item.date !== "string") continue;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(item.date) || !Number.isFinite(Date.parse(`${item.date}T00:00:00Z`))) continue;
    const count = Math.round(finiteNumber(item.count, 0, 0, 10_000));
    reviewsByDate.set(item.date, count);
  }
  return {
    totalSeconds: Math.round(finiteNumber(value.totalSeconds, 0, 0, 315_360_000)),
    sessions: Math.round(finiteNumber(value.sessions, 0, 0, 1_000_000)),
    dailyGoalMinutes: Math.round(finiteNumber(value.dailyGoalMinutes, 25, 15, 240)),
    dailyFocus: [...dailyByDate.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .slice(-90)
      .map(([date, seconds]) => ({ date, seconds })),
    dailyReviews: [...reviewsByDate.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .slice(-180)
      .map(([date, count]) => ({ date, count })),
  };
}

function defaultTimerState(mode = "focus") {
  const config = TIMER_MODES[mode] || TIMER_MODES.focus;
  return { mode, duration: config.duration, remaining: config.duration, running: false, endAt: 0 };
}

function normalizeCard(value) {
  if (!value || typeof value !== "object") return null;
  const front = typeof value.front === "string" ? value.front.trim().slice(0, 500) : "";
  const back = typeof value.back === "string" ? value.back.trim().slice(0, 2000) : "";
  if (!front || !back) return null;

  const card = newCard(front, back, value);
  if (typeof value.id === "string" && /^[a-z0-9_-]{4,80}$/i.test(value.id)) card.id = value.id;
  card.interval = finiteNumber(value.interval, 0, 0, 36_500);
  card.repetition = finiteNumber(value.repetition, 0, 0, 10_000);
  card.easeFactor = finiteNumber(value.easeFactor, 2.5, 1.3, 5);
  card.reviews = finiteNumber(value.reviews, 0, 0, 1_000_000);
  if (typeof value.dueDate === "string" && Number.isFinite(Date.parse(value.dueDate))) {
    card.dueDate = new Date(value.dueDate).toISOString();
  }
  return card;
}

function simpleConceptFromQuestion(front) {
  const clean = String(front || "")
    .replace(/\s+/g, " ")
    .replace(/[?.!]+$/, "")
    .trim();
  const direct = clean.match(/^(?:what (?:is|are)|define|explain|describe|state)\s+(.+)$/i)?.[1];
  return (direct || clean).replace(/^(?:the )?meaning of\s+/i, "").trim().slice(0, 140);
}

function shortDefinition(answer) {
  const clean = String(answer || "").replace(/\s+/g, " ").trim();
  if (clean.length <= 150) return clean;
  const sentence = clean.match(/^.{40,150}?[.!?](?:\s|$)/)?.[0];
  return (sentence || `${clean.slice(0, 147)}…`).trim();
}

function upgradeLegacyShortDeck(snapshot) {
  const savedVersion = Number(snapshot?.version);
  if ((Number.isFinite(savedVersion) ? savedVersion : 0) >= DECK_SCHEMA_VERSION) return false;
  if (!cards.length || cards.length >= GENERATED_DECK_SIZE) return false;

  const originals = [...cards];
  const existingQuestions = new Set(cards.map((card) => card.front.toLowerCase().replace(/\s+/g, " ").trim()));
  const questionVariants = [
    (concept, card) => ({
      front: `Which concept matches this explanation: “${shortDefinition(card.back)}”?`,
      back: concept,
    }),
    (concept, card) => ({ front: `Explain ${concept} in one clear sentence.`, back: card.back }),
    (concept, card) => ({ front: `What is the textbook meaning of ${concept}?`, back: card.back }),
    (concept, card) => ({ front: `What is the key point to remember about ${concept}?`, back: card.back }),
    (concept, card) => ({ front: `How would you explain ${concept} to a classmate?`, back: card.back }),
    (concept, card) => ({ front: `Write a short note on ${concept}.`, back: card.back }),
    (concept, card) => ({ front: `State the main idea behind ${concept}.`, back: card.back }),
  ];

  for (const makeQuestion of questionVariants) {
    for (const source of originals) {
      if (cards.length >= GENERATED_DECK_SIZE) break;
      const concept = simpleConceptFromQuestion(source.front) || "this topic";
      const draft = makeQuestion(concept, source);
      const key = draft.front.toLowerCase().replace(/\s+/g, " ").trim();
      if (existingQuestions.has(key)) continue;
      existingQuestions.add(key);
      cards.push(
        newCard(draft.front, draft.back, {
          subject: source.subject,
          examTags: source.examTags,
          trap: source.trap,
        })
      );
    }
    if (cards.length >= GENERATED_DECK_SIZE) break;
  }

  if (cards.length <= originals.length) return false;
  index = 0;
  studyQueueIds = [];
  return true;
}

function deckSnapshot() {
  return {
    version: DECK_SCHEMA_VERSION,
    cards,
    index,
    reviewed: [...reviewed],
    study: studyStats,
    updatedAt: deckUpdatedAt,
  };
}

function save(touch = false) {
  if (touch) deckUpdatedAt = Date.now();
  try {
    const snapshot = deckSnapshot();
    localStorage.setItem(activeStoreKey, JSON.stringify(snapshot));
    saveDeckBackup(activeStoreKey, snapshot).catch(() => {});
  } catch {
    toast("Could not save this deck locally");
  }
  if (touch && authState.user) scheduleCloudSync();
}

function applyDeckState(value) {
  if (!value || typeof value !== "object") return false;
  const normalized = Array.isArray(value.cards) ? value.cards.map(normalizeCard).filter(Boolean) : [];
  if (normalized.length !== (Array.isArray(value.cards) ? value.cards.length : 0)) return false;
  cards = normalized;
  index = Math.min(
    finiteNumber(value.index, 0, 0, Math.max(0, cards.length - 1)),
    Math.max(0, cards.length - 1)
  );
  reviewed = new Set(
    Array.isArray(value.reviewed)
      ? value.reviewed.filter((id) => typeof id === "string" && cards.some((card) => card.id === id))
      : []
  );
  studyStats = normalizeStudyStats(value.study);
  deckUpdatedAt = finiteNumber(value.updatedAt, 0, 0, Number.MAX_SAFE_INTEGER);
  return true;
}

async function load() {
  let upgraded = false;
  try {
    const localValue = localStorage.getItem(activeStoreKey);
    const backup = localValue ? null : await loadDeckBackup(activeStoreKey).catch(() => null);
    const stored = localValue ? JSON.parse(localValue) : backup || {};
    if (!applyDeckState(stored)) throw new Error();
    upgraded = upgradeLegacyShortDeck(stored);
  } catch {
    const backup = await loadDeckBackup(activeStoreKey).catch(() => null);
    if (!backup || !applyDeckState(backup)) {
      cards = [];
      index = 0;
      reviewed = new Set();
      studyStats = defaultStudyStats();
      deckUpdatedAt = 0;
    } else {
      upgraded = upgradeLegacyShortDeck(backup);
    }
  }
  render(upgraded);
  if (upgraded) toast(`Your saved deck now has ${GENERATED_DECK_SIZE} clear questions`);
}

function activateAccountStore(accountKey) {
  if (!/^[a-f0-9]{24}$/.test(accountKey) || activeAccountKey === accountKey) return;
  const nextStore = `minddeck-v3:user:${accountKey}`;
  const saved = localStorage.getItem(nextStore);
  activeStoreKey = nextStore;
  activeAccountKey = accountKey;
  accountStoreFresh = !saved;
  let upgraded = false;
  if (saved) {
    try {
      const stored = JSON.parse(saved);
      if (!applyDeckState(stored)) throw new Error();
      upgraded = upgradeLegacyShortDeck(stored);
    } catch {
      cards = [];
      index = 0;
      reviewed = new Set();
      studyStats = defaultStudyStats();
      deckUpdatedAt = 0;
      accountStoreFresh = true;
    }
  } else {
    save(false);
  }
  render(upgraded);
  if (upgraded) toast(`Your saved deck now has ${GENERATED_DECK_SIZE} clear questions`);
}

async function activateGuestStore() {
  activeStoreKey = GUEST_STORE;
  activeAccountKey = null;
  accountStoreFresh = false;
  await load();
}

function toast(message) {
  const element = $("#toast");
  element.textContent = String(message).slice(0, 180);
  element.classList.add("show");
  window.setTimeout(() => element.classList.remove("show"), 2000);
}

function mobileProfile() {
  try {
    const parsed = JSON.parse(localStorage.getItem(PROFILE_STORE) || "{}");
    return parsed && typeof parsed === "object"
      ? {
          name: typeof parsed.name === "string" ? parsed.name.slice(0, 80) : "",
          avatar: typeof parsed.avatar === "string" ? parsed.avatar.slice(0, 8) : "",
        }
      : { name: "", avatar: "" };
  } catch {
    return { name: "", avatar: "" };
  }
}

function saveMobileProfile(nextProfile) {
  const normalized = {
    name: String(nextProfile.name || "").trim().slice(0, 80),
    avatar: String(nextProfile.avatar || "").trim().slice(0, 8),
  };
  try {
    localStorage.setItem(PROFILE_STORE, JSON.stringify(normalized));
  } catch {
    // The profile still applies to this tab when local storage is unavailable.
  }
  updateMobileAccountUI();
}

function friendlyAccountName() {
  const profile = mobileProfile();
  if (profile.name) return profile.name;
  const email = authState.user?.email || "";
  if (!email) return "MindDeck learner";
  const localPart = email.split("@")[0].replace(/[._-]+/g, " ").trim();
  return localPart
    ? localPart.replace(/\b\w/g, (character) => character.toUpperCase()).slice(0, 80)
    : "MindDeck learner";
}

function selectedClassLevel() {
  const select = $("#classSelect");
  if (select?.value) return select.value;
  try {
    return localStorage.getItem(CLASS_STORE) || "Class 12";
  } catch {
    return "Class 12";
  }
}

function setOptionalText(selector, value) {
  const element = $(selector);
  if (element) element.textContent = String(value);
}

function updateMobileAccountUI() {
  if (!$("#mobileHomeView")) return;
  const profile = mobileProfile();
  const name = friendlyAccountName();
  const classLevel = selectedClassLevel();
  const email = authState.user?.email || "Saved on this device";
  const avatarUrl = authState.user?.avatarUrl || "";
  const fallback = profile.avatar || name.trim().charAt(0).toUpperCase() || "M";
  const image = $("#mobileAvatarImage");
  const fallbackElement = $("#mobileAvatarFallback");

  setOptionalText("#mobileUserName", name);
  setOptionalText("#mobileClassPill", classLevel);
  setOptionalText("#mobileOverallClass", classLevel);
  setOptionalText("#mobileSettingsName", name);
  setOptionalText("#mobileSettingsMeta", `${classLevel} · ${authState.user ? "Cloud sync on" : "Free plan"}`);
  setOptionalText("#mobileSettingsAvatar", fallback);
  setOptionalText("#mobileProfileHeroAvatar", fallback);
  setOptionalText("#mobileProfileName", name);
  setOptionalText("#mobileProfileNameValue", name);
  setOptionalText("#mobileProfileEmailValue", email);
  setOptionalText("#mobileProfileClass", classLevel);
  setOptionalText("#mobileProfileClassValue", classLevel);
  setOptionalText("#mobileProfileGoalValue", `${studyStats.dailyGoalMinutes || 25} min / day`);
  const mobileSignOut = $("#mobileSignOutOpen");
  if (mobileSignOut) {
    mobileSignOut.textContent = authState.user ? "↪ Sign out" : "Sign in to sync";
  }

  if (image && fallbackElement) {
    const showImage = Boolean(avatarUrl && !profile.avatar);
    fallbackElement.textContent = fallback;
    const showFallback = () => {
      image.hidden = true;
      fallbackElement.hidden = false;
    };
    image.onerror = showFallback;
    image.onload = () => {
      if (!image.naturalWidth) {
        showFallback();
        return;
      }
      image.hidden = false;
      fallbackElement.hidden = true;
    };
    showFallback();
    if (showImage) image.src = avatarUrl;
    else image.removeAttribute("src");
  }
}

function renderMobileWeekChart() {
  const chart = $("#mobileWeekChart");
  if (!chart) return;
  chart.replaceChildren();
  const goalSeconds = Math.max(60, (studyStats.dailyGoalMinutes || 25) * 60);
  for (let offset = 6; offset >= 0; offset -= 1) {
    const dateKey = dateKeyDaysAgo(offset);
    const seconds = focusSecondsFor(dateKey);
    const level = seconds ? Math.max(8, Math.min(100, Math.round((seconds / goalSeconds) * 100))) : 5;
    const day = document.createElement("div");
    const bar = document.createElement("i");
    const label = document.createElement("small");
    bar.style.height = `${level}%`;
    bar.title = `${Math.floor(seconds / 60)} focused minutes`;
    label.textContent = new Intl.DateTimeFormat(undefined, { weekday: "narrow" }).format(
      new Date(`${dateKey}T12:00:00`)
    );
    day.append(bar, label);
    chart.append(day);
  }
}

function syncMobileDashboard() {
  if (!$("#mobileHomeView")) return;
  const now = Date.now();
  const due = cards.filter((card) => Date.parse(card.dueDate) <= now).length;
  const mastery = cards.length
    ? Math.round((cards.filter((card) => card.repetition >= 3).length / cards.length) * 100)
    : 0;
  const streak = currentStudyStreak();
  const insights = calculateLearningInsights();
  const todayFocused = focusSecondsFor(localDateKey()) > 0;

  setOptionalText("#mobileDueCount", due);
  setOptionalText("#mobileStreakCount", streak);
  setOptionalText("#mobileGoalDue", `${Math.max(0, cards.length - due)}/${cards.length}`);
  setOptionalText("#mobileGoalDeck", `${Math.min(cards.length, GENERATED_DECK_SIZE)}/${GENERATED_DECK_SIZE}`);
  setOptionalText("#mobileGoalFocus", `${todayFocused ? 1 : 0}/1`);
  setOptionalText("#mobileOverallMastery", `${mastery}%`);
  setOptionalText("#mobileOverallReviews", cards.length);
  setOptionalText("#mobileOverallStreak", streak);
  setOptionalText("#mobileReadinessScore", insights.memoryScore);
  setOptionalText("#mobileProfileGoalValue", `${studyStats.dailyGoalMinutes || 25} min / day`);

  const readinessRing = $("#mobileReadinessRing");
  if (readinessRing) readinessRing.style.setProperty("--readiness", `${insights.memoryScore}%`);
  setOptionalText(
    "#mobileReadinessLabel",
    insights.memoryScore >= 70
      ? "Strong recall"
      : insights.memoryScore >= 35
        ? "Building strength"
        : cards.length
          ? "Taking root"
          : "Ready to begin"
  );
  setOptionalText(
    "#mobileReadinessCopy",
    cards.length
      ? "Keep reviewing honestly to strengthen long-term memory."
      : "Create a deck to start measuring long-term recall."
  );

  if (!cards.length) {
    setOptionalText("#mobileSessionTitle", "Begin your first chapter");
    setOptionalText("#mobileSessionCopy", "Pick a subject and start learning.");
    setOptionalText("#mobileReviewTitle", "Your next review is being prepared");
    setOptionalText("#mobileReviewCopy", "Create cards and MindDeck will schedule them with SM-2.");
  } else if (due) {
    setOptionalText("#mobileSessionTitle", `Review ${due} due ${due === 1 ? "card" : "cards"}`);
    setOptionalText("#mobileSessionCopy", "A short review now will protect your recall streak.");
    setOptionalText("#mobileReviewTitle", `${due} ${due === 1 ? "card is" : "cards are"} ready now`);
    setOptionalText("#mobileReviewCopy", "Rate each answer honestly so the next review stays accurate.");
  } else {
    setOptionalText("#mobileSessionTitle", "Continue your study deck");
    setOptionalText("#mobileSessionCopy", `${cards.length} cards are ready whenever you are.`);
    setOptionalText("#mobileReviewTitle", "You're caught up for now");
    setOptionalText("#mobileReviewCopy", "MindDeck will bring cards back when memory needs a refresh.");
  }

  for (const [workspaceKey] of Object.entries(SUBJECT_WORKSPACES)) {
    const matching = cards.filter((card) => subjectMatches(card, workspaceKey));
    const mastered = matching.filter((card) => card.repetition >= 3).length;
    const percent = matching.length ? Math.round((mastered / matching.length) * 100) : 0;
    setOptionalText(`[data-mobile-subject-count="${workspaceKey}"]`, matching.length);
    setOptionalText(`[data-mobile-subject-percent="${workspaceKey}"]`, `${percent}%`);
    const progress = $(`[data-mobile-subject-progress="${workspaceKey}"]`);
    if (progress) progress.value = percent;
  }
  renderMobileWeekChart();
  updateMobileAccountUI();
}

function showMobilePage(selector) {
  const page = $(selector);
  if (!page) return;
  page.hidden = false;
  page.scrollTop = 0;
  document.body.classList.add("mobile-page-open");
}

function hideMobilePage(selector) {
  const page = $(selector);
  if (!page) return;
  page.hidden = true;
  if (!$(".mobileFullPage:not([hidden])")) document.body.classList.remove("mobile-page-open");
}

function localDateKey(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function dateKeyDaysAgo(days) {
  const date = new Date();
  date.setHours(12, 0, 0, 0);
  date.setDate(date.getDate() - days);
  return localDateKey(date);
}

function focusSecondsFor(dateKey) {
  return studyStats.dailyFocus.find((item) => item.date === dateKey)?.seconds || 0;
}

function currentStudyStreak() {
  const activeDays = new Set(
    studyStats.dailyFocus.filter((item) => item.seconds >= 60).map((item) => item.date)
  );
  const cursor = new Date();
  cursor.setHours(12, 0, 0, 0);
  if (!activeDays.has(localDateKey(cursor))) cursor.setDate(cursor.getDate() - 1);
  let streak = 0;
  while (activeDays.has(localDateKey(cursor)) && streak < 3650) {
    streak += 1;
    cursor.setDate(cursor.getDate() - 1);
  }
  return streak;
}

function formatTimer(seconds) {
  const safeSeconds = Math.max(0, Math.round(seconds));
  return `${String(Math.floor(safeSeconds / 60)).padStart(2, "0")}:${String(safeSeconds % 60).padStart(2, "0")}`;
}

function formatFocusTotal(seconds) {
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  return remainder ? `${hours}h ${remainder}m` : `${hours}h`;
}

function renderWeekBars() {
  const weekBars = $("#weekBars");
  if (!weekBars) return;
  weekBars.replaceChildren();
  const goalSeconds = studyStats.dailyGoalMinutes * 60;
  for (let offset = 6; offset >= 0; offset -= 1) {
    const dateKey = dateKeyDaysAgo(offset);
    const seconds = focusSecondsFor(dateKey);
    const level = seconds ? Math.max(1, Math.min(10, Math.ceil((seconds / goalSeconds) * 10))) : 0;
    const day = document.createElement("div");
    day.className = "weekDay";
    const bar = document.createElement("div");
    bar.className = `weekBar level-${level}`;
    bar.setAttribute("aria-label", `${Math.floor(seconds / 60)} focus minutes on ${dateKey}`);
    bar.title = `${Math.floor(seconds / 60)} min`;
    const fill = document.createElement("i");
    fill.setAttribute("aria-hidden", "true");
    const label = document.createElement("small");
    label.textContent = new Intl.DateTimeFormat(undefined, { weekday: "narrow" }).format(
      new Date(`${dateKey}T12:00:00`)
    );
    bar.append(fill);
    day.append(bar, label);
    weekBars.append(day);
  }
}

function renderStudyWidgets() {
  if (!$("#timerWidget")) return;
  const config = TIMER_MODES[timerState.mode] || TIMER_MODES.focus;
  const progress = timerState.duration
    ? Math.min(100, Math.max(0, ((timerState.duration - timerState.remaining) / timerState.duration) * 100))
    : 0;
  $("#timerTime").textContent = formatTimer(timerState.remaining);
  $("#timerModeLabel").textContent = config.label;
  $("#timerArc").setAttribute("stroke-dasharray", `${progress.toFixed(2)} 100`);
  $("#timerWidget").classList.toggle("running", timerState.running);
  $("#timerToggle").textContent = timerState.running
    ? "Ⅱ Pause"
    : timerState.remaining === 0
      ? "▶ Start again"
      : config.isFocus
        ? "▶ Start focus"
        : "▶ Start break";
  $("#timerPrompt").textContent = timerState.running
    ? config.isFocus
      ? "Focus mode is active."
      : "Breathe, reset, return stronger."
    : timerState.remaining === 0
      ? `${config.label} complete.`
      : "Ready for one focused task?";
  $("#timerHint").textContent = timerState.running
    ? "The timer keeps running safely if you switch tabs."
    : timerState.remaining === 0
      ? config.isFocus
        ? "Your focused minutes were added to your progress."
        : "Your break is complete. Choose a focus session when ready."
      : "Choose a rhythm, remove distractions, and begin.";
  $$('[data-timer-mode]').forEach((button) => {
    const active = button.dataset.timerMode === timerState.mode;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });

  const todaySeconds = focusSecondsFor(localDateKey());
  const todayMinutes = Math.floor(todaySeconds / 60);
  const goalMinutes = studyStats.dailyGoalMinutes;
  const goalPercent = Math.min(100, Math.round((todaySeconds / (goalMinutes * 60)) * 100));
  $("#todayMinutes").textContent = todayMinutes;
  $("#goalMinutes").textContent = goalMinutes;
  $("#goalProgress").value = goalPercent;
  $("#goalProgress").textContent = `${goalPercent}%`;
  $("#goalMessage").textContent =
    goalPercent >= 100
      ? "Daily goal complete. Beautiful work."
      : todayMinutes
        ? `${Math.max(1, goalMinutes - todayMinutes)} focused minutes to reach today's goal.`
        : "Your first focused session starts here.";
  $("#totalFocus").textContent = formatFocusTotal(studyStats.totalSeconds);
  $("#focusSessions").textContent = studyStats.sessions;

  const streak = currentStudyStreak();
  $("#streakCount").textContent = streak;
  $("#streakMessage").textContent = streak
    ? `${streak} consistent ${streak === 1 ? "day" : "days"}. Keep the chain alive.`
    : "Complete a focus session to light up your week.";
  renderWeekBars();
  syncMobileDashboard();
}

function localDayEnd(offset = 0) {
  const date = new Date();
  date.setHours(23, 59, 59, 999);
  date.setDate(date.getDate() + offset);
  return date.getTime();
}

function calculateLearningInsights() {
  const now = Date.now();
  const todayEnd = localDayEnd();
  const tomorrowEnd = localDayEnd(1);
  const weekEnd = localDayEnd(6);
  const dueTimes = cards.map((card) => Date.parse(card.dueDate)).filter(Number.isFinite);
  const fresh = cards.filter((card) => card.repetition === 0 && card.reviews === 0).length;
  const mastered = cards.filter((card) => card.repetition >= 3).length;
  const learning = Math.max(0, cards.length - fresh - mastered);
  const memoryScore = cards.length
    ? Math.round(
        cards.reduce(
          (total, card) =>
            total + Math.min(100, card.repetition * 25 + Math.min(card.reviews, 5) * 5),
          0
        ) / cards.length
      )
    : 0;
  const dueToday = dueTimes.filter((dueTime) => dueTime <= todayEnd).length;
  const dueTomorrow = dueTimes.filter(
    (dueTime) => dueTime > todayEnd && dueTime <= tomorrowEnd
  ).length;
  const dueLater = dueTimes.filter(
    (dueTime) => dueTime > tomorrowEnd && dueTime <= weekEnd
  ).length;
  const futureDue = dueTimes.filter((dueTime) => dueTime > now).sort((left, right) => left - right)[0];
  return {
    fresh,
    learning,
    mastered,
    memoryScore,
    dueToday,
    dueTomorrow,
    dueLater,
    forecastTotal: dueToday + dueTomorrow + dueLater,
    futureDue,
  };
}

function renderLearningInsights() {
  if (!$("#memoryWidget")) return;
  const insights = calculateLearningInsights();
  const { memoryScore } = insights;
  $("#memoryScore").textContent = memoryScore;
  $("#memoryArc").setAttribute("stroke-dasharray", `${memoryScore} 100`);
  $("#freshCards").textContent = insights.fresh;
  $("#learningCards").textContent = insights.learning;
  $("#masteredCards").textContent = insights.mastered;

  const memoryLabel =
    memoryScore >= 90
      ? "Excellent retention"
      : memoryScore >= 70
        ? "Strong recall"
        : memoryScore >= 35
          ? "Building strength"
          : cards.length
            ? "Taking root"
            : "Ready to grow";
  $("#memoryLabel").textContent = memoryLabel;
  $("#memoryMessage").textContent = !cards.length
    ? "Create a deck to start measuring long-term recall."
    : memoryScore >= 70
      ? "Your recall is strong. Keep reviews consistent to protect it."
      : insights.fresh
        ? "Review fresh cards and rate honestly to strengthen this score."
        : "A short review session will keep your memory curve moving up.";

  const forecasts = [
    ["#forecastToday", "#forecastTodayBar", insights.dueToday],
    ["#forecastTomorrow", "#forecastTomorrowBar", insights.dueTomorrow],
    ["#forecastWeek", "#forecastWeekBar", insights.dueLater],
  ];
  const forecastMax = Math.max(1, cards.length);
  for (const [labelSelector, barSelector, value] of forecasts) {
    $(labelSelector).textContent = value;
    const bar = $(barSelector);
    bar.max = forecastMax;
    bar.value = value;
    bar.textContent = String(value);
  }
  $("#forecastTotal").textContent = insights.forecastTotal;

  if (insights.dueToday) {
    $("#nextReview").textContent = `${insights.dueToday} ${insights.dueToday === 1 ? "card is" : "cards are"} ready now`;
  } else if (insights.futureDue) {
    const daysAway = Math.max(1, Math.ceil((insights.futureDue - Date.now()) / 86_400_000));
    const dateLabel = new Date(insights.futureDue).toLocaleDateString(undefined, {
      month: "short",
      day: "numeric",
    });
    $("#nextReview").textContent =
      daysAway === 1 ? `Next review tomorrow · ${dateLabel}` : `Next review in ${daysAway} days · ${dateLabel}`;
  } else {
    $("#nextReview").textContent = cards.length
      ? "Rate a card to create your review schedule"
      : "No reviews scheduled yet";
  }

  $("#dailyPrompt").textContent = !cards.length
    ? "Start with one concept. MindDeck will build the study path around it."
    : insights.dueToday
      ? `Clear ${insights.dueToday} due ${insights.dueToday === 1 ? "card" : "cards"} to protect your momentum.`
      : insights.fresh
        ? `${insights.fresh} fresh ${insights.fresh === 1 ? "card is" : "cards are"} waiting for a first review.`
        : memoryScore >= 70
          ? "Your deck is healthy. A short focus sprint will keep it strong."
          : "One honest review round can lift your memory index today.";
  $("#quickReviewHint").textContent = !cards.length
    ? "Create a deck first"
    : insights.dueToday
      ? `${insights.dueToday} due now`
      : "Continue your deck";
}

function reviewCountFor(dateKey) {
  return studyStats.dailyReviews.find((item) => item.date === dateKey)?.count || 0;
}

function renderActivityHeatmap() {
  const heatmap = $("#reviewHeatmap");
  if (!heatmap) return;
  heatmap.replaceChildren();
  const fragment = document.createDocumentFragment();
  let activeDays = 0;
  let totalReviews = 0;
  for (let offset = 83; offset >= 0; offset -= 1) {
    const dateKey = dateKeyDaysAgo(offset);
    const count = reviewCountFor(dateKey);
    const level = count ? Math.min(4, Math.max(1, Math.ceil(count / 5))) : 0;
    if (count) activeDays += 1;
    totalReviews += count;
    const cell = document.createElement("span");
    cell.className = `heatCell heat-${level}`;
    cell.title = `${count} ${count === 1 ? "review" : "reviews"} on ${dateKey}`;
    cell.setAttribute("aria-label", cell.title);
    fragment.append(cell);
  }
  heatmap.append(fragment);
  $("#heatmapDays").textContent = activeDays;
  $("#heatmapReviews").textContent = totalReviews;
}

function renderSmartWidgets() {
  if (!$("#smartStudyLab")) return;
  const leeches = cards.filter((card) => card.leech);
  const clozeCount = cards.filter((card) => card.type === "cloze").length;
  const formulas = cards.filter(isFormulaCramCard);
  const mistakes = cards.filter((card) => card.mistake);
  $("#leechCount").textContent = leeches.length;
  $("#leechLabel").textContent = leeches.length
    ? `${leeches.length} stubborn ${leeches.length === 1 ? "card needs" : "cards need"} a breakdown.`
    : "No stubborn cards. Four consecutive Again ratings trigger rescue mode.";
  $("#cramLeeches").disabled = !leeches.length;
  $("#clozeCount").textContent = clozeCount;
  $("#matchReady").textContent = cards.length >= 2 ? `${Math.min(6, cards.length)} pairs ready` : "Add 2+ cards";
  $("#startMatch").disabled = cards.length < 2;
  $("#formulaCount").textContent = formulas.length;
  $("#formulaCram").disabled = !formulas.length;
  $("#mistakeCount").textContent = mistakes.length;
  $("#mistakeNotebook").disabled = !mistakes.length;
  renderActivityHeatmap();
}

function storeTimerState() {
  try {
    localStorage.setItem(FOCUS_STORE, JSON.stringify(timerState));
  } catch {
    // The timer can continue for this tab even if storage is unavailable.
  }
}

function startTimerTicker() {
  window.clearInterval(timerInterval);
  timerInterval = timerState.running ? window.setInterval(updateTimerFromClock, 250) : null;
}

function loadTimerState() {
  window.clearInterval(timerInterval);
  timerInterval = null;
  try {
    const stored = JSON.parse(localStorage.getItem(FOCUS_STORE) || "{}");
    const mode = Object.hasOwn(TIMER_MODES, stored.mode) ? stored.mode : "focus";
    const config = TIMER_MODES[mode];
    const endAt = Math.round(finiteNumber(stored.endAt, 0, 0, Number.MAX_SAFE_INTEGER));
    const running = Boolean(stored.running && endAt);
    timerState = {
      mode,
      duration: config.duration,
      remaining: Math.round(finiteNumber(stored.remaining, config.duration, 0, config.duration)),
      running,
      endAt: running ? endAt : 0,
    };
    if (running) {
      timerState.remaining = Math.min(
        config.duration,
        Math.max(0, Math.ceil((timerState.endAt - Date.now()) / 1000))
      );
      if (timerState.remaining === 0) {
        completeTimer();
        return;
      }
    }
  } catch {
    timerState = defaultTimerState();
  }
  startTimerTicker();
  renderStudyWidgets();
}

function recordFocusSession(seconds) {
  const safeSeconds = Math.round(finiteNumber(seconds, 0, 60, 4 * 60 * 60));
  const today = localDateKey();
  const dailyFocus = new Map(studyStats.dailyFocus.map((item) => [item.date, item.seconds]));
  dailyFocus.set(today, Math.min(86_400, (dailyFocus.get(today) || 0) + safeSeconds));
  studyStats = normalizeStudyStats({
    ...studyStats,
    totalSeconds: studyStats.totalSeconds + safeSeconds,
    sessions: studyStats.sessions + 1,
    dailyFocus: [...dailyFocus.entries()].map(([date, focusedSeconds]) => ({
      date,
      seconds: focusedSeconds,
    })),
  });
  renderStudyWidgets();
  save(true);
}

function completeTimer() {
  const config = TIMER_MODES[timerState.mode] || TIMER_MODES.focus;
  timerState.running = false;
  timerState.remaining = 0;
  timerState.endAt = 0;
  window.clearInterval(timerInterval);
  timerInterval = null;
  storeTimerState();
  if (config.isFocus) recordFocusSession(timerState.duration);
  else renderStudyWidgets();
  toast(config.isFocus ? `${config.label} complete · progress saved` : "Break complete · ready to focus");
}

function updateTimerFromClock() {
  if (!timerState.running) return;
  timerState.remaining = Math.min(
    timerState.duration,
    Math.max(0, Math.ceil((timerState.endAt - Date.now()) / 1000))
  );
  if (timerState.remaining === 0) completeTimer();
  else renderStudyWidgets();
}

function toggleTimer() {
  if (timerState.running) {
    timerState.remaining = Math.min(
      timerState.duration,
      Math.max(0, Math.ceil((timerState.endAt - Date.now()) / 1000))
    );
    timerState.running = false;
    timerState.endAt = 0;
    startTimerTicker();
  } else {
    if (timerState.remaining <= 0) timerState.remaining = timerState.duration;
    timerState.running = true;
    timerState.endAt = Date.now() + timerState.remaining * 1000;
    startTimerTicker();
  }
  storeTimerState();
  renderStudyWidgets();
}

function resetTimer() {
  const mode = timerState.mode;
  timerState = defaultTimerState(mode);
  startTimerTicker();
  storeTimerState();
  renderStudyWidgets();
}

function selectTimerMode(mode) {
  if (!Object.hasOwn(TIMER_MODES, mode) || mode === timerState.mode) return;
  timerState = defaultTimerState(mode);
  startTimerTicker();
  storeTimerState();
  renderStudyWidgets();
}

function changeDailyGoal(delta) {
  studyStats.dailyGoalMinutes = Math.round(
    finiteNumber(studyStats.dailyGoalMinutes + delta, 25, 15, 240)
  );
  renderStudyWidgets();
  save(true);
}

function clearStudyAssist() {
  if (feynmanRecorder?.state === "recording") {
    feynmanCardId = "";
    stopFeynmanRecording();
  }
  hintStep = 0;
  if ($("#hintPanel")) {
    $("#hintPanel").hidden = true;
    $("#hintList").replaceChildren();
    $("#hintButton").textContent = "✦ Hint";
  }
  if ($("#feynmanTranscript")) {
    $("#feynmanTranscript").value = "";
    $("#feynmanResult").hidden = true;
    $("#feynmanResult").textContent = "";
    $("#feynmanStatus").textContent = "Your recording stays on this device.";
    $("#feynmanAudio").hidden = true;
    $("#feynmanAudio").removeAttribute("src");
  }
  if (feynmanAudioUrl) URL.revokeObjectURL(feynmanAudioUrl);
  feynmanAudioUrl = "";
}

async function renderOcclusionCard(card) {
  const stage = $("#occlusionStage");
  if (!stage) return;
  const isOcclusion = card?.type === "occlusion";
  stage.hidden = !isOcclusion;
  $("#front").hidden = isOcclusion;
  if (!isOcclusion) return;

  $("#occlusionMasks").replaceChildren();
  $("#occlusionMissing").hidden = true;
  const requestedId = card.id;
  const blob = await loadImageAsset(card.imageAssetId).catch(() => null);
  if (cards[index]?.id !== requestedId) return;
  if (!blob) {
    $("#occlusionImage").removeAttribute("src");
    $("#occlusionMissing").hidden = false;
    return;
  }
  if (currentImageUrl) URL.revokeObjectURL(currentImageUrl);
  currentImageUrl = URL.createObjectURL(blob);
  $("#occlusionImage").src = currentImageUrl;
  for (const mask of card.occlusions) {
    const rect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
    rect.setAttribute("x", String(mask.x));
    rect.setAttribute("y", String(mask.y));
    rect.setAttribute("width", String(mask.width));
    rect.setAttribute("height", String(mask.height));
    rect.setAttribute("rx", "18");
    rect.setAttribute("tabindex", "0");
    rect.setAttribute("role", "button");
    rect.setAttribute("aria-label", "Reveal hidden image label");
    rect.classList.add("occlusionMask");
    const reveal = (event) => {
      event.stopPropagation();
      rect.classList.toggle("revealed");
    };
    rect.addEventListener("click", reveal);
    rect.addEventListener("keydown", (event) => {
      if (["Enter", " "].includes(event.key)) reveal(event);
    });
    $("#occlusionMasks").append(rect);
  }
}

function makeSvgElement(name, attributes = {}) {
  const element = document.createElementNS("http://www.w3.org/2000/svg", name);
  Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, String(value)));
  return element;
}

function renderGraphStage(container, card, reveal) {
  container.replaceChildren();
  const isGraph = card?.template === "graph";
  container.hidden = !isGraph;
  if (!isGraph) return;
  const svg = makeSvgElement("svg", { viewBox: "0 0 360 150", role: "img" });
  svg.setAttribute("aria-label", reveal ? "Revealed graph curve" : "Blank graph axes");
  [45, 75, 105].forEach((y) => svg.append(makeSvgElement("line", { x1: 44, y1: y, x2: 332, y2: y, class: "graphGrid" })));
  [100, 160, 220, 280].forEach((x) => svg.append(makeSvgElement("line", { x1: x, y1: 15, x2: x, y2: 126, class: "graphGrid" })));
  svg.append(
    makeSvgElement("line", { x1: 44, y1: 126, x2: 334, y2: 126, class: "graphAxis" }),
    makeSvgElement("line", { x1: 44, y1: 126, x2: 44, y2: 14, class: "graphAxis" })
  );
  const axis = new Map(card.sections.map((section) => [section.label.toLowerCase(), section.value]));
  const xLabel = makeSvgElement("text", { x: 327, y: 144 });
  xLabel.textContent = axis.get("x-axis") || "X";
  const yLabel = makeSvgElement("text", { x: 8, y: 16 });
  yLabel.textContent = axis.get("y-axis") || "Y";
  svg.append(xLabel, yLabel);
  if (reveal) {
    const paths = {
      downward: "M62 28 C135 42 236 88 317 116",
      upward: "M62 116 C135 102 236 52 317 28",
      ppc: "M63 26 C92 28 221 36 316 116",
      isotherm: "M62 30 C89 58 123 92 318 116",
      bell: "M62 116 C130 114 135 31 188 27 C241 31 246 114 318 116",
    };
    svg.append(makeSvgElement("path", { d: paths[card.graphShape] || paths.downward, class: "graphCurve" }));
  }
  container.append(svg);
}

function appendExamChip(container, text, className = "") {
  const chip = document.createElement("span");
  chip.className = `examChip ${className}`.trim();
  chip.textContent = text;
  container.append(chip);
}

function renderExamMetadata(card) {
  const container = $("#examCardMeta");
  container.replaceChildren();
  const hasMetadata = Boolean(
    card && (card.template !== "basic" || card.subject || card.examTags.length || card.trap || card.mistake)
  );
  container.hidden = !hasMetadata;
  if (!hasMetadata) return;
  if (card.template !== "basic") appendExamChip(container, TEMPLATE_LABELS[card.template], "template");
  if (card.subject) appendExamChip(container, card.subject);
  card.examTags.forEach((tag) => appendExamChip(container, tag));
  if (card.trap) appendExamChip(container, "Exception & Trap", "trap");
  if (card.mistake) appendExamChip(container, "Mistake · review ≤48h", "mistake");
}

function revealExamStep(button) {
  if (!button) return;
  button.classList.add("revealed");
  button.setAttribute("aria-expanded", "true");
}

function renderExamProgressive(card) {
  const panel = $("#examProgressive");
  const list = $("#examStepList");
  list.replaceChildren();
  examRevealCount = 0;
  const sections = card?.sections || [];
  panel.hidden = !sections.length;
  if (!sections.length) return;
  const titles = {
    reaction: "Reaction mechanism carousel",
    formula: "Formula match details",
    journal: "Tap to reveal debit, credit & narration",
    graph: "Graph interpretation",
    assertion: "Assertion–Reasoning breakdown",
    derivation: "Step-by-step derivation",
    ncert: "NCERT source detail",
  };
  $("#examProgressTitle").textContent = titles[card.template] || "Step-by-step reveal";
  sections.forEach((section, sectionIndex) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "examStep";
    button.dataset.examStep = String(sectionIndex);
    button.setAttribute("aria-expanded", "false");
    const label = document.createElement("strong");
    label.textContent = section.label;
    const value = document.createElement("span");
    value.textContent = section.value;
    button.append(label, value);
    list.append(button);
  });
  $("#revealExamStep").textContent = "Reveal next";
  $("#revealExamStep").disabled = false;
}

function revealNextExamStep() {
  const buttons = $$("#examStepList .examStep");
  const nextButton = buttons.find((button) => !button.classList.contains("revealed"));
  if (!nextButton) return;
  revealExamStep(nextButton);
  examRevealCount += 1;
  if (examRevealCount >= buttons.length) {
    $("#revealExamStep").textContent = "All revealed";
    $("#revealExamStep").disabled = true;
  } else {
    $("#revealExamStep").textContent = `Reveal ${examRevealCount + 1}/${buttons.length}`;
  }
}

function renderCurrentCard(card) {
  const frontText = card
    ? card.type === "cloze" && card.clozeText
      ? clozeFront(card.clozeText)
      : card.front
    : "Add or generate cards to begin.";
  const backText = card
    ? card.type === "cloze" && card.clozeText
      ? clozeBack(card.clozeText)
      : card.back
    : "Your answer appears here.";
  $("#front").hidden = false;
  renderRichText($("#front"), frontText);
  renderRichText($("#back"), backText);
  const frontFace = $("#front").closest(".face");
  const backFace = $("#back").closest(".face");
  frontFace.classList.toggle("graphFace", card?.template === "graph");
  backFace.classList.toggle("graphFace", card?.template === "graph");
  renderGraphStage($("#graphFront"), card, false);
  renderGraphStage($("#graphBack"), card, true);
  renderExamMetadata(card);
  renderExamProgressive(card);
  renderOcclusionCard(card).catch(() => {});
  if ($("#cardTypeBadge")) {
    $("#cardTypeBadge").textContent = card
      ? card.leech
        ? "Leech rescue"
        : card.template !== "basic"
          ? TEMPLATE_LABELS[card.template]
        : card.type === "cloze"
          ? "Cloze card"
          : card.type === "occlusion"
            ? "Image occlusion"
            : "Recall card"
      : "Recall card";
  }
}

function renderDeck() {
  const list = $("#deckList");
  list.replaceChildren();
  const dueCount = cards.filter((card) => Date.parse(card.dueDate) <= Date.now()).length;
  const masteredCount = cards.filter((card) => card.repetition >= 3).length;
  const masteredPercent = cards.length ? Math.round((masteredCount / cards.length) * 100) : 0;
  setOptionalText("#deckMetricTotal", cards.length);
  setOptionalText("#deckMetricDue", dueCount);
  setOptionalText("#deckMetricMastered", `${masteredPercent}%`);
  if (!cards.length) {
    const empty = document.createElement("div");
    empty.className = "empty deckEmptyState";
    const art = document.createElement("div");
    art.className = "deckEmptyArt";
    art.setAttribute("aria-hidden", "true");
    ["Q", "A", "✦"].forEach((label) => {
      const card = document.createElement("span");
      card.textContent = label;
      art.append(card);
    });
    const eyebrow = document.createElement("small");
    eyebrow.textContent = "START YOUR FIRST DECK";
    const title = document.createElement("h3");
    title.textContent = "Turn one chapter into lasting memory.";
    const copy = document.createElement("p");
    copy.textContent = "Paste your notes and MindDeck will build 15 clear questions, or add a card yourself.";
    const actions = document.createElement("div");
    actions.className = "deckEmptyActions";
    const generate = document.createElement("button");
    generate.type = "button";
    generate.dataset.deckEmptyAction = "generate";
    generate.textContent = "✨ Generate cards";
    const manual = document.createElement("button");
    manual.type = "button";
    manual.dataset.deckEmptyAction = "manual";
    manual.textContent = "＋ Add one card";
    actions.append(generate, manual);
    const suggestions = document.createElement("div");
    suggestions.className = "deckEmptySuggestions";
    ["Physics", "Chemistry", "Accountancy", "Biology"].forEach((subject) => {
      const chip = document.createElement("span");
      chip.textContent = subject;
      suggestions.append(chip);
    });
    empty.append(art, eyebrow, title, copy, actions, suggestions);
    list.append(empty);
    return;
  }

  cards.forEach((card, cardIndex) => {
    const row = document.createElement("div");
    row.className = "row";
    const front = document.createElement("strong");
    front.textContent = card.front;
    if (card.leech || card.type !== "basic" || card.template !== "basic") {
      const badge = document.createElement("small");
      badge.className = card.leech ? "deckBadge leechBadge" : "deckBadge";
      badge.textContent = card.leech
        ? "Leech"
        : card.template !== "basic"
          ? TEMPLATE_LABELS[card.template]
          : card.type === "cloze"
            ? "Cloze"
            : "Image";
      front.append(" ", badge);
    }
    if (card.trap) {
      const badge = document.createElement("small");
      badge.className = "deckBadge trapBadge";
      badge.textContent = "Trap";
      front.append(" ", badge);
    }
    if (card.mistake) {
      const badge = document.createElement("small");
      badge.className = "deckBadge mistakeBadge";
      badge.textContent = "Mistake";
      front.append(" ", badge);
    }
    const repetitions = document.createElement("span");
    repetitions.textContent = `${card.repetition} reps`;
    const due = document.createElement("span");
    due.textContent = new Date(card.dueDate).toLocaleDateString();
    const remove = document.createElement("button");
    remove.type = "button";
    remove.dataset.del = String(cardIndex);
    remove.setAttribute("aria-label", `Delete card ${cardIndex + 1}`);
    remove.textContent = "×";
    row.append(front, repetitions, due, remove);
    list.append(row);
  });
}

function render(touch = false) {
  const card = cards[index];
  const due = cards.filter((item) => new Date(item.dueDate) <= new Date()).length;
  const mastery = cards.length
    ? Math.round((cards.filter((item) => item.repetition >= 3).length / cards.length) * 100)
    : 0;
  const completion = cards.length ? Math.round((reviewed.size / cards.length) * 100) : 0;

  $("#count").textContent = cards.length;
  $("#due").textContent = due;
  // The sidebar badge is the deck size; the due count remains in the study status pill.
  $("#dueNav").textContent = cards.length;
  $("#mastery").textContent = `${mastery}%`;
  $("#sideMastery").textContent = `${mastery}% mastered`;
  $("#sideBar").value = mastery;
  $("#complete").textContent = `${completion}% complete`;
  $("#progress").value = completion;
  $("#status").textContent = due ? `${due} due for review` : "All caught up";
  const queuePosition = studyQueueIds.indexOf(card?.id);
  $("#pager").textContent = cards.length
    ? studyQueueIds.length && queuePosition >= 0
      ? `Question ${queuePosition + 1} of ${studyQueueIds.length}`
      : `Question ${index + 1} of ${cards.length}`
    : "No questions yet";
  setCardFlipped(false);
  clearStudyAssist();
  renderCurrentCard(card);
  renderStudyWidgets();
  renderLearningInsights();
  renderSmartWidgets();
  renderSubjectCounts();
  renderDeck();
  $("#oralQuestion").textContent = card?.front || "Add or select a card to begin an oral exam.";
  syncMobileDashboard();
  save(touch);
}

function clearTopicLabel(sentence) {
  const clean = String(sentence || "").replace(/\s+/g, " ").replace(/[.!?]+$/, "").trim();
  const subject = clean.match(
    /^(.{3,90}?)(?:\s+(?:is|are|means|refers|has|have|can|uses|includes|contains|helps|allows|involves|occurs|provides|improves|integrates|compares|supports|ensures|requires|reduces|increases|determines|affects|produces|maintains|coordinates|combines|describes|defines)\b|\s*[:;,—–-])/i
  )?.[1];
  return (subject || clean.split(/\s+/).slice(0, 8).join(" ")).trim();
}

function parseOffline(text, cardMode = "mixed") {
  const clozeDrafts = createClozeDrafts(text, GENERATED_DECK_SIZE);
  if (cardMode === "ncert") {
    return clozeDrafts.map((draft) =>
      newCard(draft.front, draft.back, {
        ...draft,
        template: "ncert",
        examTags: ["NCERT line-by-line"],
        trap: /\b(?:except|exception|only|not|unlike|however|whereas|scientist|proposed|discovered)\b/i.test(
          draft.clozeText
        ),
      })
    );
  }
  if (cardMode === "cloze") {
    return clozeDrafts.map((draft) => newCard(draft.front, draft.back, draft));
  }
  const lines = text
    .replace(/\r/g, "")
    .replace(/[•●▪]/g, "\n")
    .split(/\n+|(?<=[.!?])\s+/)
    .map((line) => line.trim())
    .filter((line) => line.length > 15);
  const output = [];
  const seen = new Set();

  const add = (question, answer) => {
    const normalized = question.toLowerCase();
    if (!seen.has(normalized) && answer.length > 5) {
      seen.add(normalized);
      output.push(newCard(question, answer.replace(/[.!]$/, "")));
    }
  };

  for (const sentence of lines) {
    let match = sentence.match(/^(.{2,70}?)\s+(?:is|are|means|refers to|is defined as)\s+(.{8,300})[.!]?$/i);
    if (match) add(`What is ${match[1]}?`, match[2]);
    else if ((match = sentence.match(/^(.{3,80}?):\s*(.{8,300})$/))) add(`What is ${match[1]}?`, match[2]);
    else if ((match = sentence.match(/^(.{5,130}?)\s+(because|due to|causes|leads to|results in)\s+(.{8,250})/i))) {
      const cause = match[1].trim();
      const connector = match[2].toLowerCase();
      add(
        ["because", "due to"].includes(connector)
          ? `What explains “${cause}”?`
          : `What result follows from “${cause}”?`,
        match[3]
      );
    } else if (sentence.length > 40) {
      add(`What key fact do the notes state about “${clearTopicLabel(sentence)}”?`, sentence);
    }
    if (output.length >= GENERATED_DECK_SIZE) break;
  }
  if (output.length < GENERATED_DECK_SIZE) {
    for (const draft of clozeDrafts) {
      if (output.length >= GENERATED_DECK_SIZE) break;
      if (cardMode === "mixed") {
        output.push(newCard(draft.front, draft.back, draft));
      } else {
        const statement = draft.front.replace(/[.!?]+$/, "");
        add(`Which key term completes this statement: “${statement}”?`, draft.back);
      }
    }
  }
  const specialModes = {
    formula: { label: "Formula Only", section: "Formula / derivation" },
    assertion: { label: "Assertion & Reason", section: "Reasoning breakdown" },
    reaction: { label: "Organic Reaction", section: "Mechanism" },
    journal: { label: "Accounting Double Entry", section: "Debit · Credit · Narration" },
    derivation: { label: "3-Mark Board Derivation", section: "Derivation steps" },
  };
  const special = specialModes[cardMode];
  if (special) {
    output.forEach((card) => {
      card.template = cardMode;
      card.examTags = [special.label];
      card.sections = [{ label: special.section, value: card.back }];
    });
  }
  return output;
}

function completeGeneratedDeck(generated, sourceText, cardMode) {
  const completed = [];
  const seen = new Set();
  const addUnique = (card) => {
    const normalized = normalizeCard(card);
    if (!normalized) return;
    const key = `${normalized.front}\n${normalized.back}`.toLowerCase();
    if (seen.has(key) || completed.length >= GENERATED_DECK_SIZE) return;
    seen.add(key);
    completed.push(normalized);
  };

  generated.forEach(addUnique);
  if (completed.length < GENERATED_DECK_SIZE && sourceText) {
    parseOffline(sourceText, cardMode).forEach(addUnique);
  }
  if (completed.length < GENERATED_DECK_SIZE) {
    throw new Error(
      `MindDeck found ${completed.length} clear questions. Add more detailed notes so it can build all ${GENERATED_DECK_SIZE}.`
    );
  }
  return completed;
}

async function apiRequest(path, { method = "POST", body = null, refreshAuth = false } = {}) {
  const headers = { Accept: "application/json" };
  if (body !== null) {
    headers["Content-Type"] = "application/json";
    headers["X-CSRF-Token"] = CSRF_TOKEN;
  }
  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
    referrerPolicy: "no-referrer",
    headers,
    body: body === null ? undefined : JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({ error: "The server returned an invalid response." }));
  if (response.status === 401 && refreshAuth && path !== "/api/auth/refresh") {
    await apiRequest("/api/auth/refresh", { body: {} });
    return apiRequest(path, { method, body, refreshAuth: false });
  }
  if (!response.ok) {
    const error = new Error(data.error || "The secure request failed.");
    error.status = response.status;
    throw error;
  }
  return data;
}

const apiPost = (path, body) => apiRequest(path, { body });

async function generateWithAI(text, cardMode) {
  const bridge = aiRuntime === "bridge";
  const data = await apiRequest(bridge ? "/api/minddeck-ai" : "/api/generate", {
    body: bridge
      ? { mode: "generate", text, cardMode }
      : { text, provider: "minddeck", cardMode },
    refreshAuth: true,
  });
  return data.cards;
}

async function generateFromImage(file, cardMode) {
  const imageData = await fileToDataUrl(file);
  const bridge = aiRuntime === "bridge";
  const data = await apiRequest(bridge ? "/api/minddeck-ai" : "/api/vision", {
    body: bridge
      ? { mode: "vision", imageData, cardMode }
      : { imageData, provider: "minddeck", cardMode },
    refreshAuth: true,
  });
  return data.cards;
}

async function generateHintsWithAI(card) {
  const bridge = aiRuntime === "bridge";
  const data = await apiRequest(bridge ? "/api/minddeck-ai" : "/api/hint", {
    body: bridge
      ? { mode: "hint", front: card.front, back: card.back }
      : { provider: "minddeck", front: card.front, back: card.back },
    refreshAuth: true,
  });
  return Array.isArray(data.hints) ? data.hints : [];
}

function generationButtonLabel() {
  return aiProviders.minddeck
    ? `✨ Generate ${GENERATED_DECK_SIZE} with AI`
    : `✨ Create ${GENERATED_DECK_SIZE} offline`;
}

function syncProvider() {
  const ready = Boolean(aiProviders.minddeck);
  const signedIn = Boolean(authState.user);
  $("#provider").value = ready ? "minddeck" : "offline";
  $("#generationProviderLabel").textContent = ready
    ? signedIn
      ? "MindDeck AI · Ready"
      : "MindDeck AI · Sign in to generate"
    : "Offline creation · AI reconnecting";
  $("#aiStatusBadge").textContent = ready ? (signedIn ? "READY" : "SIGN IN") : "OFFLINE";
  $("#aiStatusBadge").dataset.state = ready ? (signedIn ? "ready" : "signin") : "offline";
  $("#aiStatusTitle").textContent = ready
    ? signedIn
      ? "Your AI workspace is ready"
      : "Sign in once to start creating"
    : "Offline creation is still available";
  $("#securityHint").textContent = ready
    ? signedIn
      ? "🔒 Your account connects securely to MindDeck AI. No keys or access codes needed."
      : "✨ MindDeck AI is built in. Sign in to keep generation private and tied to your account."
    : "📚 You can still build cards locally while MindDeck AI reconnects.";
  $("#generate").textContent = generationButtonLabel();
}

async function loadConfig() {
  const getConfig = async (path) => {
    const response = await fetch(path, {
      cache: "no-store",
      credentials: "same-origin",
      redirect: "error",
      referrerPolicy: "no-referrer",
      headers: { Accept: "application/json" },
    });
    const data = await response.json();
    if (!response.ok) throw new Error();
    return data;
  };
  const [flaskResult, bridgeResult] = await Promise.allSettled([
    getConfig("/api/config"),
    getConfig("/api/minddeck-ai"),
  ]);
  const flaskConfig = flaskResult.status === "fulfilled" ? flaskResult.value : null;
  const bridgeConfig = bridgeResult.status === "fulfilled" ? bridgeResult.value : null;
  aiProviders = flaskConfig?.providers || { minddeck: false, openai: false, gemini: false };
  if (bridgeConfig?.ready === true) {
    aiProviders.minddeck = true;
    aiRuntime = "bridge";
  } else {
    aiRuntime = "flask";
  }
  syncProvider();
}

function openSettings() {
  syncProvider();
  $("#settingsModal").classList.add("open");
}

function openOralExam() {
  const card = cards[index];
  if (!card) {
    openNotesComposer();
    toast("Create a card before starting an oral exam");
    return;
  }
  $("#oralQuestion").textContent = card.front;
  $("#oralModal").classList.add("open");
}

function closeOralExam(returnToStudy = false) {
  stopFeynmanRecording();
  $("#oralModal").classList.remove("open");
  if (returnToStudy) setWorkspace("study");
}

function setSyncStatus(message, state = "idle") {
  const status = $("#syncState");
  status.textContent = message;
  status.dataset.state = state;
}

function markOnboardingComplete() {
  document.documentElement.classList.add("splash-seen");
  try {
    localStorage.setItem(ONBOARDING_STORE, "1");
  } catch {
    // The authenticated cookie still skips onboarding while this session is valid.
  }
}

function updateAccountUI() {
  const signedIn = Boolean(authState.user);
  const googleAvailable = authState.enabled && authState.googleEnabled && !signedIn;
  const account = $("#account");
  const accountLabel = $("#accountLabel");
  const accountAvatar = $("#accountAvatar");
  const accountAvatarImage = $("#accountAvatarImage");
  const accountAvatarFallback = $("#accountAvatarFallback");
  const accountGuestIcon = $("#accountGuestIcon");
  const avatarUrl = signedIn ? authState.user.avatarUrl || "" : "";
  const profile = mobileProfile();
  const fallback = profile.avatar || friendlyAccountName().trim().charAt(0).toUpperCase() || "M";
  account.disabled = !authState.enabled;
  accountLabel.textContent = signedIn
    ? "My account"
    : authState.enabled
      ? "Sign in"
      : "Cloud setup needed";
  account.setAttribute("aria-label", signedIn ? "Open account" : accountLabel.textContent);
  accountAvatar.hidden = !signedIn;
  if (accountGuestIcon) accountGuestIcon.hidden = signedIn;
  if (signedIn) {
    accountAvatarFallback.textContent = fallback;
    const showFallback = () => {
      accountAvatarImage.hidden = true;
      accountAvatarFallback.hidden = false;
    };
    accountAvatarImage.onerror = showFallback;
    accountAvatarImage.onload = () => {
      if (!accountAvatarImage.naturalWidth) {
        showFallback();
        return;
      }
      accountAvatarImage.hidden = false;
      accountAvatarFallback.hidden = true;
    };
    showFallback();
    if (avatarUrl && !profile.avatar) accountAvatarImage.src = avatarUrl;
    else accountAvatarImage.removeAttribute("src");
  } else {
    accountAvatarImage.removeAttribute("src");
    accountAvatarImage.hidden = true;
    accountAvatarFallback.hidden = false;
  }
  $("#authSignedOut").hidden = signedIn;
  $("#authSignedIn").hidden = !signedIn;
  $("#authUser").textContent = signedIn ? authState.user.email || "Signed in" : "";
  $("#googleSignIn").hidden = !googleAvailable;
  $("#oauthSecurity").hidden = !googleAvailable;
  $("#emailAuthDivider").hidden = !googleAvailable;
  if (!authState.enabled) setSyncStatus("Saved on this device · cloud setup pending");
  else if (!signedIn) setSyncStatus("Saved on this device · sign in to sync");
  updateMobileAccountUI();
  if (signedIn) {
    markOnboardingComplete();
    window.minddeckDismissSplash?.();
  }
  syncProvider();
}

async function fetchAuthConfig() {
  const response = await fetch("/api/auth/config", {
    cache: "no-store",
    credentials: "same-origin",
    redirect: "error",
    referrerPolicy: "no-referrer",
    headers: { Accept: "application/json" },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error("Could not check cloud account status.");
  return data;
}

async function loadAccount() {
  try {
    let data = await fetchAuthConfig();
    if (data.enabled && !data.user && data.canRefresh) {
      try {
        await apiPost("/api/auth/refresh", {});
        data = await fetchAuthConfig();
      } catch {
        data.user = null;
      }
    }
    authState = {
      enabled: Boolean(data.enabled),
      googleEnabled: Boolean(data.googleEnabled),
      user:
        data.user &&
        typeof data.user.email === "string" &&
        typeof data.user.accountKey === "string" &&
        /^[a-f0-9]{24}$/.test(data.user.accountKey)
          ? {
              email: data.user.email,
              accountKey: data.user.accountKey,
              avatarUrl:
                typeof data.user.avatarUrl === "string" &&
                data.user.avatarUrl.startsWith("https://lh3.googleusercontent.com/")
                  ? data.user.avatarUrl
                  : "",
            }
          : null,
    };
    if (authState.user) activateAccountStore(authState.user.accountKey);
    updateAccountUI();
    if (authState.user) await reconcileCloudDeck();
  } catch {
    authState = { enabled: false, googleEnabled: false, user: null };
    updateAccountUI();
  }
}

async function uploadCloudDeck() {
  if (!authState.user || syncInFlight) return;
  if (cards.length > 500) {
    setSyncStatus("Local only · cloud decks support 500 cards", "error");
    return;
  }
  syncInFlight = true;
  const savingVersion = deckUpdatedAt;
  setSyncStatus("Syncing…", "busy");
  try {
    await apiRequest("/api/deck", {
      method: "PUT",
      body: { deck: deckSnapshot() },
      refreshAuth: true,
    });
    if (removeGuestAfterSync) {
      localStorage.removeItem(GUEST_STORE);
      removeGuestAfterSync = false;
    }
    accountStoreFresh = false;
    setSyncStatus("Saved securely in the cloud", "ok");
    if (deckUpdatedAt > savingVersion) scheduleCloudSync();
  } catch (error) {
    if (error.status === 401) {
      authState.user = null;
      updateAccountUI();
    }
    setSyncStatus(error.message || "Cloud sync paused", "error");
  } finally {
    syncInFlight = false;
  }
}

function scheduleCloudSync() {
  window.clearTimeout(syncTimer);
  syncTimer = window.setTimeout(uploadCloudDeck, 900);
}

async function reconcileCloudDeck() {
  setSyncStatus("Checking cloud memory…", "busy");
  try {
    const data = await apiRequest("/api/deck", { method: "GET", refreshAuth: true });
    const cloud = data.deck;
    if (!cloud) {
      removeGuestAfterSync = accountStoreFresh;
      await uploadCloudDeck();
      return;
    }
    const cloudUpdatedAt = finiteNumber(cloud.updatedAt, 0, 0, Number.MAX_SAFE_INTEGER);
    if (!accountStoreFresh && cards.length && deckUpdatedAt > cloudUpdatedAt) {
      await uploadCloudDeck();
      return;
    }
    if (!applyDeckState(cloud)) throw new Error("The cloud deck was invalid.");
    const upgraded = upgradeLegacyShortDeck(cloud);
    accountStoreFresh = false;
    render(upgraded);
    setSyncStatus(upgraded ? `${GENERATED_DECK_SIZE}-question deck saved` : "Cloud deck restored", "ok");
    if (upgraded) toast(`Your saved deck now has ${GENERATED_DECK_SIZE} clear questions`);
  } catch (error) {
    setSyncStatus(error.message || "Cloud sync paused", "error");
  }
}

function setPasswordVisibility(visible) {
  const password = $("#authPassword");
  const toggle = $("#togglePassword");
  password.type = visible ? "text" : "password";
  toggle.textContent = visible ? "Hide" : "Show";
  toggle.setAttribute("aria-label", visible ? "Hide password" : "Show password");
  toggle.setAttribute("aria-pressed", String(visible));
}

function setAuthMode(mode) {
  const authDialog = $("#authDialog");
  const signup = mode === "signup";
  if (!authDialog) return;
  authDialog.dataset.authMode = signup ? "signup" : "signin";
  $("#authTitle").textContent = signup ? "Create your account" : "Welcome back";
  $("#authCopy").textContent = signup
    ? "Start your study streak today. MindDeck will build a revision plan around your cards."
    : "Sign in and pick up your study plan where you left off.";
  $("#authModePrompt").textContent = signup ? "Already have an account?" : "New to MindDeck?";
  $("#authModeToggle").textContent = signup ? "Log in" : "Create an account";
  $("#authPassword").autocomplete = signup ? "new-password" : "current-password";
  $("#authPassword").placeholder = signup ? "Create a strong password" : "Enter your password";
  $("#authPasswordHelp").textContent = signup
    ? "Use at least 12 characters."
    : "Use the password you created for this account.";
  $("#emailAuthDivider span").textContent = signup ? "or create with email" : "or use email";
}

function openAuthFlow(mode = "signin") {
  $("#authError").textContent = "";
  $("#authMessage").textContent = "";
  setAuthMode(mode);
  $("#authModal").classList.add("open");
  setPasswordVisibility(false);
  window.setTimeout(() => {
    const target = mode === "signup" ? $("#authFullName") : $("#authEmail");
    target?.focus({ preventScroll: true });
  }, 50);
}

async function submitAccount(path) {
  const emailInput = $("#authEmail");
  const passwordInput = $("#authPassword");
  const email = emailInput.value.trim();
  const password = passwordInput.value;
  const creatingAccount = path.endsWith("signup");
  const fullName = $("#authFullName")?.value.trim() || "";
  const buttons = [$("#signIn"), $("#signUp")];
  $("#authError").textContent = "";
  $("#authMessage").textContent = "";
  emailInput.value = email;
  if (!email || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    $("#authError").textContent = "Enter a valid email address.";
    emailInput.focus();
    return;
  }
  if (!password) {
    $("#authError").textContent = "Enter your password.";
    passwordInput.focus();
    return;
  }
  if (creatingAccount && password.length < 12) {
    $("#authError").textContent = "New passwords need at least 12 characters.";
    passwordInput.focus();
    return;
  }
  if (creatingAccount && (fullName.length < 2 || fullName.length > 80)) {
    $("#authError").textContent = "Enter your full name.";
    $("#authFullName").focus();
    return;
  }
  buttons.forEach((button) => {
    button.disabled = true;
  });
  try {
    const data = await apiPost(path, { email, password });
    passwordInput.value = "";
    if (creatingAccount) saveMobileProfile({ ...mobileProfile(), name: fullName });
    $("#authMessage").textContent = data.message || "Signed in successfully.";
    if (path.endsWith("signin") || data.signedIn) {
      await loadAccount();
      if (authState.user) {
        $("#authModal").classList.remove("open");
        setWorkspace("home", false);
        syncMobileDashboard();
      }
    }
  } catch (error) {
    passwordInput.value = "";
    $("#authError").textContent = error.message || "Account request failed.";
  } finally {
    setPasswordVisibility(false);
    buttons.forEach((button) => {
      button.disabled = false;
    });
  }
}

async function startGoogleSignIn() {
  const button = $("#googleSignIn");
  $("#authError").textContent = "";
  $("#authMessage").textContent = "";
  button.disabled = true;
  try {
    const data = await apiPost("/api/auth/google/start", {});
    const authorizationUrl = new URL(data.authorizationUrl);
    const safeSupabaseUrl =
      authorizationUrl.protocol === "https:" &&
      authorizationUrl.hostname.endsWith(".supabase.co") &&
      authorizationUrl.pathname === "/auth/v1/authorize" &&
      authorizationUrl.searchParams.get("provider") === "google";
    if (!safeSupabaseUrl) throw new Error("The Google sign-in address was invalid.");
    window.location.assign(authorizationUrl.href);
  } catch (error) {
    $("#authError").textContent = error.message || "Google Sign-In could not start.";
    button.disabled = false;
  }
}

function consumeAuthResult() {
  const params = new URLSearchParams(window.location.search);
  const result = params.get("auth");
  if (!result) return "";
  params.delete("auth");
  const query = params.toString();
  const cleanUrl = `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`;
  window.history.replaceState(null, "", cleanUrl);
  return result === "google-ok" ? "ok" : "error";
}

function next(touch = true) {
  if (cards.length && studyQueueIds.length) {
    const position = Math.max(0, studyQueueIds.indexOf(cards[index]?.id));
    const nextId = studyQueueIds[(position + 1) % studyQueueIds.length];
    const queuedIndex = cards.findIndex((card) => card.id === nextId);
    index = queuedIndex >= 0 ? queuedIndex : (index + 1) % cards.length;
  } else if (cards.length) index = (index + 1) % cards.length;
  render(touch && cards.length > 0);
}

function previous(touch = true) {
  if (cards.length && studyQueueIds.length) {
    const position = Math.max(0, studyQueueIds.indexOf(cards[index]?.id));
    const previousId = studyQueueIds[(position - 1 + studyQueueIds.length) % studyQueueIds.length];
    const queuedIndex = cards.findIndex((card) => card.id === previousId);
    index = queuedIndex >= 0 ? queuedIndex : (index - 1 + cards.length) % cards.length;
  } else if (cards.length) index = (index - 1 + cards.length) % cards.length;
  render(touch && cards.length > 0);
}

function markMistakeCard(card) {
  if (!card) return false;
  const wasMistake = card.mistake;
  const deadline = Date.now() + 48 * 60 * 60 * 1_000;
  const currentDue = Date.parse(card.dueDate);
  card.mistake = true;
  card.mistakeAt = new Date().toISOString();
  card.priority = "high";
  if (!Number.isFinite(currentDue) || currentDue > deadline) card.dueDate = new Date(deadline).toISOString();
  return !wasMistake;
}

function logCurrentMistake() {
  const card = cards[index];
  if (!card) {
    toast("Create a deck first");
    return;
  }
  const newlyLogged = markMistakeCard(card);
  render(true);
  toast(newlyLogged ? "Added to Mistake Notebook · review due within 48h" : "Already in your Mistake Notebook");
}

function score(quality) {
  const card = cards[index];
  if (!card) return;
  const wasLeech = card.leech;
  const wasMistake = card.mistake;
  if (quality < 3) {
    card.repetition = 0;
    card.interval = quality === 1 ? 0 : 1;
  } else {
    card.interval =
      card.repetition === 0
        ? 1
        : card.repetition === 1
          ? quality === 4
            ? 6
            : 3
          : Math.max(1, Math.round(card.interval * card.easeFactor * (quality === 4 ? 1.3 : 1)));
    card.repetition += 1;
  }
  card.easeFactor = Math.max(
    1.3,
    card.easeFactor + (quality === 4 ? 0.1 : quality === 3 ? 0 : quality === 2 ? -0.15 : -0.3)
  );
  card.dueDate = new Date(Date.now() + card.interval * 86_400_000).toISOString();
  card.reviews += 1;
  card.lastScore = quality;
  if (quality === 1) card.lapseStreak += 1;
  else card.lapseStreak = 0;
  if (quality === 1) markMistakeCard(card);
  if (card.lapseStreak >= 4) card.leech = true;
  const today = localDateKey();
  const dailyReviews = new Map(studyStats.dailyReviews.map((item) => [item.date, item.count]));
  dailyReviews.set(today, Math.min(10_000, (dailyReviews.get(today) || 0) + 1));
  studyStats = normalizeStudyStats({
    ...studyStats,
    dailyReviews: [...dailyReviews.entries()].map(([date, count]) => ({ date, count })),
  });
  reviewed.add(card.id);
  next();
  if (!wasLeech && card.leech) toast("Leech detected · added to Cram & Break Down");
  else if (!wasMistake && card.mistake) toast("Mistake logged · high-priority review within 48h");
}

async function readFile(file) {
  try {
    if (!file) return;
    if (file.size > 15 * 1024 * 1024) throw new Error("File must be under 15 MB.");
    if (file.name.toLowerCase().endsWith(".pdf")) {
      const pdfjs = await import(PDF_MODULE);
      pdfjs.GlobalWorkerOptions.workerSrc = PDF_WORKER;
      const pdf = await pdfjs.getDocument({ data: await file.arrayBuffer() }).promise;
      if (pdf.numPages > 200) throw new Error("PDFs are limited to 200 pages.");
      const pages = [];
      let characters = 0;
      for (let pageNumber = 1; pageNumber <= pdf.numPages; pageNumber += 1) {
        const page = await pdf.getPage(pageNumber);
        const content = await page.getTextContent();
        const text = content.items.map((item) => item.str).join(" ");
        pages.push(text);
        characters += text.length;
        if (characters > 200_000) throw new Error("Extracted PDF text is too large.");
      }
      fileText = pages.join("\n");
    } else {
      fileText = (await file.text()).slice(0, 200_000);
    }
    $("#fileInfo").hidden = false;
    $("#fileInfo").textContent = `✓ ${file.name.slice(0, 120)} · ${fileText.length.toLocaleString()} characters`;
  } catch (error) {
    $("#error").textContent = error.message || "Could not read file.";
  }
}

function openNotesComposer() {
  setWorkspace("generate", false);
  const notesTab = $('[data-tab="notes"]');
  if (!notesTab.classList.contains("active")) notesTab.click();
  const behavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
  $("#createPanel").scrollIntoView({ behavior, block: "start" });
  window.setTimeout(() => $("#notes").focus(), 350);
}

function startDashboardReview() {
  if (!cards.length) {
    openNotesComposer();
    toast("Create a deck first");
    return;
  }
  studyQueueIds = [];
  const dueIndex = cards.findIndex((card) => Date.parse(card.dueDate) <= Date.now());
  if (dueIndex >= 0) index = dueIndex;
  setWorkspace("study", false);
  render(false);
  const behavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
  $("#studyPanel").scrollIntoView({ behavior, block: "start" });
  toast(dueIndex >= 0 ? "Due review ready" : "Continuing your deck");
}

async function showNextHint() {
  const card = cards[index];
  if (!card) {
    toast("Create a deck first");
    return;
  }
  const button = $("#hintButton");
  button.disabled = true;
  try {
    if (!card.hints.length) {
      if (aiProviders.minddeck && authState.user) {
        try {
          card.hints = (await generateHintsWithAI(card)).slice(0, 3);
        } catch {
          card.hints = buildOfflineHints(card.back);
          toast("Using offline hints for this card");
        }
      } else {
        card.hints = buildOfflineHints(card.back);
      }
      save(true);
    }
    hintStep = Math.min(card.hints.length, hintStep + 1);
    const list = $("#hintList");
    list.replaceChildren();
    card.hints.slice(0, hintStep).forEach((hint, hintIndex) => {
      const item = document.createElement("li");
      const number = document.createElement("strong");
      number.textContent = String(hintIndex + 1);
      const copy = document.createElement("span");
      copy.textContent = hint;
      item.append(number, copy);
      list.append(item);
    });
    $("#hintPanel").hidden = false;
    button.textContent = hintStep >= card.hints.length ? "✓ All hints shown" : "✦ Next hint";
  } finally {
    button.disabled = false;
  }
}

function speakCurrentAnswer() {
  const card = cards[index];
  if (!card || !("speechSynthesis" in window)) {
    toast(card ? "Text-to-speech is unavailable in this browser" : "Create a deck first");
    return;
  }
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(card.type === "cloze" ? clozeBack(card.clozeText) : card.back);
  utterance.rate = 0.92;
  utterance.pitch = 1;
  window.speechSynthesis.speak(utterance);
  toast("Reading the model answer");
}

function stopFeynmanRecording() {
  window.clearInterval(feynmanTimer);
  feynmanTimer = null;
  if (feynmanRecorder?.state === "recording") feynmanRecorder.stop();
  if (feynmanRecognition) {
    try {
      feynmanRecognition.stop();
    } catch {
      // Recognition may already have stopped after a period of silence.
    }
  }
}

async function toggleFeynmanRecording() {
  if (feynmanRecorder?.state === "recording") {
    stopFeynmanRecording();
    return;
  }
  if (!cards[index]) {
    toast("Create a deck first");
    return;
  }
  if (!navigator.mediaDevices?.getUserMedia || !("MediaRecorder" in window)) {
    toast("Voice recording is unavailable. You can type your explanation instead.");
    $("#feynmanTranscript").focus();
    return;
  }
  try {
    feynmanStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    feynmanChunks = [];
    feynmanRecorder = new MediaRecorder(feynmanStream);
    feynmanRecorder.addEventListener("dataavailable", (event) => {
      if (event.data.size) feynmanChunks.push(event.data);
    });
    feynmanRecorder.addEventListener("stop", () => {
      if (cards[index]?.id !== feynmanCardId) {
        feynmanStream?.getTracks().forEach((track) => track.stop());
        feynmanStream = null;
        return;
      }
      const blob = new Blob(feynmanChunks, { type: feynmanRecorder.mimeType || "audio/webm" });
      if (feynmanAudioUrl) URL.revokeObjectURL(feynmanAudioUrl);
      feynmanAudioUrl = URL.createObjectURL(blob);
      $("#feynmanAudio").src = feynmanAudioUrl;
      $("#feynmanAudio").hidden = false;
      $("#feynmanRecord").textContent = "● Record again";
      $("#feynmanStatus").textContent = "Recording complete. Type a short transcript, then compare.";
      feynmanStream?.getTracks().forEach((track) => track.stop());
      feynmanStream = null;
    });
    feynmanRecognition = null;
    feynmanCardId = cards[index].id;
    feynmanStartedAt = Date.now();
    feynmanRecorder.start();
    $("#feynmanRecord").textContent = "■ Stop recording";
    $("#feynmanStatus").textContent = "Recording · 15 seconds remaining";
    feynmanTimer = window.setInterval(() => {
      const remaining = Math.max(0, 15 - Math.floor((Date.now() - feynmanStartedAt) / 1_000));
      $("#feynmanStatus").textContent = `Recording · ${remaining} ${remaining === 1 ? "second" : "seconds"} remaining`;
      if (!remaining) stopFeynmanRecording();
    }, 250);
  } catch {
    toast("Microphone access was not granted. Type your explanation instead.");
    $("#feynmanTranscript").focus();
  }
}

function compareFeynmanExplanation() {
  const card = cards[index];
  const transcript = $("#feynmanTranscript").value.trim();
  if (!card || transcript.length < 8) {
    toast("Record or type a fuller explanation first");
    return;
  }
  const comparison = compareExplanation(transcript, card.back);
  const result = $("#feynmanResult");
  const guidance =
    comparison.score >= 75
      ? "Strong explanation — you covered the important ideas."
      : comparison.score >= 45
        ? `Good start. Add: ${comparison.missing.join(", ") || "one more key detail"}.`
        : `Break it down again using: ${comparison.missing.join(", ") || "the main definition"}.`;
  result.textContent = `${comparison.score}% concept match · ${guidance}`;
  result.dataset.score = comparison.score >= 75 ? "high" : comparison.score >= 45 ? "medium" : "low";
  result.hidden = false;
}

function renderLeechDeck() {
  const list = $("#leechList");
  list.replaceChildren();
  const leeches = cards.filter((card) => card.leech);
  if (!leeches.length) {
    const empty = document.createElement("p");
    empty.className = "modalEmpty";
    empty.textContent = "No leeches yet. MindDeck tags a card after four consecutive Again ratings.";
    list.append(empty);
    $("#startLeechCram").disabled = true;
    return;
  }
  $("#startLeechCram").disabled = false;
  leeches.forEach((card) => {
    const row = document.createElement("div");
    row.className = "leechRow";
    const copy = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = card.front;
    const detail = document.createElement("small");
    detail.textContent = `${card.lapseStreak} consecutive lapses · try explaining why the answer is true`;
    copy.append(title, detail);
    const reset = document.createElement("button");
    reset.type = "button";
    reset.className = "btn";
    reset.dataset.resetLeech = card.id;
    reset.textContent = "Reset tag";
    row.append(copy, reset);
    list.append(row);
  });
}

function startLeechCram() {
  studyQueueIds = cards.filter((card) => card.leech).map((card) => card.id);
  if (!studyQueueIds.length) return;
  index = cards.findIndex((card) => card.id === studyQueueIds[0]);
  $("#leechModal").classList.remove("open");
  setWorkspace("study", false);
  render(false);
  $("#studyPanel").scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" });
  toast(`${studyQueueIds.length}-card rescue deck ready`);
}

function startCramSprint() {
  if (!cards.length) {
    openNotesComposer();
    toast("Create a deck first");
    return;
  }
  const due = cards.filter((card) => Date.parse(card.dueDate) <= Date.now());
  const sprintCards = (due.length ? due : cards).slice(0, GENERATED_DECK_SIZE);
  studyQueueIds = sprintCards.map((card) => card.id);
  index = cards.findIndex((card) => card.id === studyQueueIds[0]);
  selectTimerMode("focus");
  resetTimer();
  toggleTimer();
  setWorkspace("study", false);
  render(false);
  $("#studyPanel").scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" });
  toast(`${studyQueueIds.length}-card Pomodoro sprint started`);
}

function isFormulaCramCard(card) {
  if (!card) return false;
  if (card.template === "formula") return true;
  const searchable = [card.subject, card.front, ...card.examTags].join(" ");
  return /\b(?:formula|constant|identity|equation|dimension|unit)\b/i.test(searchable);
}

function startStudyQueue(queue, message, closeSelector = "") {
  if (!queue.length) return;
  studyQueueIds = queue.map((card) => card.id);
  index = cards.findIndex((card) => card.id === studyQueueIds[0]);
  if (closeSelector) $(closeSelector).classList.remove("open");
  setWorkspace("study", false);
  render(false);
  $("#studyPanel").scrollIntoView({
    behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
    block: "start",
  });
  toast(message);
}

function startFormulaCram() {
  const formulas = cards.filter(isFormulaCramCard);
  if (!formulas.length) {
    toast("Add a Formula · Unit · Dimension card first");
    return;
  }
  startStudyQueue(formulas, `${formulas.length}-card Formula Cram ready`);
}

function startSubjectReview(workspaceKey) {
  const config = SUBJECT_WORKSPACES[workspaceKey];
  if (!config) return;
  const matching = cards.filter((card) => subjectMatches(card, workspaceKey));
  if (!matching.length) {
    $("#subjectSelect").value = config.subject;
    openNotesComposer();
    toast(`Create your first ${config.label} cards`);
    return;
  }
  const due = matching.filter((card) => Date.parse(card.dueDate) <= Date.now());
  const queue = due.length ? due : matching;
  startStudyQueue(queue, `${queue.length}-card ${config.label} revision ready`);
}

function renderMistakeNotebook() {
  const list = $("#mistakeList");
  list.replaceChildren();
  const mistakes = cards
    .filter((card) => card.mistake)
    .sort((left, right) => Date.parse(left.dueDate) - Date.parse(right.dueDate));
  $("#startMistakeReview").disabled = !mistakes.length;
  if (!mistakes.length) {
    const empty = document.createElement("p");
    empty.className = "modalEmpty";
    empty.textContent = "No active mistakes. Rate a missed card Again or use Log mistake during study.";
    list.append(empty);
    return;
  }
  mistakes.forEach((card) => {
    const row = document.createElement("div");
    row.className = "leechRow";
    const copy = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = card.front;
    const detail = document.createElement("small");
    const due = Date.parse(card.dueDate) <= Date.now() ? "due now" : `due ${new Date(card.dueDate).toLocaleString()}`;
    detail.textContent = `High priority · ${due}`;
    copy.append(title, detail);
    const actions = document.createElement("div");
    actions.className = "mistakeActions";
    const resolve = document.createElement("button");
    resolve.type = "button";
    resolve.className = "btn";
    resolve.dataset.resolveMistake = card.id;
    resolve.textContent = "Resolve";
    actions.append(resolve);
    row.append(copy, actions);
    list.append(row);
  });
}

function startMistakeReview() {
  const mistakes = cards
    .filter((card) => card.mistake)
    .sort((left, right) => Date.parse(left.dueDate) - Date.parse(right.dueDate));
  startStudyQueue(mistakes, `${mistakes.length}-card mistake review ready`, "#mistakeModal");
}

function syncExamTemplate() {
  const template = $("#examTemplate").value;
  $$('[data-exam-fields]').forEach((group) => {
    group.hidden = group.dataset.examFields !== template;
  });
}

function resetExamCreator() {
  [
    "examSubject", "examTags", "examNcertLine", "examReactionPrompt", "examReactants", "examReagent",
    "examIntermediate", "examMajor", "examMinor", "examFormulaName", "examFormula", "examUnit",
    "examDimension", "examAssumptions", "examTransaction", "examDebit", "examCredit", "examNarration",
    "examGraphTitle", "examXAxis", "examYAxis", "examGraphExplain", "examAssertion", "examReason",
    "examAssertionExplain", "examDerivationPrompt", "examDerivationSteps",
  ].forEach((id) => {
    $(`#${id}`).value = "";
  });
  $("#examTrap").checked = false;
  $("#examTemplate").value = "ncert";
  $("#examGraphShape").value = "downward";
  $("#examAssertionOption").selectedIndex = 0;
  syncExamTemplate();
}

function openExamCreator() {
  resetExamCreator();
  $("#examModal").classList.add("open");
  window.setTimeout(() => $("#examSubject").focus(), 80);
}

function examValue(id, maximum = 500) {
  return $(`#${id}`).value.trim().slice(0, maximum);
}

function compactSections(sections) {
  return sections.filter((section) => section.label && section.value);
}

function buildExamCard() {
  const template = $("#examTemplate").value;
  const subject = examValue("examSubject", 80);
  const examTags = [...new Set(
    examValue("examTags", 240)
      .split(",")
      .map((tag) => tag.trim().slice(0, 60))
      .filter(Boolean)
  )].slice(0, 6);
  const enhancements = { template, subject, examTags, trap: $("#examTrap").checked };
  let front = "";
  let back = "";

  if (template === "ncert") {
    const clozeText = examValue("examNcertLine", 2_000);
    if (!/\{\{(?:c\d+::)?[^}]+\}\}/i.test(clozeText)) throw new Error("Wrap one NCERT keyword in {{double braces}}.");
    front = clozeFront(clozeText);
    back = clozeBack(clozeText);
    Object.assign(enhancements, {
      type: "cloze",
      clozeText,
      sections: examTags.length ? [{ label: "Source / occurrence", value: examTags.join(" · ") }] : [],
    });
  } else if (template === "reaction") {
    const prompt = examValue("examReactionPrompt");
    const sections = compactSections([
      { label: "Reactants", value: examValue("examReactants") },
      { label: "Reagent / conditions", value: examValue("examReagent") },
      { label: "Intermediate", value: examValue("examIntermediate") },
      { label: "Major product", value: examValue("examMajor") },
      { label: "Minor product", value: examValue("examMinor") },
    ]);
    if (!prompt || sections.length < 3) throw new Error("Add the reaction and at least three mechanism stages.");
    front = `Complete the mechanism: ${prompt}`;
    back = sections.find((section) => section.label === "Major product")?.value || sections.at(-1).value;
    enhancements.sections = sections;
  } else if (template === "formula") {
    const name = examValue("examFormulaName");
    const formula = examValue("examFormula");
    const unit = examValue("examUnit");
    const dimension = examValue("examDimension");
    if (!formula || !unit || !dimension) throw new Error("Add the formula, SI unit, and dimensional formula.");
    front = formula;
    back = `${unit} · ${dimension}`;
    enhancements.examTags = [...new Set([...examTags, "Formula"])].slice(0, 6);
    enhancements.sections = compactSections([
      { label: "Quantity", value: name },
      { label: "SI unit", value: unit },
      { label: "Dimensional formula", value: dimension },
      { label: "Boundary conditions / assumptions", value: examValue("examAssumptions") },
    ]);
  } else if (template === "journal") {
    const transaction = examValue("examTransaction");
    const debit = examValue("examDebit");
    const credit = examValue("examCredit");
    const narration = examValue("examNarration");
    if (!transaction || !debit || !credit || !narration) throw new Error("Complete the transaction, debit, credit, and narration.");
    front = transaction;
    back = `${debit} Dr. · ${credit} Cr.`;
    enhancements.sections = [
      { label: "Debit", value: debit },
      { label: "Credit", value: credit },
      { label: "Narration", value: narration },
    ];
  } else if (template === "graph") {
    const title = examValue("examGraphTitle");
    const explanation = examValue("examGraphExplain");
    if (!title || !explanation) throw new Error("Add the graph relationship and its interpretation.");
    front = title;
    back = explanation;
    enhancements.graphShape = $("#examGraphShape").value;
    enhancements.sections = compactSections([
      { label: "X-axis", value: examValue("examXAxis", 60) || "X" },
      { label: "Y-axis", value: examValue("examYAxis", 60) || "Y" },
      { label: "Shift / rotation", value: explanation },
    ]);
  } else if (template === "assertion") {
    const assertion = examValue("examAssertion");
    const reason = examValue("examReason");
    const option = $("#examAssertionOption").value;
    const explanation = examValue("examAssertionExplain");
    if (!assertion || !reason || !explanation) throw new Error("Complete the assertion, reason, and breakdown.");
    front = `Assertion (A): ${assertion}\nReason (R): ${reason}`;
    back = option;
    enhancements.sections = [
      { label: "Correct option", value: option },
      { label: "Why", value: explanation },
    ];
  } else if (template === "derivation") {
    const prompt = examValue("examDerivationPrompt");
    const steps = examValue("examDerivationSteps", 4_000)
      .split("\n")
      .map((step) => step.trim().slice(0, 500))
      .filter(Boolean)
      .slice(0, 12);
    if (!prompt || steps.length < 2) throw new Error("Add a derivation prompt and at least two steps.");
    front = prompt;
    back = steps.at(-1);
    enhancements.sections = steps.map((step, stepIndex) => ({ label: `Step ${stepIndex + 1}`, value: step }));
  }

  if (!front || !back) throw new Error("Complete the required card fields.");
  return newCard(front, back, enhancements);
}

function saveExamCard() {
  try {
    const card = buildExamCard();
    cards.push(card);
    index = cards.length - 1;
    studyQueueIds = [];
    $("#examModal").classList.remove("open");
    resetExamCreator();
    render(true);
    toast(`${TEMPLATE_LABELS[card.template]} card added`);
  } catch (error) {
    toast(error.message || "Could not add this exam card");
  }
}

function shuffled(values) {
  const output = [...values];
  for (let cursor = output.length - 1; cursor > 0; cursor -= 1) {
    const swap = Math.floor(Math.random() * (cursor + 1));
    [output[cursor], output[swap]] = [output[swap], output[cursor]];
  }
  return output;
}

function finishMatchGame() {
  window.clearInterval(matchTimer);
  matchTimer = null;
  const elapsed = Math.max(1, Math.round((Date.now() - matchState.startedAt) / 1_000));
  $("#matchStatus").textContent = `Complete in ${elapsed}s · ${matchState.mistakes} ${matchState.mistakes === 1 ? "mistake" : "mistakes"}`;
  $("#matchStatus").dataset.state = "complete";
}

function selectMatchCard(button) {
  if (!matchState || button.disabled) return;
  const side = button.dataset.matchSide;
  const previous = matchState[side];
  if (previous) previous.classList.remove("selected");
  matchState[side] = button;
  button.classList.add("selected");
  if (!matchState.term || !matchState.definition) return;
  const term = matchState.term;
  const definition = matchState.definition;
  if (term.dataset.matchId === definition.dataset.matchId) {
    [term, definition].forEach((item) => {
      item.disabled = true;
      item.classList.remove("selected");
      item.classList.add("matched");
    });
    matchState.matches += 1;
    $("#matchScore").textContent = `${matchState.matches}/${matchState.total}`;
    if (matchState.matches === matchState.total) finishMatchGame();
  } else {
    matchState.mistakes += 1;
    term.classList.add("missed");
    definition.classList.add("missed");
    window.setTimeout(() => {
      term.classList.remove("selected", "missed");
      definition.classList.remove("selected", "missed");
    }, 420);
  }
  matchState.term = null;
  matchState.definition = null;
}

function startMatchGame() {
  if (cards.length < 2) {
    toast("Add at least two cards first");
    return;
  }
  const selected = shuffled(cards).slice(0, Math.min(6, cards.length));
  const terms = $("#matchTerms");
  const definitions = $("#matchDefinitions");
  terms.replaceChildren();
  definitions.replaceChildren();
  const makeButton = (card, side, text) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "matchCard";
    button.dataset.matchId = card.id;
    button.dataset.matchSide = side;
    button.textContent = text;
    button.addEventListener("click", () => selectMatchCard(button));
    return button;
  };
  selected.forEach((card) => terms.append(makeButton(card, "term", card.front)));
  shuffled(selected).forEach((card) => definitions.append(makeButton(card, "definition", card.back)));
  matchState = { term: null, definition: null, matches: 0, mistakes: 0, total: selected.length, startedAt: Date.now() };
  $("#matchScore").textContent = `0/${selected.length}`;
  $("#matchStatus").textContent = "Match each term with its answer";
  $("#matchStatus").dataset.state = "playing";
  $("#matchTime").textContent = "0s";
  $("#matchModal").classList.add("open");
  window.clearInterval(matchTimer);
  matchTimer = window.setInterval(() => {
    $("#matchTime").textContent = `${Math.floor((Date.now() - matchState.startedAt) / 1_000)}s`;
  }, 250);
}

function renderStarterDecks() {
  const list = $("#starterDecks");
  list.replaceChildren();
  STARTER_DECKS.forEach((deck) => {
    const card = document.createElement("article");
    card.className = "starterDeck";
    const copy = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = deck.title;
    const description = document.createElement("small");
    description.textContent = deck.description;
    copy.append(title, description);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "btn";
    button.dataset.starterDeck = deck.id;
    button.textContent = "Import";
    card.append(copy, button);
    list.append(card);
  });
}

function importSharedDrafts(drafts, message = "Shared deck imported") {
  const normalized = drafts.map((draft) => normalizeCard(draft)).filter(Boolean);
  if (!normalized.length || normalized.length !== drafts.length) throw new Error("That deck code is invalid.");
  cards = normalized;
  studyQueueIds = [];
  index = 0;
  reviewed.clear();
  render(true);
  toast(message);
}

async function shareCurrentDeck() {
  try {
    const code = encodeDeckShare(cards);
    const url = `${window.location.origin}${window.location.pathname}#deck=${code}`;
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url);
      toast("Unlisted deck link copied · anyone with it can import");
    } else {
      window.prompt("Copy this unlisted deck link (anyone with it can import)", url);
    }
  } catch (error) {
    toast(error.message || "Could not create a share link");
  }
}

function importDeckCode(rawValue) {
  const raw = String(rawValue || "").trim();
  const code = raw.includes("#deck=") ? raw.split("#deck=").pop() : raw.replace(/^MD-/i, "");
  importSharedDrafts(decodeDeckShare(code));
  $("#exchangeModal").classList.remove("open");
  window.history.replaceState(null, "", window.location.pathname);
}

function importSharedDeckFromHash() {
  if (!window.location.hash.startsWith("#deck=")) return;
  try {
    const drafts = decodeDeckShare(window.location.hash.slice(6));
    if (window.confirm(`Import this shared deck with ${drafts.length} cards?`)) importSharedDrafts(drafts);
  } catch {
    toast("This shared deck link is invalid or expired");
  } finally {
    window.history.replaceState(null, "", window.location.pathname);
  }
}

$$('.tab').forEach((button) => {
  button.addEventListener("click", () => {
    $$(".tab,.pane").forEach((element) => element.classList.remove("active"));
    button.classList.add("active");
    $(`#${button.dataset.tab}Pane`).classList.add("active");
  });
});

const authModal = $("#authModal");
$("#themeToggle").addEventListener("click", cycleTheme);
$("#account").addEventListener("click", () => {
  if (window.matchMedia("(max-width: 760px)").matches && authState.user) {
    showMobilePage("#mobileSettingsView");
    return;
  }
  openAuthFlow("signin");
});
$("#togglePassword").addEventListener("click", () => {
  setPasswordVisibility($("#authPassword").type === "password");
});
$("#authSignedOut").addEventListener("keydown", (event) => {
  if (event.key === "Enter" && event.target.matches("input")) {
    event.preventDefault();
    submitAccount($("#authDialog").dataset.authMode === "signup" ? "/api/auth/signup" : "/api/auth/signin");
  }
});
$("#authClose").addEventListener("click", () => authModal.classList.remove("open"));
$("#authCancel").addEventListener("click", () => {
  authModal.classList.remove("open");
  setWorkspace("home", false);
});
$("#authModeToggle").addEventListener("click", () => {
  setAuthMode($("#authDialog").dataset.authMode === "signup" ? "signin" : "signup");
});
window.addEventListener("minddeck:first-run", (event) => openAuthFlow(event.detail?.mode || "signup"));
if (["signup", "signin"].includes(window.__minddeckAuthMode)) {
  openAuthFlow(window.__minddeckAuthMode);
}
$("#googleSignIn").addEventListener("click", startGoogleSignIn);
$("#signIn").addEventListener("click", () => submitAccount("/api/auth/signin"));
$("#signUp").addEventListener("click", () => submitAccount("/api/auth/signup"));
$("#signOut").addEventListener("click", async () => {
  try {
    await apiPost("/api/auth/signout", {});
    authState.user = null;
    await activateGuestStore();
    updateAccountUI();
    authModal.classList.remove("open");
    $$(".mobileFullPage").forEach((page) => { page.hidden = true; });
    document.body.classList.remove("mobile-page-open");
    setWorkspace("home", false);
    toast("Signed out · your local deck is still here");
  } catch (error) {
    toast(error.message || "Could not sign out");
  }
});

$$("[data-workspace-target]").forEach((button) =>
  button.addEventListener("click", () => setWorkspace(button.dataset.workspaceTarget))
);
$$("[data-subject-filter]").forEach((button) =>
  button.addEventListener("click", () => startSubjectReview(button.dataset.subjectFilter))
);

const openPrimaryStudyFlow = () => {
  if (!cards.length) {
    setWorkspace("generate");
    toast("Create your first deck to begin");
    return;
  }
  const dueIndex = cards.findIndex((card) => Date.parse(card.dueDate) <= Date.now());
  index = dueIndex >= 0 ? dueIndex : Math.min(index, cards.length - 1);
  studyQueueIds = [];
  setWorkspace("study");
  render(false);
};

$("#mobileStartLearning").addEventListener("click", openPrimaryStudyFlow);
$("#mobileReviewNow").addEventListener("click", openPrimaryStudyFlow);
$("#mobileSeeDeck").addEventListener("click", () => setWorkspace("deck"));
$("#deckGenerateAction").addEventListener("click", () => setWorkspace("generate"));
$("#deckAddAction").addEventListener("click", () => openManualCreator());
$("#mobilePlannerShortcut").addEventListener("click", () => setWorkspace("planner"));
$("#mobilePlanOpen").addEventListener("click", () => setWorkspace("planner"));
$("#mobileFormulaShortcut").addEventListener("click", startFormulaCram);
$("#mobileOpenOral").addEventListener("click", openOralExam);
$("#mobileProgressShortcut").addEventListener("click", () => setWorkspace("overall"));
$("#mobileCreateShortcut").addEventListener("click", () => setWorkspace("generate"));
$("#mobileOverallBack").addEventListener("click", () => setWorkspace("home"));
$("#mobileDeckSearchForm").addEventListener("submit", (event) => {
  event.preventDefault();
  const query = $("#mobileDeckSearch").value.trim().toLowerCase();
  if (!query) return;
  const foundIndex = cards.findIndex((card) =>
    [card.front, card.back, card.subject, card.chapter].some((value) =>
      String(value || "").toLowerCase().includes(query)
    )
  );
  if (foundIndex < 0) {
    toast(cards.length ? "No matching card found" : "Create a deck before searching");
    return;
  }
  index = foundIndex;
  studyQueueIds = [];
  $("#mobileDeckSearch").value = "";
  setWorkspace("study");
  render(false);
});

const openMobileSettings = () => {
  syncMobileDashboard();
  showMobilePage("#mobileSettingsView");
};
$("#mobileSettingsOpen").addEventListener("click", openMobileSettings);
$("#mobileClassPill").addEventListener("click", () => {
  const nextClass = selectedClassLevel() === "Class 11" ? "Class 12" : "Class 11";
  $("#classSelect").value = nextClass;
  try {
    localStorage.setItem(CLASS_STORE, nextClass);
  } catch {
    // The selection remains active for this tab.
  }
  updateMobileAccountUI();
  toast(`${nextClass} study plan selected`);
});
$("#mobileAccountOpen").addEventListener("click", () => {
  if (authState.user) openMobileSettings();
  else openAuthFlow("signin");
});
$("#mobileSettingsClose").addEventListener("click", () => hideMobilePage("#mobileSettingsView"));
$("#mobileProfileOpen").addEventListener("click", () => showMobilePage("#mobileProfileView"));
$("#mobileProfileDetailsOpen").addEventListener("click", () => showMobilePage("#mobileProfileView"));
$("#mobileNotificationsOpen").addEventListener("click", () => showMobilePage("#mobileNotificationsView"));
$("#mobileAvatarOpen").addEventListener("click", () => showMobilePage("#mobileAvatarView"));
$("#mobileShareOpen").addEventListener("click", () => showMobilePage("#mobileShareView"));
$$('[data-mobile-close]').forEach((button) =>
  button.addEventListener("click", () => hideMobilePage(`#${button.dataset.mobileClose}`))
);

let pendingMobileAvatar = mobileProfile().avatar || "📚";
const selectMobileAvatar = (value) => {
  pendingMobileAvatar = value;
  setOptionalText("#mobileAvatarPreview", value);
  $$('[data-mobile-avatar]').forEach((button) => {
    const selected = button.dataset.mobileAvatar === value;
    button.classList.toggle("selected", selected);
    button.setAttribute("aria-selected", String(selected));
  });
};
$$('[data-mobile-avatar]').forEach((button) =>
  button.addEventListener("click", () => selectMobileAvatar(button.dataset.mobileAvatar))
);
selectMobileAvatar(pendingMobileAvatar);
$("#mobileAvatarSave").addEventListener("click", () => {
  saveMobileProfile({ ...mobileProfile(), avatar: pendingMobileAvatar });
  hideMobilePage("#mobileAvatarView");
  toast("Avatar updated");
});

let storedMobileSettings = {};
try {
  storedMobileSettings = JSON.parse(localStorage.getItem(MOBILE_SETTINGS_STORE) || "{}");
} catch {
  storedMobileSettings = {};
}
$$('[data-mobile-setting]').forEach((input) => {
  if (typeof storedMobileSettings[input.dataset.mobileSetting] === "boolean") {
    input.checked = storedMobileSettings[input.dataset.mobileSetting];
  }
  input.addEventListener("change", () => {
    storedMobileSettings[input.dataset.mobileSetting] = input.checked;
    try {
      localStorage.setItem(MOBILE_SETTINGS_STORE, JSON.stringify(storedMobileSettings));
    } catch {
      // The toggle remains active for the current tab.
    }
  });
});

$("#mobileCopyReferral").addEventListener("click", async () => {
  const code = $("#mobileReferralCode").textContent;
  try {
    await navigator.clipboard.writeText(code);
    toast("Invite code copied");
  } catch {
    window.prompt("Copy your MindDeck invite code", code);
  }
});
$("#mobileShareReferral").addEventListener("click", async () => {
  const shareData = {
    title: "MindDeck AI Flashcards",
    text: "Study with me on MindDeck — turn notes into smart flashcards and review with SM-2.",
    url: window.location.origin,
  };
  try {
    if (navigator.share) await navigator.share(shareData);
    else {
      await navigator.clipboard.writeText(`${shareData.text} ${shareData.url}`);
      toast("MindDeck link copied");
    }
  } catch (error) {
    if (error?.name !== "AbortError") toast("Could not share right now");
  }
});
$("#mobileLegacySettingsOpen").addEventListener("click", () => {
  hideMobilePage("#mobileSettingsView");
  openSettings();
});
$("#mobilePrivacy").addEventListener("click", () => toast("Your account and decks use secure, private cloud sync"));
$("#mobileRateApp").addEventListener("click", () => toast("Thank you — your feedback helps shape MindDeck"));
$("#mobileUpgradeButton").addEventListener("click", () => {
  hideMobilePage("#mobileSettingsView");
  setWorkspace("overall");
});
$("#mobileSignOutOpen").addEventListener("click", () => {
  if (!authState.user) {
    hideMobilePage("#mobileSettingsView");
    openAuthFlow("signin");
    return;
  }
  $("#mobileSignOutConfirm").hidden = false;
});
$("#mobileSignOutCancel").addEventListener("click", () => { $("#mobileSignOutConfirm").hidden = true; });
$("#mobileSignOutConfirmButton").addEventListener("click", () => {
  $("#mobileSignOutConfirm").hidden = true;
  $("#signOut").click();
});

$$('[data-progress-tab]').forEach((button) =>
  button.addEventListener("click", () => {
    $$('[data-progress-tab]').forEach((item) => item.classList.toggle("active", item === button));
    $$('[data-progress-panel]').forEach((panel) =>
      panel.classList.toggle("active", panel.dataset.progressPanel === button.dataset.progressTab)
    );
  })
);

$("#settings").addEventListener("click", openSettings);
$("#openGeneratorSettings").addEventListener("click", openSettings);
$("#settingsClose").addEventListener("click", () => $("#settingsModal").classList.remove("open"));
$("#settingsDone").addEventListener("click", () => $("#settingsModal").classList.remove("open"));
$("#openOral").addEventListener("click", openOralExam);
$("#oralClose").addEventListener("click", () => closeOralExam(false));
$("#oralDone").addEventListener("click", () => closeOralExam(true));
$("#classSelect").addEventListener("change", (event) => {
  try {
    localStorage.setItem(CLASS_STORE, event.target.value);
  } catch {
    // The chosen class still applies to the current deck.
  }
  updateMobileAccountUI();
});
$("#scene").addEventListener("click", toggleCardFlip);
$("#next").addEventListener("click", next);
$("#prev").addEventListener("click", previous);
$$('.rate').forEach((button) => button.addEventListener("click", () => score(Number(button.dataset.score))));
$("#hintButton").addEventListener("click", () => showNextHint().catch(() => toast("Could not create a hint")));
$("#ttsButton").addEventListener("click", speakCurrentAnswer);
$("#logMistake").addEventListener("click", logCurrentMistake);
$("#feynmanRecord").addEventListener("click", toggleFeynmanRecording);
$("#feynmanCompare").addEventListener("click", compareFeynmanExplanation);
$("#revealExamStep").addEventListener("click", revealNextExamStep);
$("#examStepList").addEventListener("click", (event) => {
  const button = event.target.closest("[data-exam-step]");
  if (!button) return;
  revealExamStep(button);
  examRevealCount = $$("#examStepList .examStep.revealed").length;
  if (examRevealCount === $$("#examStepList .examStep").length) {
    $("#revealExamStep").textContent = "All revealed";
    $("#revealExamStep").disabled = true;
  }
});
$("#cramLeeches").addEventListener("click", () => {
  renderLeechDeck();
  $("#leechModal").classList.add("open");
});
$("#leechClose").addEventListener("click", () => $("#leechModal").classList.remove("open"));
$("#startLeechCram").addEventListener("click", startLeechCram);
$("#leechList").addEventListener("click", (event) => {
  const button = event.target.closest("[data-reset-leech]");
  if (!button) return;
  const card = cards.find((item) => item.id === button.dataset.resetLeech);
  if (!card) return;
  card.leech = false;
  card.lapseStreak = 0;
  render(true);
  renderLeechDeck();
  toast("Leech tag reset");
});
$("#startCramSprint").addEventListener("click", startCramSprint);
$("#openExamEngine").addEventListener("click", openExamCreator);
$("#formulaCram").addEventListener("click", startFormulaCram);
$("#quickFormulaCram").addEventListener("click", startFormulaCram);
$("#mistakeNotebook").addEventListener("click", () => {
  renderMistakeNotebook();
  $("#mistakeModal").classList.add("open");
});
$("#mistakeClose").addEventListener("click", () => $("#mistakeModal").classList.remove("open"));
$("#startMistakeReview").addEventListener("click", startMistakeReview);
$("#mistakeList").addEventListener("click", (event) => {
  const button = event.target.closest("[data-resolve-mistake]");
  if (!button) return;
  const card = cards.find((item) => item.id === button.dataset.resolveMistake);
  if (!card) return;
  card.mistake = false;
  card.mistakeAt = "";
  card.priority = "normal";
  render(true);
  renderMistakeNotebook();
  toast("Mistake marked resolved");
});
$("#examTemplate").addEventListener("change", syncExamTemplate);
$("#examClose").addEventListener("click", () => {
  $("#examModal").classList.remove("open");
  resetExamCreator();
});
$("#examCancel").addEventListener("click", () => {
  $("#examModal").classList.remove("open");
  resetExamCreator();
});
$("#saveExamCard").addEventListener("click", saveExamCard);
$("#startMatch").addEventListener("click", startMatchGame);
$("#matchClose").addEventListener("click", () => {
  $("#matchModal").classList.remove("open");
  window.clearInterval(matchTimer);
  matchTimer = null;
});
$("#openExchange").addEventListener("click", () => {
  renderStarterDecks();
  $("#exchangeModal").classList.add("open");
});
$("#exchangeClose").addEventListener("click", () => $("#exchangeModal").classList.remove("open"));
$("#shareDeck").addEventListener("click", shareCurrentDeck);
$("#importShareCode").addEventListener("click", () => {
  try {
    importDeckCode($("#shareCode").value);
  } catch (error) {
    $("#shareCodeError").textContent = error.message || "Invalid deck code.";
  }
});
$("#starterDecks").addEventListener("click", (event) => {
  const button = event.target.closest("[data-starter-deck]");
  if (!button) return;
  const deck = STARTER_DECKS.find((item) => item.id === button.dataset.starterDeck);
  if (!deck) return;
  importSharedDrafts(deck.cards.map(([front, back]) => ({ front, back })), `${deck.title} imported`);
  $("#exchangeModal").classList.remove("open");
});
$("#openOcclusion").addEventListener("click", () => openManualCreator("occlusion"));
$("#timerToggle").addEventListener("click", toggleTimer);
$("#timerReset").addEventListener("click", resetTimer);
$$('[data-timer-mode]').forEach((button) =>
  button.addEventListener("click", () => selectTimerMode(button.dataset.timerMode))
);
$("#goalDown").addEventListener("click", () => changeDailyGoal(-5));
$("#goalUp").addEventListener("click", () => changeDailyGoal(5));
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) updateTimerFromClock();
});
window.addEventListener("beforeunload", storeTimerState);

$("#generate").addEventListener("click", async () => {
  const activePane = $(".pane.active")?.id || "notesPane";
  const isPhoto = activePane === "photoPane";
  const text = (activePane === "notesPane" ? $("#notes").value : fileText).trim();
  const provider = $("#provider").value;
  const cardMode = $("#cardMode").value;
  const button = $("#generate");
  $("#error").textContent = "";

  if (isPhoto && !photoFile) {
    $("#error").textContent = "Snap or choose a clear textbook, notes, or whiteboard image first.";
    return;
  }
  if (!isPhoto && text.length < 20) {
    $("#error").textContent = "Add more notes or upload a file first.";
    return;
  }
  if (provider === "minddeck" && !authState.user) {
    $("#error").textContent = "Sign in once to generate with MindDeck AI—no key or access code needed.";
    openAuthFlow("signin");
    return;
  }

  button.disabled = true;
  try {
    button.textContent = isPhoto
      ? provider === "minddeck" ? "✨ AI is reading your image…" : "Reading image offline…"
      : provider === "minddeck" ? "✨ MindDeck AI is creating…" : "Creating offline…";
    let generated;
    if (isPhoto && provider === "offline") {
      const extracted = await extractTextWithBrowserOcr(photoFile);
      if (extracted.length < 20) throw new Error("The browser could not find enough readable text in that image.");
      generated = parseOffline(extracted, cardMode);
    } else if (isPhoto) {
      generated = (await generateFromImage(photoFile, cardMode))
        .map((item) => normalizeCard(item))
        .filter(Boolean);
    } else if (provider === "offline") {
      generated = parseOffline(text, cardMode);
    } else {
      generated = (await generateWithAI(text, cardMode))
        .map((item) => normalizeCard(item))
        .filter(Boolean);
    }
    if (!generated.length) throw new Error("No usable concepts found.");
    generated = completeGeneratedDeck(generated, isPhoto ? "" : text, cardMode);
    cards = applyGenerationMetadata(generated, cardMode);
    studyQueueIds = [];
    index = 0;
    reviewed.clear();
    setWorkspace("study", false);
    render(true);
    toast(`Created all ${GENERATED_DECK_SIZE} exam-ready questions`);
  } catch (error) {
    if (error.status === 401) {
      await loadAccount();
      openAuthFlow("signin");
    }
    $("#error").textContent = error.message || "Generation failed.";
  } finally {
    button.disabled = false;
    button.textContent = generationButtonLabel();
  }
});

const drop = $("#drop");
const fileInput = $("#file");
drop.addEventListener("click", () => fileInput.click());
["dragover", "dragenter"].forEach((name) =>
  drop.addEventListener(name, (event) => {
    event.preventDefault();
    drop.classList.add("drag");
  })
);
["dragleave", "drop"].forEach((name) =>
  drop.addEventListener(name, (event) => {
    event.preventDefault();
    drop.classList.remove("drag");
  })
);
drop.addEventListener("drop", (event) => readFile(event.dataTransfer.files[0]));
fileInput.addEventListener("change", (event) => readFile(event.target.files[0]));

const photoInput = $("#photoFile");
const photoDrop = $("#photoDrop");

function selectPhoto(file) {
  $("#error").textContent = "";
  if (!file) return;
  if (!["image/jpeg", "image/png", "image/webp"].includes(file.type) || file.size > 4 * 1024 * 1024) {
    photoFile = null;
    $("#error").textContent = "Use a JPG, PNG, or WebP photo under 4 MB.";
    return;
  }
  photoFile = file;
  if (photoPreviewUrl) URL.revokeObjectURL(photoPreviewUrl);
  photoPreviewUrl = URL.createObjectURL(file);
  $("#photoPreview").src = photoPreviewUrl;
  $("#photoPreviewWrap").hidden = false;
  photoDrop.hidden = true;
  $("#photoInfo").textContent = `${file.name.slice(0, 80)} · ${(file.size / 1024 / 1024).toFixed(1)} MB`;
}

photoDrop.addEventListener("click", () => photoInput.click());
photoDrop.addEventListener("keydown", (event) => {
  if (["Enter", " "].includes(event.key)) {
    event.preventDefault();
    photoInput.click();
  }
});
$("#photoPreviewWrap").addEventListener("click", () => photoInput.click());
photoInput.addEventListener("change", (event) => selectPhoto(event.target.files[0]));
["dragover", "dragenter"].forEach((name) =>
  photoDrop.addEventListener(name, (event) => {
    event.preventDefault();
    photoDrop.classList.add("drag");
  })
);
["dragleave", "drop"].forEach((name) =>
  photoDrop.addEventListener(name, (event) => {
    event.preventDefault();
    photoDrop.classList.remove("drag");
  })
);
photoDrop.addEventListener("drop", (event) => selectPhoto(event.dataTransfer.files[0]));

const modal = $("#modal");
function openManualCreator(type = "basic") {
  $("#mType").value = type;
  syncManualType();
  modal.classList.add("open");
}

function syncManualType() {
  const type = $("#mType").value;
  $("#basicFields").hidden = type === "cloze";
  $("#clozeFields").hidden = type !== "cloze";
  $("#occlusionFields").hidden = type !== "occlusion";
}

function renderPendingOcclusions() {
  $("#occlusionDraftMasks").replaceChildren();
  for (const mask of pendingOcclusions) {
    const rect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
    rect.setAttribute("x", String(mask.x));
    rect.setAttribute("y", String(mask.y));
    rect.setAttribute("width", String(mask.width));
    rect.setAttribute("height", String(mask.height));
    rect.setAttribute("rx", "18");
    rect.classList.add("occlusionMask");
    $("#occlusionDraftMasks").append(rect);
  }
  $("#maskCount").textContent = `${pendingOcclusions.length} ${pendingOcclusions.length === 1 ? "mask" : "masks"}`;
}

function resetManualCreator() {
  $("#mFront").value = "";
  $("#mBack").value = "";
  $("#mCloze").value = "";
  $("#mImage").value = "";
  pendingOcclusionFile = null;
  pendingOcclusions = [];
  if (pendingOcclusionUrl) URL.revokeObjectURL(pendingOcclusionUrl);
  pendingOcclusionUrl = "";
  $("#occlusionBuilder").hidden = true;
  $("#occlusionDraftImage").removeAttribute("src");
  renderPendingOcclusions();
}

$("#manual").addEventListener("click", () => openManualCreator());
$("#quickCreate").addEventListener("click", () => openManualCreator());
$("#quickReview").addEventListener("click", startDashboardReview);
$("#quickNotes").addEventListener("click", openNotesComposer);
$("#quickExam").addEventListener("click", openExamCreator);
$("#mType").addEventListener("change", syncManualType);
$("#mImage").addEventListener("change", (event) => {
  const file = event.target.files[0];
  if (!file || !["image/jpeg", "image/png", "image/webp"].includes(file.type) || file.size > 5 * 1024 * 1024) {
    toast("Use a JPG, PNG, or WebP image under 5 MB");
    return;
  }
  pendingOcclusionFile = file;
  pendingOcclusions = [];
  if (pendingOcclusionUrl) URL.revokeObjectURL(pendingOcclusionUrl);
  pendingOcclusionUrl = URL.createObjectURL(file);
  $("#occlusionDraftImage").src = pendingOcclusionUrl;
  $("#occlusionBuilder").hidden = false;
  renderPendingOcclusions();
});
$("#occlusionBuilder").addEventListener("click", (event) => {
  if (event.target.closest("button") || !pendingOcclusionFile || pendingOcclusions.length >= 24) return;
  const image = $("#occlusionDraftImage");
  const bounds = image.getBoundingClientRect();
  if (!bounds.width || !bounds.height) return;
  const centerX = ((event.clientX - bounds.left) / bounds.width) * 1_000;
  const centerY = ((event.clientY - bounds.top) / bounds.height) * 1_000;
  const width = 210;
  const height = 105;
  pendingOcclusions.push({
    x: Math.round(Math.min(1_000 - width, Math.max(0, centerX - width / 2))),
    y: Math.round(Math.min(1_000 - height, Math.max(0, centerY - height / 2))),
    width,
    height,
  });
  renderPendingOcclusions();
});
$("#clearMasks").addEventListener("click", (event) => {
  event.stopPropagation();
  pendingOcclusions = [];
  renderPendingOcclusions();
});
$("#close").addEventListener("click", () => {
  modal.classList.remove("open");
  resetManualCreator();
});
$("#cancel").addEventListener("click", () => {
  modal.classList.remove("open");
  resetManualCreator();
});
$("#saveCard").addEventListener("click", async () => {
  const type = $("#mType").value;
  let front = $("#mFront").value.trim().slice(0, 500);
  let back = $("#mBack").value.trim().slice(0, 2000);
  const enhancements = { type };
  if (type === "cloze") {
    const clozeText = $("#mCloze").value.trim().slice(0, 2000);
    if (!/\{\{(?:c\d+::)?[^}]+\}\}/i.test(clozeText)) {
      toast("Wrap the hidden answer in {{double braces}}");
      return;
    }
    enhancements.clozeText = clozeText;
    front = clozeFront(clozeText).slice(0, 500);
    back = clozeBack(clozeText).slice(0, 2000);
  }
  if (!front || !back) {
    toast("Add both sides");
    return;
  }
  if (type === "occlusion" && (!pendingOcclusionFile || !pendingOcclusions.length)) {
    toast("Choose an image and add at least one mask");
    return;
  }
  const button = $("#saveCard");
  button.disabled = true;
  try {
    const card = newCard(front, back, { ...enhancements, occlusions: pendingOcclusions });
    if (type === "occlusion") {
      card.imageAssetId = card.id;
      await saveImageAsset(card.id, pendingOcclusionFile);
    }
    cards.push(card);
    index = cards.length - 1;
    modal.classList.remove("open");
    resetManualCreator();
    render(true);
    toast(type === "occlusion" ? "Image occlusion card added" : type === "cloze" ? "Cloze card added" : "Card added");
  } catch (error) {
    toast(error.message || "Could not add this card");
  } finally {
    button.disabled = false;
  }
});

$("#deckList").addEventListener("click", (event) => {
  const emptyAction = event.target.closest("[data-deck-empty-action]");
  if (emptyAction?.dataset.deckEmptyAction === "generate") {
    setWorkspace("generate");
    return;
  }
  if (emptyAction?.dataset.deckEmptyAction === "manual") {
    openManualCreator();
    return;
  }
  const button = event.target.closest("[data-del]");
  if (!button) return;
  const [removed] = cards.splice(Number(button.dataset.del), 1);
  if (removed?.imageAssetId) deleteImageAsset(removed.imageAssetId).catch(() => {});
  index = Math.min(index, Math.max(0, cards.length - 1));
  render(true);
});

$("#clear").addEventListener("click", () => {
  if (cards.length && !window.confirm("Start a new deck?")) return;
  cards.forEach((card) => {
    if (card.imageAssetId) deleteImageAsset(card.imageAssetId).catch(() => {});
  });
  cards = [];
  studyQueueIds = [];
  index = 0;
  reviewed.clear();
  setWorkspace("generate", false);
  render(true);
});

$("#export").addEventListener("click", () => {
  if (!cards.length) {
    toast("No deck to export");
    return;
  }
  const blob = new Blob([JSON.stringify({ name: "MindDeck Export", cards }, null, 2)], {
    type: "application/json",
  });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = "minddeck-deck.json";
  link.click();
  URL.revokeObjectURL(link.href);
});

$("#import").addEventListener("click", () => $("#jsonFile").click());
$("#jsonFile").addEventListener("change", async (event) => {
  try {
    const file = event.target.files[0];
    if (!file || file.size > 5 * 1024 * 1024) throw new Error();
    const parsed = JSON.parse(await file.text());
    const imported = Array.isArray(parsed) ? parsed : parsed.cards;
    if (!Array.isArray(imported) || imported.length > 10_000) throw new Error();
    const normalized = imported.map(normalizeCard).filter(Boolean);
    if (!normalized.length || normalized.length !== imported.length) throw new Error();
    cards = normalized;
    studyQueueIds = [];
    index = 0;
    reviewed.clear();
    setWorkspace("deck", false);
    render(true);
    toast("Deck imported");
  } catch {
    toast("Invalid deck JSON");
  } finally {
    event.target.value = "";
  }
});

document.addEventListener("keydown", (event) => {
  if (["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement.tagName)) return;
  if ($(".modal.open")) return;
  if (event.code === "Space") {
    event.preventDefault();
    toggleCardFlip();
  } else if (event.key === "ArrowRight") next();
  else if (event.key === "ArrowLeft") previous();
  else if ("1234".includes(event.key)) score(Number(event.key));
  else if (event.key.toLowerCase() === "t") toggleTimer();
});

setupSpatialUi();
restoreClassLevel();
loadTheme();
await load();
importSharedDeckFromHash();
loadConfig();
const authResult = consumeAuthResult();
loadAccount()
  .then(() => {
    if (authResult === "ok" && authState.user) toast("Signed in with Google · cloud memory restored");
    else if (authResult === "ok") toast("Google Sign-In finished, but the account could not be loaded");
    else if (authResult === "error") toast("Google Sign-In was cancelled or could not be completed");
  })
  .finally(loadTimerState);
if ("serviceWorker" in navigator) {
  const shellRefreshKey = "minddeck-shell-v27-refreshed";
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    try {
      if (sessionStorage.getItem(shellRefreshKey)) return;
      sessionStorage.setItem(shellRefreshKey, "1");
    } catch {
      // Reloading once is still safe when session storage is unavailable.
    }
    window.location.reload();
  });
  window.addEventListener("load", () => {
    navigator.serviceWorker
      .register("/static/sw.js?v=27", { scope: "/", updateViaCache: "none" })
      .then((registration) => registration.update())
      .catch(() => {});
  });
}
