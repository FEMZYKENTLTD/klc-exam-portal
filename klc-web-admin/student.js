// KLC Student Portal — own data only, enforced server-side.
// Exams are listed here but can only be SAT in the desktop app: every exam
// button opens the exam-gate dialog (see exam-gate.js). No web API can start
// an exam or submit answers.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "-").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const dt = (v) => v ? String(v).slice(0, 10) : "-";
const dtm = (v) => v ? String(v).slice(0, 16).replace("T", " ") : "—";
let CRED = { email: "", password: "" };
let DATA = null, PEER = null;

function table(cols, rows, fn) {
  if (!rows?.length) return `<p class="muted">Nothing here yet.</p>`;
  return `<div class="scrollx"><table><thead><tr>${cols.map(c => `<th>${c}</th>`).join("")}</tr></thead><tbody>${rows.map(fn).join("")}</tbody></table></div>`;
}
const rpc = (fn, args = {}) => klcRpc(fn, { p_email: CRED.email, p_password: CRED.password, ...args });
const gradeRemark = (g) => ({ A1: "Excellent", B2: "Very Good", B3: "Good", C4: "Credit", C5: "Credit", C6: "Credit", D7: "Pass", E8: "Pass", F9: "Fail" }[g] || "");

window.addEventListener("DOMContentLoaded", () => {
  $("gateTeaser").onclick = (e) => { e.preventDefault(); showExamGate(null); };
  const em = sessionStorage.getItem("klc_student_email"), pw = sessionStorage.getItem("klc_student_pass");
  if (em && pw) { CRED = { email: em, password: pw }; load().catch(() => sessionStorage.clear()); }
  $("loginForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const err = $("loginError"); err.hidden = true;
    CRED = { email: $("email").value.trim(), password: $("password").value };
    try {
      await rpc("student_portal");
      sessionStorage.setItem("klc_student_email", CRED.email);
      sessionStorage.setItem("klc_student_pass", CRED.password);
      load();
    } catch (ex) { err.textContent = ex.message || "Login failed — student accounts only."; err.hidden = false; }
  });
  $("logout").onclick = () => { sessionStorage.removeItem("klc_student_email"); sessionStorage.removeItem("klc_student_pass"); location.reload(); };
});

async function load() {
  const d = await rpc("student_portal");
  DATA = d;
  $("loginCard").hidden = true; $("portal").hidden = false;
  const p = d.profile || {};
  $("who").textContent = p.full_name || CRED.email;
  $("meta").textContent = `Admission: ${p.admission_no || "-"} · Class: ${p.class_level || "-"} ${p.arm || ""} · Result PIN: ${p.result_pin || "-"} · Fees: ${p.fee_status || "-"}`;
  if (d.school) { $("rcSession").value = d.school.session_current || ""; $("rcTerm").value = d.school.term_current || "1st"; }
  renderExams(); renderResults(d); renderCa(d); renderMats(d); renderAnn(d); renderAppeals(d);
  renderIdCard(d); buildReportCard();
  loadSocial(); loadProfile();
}

// ------------------------------------------------------------------- exams
async function renderExams() {
  const exams = await rpc("student_exams");
  window._exams = exams;
  $("exams").innerHTML = exams.length ? exams.map((x, i) => {
    const mode = x.is_practice ? "Practice" : x.is_mock ? "Mock" : "Official";
    const left = Math.max(0, (x.attempt_limit ?? 1) - (x.attempts_used ?? 0));
    const avail = x.availability === "OPEN" ? ["open", "● Open now"] : x.availability === "SCHEDULED" ? ["scheduled", "● Opens " + dtm(x.start_at)] : ["closed", "● Closed " + dtm(x.end_at)];
    return `<div class="examcard">
      <h3>${esc(x.title)}</h3>
      <p><span class="badge mode">${mode}</span> <span class="badge ${avail[0]}">${avail[1]}</span></p>
      <p class="small muted">${esc(x.subject_code)} · ${esc(x.class_level)}${x.arm ? " " + esc(x.arm) : ""} · ${esc(x.duration_minutes)} mins · ${esc(x.question_count)} questions<br/>
      ${esc(x.term || "")} Term · ${esc(x.session || "")} · Attempts left: <b>${left}/${esc(x.attempt_limit ?? 1)}</b></p>
      <button class="btn gold" onclick="showExamGate(window._exams[${i}])">📝 Take this exam</button>
    </div>`;
  }).join("") : `<p class="muted">No exams are scheduled for your class right now. When your teacher publishes one, it will appear here.</p>`;
}

// ----------------------------------------------------------------- results
function renderResults(d) {
  $("results").innerHTML = table(["Date","Subject","Term","Session","%","Grade","Position","Verify Code",""], d.results, r =>
    `<tr><td>${dt(r.created_at)}</td><td>${esc(r.subject_code)}</td><td>${esc(r.term)}</td><td>${esc(r.session)}</td>
     <td><b>${Number(r.percentage || 0).toFixed(1)}%</b></td><td>${esc(r.grade)}</td><td>${esc(r.position)}</td><td class="small">${esc(r.qr_verify_code)}</td>
     <td><button class="btn small" onclick="appeal('${r.id}','${esc(r.subject_code)}')">Appeal</button></td></tr>`);
  window.appeal = async (resultId, subject) => {
    const reason = prompt(`Reason for appealing your ${subject} result:`);
    if (!reason) return;
    await rpc("student_appeal_submit", { p_result: resultId, p_subject: subject, p_reason: reason });
    alert("Appeal submitted — the school will review it and the status will appear under My Appeals.");
    load();
  };
}

function renderCa(d) {
  $("ca").innerHTML = table(["Subject","Term","Session","CA1","CA2","Exam","Total","Grade","Pos"], d.ca, r =>
    `<tr><td>${esc(r.subject_code)}</td><td>${esc(r.term)}</td><td>${esc(r.session)}</td><td>${esc(r.ca1)}</td><td>${esc(r.ca2)}</td>
     <td>${esc(r.exam_score)}</td><td><b>${esc(r.total)}</b></td><td>${esc(r.grade)}</td><td>${esc(r.position)}</td></tr>`);
}
function renderMats(d) {
  $("mats").innerHTML = table(["Subject","Class","Title","Description","File"], d.materials, r =>
    `<tr><td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td><td>${esc(r.title)}</td><td>${esc(String(r.description || "").slice(0, 80))}</td>
     <td>${r.file_url ? `<a href="${esc(r.file_url)}" target="_blank" rel="noopener">Open</a>` : "-"}</td></tr>`);
}
function renderAnn(d) {
  $("ann").innerHTML = (d.announcements || []).map(a =>
    `<div class="announce"><h3>${esc(a.title)}</h3><p>${esc(a.body)}</p><p class="muted small">${dt(a.created_at)}</p></div>`).join("") || `<p class="muted">No announcements.</p>`;
}
function renderAppeals(d) {
  $("appeals").innerHTML = table(["Date","Subject","Reason","Status"], d.appeals, r =>
    `<tr><td>${dt(r.created_at)}</td><td>${esc(r.subject_code)}</td><td>${esc(r.reason)}</td><td>${esc(r.status)}</td></tr>`);
}

// -------------------------------------------------------------- report card
function buildReportCard() {
  const term = $("rcTerm").value, session = $("rcSession").value.trim();
  const rows = (DATA.ca || []).filter(r => r.term === term && (!session || r.session === session));
  const resBySubj = Object.fromEntries((DATA.results || []).filter(r => r.term === term).map(r => [r.subject_code, r]));
  const p = DATA.profile || {}, s = DATA.school || {};
  const avg = rows.length ? (rows.reduce((a, r) => a + (+r.total || 0), 0) / rows.length).toFixed(1) : "—";
  $("reportCard").innerHTML = `
    <div class="rc-head">
      <h2 style="margin:2px">${esc(s.school_name || "KNOWLEDGE LAND COLLEGE")}</h2>
      <p class="muted" style="margin:2px">${esc(s.motto || "")}</p>
      <b>TERMINAL REPORT CARD — ${esc(term)} Term, ${esc(session || s.session_current || "")} Session</b>
    </div>
    <p><b>Student:</b> ${esc(p.full_name)} &nbsp; <b>Admission No:</b> ${esc(p.admission_no)} &nbsp;
       <b>Class:</b> ${esc(p.class_level)} ${esc(p.arm || "")} &nbsp; <b>Average:</b> <b>${avg}</b></p>
    ${table(["Subject","CA1","CA2","Exam","Total","Grade","Position","Remark"], rows, r =>
      `<tr><td>${esc(r.subject_code)}</td><td>${esc(r.ca1)}</td><td>${esc(r.ca2)}</td><td>${esc(r.exam_score ?? resBySubj[r.subject_code]?.percentage ?? "-")}</td>
       <td><b>${esc(r.total)}</b></td><td>${esc(r.grade)}</td><td>${esc(r.position)}</td><td>${gradeRemark(r.grade)}</td></tr>`)}
    <p class="muted small">Verify any result with the school office using its KLC verify code (listed under My Exam Results).
    Principal: ${esc(s.principal_name || "—")}</p>`;
}
document.addEventListener("click", (e) => {
  if (e.target.id === "rcBuild") buildReportCard();
  if (e.target.id === "rcPrint" || e.target.id === "idPrint") window.print();
});

// ------------------------------------------------------------------ ID card
function renderIdCard(d) {
  const p = d.profile || {}, s = d.school || {};
  $("idCard").innerHTML = `<div class="idcard">
    <div class="idhead"><b>${esc(s.school_name || "KNOWLEDGE LAND COLLEGE")}</b><br/><span class="small">STUDENT IDENTITY CARD</span></div>
    <div class="idbody">
      <div><span>Name</span><b>${esc(p.full_name)}</b></div>
      <div><span>Admission No</span><b>${esc(p.admission_no)}</b></div>
      <div><span>Class</span><b>${esc(p.class_level)} ${esc(p.arm || "")}</b></div>
      <div><span>Result PIN</span><b>${esc(p.result_pin)}</b></div>
      <div><span>Fee Status</span><b>${esc(p.fee_status)}</b></div>
      <div><span>Session</span><b>${esc(s.session_current || "")}</b></div>
    </div></div>
    <p class="muted small">Present this card (printed or on screen) at the ICT lab. It identifies you for exam sittings in the desktop app.</p>`;
}

// ------------------------------------------------------------------- social
async function loadSocial() {
  const f = await rpc("web_friends");
  $("requests").innerHTML = (f.incoming?.length ? f.incoming.map(r =>
    `<p>👋 <b>${esc(r.full_name)}</b> (${esc(r.role)}) wants to connect
     <button class="btn small gold" onclick="respondFriend('${r.friendship_id}', true)">Accept</button>
     <button class="btn small" onclick="respondFriend('${r.friendship_id}', false)">Decline</button></p>`).join("") : `<p class="muted">No pending requests.</p>`)
    + (f.outgoing?.length ? `<p class="muted small">Sent: ${f.outgoing.map(r => esc(r.full_name)).join(", ")}</p>` : "");
  $("friends").innerHTML = f.friends?.length ? f.friends.map(r =>
    `<p>• <b>${esc(r.full_name)}</b> (${esc(r.role)}) <button class="btn small" onclick="openChat('${r.user_id}','${esc(r.full_name)}')">💬 Message</button></p>`).join("")
    : `<p class="muted">No friends yet — search the directory above.</p>`;
  const inbox = await rpc("web_inbox");
  $("inbox").innerHTML = inbox.length ? inbox.map(c =>
    `<p>💬 <b>${esc(c.full_name)}</b> ${c.unread ? `<span class="badge closed">${esc(c.unread)} new</span>` : ""}
     <span class="muted small">${esc(c.last_message)} · ${dtm(c.last_at)}</span>
     <button class="btn small" onclick="openChat('${c.peer_id}','${esc(c.full_name)}')">Open</button></p>`).join("") : "";
  window.respondFriend = async (id, ok) => { await rpc("web_friend_respond", { p_id: id, p_accept: ok }); loadSocial(); };
  window.openChat = async (peerId, name) => {
    PEER = peerId; $("chatWith").textContent = `Chatting with ${name}`;
    const msgs = await rpc("web_messages", { p_peer: peerId });
    $("chatBox").innerHTML = msgs.length ? msgs.map(m =>
      `<div class="bubble ${m.mine ? "mine" : "theirs"}">${esc(m.content)}<div class="small">${dtm(m.created_at)}</div></div>`).join("")
      : `<p class="muted">No messages yet — say hello!</p>`;
    $("chatBox").scrollTop = $("chatBox").scrollHeight;
  };
  $("dirGo").onclick = async () => {
    const rows = await rpc("web_directory", { p_search: $("dirSearch").value.trim() || null });
    $("directory").innerHTML = rows.length ? rows.map(r =>
      `<p>• <b>${esc(r.full_name)}</b> <span class="muted small">${esc(r.role)}${r.class_level ? " · " + esc(r.class_level) + " " + esc(r.arm || "") : ""}</span>
       ${r.friend_status === "ACCEPTED" ? `<button class="btn small" onclick="openChat('${r.user_id}','${esc(r.full_name)}')">💬 Message</button>`
         : r.friend_status === "PENDING" ? `<span class="muted small">request pending</span>`
         : `<button class="btn small" onclick="addFriend('${r.user_id}')">➕ Add friend</button>`}</p>`).join("")
      : `<p class="muted">Nobody matches that search.</p>`;
  };
  window.addFriend = async (uid) => { try { await rpc("web_friend_request", { p_user: uid }); alert("Friend request sent."); $("dirGo").click(); } catch (e) { alert(e.message); } };
  $("chatSend").onclick = async () => {
    if (!PEER) { alert("Open a conversation first."); return; }
    const text = $("chatInput").value.trim(); if (!text) return;
    try { await rpc("web_message_send", { p_peer: PEER, p_content: text }); $("chatInput").value = ""; window.openChat(PEER, $("chatWith").textContent.replace("Chatting with ", "")); loadSocial(); }
    catch (e) { alert(e.message); }
  };
}

// ------------------------------------------------------------------ profile
async function loadProfile() {
  const p = await rpc("web_profile");
  $("pf_name").value = p.full_name || ""; $("pf_phone").value = p.phone || "";
  $("pf_dob").value = p.date_of_birth || ""; $("pf_photo").value = p.photo_url || "";
  $("pf_address").value = p.address || ""; $("pf_bio").value = p.bio || "";
  $("totpState").textContent = p.totp_enabled
    ? "2FA is ON for your account. The desktop app will ask for a code at login."
    : "2FA is OFF. Set it up with an authenticator app (Google Authenticator, Microsoft Authenticator, Authy…) for extra protection — the desktop app enforces it at login.";
  $("pfSave").onclick = async () => {
    await rpc("web_profile_save", { p: { full_name: $("pf_name").value, phone: $("pf_phone").value,
      date_of_birth: $("pf_dob").value, photo_url: $("pf_photo").value,
      address: $("pf_address").value, bio: $("pf_bio").value } });
    alert("Profile saved.");
  };
  $("pwSave").onclick = async () => {
    try {
      await rpc("web_change_password", { p_new_password: $("pw_new").value });
      sessionStorage.setItem("klc_student_pass", $("pw_new").value); CRED.password = $("pw_new").value;
      $("pw_new").value = ""; alert("Password changed. Use it next time you log in — on the web and in the app.");
    } catch (e) { alert(e.message); }
  };
  $("totpSetup").onclick = async () => {
    const out = await rpc("web_totp_setup");
    $("totpSecret").innerHTML = `In your authenticator app, add an account manually with this secret key:<br/>
      <b style="font-size:1.05rem; letter-spacing:.08em">${esc(out.secret)}</b><br/>
      (Account: KLC CBT · Issuer: KLC CBT · Time-based). Then type the 6-digit code it shows and click <b>Enable 2FA</b>.`;
  };
  $("totpEnable").onclick = async () => { try { await rpc("web_totp_enable", { p_code: $("totpCode").value.trim() }); alert("2FA enabled."); loadProfile(); } catch (e) { alert(e.message); } };
  $("totpDisable").onclick = async () => { try { await rpc("web_totp_disable", { p_code: $("totpCode").value.trim() }); alert("2FA disabled."); loadProfile(); } catch (e) { alert(e.message); } };
}
