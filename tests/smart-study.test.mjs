import assert from "node:assert/strict";

import {
  decodeDeckShare,
  encodeDeckShare,
  normalizeEnhancements,
} from "../static/smart-study.js";

const examDeck = [
  {
    front: "$F = ma$",
    back: "newton · [M L T^-2]",
    type: "basic",
    template: "formula",
    subject: "Physics",
    examTags: ["JEE Main 2024", "Formula"],
    trap: true,
    sections: [
      { label: "SI unit", value: "newton (N)" },
      { label: "Dimensional formula", value: "[M L T^-2]" },
    ],
  },
];

const restored = decodeDeckShare(encodeDeckShare(examDeck));
assert.equal(restored[0].template, "formula");
assert.equal(restored[0].subject, "Physics");
assert.deepEqual(restored[0].examTags, ["JEE Main 2024", "Formula"]);
assert.equal(restored[0].trap, true);
assert.deepEqual(restored[0].sections, examDeck[0].sections);

const legacyCode = Buffer.from(
  JSON.stringify({ v: 1, c: [{ f: "Legacy front", b: "Legacy back", t: "b", c: "" }] }),
  "utf8"
).toString("base64url");
const legacy = decodeDeckShare(legacyCode);
assert.equal(legacy[0].front, "Legacy front");
assert.equal(legacy[0].template, "basic");

const sanitized = normalizeEnhancements({
  template: "unknown",
  subject: "x".repeat(200),
  examTags: ["PYQ", "PYQ", 42],
  sections: [{ label: "Unit", value: "N" }, { label: 42, value: "ignored" }],
  graphShape: "unsafe",
});
assert.equal(sanitized.template, "basic");
assert.equal(sanitized.subject.length, 80);
assert.deepEqual(sanitized.examTags, ["PYQ"]);
assert.deepEqual(sanitized.sections, [{ label: "Unit", value: "N" }]);
assert.equal(sanitized.graphShape, "downward");

console.log("Smart Study JavaScript tests passed.");
