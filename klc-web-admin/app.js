// KLC Staff Web Console - full management counterpart of the desktop app.
// Sitting an exam is intentionally NOT available here (desktop app only).
const S = {
  email: sessionStorage.getItem("klc_email") || "",
  password: sessionStorage.getItem("klc_pass") || "",
  role: sessionStorage.getItem("klc_role") || "",
};
const creds = { p_email: S.email, p_password: S.password };
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "-").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const dt = (v) => v ? String(v).slice(0, 16).replace("T", " ") : "-";
const isAdmin = () => ["SUPER_ADMIN", "PRINCIPAL_ADMIN"].includes(S.role);
const isOfficerPlus = () => ["SUPER_ADMIN", "PRINCIPAL_ADMIN", "EXAM_OFFICER"].includes(S.role);

function msg(t, bad) { const el = $("modMsg"); el.textContent = t || ""; el.className = bad ? "error" : "muted"; }
function table(cols, rows, rowFn) {
  if (!rows?.length) return `<p class="muted">No records found.</p>`;
  return `<div class="scrollx"><table><thead><tr>${cols.map(c => `<th>${c}</th>`).join("")}</tr></thead>` +
    `<tbody>${rows.map(rowFn).join("")}</tbody></table></div>`;
}
function download(name, text, type = "text/csv") {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type }));
  a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}
const csvCell = (v) => { const s = String(v ?? ""); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
function toCsv(head, rows) { return [head.join(","), ...rows.map(r => r.map(csvCell).join(","))].join("\n"); }
async function rpc(fn, args = {}) {
  try { return await klcRpc(fn, { ...creds, ...args }); }
  catch (e) {
    if (String(e.message).includes("Invalid credentials")) { sessionStorage.clear(); location.reload(); }
    throw e;
  }
}
const val = (id) => $(id)?.value?.trim() ?? "";
const num = (id) => { const v = parseFloat(val(id)); return Number.isFinite(v) ? v : null; };
function formGrid(fields) {
  return `<div class="grid">${fields.map(f => f.html || `<label>${f.label}<input id="${f.id}" type="${f.type || "text"}" value="${esc(f.value ?? "")}" placeholder="${esc(f.ph || "")}"/></label>`).join("")}</div>`;
}
const sel = (id, label, opts, cur) => `<label>${label}<select id="${id}">${opts.map(o => `<option ${o === cur ? "selected" : ""}>${o}</option>`).join("")}</select></label>`;
const CLASSES = ["JSS1","JSS2","JSS3","SS1","SS2","SS3"], TERMS = ["1st","2nd","3rd"];

// ------------------------------------------------------------------ modules
const MODULES = [
  { id: "dashboard", label: "📊 Dashboard", roles: null, load: loadDashboard },
  { id: "students", label: "🎓 Students", roles: null, load: loadStudents },
  { id: "teachers", label: "👩‍🏫 Teachers & Staff", roles: ["SUPER_ADMIN","PRINCIPAL_ADMIN"], load: loadTeachers },
  { id: "classes", label: "🏫 Classes", roles: null, load: loadClasses },
  { id: "subjects", label: "📚 Subjects", roles: null, load: loadSubjects },
  { id: "questions", label: "❓ Question Bank", roles: null, load: loadQuestions },
  { id: "exams", label: "📝 Exam Manager", roles: null, load: loadExams },
  { id: "ca", label: "🧮 CA Scores", roles: null, load: loadCa },
  { id: "results", label: "🏆 Results", roles: null, load: loadResults },
  { id: "broadsheet", label: "📋 Broadsheet", roles: null, load: loadBroadsheet },
  { id: "analytics", label: "📈 Analytics", roles: null, load: loadAnalytics },
  { id: "appeals", label: "⚖️ Appeals", roles: ["SUPER_ADMIN","PRINCIPAL_ADMIN","EXAM_OFFICER"], load: loadAppeals },
  { id: "announce", label: "📣 Announcements", roles: null, load: loadAnnouncements },
  { id: "materials", label: "📖 Study Materials", roles: null, load: loadMaterials },
  { id: "liveroom", label: "🎥 Live Exam Room", roles: null, load: loadLiveRoom },
  { id: "notify", label: "✉️ Notifications", roles: ["SUPER_ADMIN","PRINCIPAL_ADMIN","EXAM_OFFICER"], load: loadNotifications },
  { id: "audit", label: "🛡️ Audit Trail", roles: ["SUPER_ADMIN","PRINCIPAL_ADMIN"], load: loadAudit },
  { id: "settings", label: "⚙️ School Settings", roles: ["SUPER_ADMIN","PRINCIPAL_ADMIN"], load: loadSettings },
];
let liveTimer = null;

function buildNav() {
  $("sidenav").innerHTML = MODULES
    .filter(m => !m.roles || m.roles.includes(S.role))
    .map(m => `<button class="btn subtle navbtn" data-mod="${m.id}">${m.label}</button>`).join("");
  document.querySelectorAll("[data-mod]").forEach(b => b.onclick = () => openModule(b.dataset.mod));
}
async function openModule(id) {
  const m = MODULES.find(x => x.id === id);
  document.querySelectorAll("[data-mod]").forEach(b => b.classList.toggle("gold", b.dataset.mod === id));
  $("modTitle").textContent = m.label.replace(/^\S+\s/, "");
  msg("Loading…");
  if (liveTimer) { clearInterval(liveTimer); liveTimer = null; }
  try { await m.load(); msg(""); } catch (e) { msg(e.message, true); }
}

// ---------------------------------------------------------------- dashboard
async function loadDashboard() {
  const d = await rpc("staff_dashboard");
  $("schoolLine").textContent = d.school ? `${d.school.school_name} · ${d.school.session_current} · ${d.school.term_current} Term` : "";
  const cards = [["Active Students", d.students], ["Teachers", d.teachers], ["Subjects", d.subjects],
    ["Questions ✓", d.questions_approved], ["Questions Pending", d.questions_pending],
    ["Exams", `${d.exams_active}/${d.exams} active`], ["Results", d.results],
    ["Live Attempts", d.attempts_live], ["Open Appeals", d.appeals_open], ["Announcements", d.announcements]];
  $("modBody").innerHTML = `<div class="stats">${cards.map(([k, v]) => `<div class="stat"><div class="statnum">${esc(v)}</div><div class="muted small">${k}</div></div>`).join("")}</div>
    <p class="muted">Exams are <b>managed</b> here but <b>sat only in the desktop app</b> (proctoring, kiosk mode, offline cache). Use Live Exam Room on exam day.</p>`;
}

// ----------------------------------------------------------------- students
async function loadStudents() {
  $("modBody").innerHTML = `
    <div class="row">
      ${sel("fClass", "Class", ["", ...CLASSES])}
      <label>Search <input id="fSearch" placeholder="name or admission no"/></label>
      <button class="btn" id="fGo">Filter</button>
      <button class="btn" id="sCsv">⬇ CSV</button>
    </div>
    <h3>Student editor</h3>
    ${formGrid([
      { id: "s_id", label: "User ID (blank = new)", ph: "auto" },
      { id: "s_name", label: "Full name" }, { id: "s_email", label: "Email", type: "email" },
      { id: "s_pass", label: "Password (blank = keep)", type: "password" },
      { id: "s_adm", label: "Admission No" },
      { html: sel("s_class", "Class", CLASSES, "SS1") },
      { html: sel("s_arm", "Arm", ["", "A", "B", "C", "Science", "Art", "Commercial"]) },
      { html: sel("s_gender", "Gender", ["", "Male", "Female"]) },
      { id: "s_phone", label: "Parent phone" }, { id: "s_pemail", label: "Parent email", type: "email" },
      { html: sel("s_fee", "Fee status", ["PAID", "PART", "UNPAID"]) },
      { html: sel("s_status", "Status", ["ACTIVE", "REPEATING", "GRADUATED", "WITHDRAWN"]) },
    ])}
    <div class="actions"><button class="btn gold" id="sSave">Save Student</button></div>
    <div id="sTable"></div>`;
  const render = async () => {
    const rows = await rpc("staff_students", { p_class: val("fClass") || null, p_search: val("fSearch") || null });
    window._students = rows;
    $("sTable").innerHTML = table(["Admission","Name","Class","Arm","Fee","Status","PIN",""], rows, r =>
      `<tr><td>${esc(r.admission_no)}</td><td>${esc(r.full_name)}</td><td>${esc(r.class_level)}</td><td>${esc(r.arm)}</td>
       <td>${esc(r.fee_status)}</td><td>${esc(r.status)}</td><td>${esc(r.result_pin)}</td>
       <td><button class="btn small" onclick="editStudent('${r.user_id}')">Edit</button></td></tr>`);
  };
  window.editStudent = (uid) => {
    const r = window._students.find(x => x.user_id === uid); if (!r) return;
    $("s_id").value = r.user_id; $("s_name").value = r.full_name || ""; $("s_email").value = r.email || "";
    $("s_adm").value = r.admission_no || ""; $("s_class").value = r.class_level || "SS1";
    $("s_arm").value = r.arm || ""; $("s_gender").value = r.gender || "";
    $("s_phone").value = r.parent_phone || ""; $("s_pemail").value = r.parent_email || "";
    $("s_fee").value = r.fee_status || "PAID"; $("s_status").value = r.status || "ACTIVE";
    window.scrollTo({ top: 0, behavior: "smooth" });
  };
  $("fGo").onclick = render;
  $("sCsv").onclick = () => download("klc-students.csv", toCsv(["Admission","Name","Class","Arm","Fee","Status","PIN"],
    (window._students || []).map(r => [r.admission_no, r.full_name, r.class_level, r.arm, r.fee_status, r.status, r.result_pin])));
  $("sSave").onclick = async () => {
    const out = await rpc("staff_student_save", { p: {
      user_id: val("s_id"), full_name: val("s_name"), email: val("s_email"), password: val("s_pass"),
      admission_no: val("s_adm"), class_level: val("s_class"), arm: val("s_arm"), gender: val("s_gender"),
      parent_phone: val("s_phone"), parent_email: val("s_pemail"), fee_status: val("s_fee"), status: val("s_status") } });
    msg(`Saved. Result PIN: ${out.result_pin || "(unchanged)"}`); render();
  };
  render();
}

// ----------------------------------------------------------------- teachers
async function loadTeachers() {
  const rows = await rpc("staff_teachers");
  window._teachers = rows;
  $("modBody").innerHTML = `
    ${formGrid([
      { id: "t_id", label: "User ID (blank = new)" }, { id: "t_name", label: "Full name" },
      { id: "t_email", label: "Email", type: "email" }, { id: "t_pass", label: "Password (blank = keep)", type: "password" },
      { id: "t_phone", label: "Phone" },
      { html: sel("t_role", "Role", ["TEACHER", "EXAM_OFFICER", "PRINCIPAL_ADMIN"]) },
      { id: "t_subjects", label: "Subject codes (comma separated)", ph: "MTH-SS1, ENG-SS1" },
      { html: sel("t_active", "Active", ["true", "false"], "true") },
    ])}
    <div class="actions"><button class="btn gold" id="tSave">Save Staff</button></div>
    ${table(["Name","Email","Role","Subjects","Active",""], rows, r =>
      `<tr><td>${esc(r.full_name)}</td><td>${esc(r.email)}</td><td>${esc(r.role)}</td>
       <td>${esc((r.subjects || []).join(", "))}</td><td>${r.is_active ? "✅" : "⛔"}</td>
       <td><button class="btn small" onclick="editTeacher('${r.user_id}')">Edit</button></td></tr>`)}`;
  window.editTeacher = (uid) => {
    const r = window._teachers.find(x => x.user_id === uid); if (!r) return;
    $("t_id").value = r.user_id; $("t_name").value = r.full_name; $("t_email").value = r.email;
    $("t_phone").value = r.phone || ""; $("t_role").value = r.role;
    $("t_subjects").value = (r.subjects || []).join(", "); $("t_active").value = String(!!r.is_active);
  };
  $("tSave").onclick = async () => {
    await rpc("staff_teacher_save", { p: {
      user_id: val("t_id"), full_name: val("t_name"), email: val("t_email"), password: val("t_pass"),
      phone: val("t_phone"), role: val("t_role"), is_active: val("t_active") === "true",
      subject_codes: val("t_subjects") ? val("t_subjects").split(",").map(s => s.trim()).filter(Boolean) : [] } });
    msg("Saved."); openModule("teachers");
  };
}

// ----------------------------------------------------------- classes/subjects
async function loadClasses() {
  const rows = await rpc("staff_classes");
  $("modBody").innerHTML = `
    ${formGrid([
      { id: "c_id", label: "Class ID (blank = new)" },
      { html: sel("c_class", "Class", CLASSES) }, { id: "c_arm", label: "Arm", ph: "A / Science / …" },
      { id: "c_session", label: "Session", ph: "2024/2025" },
      { html: sel("c_active", "Active", ["true", "false"], "true") },
    ])}
    <div class="actions"><button class="btn gold" id="cSave">Save Class</button></div>
    ${table(["Class","Arm","Session","Students","Active"], rows, r =>
      `<tr><td>${esc(r.class_level)}</td><td>${esc(r.arm)}</td><td>${esc(r.session)}</td><td>${esc(r.students)}</td><td>${r.is_active ? "✅" : "⛔"}</td></tr>`)}`;
  $("cSave").onclick = async () => {
    await rpc("staff_class_save", { p: { id: val("c_id"), class_level: val("c_class"), arm: val("c_arm"),
      session: val("c_session"), is_active: val("c_active") === "true" } });
    msg("Saved."); openModule("classes");
  };
}

async function loadSubjects() {
  const rows = await rpc("staff_subjects");
  $("modBody").innerHTML = (isAdmin() ? `
    ${formGrid([
      { id: "u_name", label: "Subject name" }, { id: "u_code", label: "Subject code", ph: "MTH-SS1" },
      { html: sel("u_class", "Class", CLASSES) }, { id: "u_pass", label: "Pass mark", type: "number", value: "40" },
      { html: sel("u_active", "Active", ["true", "false"], "true") },
    ])}
    <div class="actions"><button class="btn gold" id="uSave">Save Subject</button></div>` : "") +
    table(["Code","Subject","Class","Active"], rows, r =>
      `<tr><td>${esc(r.subject_code)}</td><td>${esc(r.subject_name)}</td><td>${esc(r.class_level)}</td><td>${r.is_active ? "✅" : "⛔"}</td></tr>`);
  if ($("uSave")) $("uSave").onclick = async () => {
    await rpc("staff_subject_save", { p: { subject_name: val("u_name"), subject_code: val("u_code"),
      class_level: val("u_class"), pass_mark: num("u_pass"), is_active: val("u_active") === "true" } });
    msg("Saved."); openModule("subjects");
  };
}

// ---------------------------------------------------------------- questions
async function loadQuestions() {
  $("modBody").innerHTML = `
    <div class="row">
      <label>Subject code <input id="q_subj" placeholder="MTH-SS1"/></label>
      ${sel("q_appr", "Approval", ["", "true", "false"])}
      <label>Search <input id="q_search"/></label>
      <button class="btn" id="qGo">Load</button>
      <a class="btn" href="question_upload.html">Bulk Upload Page</a>
    </div>
    <h3>Question editor</h3>
    ${formGrid([
      { id: "qe_id", label: "Question ID (blank = new)" }, { id: "qe_subj", label: "Subject code" },
      { html: sel("qe_class", "Class", CLASSES) }, { id: "qe_topic", label: "Topic" },
      { html: sel("qe_type", "Type", ["MCQ", "TRUE_FALSE", "IMAGE"]) },
    ])}
    <label>Question text <textarea id="qe_text" rows="3"></textarea></label>
    <div id="qe_opts"></div>
    <div class="actions"><button class="btn" id="qe_add">+ Option</button>
      <button class="btn gold" id="qe_save">Save Question</button></div>
    <div id="qTable"></div>`;
  const optRow = (o = {}) => {
    const d = document.createElement("div"); d.className = "row optrow";
    d.innerHTML = `<input class="o_label" size="2" maxlength="1" value="${esc(o.label || "")}" placeholder="A"/>
      <input class="o_text" value="${esc(o.text || "")}" placeholder="Option text"/>
      <label class="small"><input type="radio" name="o_correct" class="o_correct" ${o.correct ? "checked" : ""}/> correct</label>`;
    $("qe_opts").appendChild(d);
  };
  ["A", "B", "C", "D"].forEach(l => optRow({ label: l }));
  $("qe_add").onclick = () => optRow({});
  const render = async () => {
    const rows = await rpc("staff_questions", {
      p_subject_code: val("q_subj") || null,
      p_approved: val("q_appr") === "" ? null : val("q_appr") === "true",
      p_search: val("q_search") || null });
    window._qs = rows;
    $("qTable").innerHTML = table(["Subject","Class","Topic","Question","Type","Status",""], rows, r =>
      `<tr><td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td><td>${esc(r.topic)}</td>
       <td>${esc(String(r.question_text).slice(0, 90))}…</td><td>${esc(r.question_type)}</td>
       <td>${r.is_approved ? "✅ Approved" : "⏳ Pending"}</td>
       <td><button class="btn small" onclick="editQ('${r.id}')">Edit</button>
       ${isOfficerPlus() ? `<button class="btn small" onclick="modQ('${r.id}','${r.is_approved ? "unapprove" : "approve"}')">${r.is_approved ? "Unapprove" : "Approve"}</button>
       <button class="btn small danger" onclick="modQ('${r.id}','delete')">Delete</button>` : ""}</td></tr>`);
  };
  window.editQ = (id) => {
    const r = window._qs.find(x => x.id === id); if (!r) return;
    $("qe_id").value = r.id; $("qe_subj").value = r.subject_code; $("qe_class").value = r.class_level || "SS1";
    $("qe_topic").value = r.topic || ""; $("qe_type").value = r.question_type || "MCQ"; $("qe_text").value = r.question_text;
    $("qe_opts").innerHTML = ""; (r.options || []).forEach(optRow);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };
  window.modQ = async (id, action) => {
    if (action === "delete" && !confirm("Delete this question permanently?")) return;
    await rpc("staff_question_moderate", { p_id: id, p_action: action }); render();
  };
  $("qe_save").onclick = async () => {
    const options = [...document.querySelectorAll(".optrow")].map(d => ({
      label: d.querySelector(".o_label").value.trim().toUpperCase(),
      text: d.querySelector(".o_text").value.trim(),
      correct: d.querySelector(".o_correct").checked })).filter(o => o.text);
    await rpc("staff_question_save", { p: { id: val("qe_id"), subject_code: val("qe_subj"),
      class_level: val("qe_class"), topic: val("qe_topic"), question_type: val("qe_type"),
      question_text: val("qe_text"), options } });
    msg("Saved (pending approval)."); render();
  };
  $("qGo").onclick = render;
  render();
}

// -------------------------------------------------------------------- exams
async function loadExams() {
  const rows = await rpc("staff_exams");
  window._exams = rows;
  $("modBody").innerHTML = `
    <p class="muted">Manage exams here. <b>Students sit exams only in the desktop app</b> — this console never starts an attempt.</p>
    ${formGrid([
      { id: "e_id", label: "Exam ID (blank = new)" }, { id: "e_subj", label: "Subject code" },
      { html: sel("e_class", "Class", CLASSES) }, { id: "e_arm", label: "Arm" },
      { html: sel("e_term", "Term", TERMS) }, { id: "e_session", label: "Session", ph: "2024/2025" },
      { id: "e_title", label: "Title" }, { id: "e_dur", label: "Duration (mins)", type: "number", value: "60" },
      { id: "e_limit", label: "Attempt limit", type: "number", value: "1" },
      { id: "e_start", label: "Starts (YYYY-MM-DD HH:MM)", ph: "2025-01-10 08:00" },
      { id: "e_end", label: "Ends (YYYY-MM-DD HH:MM)", ph: "2025-01-10 18:00" },
      { html: sel("e_practice", "Practice", ["false", "true"]) },
      { html: sel("e_mock", "Mock", ["false", "true"]) },
      { html: sel("e_active", "Active", ["true", "false"], "true") },
    ])}
    <label>Instructions <textarea id="e_instr" rows="2"></textarea></label>
    <div class="actions"><button class="btn gold" id="eSave">Save Exam</button>
      <button class="btn" id="eAttach">Attach All Approved Questions (subject+class)</button></div>
    ${table(["Title","Subject","Class","Term","Dur","Limit","Questions","Attempts","Mode","Active",""], rows, r =>
      `<tr><td>${esc(r.title || "(untitled)")}</td><td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td>
       <td>${esc(r.term)}</td><td>${esc(r.duration_minutes)}m</td><td>${esc(r.attempt_limit)}</td>
       <td>${esc(r.question_count)}</td><td>${esc(r.attempts)}</td>
       <td>${r.is_practice ? "Practice" : r.is_mock ? "Mock" : "Official"}</td><td>${r.is_active ? "✅" : "⛔"}</td>
       <td><button class="btn small" onclick="editExam('${r.id}')">Edit</button></td></tr>`)}`;
  window.editExam = (id) => {
    const r = window._exams.find(x => x.id === id); if (!r) return;
    $("e_id").value = r.id; $("e_subj").value = r.subject_code; $("e_class").value = r.class_level;
    $("e_arm").value = r.arm || ""; $("e_term").value = r.term || "1st"; $("e_session").value = r.session || "";
    $("e_title").value = r.title || ""; $("e_dur").value = r.duration_minutes; $("e_limit").value = r.attempt_limit;
    $("e_instr").value = r.instructions || ""; $("e_practice").value = String(!!r.is_practice);
    $("e_mock").value = String(!!r.is_mock); $("e_active").value = String(!!r.is_active);
    $("e_start").value = r.start_at ? String(r.start_at).slice(0, 16).replace("T", " ") : "";
    $("e_end").value = r.end_at ? String(r.end_at).slice(0, 16).replace("T", " ") : "";
    window.scrollTo({ top: 0, behavior: "smooth" });
  };
  const payload = () => ({ id: val("e_id"), subject_code: val("e_subj"), class_level: val("e_class"),
    arm: val("e_arm"), term: val("e_term"), session: val("e_session"), title: val("e_title"),
    instructions: $("e_instr").value, duration_minutes: num("e_dur"), attempt_limit: num("e_limit"),
    start_at: val("e_start"), end_at: val("e_end"), is_practice: val("e_practice") === "true",
    is_mock: val("e_mock") === "true", is_active: val("e_active") === "true" });
  $("eSave").onclick = async () => { await rpc("staff_exam_save", { p: payload() }); msg("Saved."); openModule("exams"); };
  $("eAttach").onclick = async () => {
    let examId = val("e_id");
    if (!examId) { const out = await rpc("staff_exam_save", { p: payload() }); examId = out.id; $("e_id").value = examId; }
    const qs = await rpc("staff_questions", { p_subject_code: val("e_subj"), p_approved: true, p_search: null });
    const ids = qs.filter(q => !val("e_class") || q.class_level === val("e_class")).map(q => q.id);
    if (!ids.length) { msg("No approved questions for that subject/class.", true); return; }
    const out = await rpc("staff_exam_attach", { p_exam: examId, p_question_ids: ids, p_replace: false });
    msg(`Attached. Exam now has ${out.question_count} question(s).`); openModule("exams");
  };
}

// ----------------------------------------------------------------------- CA
async function loadCa() {
  $("modBody").innerHTML = `
    <div class="row">
      ${sel("ca_class", "Class", CLASSES)} <label>Subject code <input id="ca_subj" placeholder="MTH-SS1"/></label>
      ${sel("ca_term", "Term", TERMS)} <label>Session <input id="ca_session" value="2024/2025"/></label>
      <button class="btn" id="caLoad">Load</button><button class="btn gold" id="caSave">Save CA Scores</button>
      <button class="btn" id="caCsv">⬇ CSV Template (loaded rows)</button>
      <label class="btn filebtn">Import CSV <input id="caFile" type="file" accept=".csv" hidden/></label>
    </div>
    <p class="muted small">CSV columns: student_id, ca1, ca2, exam_score, grade. Export loaded rows, edit in Excel, re-import, then Save.</p>
    <div id="caTable"></div>`;
  let rows = [];
  const render = () => {
    $("caTable").innerHTML = table(["Admission","Student","CA1","CA2","Exam","Total","Grade"], rows, (r, i) =>
      `<tr><td>${esc(r.admission_no)}</td><td>${esc(r.student)}</td>
       <td><input class="ca_in" data-i="${i}" data-k="ca1" type="number" step="0.5" value="${r.ca1 ?? ""}"/></td>
       <td><input class="ca_in" data-i="${i}" data-k="ca2" type="number" step="0.5" value="${r.ca2 ?? ""}"/></td>
       <td><input class="ca_in" data-i="${i}" data-k="exam_score" type="number" step="0.5" value="${r.exam_score ?? ""}"/></td>
       <td>${esc(r.total ?? "")}</td><td>${esc(r.grade ?? "")}</td></tr>`);
    document.querySelectorAll(".ca_in").forEach(inp => inp.onchange = () => {
      rows[+inp.dataset.i][inp.dataset.k] = inp.value === "" ? null : +inp.value;
    });
  };
  $("caLoad").onclick = async () => {
    rows = await rpc("staff_ca_scores", { p_class: val("ca_class"), p_subject_code: val("ca_subj"),
      p_term: val("ca_term"), p_session: val("ca_session") });
    render(); msg(`${rows.length} student(s) loaded.`);
  };
  $("caSave").onclick = async () => {
    const out = await rpc("staff_ca_save", { p: { subject_code: val("ca_subj"), class_level: val("ca_class"),
      term: val("ca_term"), session: val("ca_session"),
      rows: rows.filter(r => r.ca1 != null || r.ca2 != null || r.exam_score != null)
        .map(r => ({ student_id: r.student_id, ca1: r.ca1 ?? 0, ca2: r.ca2 ?? 0, exam_score: r.exam_score, grade: gradeFor((r.ca1 || 0) + (r.ca2 || 0) + (r.exam_score || 0)) })) } });
    msg(`Saved ${out.saved} row(s).`); $("caLoad").click();
  };
  $("caCsv").onclick = () => download("klc-ca.csv", toCsv(["student_id","admission_no","student","ca1","ca2","exam_score","grade"],
    rows.map(r => [r.student_id, r.admission_no, r.student, r.ca1 ?? "", r.ca2 ?? "", r.exam_score ?? "", r.grade ?? ""])));
  $("caFile").onchange = async (e) => {
    const text = await e.target.files[0].text();
    const [head, ...lines] = text.trim().split(/\r?\n/);
    const idx = head.split(",").map(h => h.trim());
    lines.forEach(l => {
      const cells = l.split(","); const rec = {};
      idx.forEach((h, i) => rec[h] = cells[i]);
      const r = rows.find(x => x.student_id === rec.student_id);
      if (r) { r.ca1 = +rec.ca1 || 0; r.ca2 = +rec.ca2 || 0; r.exam_score = rec.exam_score === "" ? null : +rec.exam_score; }
    });
    render(); msg("CSV imported into the grid — click Save CA Scores to persist.");
  };
}
function gradeFor(t) { return t >= 75 ? "A1" : t >= 70 ? "B2" : t >= 65 ? "B3" : t >= 60 ? "C4" : t >= 55 ? "C5" : t >= 50 ? "C6" : t >= 45 ? "D7" : t >= 40 ? "E8" : "F9"; }

// ------------------------------------------------------------------- results
async function loadResults() {
  const rows = await rpc("staff_results", { p_limit: 500 });
  window._results = rows;
  $("modBody").innerHTML = `
    <div class="actions"><button class="btn" id="rCsv">⬇ CSV</button></div>
    ${table(["Date","Admission","Student","Subject","Class","Term","Score","%","Grade","Pos","Published","Verify Code",""], rows, r =>
      `<tr><td>${dt(r.created_at)}</td><td>${esc(r.admission_no)}</td><td>${esc(r.student)}</td>
       <td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td><td>${esc(r.term)}</td><td>${esc(r.score)}</td>
       <td><b>${Number(r.percentage || 0).toFixed(1)}%</b></td><td>${esc(r.grade)}</td><td>${esc(r.position)}</td>
       <td>${r.published ? "✅" : "🚫"}</td><td class="small">${esc(r.qr_verify_code)}</td>
       <td>${isOfficerPlus() ? `<button class="btn small" onclick="pubResult('${r.id}', ${!r.published})">${r.published ? "Unpublish" : "Publish"}</button>` : ""}</td></tr>`)}`;
  window.pubResult = async (id, pub) => { await rpc("staff_result_publish", { p_result: id, p_published: pub }); openModule("results"); };
  $("rCsv").onclick = () => download("klc-results.csv", toCsv(["Date","Admission","Student","Subject","Class","Term","Session","Score","Percentage","Grade","Position","VerifyCode"],
    rows.map(r => [dt(r.created_at), r.admission_no, r.student, r.subject_code, r.class_level, r.term, r.session, r.score, r.percentage, r.grade, r.position, r.qr_verify_code])));
}

async function loadBroadsheet() {
  $("modBody").innerHTML = `
    <form id="bsForm" class="row">
      ${sel("bsClass", "Class", CLASSES)}
      <label>Session <input id="bsSession" value="2024/2025"/></label>
      ${sel("bsTerm", "Term", TERMS)}
      <button class="btn gold" type="submit">LOAD</button>
      <button class="btn" type="button" id="csvBroadsheet">⬇ CSV</button>
    </form><div id="bsOut"></div>`;
  let data = [];
  $("bsForm").onsubmit = async (e) => {
    e.preventDefault();
    data = await rpc("staff_broadsheet", { p_class: val("bsClass"), p_session: val("bsSession"), p_term: val("bsTerm") });
    $("bsOut").innerHTML = table(["Admission","Student","Subject","CA","Exam","Total"], data, b =>
      `<tr><td>${esc(b.admission_no)}</td><td>${esc(b.student)}</td><td>${esc(b.subject_code)}</td>
       <td>${esc(b.ca_total ?? 0)}</td><td>${esc(b.exam_score ?? "-")}</td><td><b>${esc(b.grand_total ?? "-")}</b></td></tr>`);
  };
  $("csvBroadsheet").onclick = () => download("klc-broadsheet.csv",
    toCsv(["Admission","Student","Subject","CA","Exam","Total"],
      data.map(b => [b.admission_no, b.student, b.subject_code, b.ca_total, b.exam_score, b.grand_total])));
}

// ----------------------------------------------------------------- analytics
async function loadAnalytics() {
  const a = await rpc("staff_analytics");
  const sect = (title, cols, rows, fn) => `<h3>${title}</h3>` + table(cols, rows, fn);
  $("modBody").innerHTML = `
    <div class="actions"><button class="btn" id="aJson">⬇ Download Analytics JSON</button></div>
    ${sect("Average by Subject", ["Subject","Class","Results","Avg %"], a.by_subject, r =>
      `<tr><td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td><td>${esc(r.n)}</td><td>${esc(r.avg_pct)}%</td></tr>`)}
    ${sect("Average by Class", ["Class","Results","Avg %"], a.by_class, r =>
      `<tr><td>${esc(r.class_level)}</td><td>${esc(r.n)}</td><td>${esc(r.avg_pct)}%</td></tr>`)}
    ${sect("Hardest Questions (item analysis)", ["Question","Attempts","% Correct"], a.hardest_questions, r =>
      `<tr><td>${esc(r.question)}</td><td>${esc(r.attempts)}</td><td>${esc(r.pct_correct)}%</td></tr>`)}
    ${sect("Teacher Workload", ["Teacher","Questions","Exams"], a.teacher_workload, r =>
      `<tr><td>${esc(r.name)}</td><td>${esc(r.questions)}</td><td>${esc(r.exams)}</td></tr>`)}
    <h3>Malpractice Summary</h3>
    <p>Submitted sittings: <b>${esc(a.malpractice.submitted)}</b> · Malpractice auto-submits: <b>${esc(a.malpractice.malpractice)}</b> · Total strikes: <b>${esc(a.malpractice.strikes)}</b></p>`;
  $("aJson").onclick = () => download("klc-analytics.json", JSON.stringify(a, null, 2), "application/json");
}

// ------------------------------------------------------------------- appeals
async function loadAppeals() {
  const rows = await rpc("staff_appeals", { p_status: null });
  $("modBody").innerHTML = table(["Date","Student","Admission","Subject","Reason","Status",""], rows, r =>
    `<tr><td>${dt(r.created_at)}</td><td>${esc(r.student)}</td><td>${esc(r.admission_no)}</td>
     <td>${esc(r.subject_code)}</td><td>${esc(r.reason)}</td><td>${esc(r.status)}</td>
     <td>${["OPEN","IN_REVIEW","RESOLVED","REJECTED"].map(s => `<button class="btn small" onclick="resolveAppeal('${r.id}','${s}')">${s}</button>`).join(" ")}</td></tr>`);
  window.resolveAppeal = async (id, s) => { await rpc("staff_appeal_resolve", { p_id: id, p_status: s }); openModule("appeals"); };
}

// ------------------------------------------------------------- announcements
async function loadAnnouncements() {
  const rows = await rpc("staff_announcements");
  $("modBody").innerHTML = (isOfficerPlus() ? `
    ${formGrid([{ id: "n_title", label: "Title" },
      { html: sel("n_role", "Target", ["ALL", "STUDENT", "TEACHER", "PARENT"]) },
      { id: "n_exp", label: "Expires (optional)", ph: "2025-12-31 23:59" }])}
    <label>Body <textarea id="n_body" rows="3"></textarea></label>
    <div class="actions"><button class="btn gold" id="nSave">Publish Announcement</button></div>` : "") +
    table(["Date","Title","Body","Target","Expires",""], rows, r =>
      `<tr><td>${dt(r.created_at)}</td><td>${esc(r.title)}</td><td>${esc(String(r.body).slice(0, 120))}</td>
       <td>${esc(r.target_role)}</td><td>${dt(r.expires_at)}</td>
       <td>${isOfficerPlus() ? `<button class="btn small danger" onclick="delAnn('${r.id}')">Delete</button>` : ""}</td></tr>`);
  if ($("nSave")) $("nSave").onclick = async () => {
    await rpc("staff_announcement_save", { p: { title: val("n_title"), body: $("n_body").value,
      target_role: val("n_role"), expires_at: val("n_exp") } });
    msg("Published."); openModule("announce");
  };
  window.delAnn = async (id) => { if (confirm("Delete this announcement?")) { await rpc("staff_announcement_save", { p: { id, delete: true } }); openModule("announce"); } };
}

// ----------------------------------------------------------------- materials
async function loadMaterials() {
  const rows = await rpc("staff_materials");
  $("modBody").innerHTML = `
    ${formGrid([{ id: "m_id", label: "Material ID (blank = new)" }, { id: "m_subj", label: "Subject code" },
      { html: sel("m_class", "Class", ["", ...CLASSES]) }, { id: "m_title", label: "Title" },
      { id: "m_url", label: "File URL (Supabase Storage / Drive link)" }])}
    <label>Description <textarea id="m_desc" rows="2"></textarea></label>
    <div class="actions"><button class="btn gold" id="mSave">Save Material</button></div>
    ${table(["Date","Subject","Class","Title","File",""], rows, r =>
      `<tr><td>${dt(r.created_at)}</td><td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td>
       <td>${esc(r.title)}</td><td>${r.file_url ? `<a href="${esc(r.file_url)}" target="_blank" rel="noopener">Open</a>` : "-"}</td>
       <td><button class="btn small danger" onclick="delMat('${r.id}')">Delete</button></td></tr>`)}`;
  $("mSave").onclick = async () => {
    await rpc("staff_material_save", { p: { id: val("m_id"), subject_code: val("m_subj"), class_level: val("m_class"),
      title: val("m_title"), description: $("m_desc").value, file_url: val("m_url") } });
    msg("Saved."); openModule("materials");
  };
  window.delMat = async (id) => { if (confirm("Delete this material?")) { await rpc("staff_material_save", { p: { id, delete: true } }); openModule("materials"); } };
}

// ----------------------------------------------------------------- live room
async function loadLiveRoom() {
  const render = async () => {
    const rows = await rpc("staff_live_room", { p_minutes: 240 });
    $("modBody").innerHTML = `<p class="muted">Official exams in progress (practice/mock hidden). Auto-refresh 15s · Students writing now: <b>${rows.length}</b></p>` +
      table(["Student","Admission","Exam","Class","Arm","Started","Elapsed (min)","Strikes","Status"], rows, r =>
        `<tr><td>${esc(r.student)}</td><td>${esc(r.admission_no)}</td><td>${esc(r.exam_title)}</td>
         <td>${esc(r.class_level)}</td><td>${esc(r.arm)}</td><td>${dt(r.started_at)}</td>
         <td>${esc(r.elapsed_min)}</td><td>${esc(r.strike_count)}</td><td>${esc(r.status)}</td></tr>`);
  };
  await render(); liveTimer = setInterval(() => render().catch(() => {}), 15000);
}

async function loadNotifications() {
  const rows = await rpc("staff_notifications", { p_limit: 200 });
  $("modBody").innerHTML = table(["Date","Recipient","Subject","Channel","Status"], rows, r =>
    `<tr><td>${dt(r.created_at)}</td><td>${esc(r.recipient_email)}</td><td>${esc(r.subject)}</td><td>${esc(r.channel)}</td><td>${esc(r.status)}</td></tr>`);
}

async function loadAudit() {
  const rows = await rpc("staff_audit", { p_limit: 300 });
  $("modBody").innerHTML = `
    <div class="actions"><button class="btn" id="auCsv">⬇ CSV</button></div>
    <p class="muted small">Append-only (WORM) on the cloud database.</p>
    ${table(["Date","Actor","Action","Entity","Details"], rows, r =>
      `<tr><td>${dt(r.created_at)}</td><td>${esc(r.actor)}</td><td>${esc(r.action)}</td><td>${esc(r.entity_type)}</td><td>${esc(r.details)}</td></tr>`)}`;
  $("auCsv").onclick = () => download("klc-audit.csv", toCsv(["Date","Actor","Action","Entity","Details"],
    rows.map(r => [dt(r.created_at), r.actor, r.action, r.entity_type, r.details])));
}

// ------------------------------------------------------------------ settings
async function loadSettings() {
  const s = await rpc("staff_school");
  $("modBody").innerHTML = `
    ${formGrid([
      { id: "sc_name", label: "School name", value: s?.school_name || "" },
      { id: "sc_motto", label: "Motto", value: s?.motto || "" },
      { id: "sc_principal", label: "Principal", value: s?.principal_name || "" },
      { id: "sc_session", label: "Current session", value: s?.session_current || "" },
      { html: sel("sc_term", "Current term", TERMS, s?.term_current || "1st") },
    ])}
    <label>Grading scale (JSON: [{"grade":"A1","min":75,"max":100,"remark":"Excellent"}, …], CA weights via "weights":[20,20,60])
      <textarea id="sc_grading" rows="6">${esc(s?.grading_scale ? JSON.stringify(s.grading_scale, null, 1) : "")}</textarea></label>
    <div class="actions"><button class="btn gold" id="scSave">Save School Settings</button></div>
    <p class="muted small">These settings drive new registrations, broadsheets and report cards on every client (desktop + web).</p>`;
  $("scSave").onclick = async () => {
    let grading = null;
    const raw = $("sc_grading").value.trim();
    if (raw) { try { grading = JSON.parse(raw); } catch { msg("Grading scale JSON is invalid.", true); return; } }
    await rpc("staff_school_save", { p: { school_name: val("sc_name"), motto: val("sc_motto"),
      principal_name: val("sc_principal"), session_current: val("sc_session"), term_current: val("sc_term"),
      grading_scale: grading } });
    msg("Saved.");
  };
}

// --------------------------------------------------------------------- boot
window.addEventListener("DOMContentLoaded", () => {
  $("loginForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const err = $("loginError"); err.hidden = true;
    const email = val("email"), password = $("password").value;
    try {
      const role = await klcRpc("staff_check", { p_email: email, p_password: password });
      S.email = email; S.password = password; S.role = role;
      Object.assign(creds, { p_email: email, p_password: password });
      sessionStorage.setItem("klc_email", email); sessionStorage.setItem("klc_pass", password);
      sessionStorage.setItem("klc_role", role);
      enter();
    } catch (ex) { err.textContent = ex.message || "Login failed."; err.hidden = false; }
  });
  $("logout").onclick = () => { sessionStorage.clear(); location.reload(); };
  if (S.email && S.password && S.role) enter();
});
function enter() {
  $("loginCard").hidden = true; $("console").hidden = false;
  $("who").textContent = `${S.email.split("@")[0]} · ${S.role.replaceAll("_", " ")}`;
  buildNav(); openModule("dashboard");
}
