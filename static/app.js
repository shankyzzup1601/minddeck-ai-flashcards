const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];

const GUEST_STORE = "minddeck-v2";
const CSRF_TOKEN = document.querySelector('meta[name="csrf-token"]')?.content || "";
const PDF_MODULE = "/static/vendor/pdf-4.10.38.min.mjs";
const PDF_WORKER = "/static/vendor/pdf-4.10.38.worker.min.mjs";
const FOCUS_STORE = "minddeck-focus-timer-v1";
const THEME_STORE = "minddeck-visual-theme-v1";
const THEMES = Object.freeze([
  { key: "cosmic", label: "Cosmic" },
  { key: "aurora", label: "Aurora" },
  { key: "rose", label: "Rose" },
]);
const TIMER_MODES = Object.freeze({
  focus: { label: "Focus sprint", duration: 25 * 60, isFocus: true },
  break: { label: "Quick reset", duration: 5 * 60, isFocus: false },
  deep: { label: "Deep focus", duration: 50 * 60, isFocus: true },
});

let cards = [];
let index = 0;
let reviewed = new Set();
let fileText = "";
let aiProviders = { openai: false, gemini: false };
let unlockedProvider = null;
let deckUpdatedAt = 0;
let authState = { enabled: false, user: null };
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

function newCard(front, back) {
  return {
    id: Date.now().toString(36) + Math.random().toString(36).slice(2),
    front: front.trim().slice(0, 500),
    back: back.trim().slice(0, 2000),
    interval: 0,
    repetition: 0,
    easeFactor: 2.5,
    dueDate: new Date().toISOString(),
    reviews: 0,
  };
}

function finiteNumber(value, fallback, minimum, maximum) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.min(maximum, Math.max(minimum, number)) : fallback;
}

function defaultStudyStats() {
  return { totalSeconds: 0, sessions: 0, dailyGoalMinutes: 25, dailyFocus: [] };
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
  return {
    totalSeconds: Math.round(finiteNumber(value.totalSeconds, 0, 0, 315_360_000)),
    sessions: Math.round(finiteNumber(value.sessions, 0, 0, 1_000_000)),
    dailyGoalMinutes: Math.round(finiteNumber(value.dailyGoalMinutes, 25, 15, 240)),
    dailyFocus: [...dailyByDate.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .slice(-90)
      .map(([date, seconds]) => ({ date, seconds })),
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

  const card = newCard(front, back);
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

function deckSnapshot() {
  return {
    version: 3,
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
    localStorage.setItem(activeStoreKey, JSON.stringify(deckSnapshot()));
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

function load() {
  try {
    const stored = JSON.parse(localStorage.getItem(activeStoreKey) || "{}");
    if (!applyDeckState(stored)) throw new Error();
  } catch {
    cards = [];
    index = 0;
    reviewed = new Set();
    studyStats = defaultStudyStats();
    deckUpdatedAt = 0;
  }
  render(false);
}

function activateAccountStore(accountKey) {
  if (!/^[a-f0-9]{24}$/.test(accountKey) || activeAccountKey === accountKey) return;
  const nextStore = `minddeck-v3:user:${accountKey}`;
  const saved = localStorage.getItem(nextStore);
  activeStoreKey = nextStore;
  activeAccountKey = accountKey;
  accountStoreFresh = !saved;
  if (saved) {
    try {
      if (!applyDeckState(JSON.parse(saved))) throw new Error();
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
  render(false);
}

function activateGuestStore() {
  activeStoreKey = GUEST_STORE;
  activeAccountKey = null;
  accountStoreFresh = false;
  load();
}

function toast(message) {
  const element = $("#toast");
  element.textContent = String(message).slice(0, 180);
  element.classList.add("show");
  window.setTimeout(() => element.classList.remove("show"), 2000);
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

function renderDeck() {
  const list = $("#deckList");
  list.replaceChildren();
  if (!cards.length) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "No cards yet. Generate a deck or add one manually.";
    list.append(empty);
    return;
  }

  cards.forEach((card, cardIndex) => {
    const row = document.createElement("div");
    row.className = "row";
    const front = document.createElement("strong");
    front.textContent = card.front;
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
  $("#dueNav").textContent = due;
  $("#mastery").textContent = `${mastery}%`;
  $("#sideMastery").textContent = `${mastery}% mastered`;
  $("#sideBar").value = mastery;
  $("#complete").textContent = `${completion}% complete`;
  $("#progress").value = completion;
  $("#status").textContent = due ? `${due} due for review` : "All caught up";
  $("#pager").textContent = cards.length ? `${index + 1} / ${cards.length}` : "0 / 0";
  $("#card").classList.remove("flip");
  $("#front").textContent = card ? card.front : "Add or generate cards to begin.";
  $("#back").textContent = card ? card.back : "Your answer appears here.";
  renderStudyWidgets();
  renderDeck();
  save(touch);
}

function parseOffline(text) {
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
    else if ((match = sentence.match(/^(.{5,130}?)\s+(?:because|due to|causes|leads to|results in)\s+(.{8,250})/i))) {
      add(`Explain the relationship involving “${match[1]}”.`, match[2]);
    } else if (sentence.length > 40) add("What is the key idea in this concept?", sentence);
    if (output.length >= 30) break;
  }
  return output;
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

async function unlockAI(provider, accessCode) {
  const data = await apiPost("/api/unlock", { provider, accessCode });
  unlockedProvider = data.provider;
  return data;
}

async function generateWithAI(text, provider) {
  const data = await apiPost("/api/generate", { text, provider });
  return data.cards;
}

async function lockAI() {
  await apiPost("/api/lock", {});
  unlockedProvider = null;
  $("#accessCode").value = "";
  syncProvider();
  toast("Online AI locked");
}

function updateSecurityHint() {
  const ready = aiProviders.openai || aiProviders.gemini;
  if (unlockedProvider) {
    $("#securityHint").textContent = "🔐 Online AI unlocked in a secure 15-minute session.";
  } else if (ready) {
    $("#securityHint").textContent = "🔒 API key protected on the server. Enter the owner access code to unlock AI.";
  } else {
    $("#securityHint").textContent = "🔒 Online AI is fully locked. Offline mode is ready.";
  }
}

function syncProvider() {
  const provider = $("#provider").value;
  const online = provider !== "offline";
  const alreadyUnlocked = online && unlockedProvider === provider;
  $("#accessCode").disabled = !online || alreadyUnlocked;
  $("#accessCode").placeholder = alreadyUnlocked ? "Secure session unlocked" : "Owner access code";
  $("#lockAi").hidden = !unlockedProvider;
  if (!online || alreadyUnlocked) $("#accessCode").value = "";
  updateSecurityHint();
}

async function loadConfig() {
  try {
    const response = await fetch("/api/config", {
      cache: "no-store",
      credentials: "same-origin",
      redirect: "error",
      referrerPolicy: "no-referrer",
      headers: { Accept: "application/json" },
    });
    const data = await response.json();
    if (!response.ok) throw new Error();
    aiProviders = data.providers || aiProviders;
    unlockedProvider = typeof data.unlockedProvider === "string" ? data.unlockedProvider : null;
    for (const provider of ["openai", "gemini"]) {
      const option = $(`#provider option[value="${provider}"]`);
      const ready = Boolean(aiProviders[provider]);
      option.disabled = !ready;
      option.textContent = `${provider === "openai" ? "Secure OpenAI" : "Secure Gemini"}${ready ? "" : " · locked"}`;
    }
  } catch {
    aiProviders = { openai: false, gemini: false };
    unlockedProvider = null;
  }
  syncProvider();
}

function setSyncStatus(message, state = "idle") {
  const status = $("#syncState");
  status.textContent = message;
  status.dataset.state = state;
}

function updateAccountUI() {
  const signedIn = Boolean(authState.user);
  const account = $("#account");
  account.disabled = !authState.enabled;
  account.textContent = signedIn
    ? authState.user.email || "My account"
    : authState.enabled
      ? "Sign in"
      : "Cloud setup needed";
  $("#authSignedOut").hidden = signedIn;
  $("#authSignedIn").hidden = !signedIn;
  $("#authUser").textContent = signedIn ? authState.user.email || "Signed in" : "";
  if (!authState.enabled) setSyncStatus("Saved on this device · cloud setup pending");
  else if (!signedIn) setSyncStatus("Saved on this device · sign in to sync");
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
      user:
        data.user &&
        typeof data.user.email === "string" &&
        typeof data.user.accountKey === "string" &&
        /^[a-f0-9]{24}$/.test(data.user.accountKey)
          ? data.user
          : null,
    };
    if (authState.user) activateAccountStore(authState.user.accountKey);
    updateAccountUI();
    if (authState.user) await reconcileCloudDeck();
  } catch {
    authState = { enabled: false, user: null };
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
    accountStoreFresh = false;
    render(false);
    setSyncStatus("Cloud deck restored", "ok");
  } catch (error) {
    setSyncStatus(error.message || "Cloud sync paused", "error");
  }
}

async function submitAccount(path) {
  const email = $("#authEmail").value.trim();
  const password = $("#authPassword").value;
  const buttons = [$("#signIn"), $("#signUp")];
  $("#authError").textContent = "";
  if (password.length < 12) {
    $("#authError").textContent = "Use a password with at least 12 characters.";
    return;
  }
  buttons.forEach((button) => {
    button.disabled = true;
  });
  try {
    const data = await apiPost(path, { email, password });
    $("#authPassword").value = "";
    $("#authMessage").textContent = data.message || "Signed in successfully.";
    if (path.endsWith("signin") || data.signedIn) {
      await loadAccount();
      if (authState.user) $("#authModal").classList.remove("open");
    }
  } catch (error) {
    $("#authPassword").value = "";
    $("#authError").textContent = error.message || "Account request failed.";
  } finally {
    buttons.forEach((button) => {
      button.disabled = false;
    });
  }
}

function next(touch = true) {
  if (cards.length) index = (index + 1) % cards.length;
  render(touch && cards.length > 0);
}

function previous(touch = true) {
  if (cards.length) index = (index - 1 + cards.length) % cards.length;
  render(touch && cards.length > 0);
}

function score(quality) {
  const card = cards[index];
  if (!card) return;
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
  reviewed.add(card.id);
  next();
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
  $("#authError").textContent = "";
  $("#authMessage").textContent = "";
  authModal.classList.add("open");
});
$("#authClose").addEventListener("click", () => authModal.classList.remove("open"));
$("#authCancel").addEventListener("click", () => authModal.classList.remove("open"));
$("#signIn").addEventListener("click", () => submitAccount("/api/auth/signin"));
$("#signUp").addEventListener("click", () => submitAccount("/api/auth/signup"));
$("#signOut").addEventListener("click", async () => {
  try {
    await apiPost("/api/auth/signout", {});
    authState.user = null;
    activateGuestStore();
    updateAccountUI();
    authModal.classList.remove("open");
    toast("Signed out · your local deck is still here");
  } catch (error) {
    toast(error.message || "Could not sign out");
  }
});

$("#provider").addEventListener("change", syncProvider);
$("#lockAi").addEventListener("click", () => lockAI().catch((error) => {
  $("#error").textContent = error.message;
}));
$("#scene").addEventListener("click", () => cards.length && $("#card").classList.toggle("flip"));
$("#next").addEventListener("click", next);
$("#prev").addEventListener("click", previous);
$$('.rate').forEach((button) => button.addEventListener("click", () => score(Number(button.dataset.score))));
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
  const text = ($("#notesPane").classList.contains("active") ? $("#notes").value : fileText).trim();
  const provider = $("#provider").value;
  const accessCode = $("#accessCode").value;
  const button = $("#generate");
  $("#error").textContent = "";

  if (text.length < 20) {
    $("#error").textContent = "Add more notes or upload a file first.";
    return;
  }
  if (provider !== "offline" && !aiProviders[provider]) {
    $("#error").textContent = "This AI provider is securely locked.";
    return;
  }
  if (provider !== "offline" && unlockedProvider !== provider && !accessCode) {
    $("#error").textContent = "Enter the owner access code or choose offline mode.";
    return;
  }

  button.disabled = true;
  try {
    if (provider !== "offline" && unlockedProvider !== provider) {
      button.textContent = "Unlocking secure AI…";
      await unlockAI(provider, accessCode);
      $("#accessCode").value = "";
      syncProvider();
    }
    button.textContent = "Generating…";
    const generated =
      provider === "offline"
        ? parseOffline(text)
        : (await generateWithAI(text, provider)).map((item) => normalizeCard(item)).filter(Boolean);
    if (!generated.length) throw new Error("No usable concepts found.");
    cards = generated;
    index = 0;
    reviewed.clear();
    render(true);
    toast(`Created ${generated.length} cards`);
  } catch (error) {
    if (error.status === 401) {
      unlockedProvider = null;
      syncProvider();
    }
    $("#error").textContent = error.message || "Generation failed.";
  } finally {
    $("#accessCode").value = "";
    button.disabled = false;
    button.textContent = "✦ Generate flashcards";
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

const modal = $("#modal");
$("#manual").addEventListener("click", () => modal.classList.add("open"));
$("#close").addEventListener("click", () => modal.classList.remove("open"));
$("#cancel").addEventListener("click", () => modal.classList.remove("open"));
$("#saveCard").addEventListener("click", () => {
  const front = $("#mFront").value.trim().slice(0, 500);
  const back = $("#mBack").value.trim().slice(0, 2000);
  if (!front || !back) {
    toast("Add both sides");
    return;
  }
  cards.push(newCard(front, back));
  index = cards.length - 1;
  $("#mFront").value = "";
  $("#mBack").value = "";
  modal.classList.remove("open");
  render(true);
  toast("Card added");
});

$("#deckList").addEventListener("click", (event) => {
  const button = event.target.closest("[data-del]");
  if (!button) return;
  cards.splice(Number(button.dataset.del), 1);
  index = Math.min(index, Math.max(0, cards.length - 1));
  render(true);
});

$("#clear").addEventListener("click", () => {
  if (cards.length && !window.confirm("Start a new deck?")) return;
  cards = [];
  index = 0;
  reviewed.clear();
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
    index = 0;
    reviewed.clear();
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
    $("#card").classList.toggle("flip");
  } else if (event.key === "ArrowRight") next();
  else if (event.key === "ArrowLeft") previous();
  else if ("1234".includes(event.key)) score(Number(event.key));
  else if (event.key.toLowerCase() === "t") toggleTimer();
});

loadTheme();
load();
loadConfig();
loadAccount().finally(loadTimerState);
