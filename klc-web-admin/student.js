// KLC Student Portal — own data only, enforced server-side by student_portal.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "-").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const dt = (v) => v ? String(v).slice(0, 10) : "-";

function table(cols, rows, fn) {
  if (!rows?.length) return `<p class="muted">Nothing here yet.</p>`;
  return `<div class="scrollx"><table><thead><tr>${cols.map(c => `<th>${c}</th>`).join("")}</tr></thead><tbody>${rows.map(fn).join("")}</tbody></table></div>`;
}

window.addEventListener("DOMContentLoaded", () => {
  const em = sessionStorage.getItem("klc_student_email"), pw = sessionStorage.getItem("klc_student_pass");
  if (em && pw) load(em, pw);
  $("loginForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const err = $("loginError"); err.hidden = true;
    const email = $("email").value.trim(), password = $("password").value;
    try {
      await klcRpc("student_portal", { p_email: email, p_password: password });
      sessionStorage.setItem("klc_student_email", email);
      sessionStorage.setItem("klc_student_pass", password);
      load(email, password);
    } catch (ex) { err.textContent = ex.message || "Login failed — student accounts only."; err.hidden = false; }
  });
  $("logout").onclick = () => { sessionStorage.removeItem("klc_student_email"); sessionStorage.removeItem("klc_student_pass"); location.reload(); };
});

async function load(email, password) {
  const d = await klcRpc("student_portal", { p_email: email, p_password: password });
  $("loginCard").hidden = true; $("portal").hidden = false;
  const p = d.profile || {};
  $("who").textContent = p.full_name || email;
  $("meta").textContent = `Admission: ${p.admission_no || "-"} · Class: ${p.class_level || "-"} ${p.arm || ""} · Result PIN: ${p.result_pin || "-"} · Fees: ${p.fee_status || "-"}`;
  $("ann").innerHTML = (d.announcements || []).map(a =>
    `<div class="announce"><h3>${esc(a.title)}</h3><p>${esc(a.body)}</p><p class="muted small">${dt(a.created_at)}</p></div>`).join("") || `<p class="muted">No announcements.</p>`;
  $("results").innerHTML = table(["Date","Subject","Term","Session","%","Grade","Position","Verify Code",""], d.results, r =>
    `<tr><td>${dt(r.created_at)}</td><td>${esc(r.subject_code)}</td><td>${esc(r.term)}</td><td>${esc(r.session)}</td>
     <td><b>${Number(r.percentage || 0).toFixed(1)}%</b></td><td>${esc(r.grade)}</td><td>${esc(r.position)}</td><td class="small">${esc(r.qr_verify_code)}</td>
     <td><button class="btn small" onclick="appeal('${r.id}','${esc(r.subject_code)}')">Appeal</button></td></tr>`);
  window.appeal = async (resultId, subject) => {
    const reason = prompt(`Reason for appealing ${subject} result:`);
    if (!reason) return;
    await klcRpc("student_appeal_submit", { p_email: email, p_password: password, p_result: resultId, p_subject: subject, p_reason: reason });
    alert("Appeal submitted — the school will review it.");
    load(email, password);
  };
  $("ca").innerHTML = table(["Subject","Term","Session","CA1","CA2","Exam","Total","Grade","Pos"], d.ca, r =>
    `<tr><td>${esc(r.subject_code)}</td><td>${esc(r.term)}</td><td>${esc(r.session)}</td><td>${esc(r.ca1)}</td><td>${esc(r.ca2)}</td>
     <td>${esc(r.exam_score)}</td><td><b>${esc(r.total)}</b></td><td>${esc(r.grade)}</td><td>${esc(r.position)}</td></tr>`);
  $("mats").innerHTML = table(["Subject","Class","Title","Description","File"], d.materials, r =>
    `<tr><td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td><td>${esc(r.title)}</td><td>${esc(String(r.description || "").slice(0, 80))}</td>
     <td>${r.file_url ? `<a href="${esc(r.file_url)}" target="_blank" rel="noopener">Open</a>` : "-"}</td></tr>`);
  $("appeals").innerHTML = table(["Date","Subject","Reason","Status"], d.appeals, r =>
    `<tr><td>${dt(r.created_at)}</td><td>${esc(r.subject_code)}</td><td>${esc(r.reason)}</td><td>${esc(r.status)}</td></tr>`);
}
