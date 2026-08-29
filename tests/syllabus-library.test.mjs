import assert from "node:assert/strict";
import test from "node:test";

import {
  CBSE_SYLLABUS,
  chaptersFor,
  subjectGuide,
  syllabusChapterCount,
  totalSyllabusChapterCount,
} from "../static/cbse-syllabus.js";

const science = ["Physics", "Chemistry", "Mathematics", "Biology"];
const commerce = ["Accountancy", "Business Studies", "Economics", "Entrepreneurship"];

test("ready syllabus covers both classes and every requested subject", () => {
  assert.deepEqual(Object.keys(CBSE_SYLLABUS), ["Class 11", "Class 12"]);
  for (const classLevel of Object.keys(CBSE_SYLLABUS)) {
    for (const subject of [...science, ...commerce]) {
      assert.ok(chaptersFor(classLevel, subject).length >= 6, `${classLevel} ${subject} should include its chapter catalog`);
      assert.ok(subjectGuide(subject).length > 30, `${subject} should include a revision guide`);
    }
  }
});

test("chapter catalog has stable numbered entries and complete coverage", () => {
  assert.equal(totalSyllabusChapterCount(), 190);
  assert.equal(syllabusChapterCount("Class 12", ["Physics", "Chemistry", "Mathematics"]), 37);
  assert.equal(syllabusChapterCount("Class 12", ["Physics", "Chemistry", "Biology"]), 37);
  assert.equal(syllabusChapterCount("Class 11", commerce), 41);
  for (const catalog of Object.values(CBSE_SYLLABUS)) {
    for (const chapters of Object.values(catalog)) {
      chapters.forEach((chapter, index) => {
        assert.equal(chapter.number, index + 1);
        assert.match(chapter.id, /^\d{2}-[a-z0-9-]+$/);
      });
    }
  }
});
