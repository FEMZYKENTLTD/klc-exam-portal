// Web question upload. Parses teacher-friendly plain text into questions,
// previews the parse, then calls staff_upload_questions (SECURITY DEFINER)
// which verifies credentials + subject ownership and stores is_approved=FALSE.
const session = {
  email: sessionStorage.getItem("klc_email") || "",
  password: sessionStorage.getItem("klc_pass") || "",
  role: sessionStorage.getItem("klc_role") || "",
};

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;",
            "'": "&#39;" }[c]));
}

function parseQuestions(raw) {
  const out = [];
  const lines = String(raw || "").split(/\r?\n/);
  let cur = null;
  const push = () => {
    if (!cur || !cur.q.trim()) return;
    cur.type = cur.opts.length === 2 &&
      cur.opts.some(o => /^true$/i.test(o.text)) &&
      cur.opts.some(o => /^false$/i.test(o.text))
      ? "TRUE_FALSE" : "MCQ";
    out.push(cur);
  };
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line) { if (cur && cur.q) { push(); cur = null; } continue; }
    const qm = line.match(/^Question\s*:\s?(.*)$/i);
    if (qm) { if (cur && cur.q) push(); cur = { q: qm[1], topic: "", source: "", opts: [] }; continue; }
    const tm = line.match(/^Topic\s*:\s?(.*)$/i);
    if (tm) { if (!cur) cur = { q: "", topic: "", source: "", opts: [] }; cur.topic = tm[1]; continue; }
    const sm = line.match(/^Source\s*:\s?(.*)$/i);
    if (sm) { if (!cur) cur = { q: "", topic: "", source: "", opts: [] }; cur.source = sm[1]; continue; }
    const om = line.match(/^\[(x| )\]\s?(.*)$/i);
    if (om) {
      if (!cur) cur = { q: "", topic: "", source: "", opts: [] };
      cur.opts.push({ label: "", text: om[2], correct: /x/i.test(om[1]) });
      continue;
    }
    // continuation line of a question/option
    if (cur) {
      if (cur.opts.length) cur.opts[cur.opts.length - 1].text += " " + line;
      else cur.q += (cur.q ? " " : "") + line;
    }
  }
  if (cur && cur.q) push();
  // assign A-E labels, drop empty options, keep at most 5
  for (const q of out) {
    const labels = ["A", "B", "C", "D", "E"];
    q.opts = q.opts.filter(o => o.text.trim()).slice(0, 5)
      .map((o, i) => ({ label: labels[i], text: o.text.trim(), correct: o.correct }));
  }
  return out;
}

function preview() {
  const el = document.getElementById("preview");
  const err = document.getElementById("parseError");
  let qs;
  try { qs = parseQuestions(document.getElementById("questions").value); }
  catch (e) { err.textContent = "Parse error: " + e.message; err.hidden = false; return; }
  err.hidden = true;
  el.textContent = "Parsed " + qs.length + " question(s)\n" + qs.map((q, i) =>
    `${i + 1}. [${q.type}] ${q.q.slice(0, 110)}`).join("\n");
}

window.addEventListener("DOMContentLoaded", () => {
  document.getElementById("loginForm").addEventListener("submit", async e => {
    e.preventDefault();
    const err = document.getElementById("loginError");
    err.hidden = true;
    session.email = document.getElementById("email").value.trim();
    session.password = document.getElementById("password").value;
    try {
      session.role = await klcRpc("staff_check",
        { p_email: session.email, p_password: session.password });
      sessionStorage.setItem("klc_email", session.email);
      sessionStorage.setItem("klc_pass", session.password);
      sessionStorage.setItem("klc_role", session.role);
      enter();
    } catch (ex) {
      err.textContent = ex.message || "Login failed.";
      err.hidden = false;
    }
  });
  document.getElementById("questions").addEventListener("input", preview);
  document.getElementById("upForm").addEventListener("submit", upload);
  if (session.email && session.password) enter();
});

async function enter() {
  document.getElementById("loginCard").hidden = true;
  document.getElementById("uploadCard").hidden = false;
  document.getElementById("who").textContent =
    session.email.replace(/@.*/, "") + " · " +
    String(session.role || "").replace("_", " ");
  preview();
  try {
    const rows = await klcRpc("staff_subjects",
      { p_email: session.email, p_password: session.password });
    document.getElementById("subjectList").innerHTML = (rows || [])
      .map(r => `<option value="${esc(r.subject_code)}">${esc(r.subject_name)} – ${esc(r.class_level)}</option>`)
      .join("");
  } catch (_) { /* datalist stays empty - type the code manually */ }
}

async function upload(e) {
  e.preventDefault();
  const res = document.getElementById("result");
  const err = document.getElementById("parseError");
  const qs = parseQuestions(document.getElementById("questions").value);
  err.hidden = true;
  res.className = "";
  res.textContent = "";
  if (!qs.length) {
    err.textContent = "No valid questions found. Check the format rules.";
    err.hidden = false;
    return;
  }
  try {
    const n = await klcRpc("staff_upload_questions", {
      p_email: session.email, p_password: session.password,
      p_subject_code: document.getElementById("subjectCode").value.trim(),
      p_class_level: document.getElementById("classLevel").value,
      p_questions: qs,
    });
    res.className = "ok";
    res.innerHTML = `✅ Uploaded <b>${n}</b> question(s) for approval.
      An Exam Officer or Super Admin must approve them before students
      can see them.`;
    document.getElementById("questions").value = "";
    preview();
  } catch (ex) {
    res.className = "error";
    res.innerHTML = esc(ex.message || "Upload failed.");
  }
}
