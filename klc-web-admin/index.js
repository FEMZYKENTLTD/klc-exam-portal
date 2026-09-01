// Staff portal logic - every RPC re-verifies credentials server-side
// (bcrypt in SECURITY DEFINER functions - no tokens in this v1).
const session = {
  email: sessionStorage.getItem("klc_email") || "",
  password: sessionStorage.getItem("klc_pass") || "",
  name: sessionStorage.getItem("klc_name") || "",
  role: sessionStorage.getItem("klc_role") || "",
};
let results = [], broadsheet = [];

window.addEventListener("DOMContentLoaded", () => {
  if (session.email && session.password) enterDash();
});

document.getElementById("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const email = document.getElementById("email").value.trim();
  const password = document.getElementById("password").value;
  const err = document.getElementById("loginError");
  err.hidden = true;
  try {
    const role = await klcRpc("staff_check",
      { p_email: email, p_password: password });
    session.email = email; session.password = password; session.role = role;
    try {
      const me = await klcRpc("staff_subjects",
        { p_email: email, p_password: password });
      void me; // (subjects also prove the session works)
    } catch (_) {}
    session.name = email.split("@")[0];
    Object.entries({ klc_email: email, klc_pass: password,
                     klc_role: role, klc_name: session.name })
      .forEach(([k, v]) => sessionStorage.setItem(k, v));
    enterDash();
  } catch (ex) {
    err.textContent = ex.message || "Login failed.";
    err.hidden = false;
  }
});

function enterDash() {
  document.getElementById("loginCard").hidden = true;
  document.getElementById("dash").hidden = false;
  document.getElementById("who").textContent =
    `${session.name}  ·  ${session.role.replace("_", " ")}`;
  showPane("paneResults");
  loadResults();
}

function showPane(id) {
  ["paneResults", "paneBroadsheet", "paneSubjects"].forEach(p =>
    document.getElementById(p).hidden = p !== id);
}

document.getElementById("tabResults").onclick = () => { showPane("paneResults"); loadResults(); };
document.getElementById("tabBroadsheet").onclick = () => showPane("paneBroadsheet");
document.getElementById("tabSubjects").onclick = async () => {
  showPane("paneSubjects");
  try {
    const rows = await klcRpc("staff_subjects",
      { p_email: session.email, p_password: session.password });
    document.querySelector("#subjTable tbody").innerHTML = rows.map(s =>
      `<tr><td>${esc(s.subject_code)}</td><td>${esc(s.subject_name)}</td>
       <td>${esc(s.class_level)}</td><td>${s.is_active ? "✅" : "⛔"}</td></tr>`).join("");
  } catch (ex) { alert(ex.message); }
};

document.getElementById("logout").onclick = () => {
  sessionStorage.clear();
  location.reload();
};

async function loadResults() {
  try {
    results = await klcRpc("staff_recent_results",
      { p_email: session.email, p_password: session.password, p_limit: 200 });
    document.querySelector("#resultsTable tbody").innerHTML = results.map(r =>
      `<tr><td>${(r.result_date || "").slice(0, 10)}</td>
       <td>${esc(r.admission_no)}</td><td>${esc(r.student)}</td>
       <td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td>
       <td>${esc(r.term)}</td><td>${esc(r.session)}</td>
       <td>${r.score ?? "-"}</td>
       <td><b>${Number(r.percentage || 0).toFixed(1)}%</b></td></tr>`).join("");
  } catch (ex) {
    if (String(ex.message).includes("Invalid credentials")) { sessionStorage.clear(); location.reload(); return; }
    alert(ex.message);
  }
}

document.getElementById("bsForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    broadsheet = await klcRpc("staff_broadsheet", {
      p_email: session.email, p_password: session.password,
      p_class: document.getElementById("bsClass").value,
      p_session: document.getElementById("bsSession").value.trim(),
      p_term: document.getElementById("bsTerm").value,
    });
    document.querySelector("#bsTable tbody").innerHTML = broadsheet.map(b =>
      `<tr><td>${esc(b.admission_no)}</td><td>${esc(b.student)}</td>
       <td>${esc(b.subject_code)}</td><td>${b.ca_total ?? 0}</td>
       <td>${b.exam_score ?? "-"}</td><td><b>${b.grand_total ?? "-"}</b></td></tr>`).join("");
  } catch (ex) { alert(ex.message); }
});

document.getElementById("csvResults").onclick = () =>
  downloadCsv("klc-recent-results.csv",
    ["Date","Admission","Student","Subject","Class","Term","Session","Score","Percentage"],
    results.map(r => [(r.result_date || "").slice(0, 10), r.admission_no, r.student,
      r.subject_code, r.class_level, r.term, r.session, r.score,
      Number(r.percentage || 0).toFixed(1)]));

document.getElementById("csvBroadsheet").onclick = () =>
  downloadCsv("klc-broadsheet.csv",
    ["Admission","Student","Subject","CA","Exam","Total"],
    broadsheet.map(b => [b.admission_no, b.student, b.subject_code,
      b.ca_total, b.exam_score, b.grand_total]));

function downloadCsv(name, head, rows) {
  const csv = [head.join(","), ...rows.map(r => r.map(csvCell).join(","))].join("\n");
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
  a.download = name; a.click();
}
function csvCell(v) {
  const s = String(v ?? "");
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}
function esc(s) {
  return String(s ?? "-").replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
