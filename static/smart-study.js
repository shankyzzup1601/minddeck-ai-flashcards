const DB_NAME = "minddeck-offline-v1";
const DB_VERSION = 1;

const STOP_WORDS = new Set([
  "about", "after", "again", "also", "because", "before", "being", "between", "could",
  "does", "from", "have", "into", "more", "most", "other", "should", "than", "that",
  "their", "there", "these", "they", "this", "those", "through", "under", "using", "very",
  "what", "when", "where", "which", "while", "with", "would", "your",
]);

function openDatabase() {
  return new Promise((resolve, reject) => {
    if (!window.indexedDB) {
      reject(new Error("IndexedDB is unavailable."));
      return;
    }
    const request = window.indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains("decks")) database.createObjectStore("decks");
      if (!database.objectStoreNames.contains("assets")) database.createObjectStore("assets");
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("Could not open offline storage."));
  });
}

async function databaseRequest(storeName, mode, action) {
  const database = await openDatabase();
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(storeName, mode);
    const store = transaction.objectStore(storeName);
    let request;
    try {
      request = action(store);
    } catch (error) {
      database.close();
      reject(error);
      return;
    }
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("Offline storage failed."));
    transaction.oncomplete = () => database.close();
    transaction.onerror = () => database.close();
  });
}

export async function saveDeckBackup(key, snapshot) {
  if (typeof key !== "string" || !key || !snapshot) return;
  await databaseRequest("decks", "readwrite", (store) =>
    store.put({ snapshot, savedAt: Date.now() }, key.slice(0, 160))
  );
}

export async function loadDeckBackup(key) {
  if (typeof key !== "string" || !key) return null;
  const result = await databaseRequest("decks", "readonly", (store) => store.get(key.slice(0, 160)));
  return result && typeof result === "object" ? result.snapshot || null : null;
}

export async function saveImageAsset(assetId, file) {
  if (!/^[a-z0-9_-]{4,80}$/i.test(assetId) || !(file instanceof Blob)) {
    throw new Error("Invalid image asset.");
  }
  if (file.size > 5 * 1024 * 1024 || !["image/jpeg", "image/png", "image/webp"].includes(file.type)) {
    throw new Error("Use a JPG, PNG, or WebP image under 5 MB.");
  }
  await databaseRequest("assets", "readwrite", (store) =>
    store.put({ blob: file, type: file.type, savedAt: Date.now() }, assetId)
  );
  return assetId;
}

export async function loadImageAsset(assetId) {
  if (!/^[a-z0-9_-]{4,80}$/i.test(String(assetId || ""))) return null;
  const result = await databaseRequest("assets", "readonly", (store) => store.get(assetId));
  return result?.blob instanceof Blob ? result.blob : null;
}

export async function deleteImageAsset(assetId) {
  if (!/^[a-z0-9_-]{4,80}$/i.test(String(assetId || ""))) return;
  await databaseRequest("assets", "readwrite", (store) => store.delete(assetId));
}

export function normalizeEnhancements(value) {
  const type = ["basic", "cloze", "occlusion"].includes(value?.type) ? value.type : "basic";
  const template = [
    "basic",
    "ncert",
    "reaction",
    "formula",
    "journal",
    "graph",
    "assertion",
    "derivation",
  ].includes(value?.template)
    ? value.template
    : "basic";
  const clozeText = typeof value?.clozeText === "string" ? value.clozeText.trim().slice(0, 2_000) : "";
  const imageAssetId =
    typeof value?.imageAssetId === "string" && /^[a-z0-9_-]{4,80}$/i.test(value.imageAssetId)
      ? value.imageAssetId
      : "";
  const occlusions = Array.isArray(value?.occlusions)
    ? value.occlusions.slice(0, 24).flatMap((mask) => {
        if (!mask || typeof mask !== "object") return [];
        const values = [mask.x, mask.y, mask.width, mask.height].map(Number);
        if (!values.every(Number.isFinite)) return [];
        const [x, y, width, height] = values;
        if (x < 0 || y < 0 || width < 20 || height < 20 || x + width > 1_000 || y + height > 1_000) {
          return [];
        }
        return [{ x: Math.round(x), y: Math.round(y), width: Math.round(width), height: Math.round(height) }];
      })
    : [];
  const hints = Array.isArray(value?.hints)
    ? value.hints.filter((hint) => typeof hint === "string" && hint.trim()).slice(0, 3).map((hint) => hint.trim().slice(0, 300))
    : [];
  const examTags = Array.isArray(value?.examTags)
    ? [...new Set(
        value.examTags
          .filter((tag) => typeof tag === "string" && tag.trim())
          .map((tag) => tag.trim().slice(0, 60))
      )].slice(0, 6)
    : [];
  const sections = Array.isArray(value?.sections)
    ? value.sections.slice(0, 12).flatMap((section) => {
        if (!section || typeof section !== "object") return [];
        const label = typeof section.label === "string" ? section.label.trim().slice(0, 60) : "";
        const content = typeof section.value === "string" ? section.value.trim().slice(0, 500) : "";
        return label && content ? [{ label, value: content }] : [];
      })
    : [];
  const subject = typeof value?.subject === "string" ? value.subject.trim().slice(0, 80) : "";
  const graphShape = ["downward", "upward", "ppc", "isotherm", "bell"].includes(value?.graphShape)
    ? value.graphShape
    : "downward";
  const mistakeAt =
    typeof value?.mistakeAt === "string" && Number.isFinite(Date.parse(value.mistakeAt))
      ? new Date(value.mistakeAt).toISOString()
      : "";
  return {
    type,
    clozeText: type === "cloze" ? clozeText : "",
    imageAssetId: type === "occlusion" ? imageAssetId : "",
    occlusions: type === "occlusion" ? occlusions : [],
    lapseStreak: Math.min(10_000, Math.max(0, Math.round(Number(value?.lapseStreak) || 0))),
    leech: Boolean(value?.leech),
    lastScore: Math.min(4, Math.max(0, Math.round(Number(value?.lastScore) || 0))),
    hints,
    template,
    subject,
    examTags,
    trap: Boolean(value?.trap),
    mistake: Boolean(value?.mistake),
    mistakeAt,
    priority: value?.priority === "high" ? "high" : "normal",
    sections,
    graphShape: template === "graph" ? graphShape : "downward",
  };
}

export function clozeFront(text) {
  return String(text || "")
    .replace(/\{\{c\d+::([^}:]+)(?:::[^}]+)?\}\}/gi, "_____")
    .replace(/\{\{([^}]+)\}\}/g, "_____");
}

export function clozeBack(text) {
  return String(text || "")
    .replace(/\{\{c\d+::([^}:]+)(?:::[^}]+)?\}\}/gi, "$1")
    .replace(/\{\{([^}]+)\}\}/g, "$1");
}

function appendInline(container, text) {
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`|\$[^$]+\$)/g;
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > cursor) container.append(document.createTextNode(text.slice(cursor, match.index)));
    const token = match[0];
    let element;
    if (token.startsWith("**")) {
      element = document.createElement("strong");
      element.textContent = token.slice(2, -2);
    } else if (token.startsWith("`")) {
      element = document.createElement("code");
      element.textContent = token.slice(1, -1);
    } else {
      element = document.createElement("span");
      element.className = "mathToken";
      element.textContent = token.slice(1, -1);
    }
    container.append(element);
    cursor = match.index + token.length;
  }
  if (cursor < text.length) container.append(document.createTextNode(text.slice(cursor)));
}

export function renderRichText(container, value) {
  container.replaceChildren();
  const text = String(value || "").slice(0, 2_000);
  const lines = text.split("\n");
  let codeMode = false;
  let code = null;
  for (const line of lines) {
    if (line.trim().startsWith("```")) {
      codeMode = !codeMode;
      if (codeMode) {
        code = document.createElement("code");
        code.className = "codeBlock";
        container.append(code);
      }
      continue;
    }
    if (codeMode && code) {
      code.append(document.createTextNode(`${line}\n`));
      continue;
    }
    appendInline(container, line);
    container.append(document.createElement("br"));
  }
  if (container.lastElementChild?.tagName === "BR") container.lastElementChild.remove();
}

function keywords(text) {
  return [...new Set(
    String(text || "")
      .toLowerCase()
      .replace(/[^a-z0-9\s-]/g, " ")
      .split(/\s+/)
      .filter((word) => word.length >= 4 && !STOP_WORDS.has(word))
  )];
}

export function compareExplanation(transcript, answer) {
  const answerTerms = keywords(answer);
  const spokenTerms = new Set(keywords(transcript));
  if (!answerTerms.length || !spokenTerms.size) return { score: 0, matched: [], missing: answerTerms.slice(0, 5) };
  const matched = answerTerms.filter((term) => spokenTerms.has(term));
  const score = Math.min(100, Math.round((matched.length / Math.min(answerTerms.length, 10)) * 100));
  return { score, matched: matched.slice(0, 5), missing: answerTerms.filter((term) => !spokenTerms.has(term)).slice(0, 5) };
}

export function buildOfflineHints(answer) {
  const clean = String(answer || "").replace(/\s+/g, " ").trim();
  const terms = keywords(clean).sort((left, right) => right.length - left.length).slice(0, 4);
  if (!clean) return ["Think about the core definition.", "Recall the main relationship.", "Try explaining it in one sentence."];
  const initials = terms.map((term) => `${term[0].toUpperCase()}…`).join(", ") || clean[0].toUpperCase();
  const firstClause = clean.split(/[.;:]/)[0].split(/\s+/).slice(0, 6).join(" ");
  return [
    `Key-word initials: ${initials}`,
    terms.length ? `Connect these ideas: ${terms.slice(0, 3).join(" · ")}` : "Focus on the subject and what it does.",
    `The answer begins: “${firstClause}${clean.length > firstClause.length ? "…" : ""}”`,
  ];
}

export function createClozeDrafts(text, limit = 12) {
  const sentences = String(text || "")
    .replace(/\r/g, "")
    .split(/\n+|(?<=[.!?])\s+/)
    .map((sentence) => sentence.trim())
    .filter((sentence) => sentence.length >= 28 && sentence.length <= 360);
  const drafts = [];
  for (const sentence of sentences) {
    const candidates = keywords(sentence).sort((left, right) => right.length - left.length);
    const answer = candidates[0];
    if (!answer) continue;
    const pattern = new RegExp(`\\b${answer.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`, "i");
    const clozeText = sentence.replace(pattern, (match) => `{{c1::${match}}}`);
    if (clozeText === sentence) continue;
    drafts.push({ type: "cloze", clozeText, front: clozeFront(clozeText), back: clozeBack(clozeText) });
    if (drafts.length >= limit) break;
  }
  return drafts;
}

export function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    if (!(file instanceof Blob)) {
      reject(new Error("Choose an image first."));
      return;
    }
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("Could not read that image."));
    reader.readAsDataURL(file);
  });
}

export async function extractTextWithBrowserOcr(file) {
  if (!("TextDetector" in window)) throw new Error("Offline OCR is not supported in this browser. Select Secure AI for photo scanning.");
  const bitmap = await createImageBitmap(file);
  try {
    const detector = new window.TextDetector();
    const results = await detector.detect(bitmap);
    return results.map((item) => item.rawValue || "").join("\n").trim();
  } finally {
    bitmap.close();
  }
}

function utf8ToBase64Url(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlToUtf8(value) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(normalized + "=".repeat((4 - (normalized.length % 4)) % 4));
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export function encodeDeckShare(cards) {
  if (!Array.isArray(cards) || !cards.length || cards.length > 40) {
    throw new Error("Share links support decks with 1–40 cards.");
  }
  const compact = cards.map((card) => ({
    f: String(card.front || "").slice(0, 500),
    b: String(card.back || "").slice(0, 2_000),
    t: card.type === "cloze" ? "c" : "b",
    c: card.type === "cloze" ? String(card.clozeText || "").slice(0, 2_000) : "",
  }));
  const code = utf8ToBase64Url(JSON.stringify({ v: 1, c: compact }));
  if (code.length > 12_000) throw new Error("This deck is too large for a share link. Export JSON instead.");
  return code;
}

export function decodeDeckShare(code) {
  if (typeof code !== "string" || !/^[A-Za-z0-9_-]{8,12000}$/.test(code)) throw new Error("Invalid deck code.");
  const parsed = JSON.parse(base64UrlToUtf8(code));
  if (parsed?.v !== 1 || !Array.isArray(parsed.c) || !parsed.c.length || parsed.c.length > 40) {
    throw new Error("Invalid deck code.");
  }
  return parsed.c.map((card) => ({
    front: String(card.f || "").slice(0, 500),
    back: String(card.b || "").slice(0, 2_000),
    type: card.t === "c" ? "cloze" : "basic",
    clozeText: card.t === "c" ? String(card.c || "").slice(0, 2_000) : "",
  }));
}

export const STARTER_DECKS = Object.freeze([
  {
    id: "management",
    title: "Management Foundations",
    description: "Core BBA concepts: functions, skills, Taylor and Fayol.",
    cards: [
      ["What are the five functions of management?", "Planning, organizing, staffing, directing, and controlling."],
      ["What is conceptual skill?", "The ability to understand the organization as a whole and connect complex ideas."],
      ["State Taylor's principle of science, not rule of thumb.", "Managers should replace guesswork with scientific study of each task."],
      ["What is unity of command?", "Each employee should receive orders from only one superior."],
    ],
  },
  {
    id: "computers",
    title: "Computer Fundamentals",
    description: "Hardware, software, storage, operating systems, and generations.",
    cards: [
      ["What is hardware?", "The physical components of a computer that can be seen and touched."],
      ["What is system software?", "Software that manages hardware and provides a platform for applications."],
      ["What is the function of RAM?", "RAM temporarily stores data and instructions currently used by the CPU."],
      ["What is an operating system?", "System software that manages computer resources and provides a user interface."],
    ],
  },
  {
    id: "values",
    title: "Human Values",
    description: "Fast revision for values, ethics, harmony, and right conduct.",
    cards: [
      ["What are human values?", "Good qualities and principles that guide behavior and decision-making."],
      ["What is professional ethics?", "Standards that guide responsible conduct within a profession."],
      ["What is mutual responsibility?", "Sharing duties and supporting one another for collective well-being."],
      ["Name the five universal values.", "Truth, right conduct, peace, love, and non-violence."],
    ],
  },
]);
