# KLC CBT Suite v1.0 — Teacher Question Upload Guide

## Rules
- You can only upload/edit questions for **subjects assigned to you**
  (Super Admin can upload on behalf of any teacher).
- Questions go live only after the **Exam Officer approves** them
  (Question Bank → Approve).

## Upload methods (Question Importer)
1. **PDF** (Apache PDFBox) and **DOCX** (Apache POI) auto-parse.
2. Paste or type the **Answer Key** in the answer-key box
   (`1. A`, `2. C`, …). The import is **REJECTED** unless the number of
   answers exactly matches the number of parsed questions — fix and retry.
3. Review the preview grid, then commit.
4. Manual entry: **Question Editor** — text, image, options A–E, correct
   answer, **topic**, **difficulty**, **year** (WAEC/NECO), **Bloom's level**,
   explanation.

## Good practice
- Tag every question with a topic — students and principals see the
  topic-by-topic breakdown; weak-topic teaching depends on it.
- Use "Year" for past WAEC/NECO questions so Practice can filter them.
