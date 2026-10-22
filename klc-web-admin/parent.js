// KLC Parent Checker — full ward report via parent_report (PIN-gated server-side).
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "-").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const dt = (v) => v ? String(v).slice(0, 10) : "-";

window.addEventListener("DOMContentLoaded", () => {
  $("form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const err = $("err"), out = $("out");
    err.hidden = true; out.innerHTML = "";
    try {
      const d = await klcRpc("parent_report", {
        p_admission: $("admission").value.trim(),
        p_pin: $("pin").value.trim(),
      });
      const w = d.ward || {};
      const resRows = (d.results || []).map(r =>
        `<tr><td>${dt(r.created_at)}</td><td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td><td>${esc(r.term)}</td><td>${esc(r.session)}</td>
         <td>${esc(r.score)}/${esc(r.total_questions)}</td><td><b>${Number(r.percentage || 0).toFixed(1)}%</b></td><td>${esc(r.grade)}</td><td>${esc(r.position)}</td><td class="small">${esc(r.qr_verify_code)}</td></tr>`).join("");
      const caRows = (d.ca || []).map(r =>
        `<tr><td>${esc(r.subject_code)}</td><td>${esc(r.term)}</td><td>${esc(r.session)}</td><td>${esc(r.ca1)}</td><td>${esc(r.ca2)}</td><td>${esc(r.exam_score)}</td>
         <td><b>${esc(r.total)}</b></td><td>${esc(r.grade)}</td><td>${esc(r.position)}</td></tr>`).join("");
      out.innerHTML = `
        <div class="card">
          <h2>${esc(w.name)} — ${esc(w.admission_no)}</h2>
          <p class="muted">Class: ${esc(w.class_level)} ${esc(w.arm || "")} · Official results only (unpublished results are hidden)</p>
          <h3>🏆 Exam Results</h3>
          <div class="scrollx"><table><thead><tr><th>Date</th><th>Subject</th><th>Class</th><th>Term</th><th>Session</th><th>Score</th><th>%</th><th>Grade</th><th>Pos</th><th>Verify Code</th></tr></thead>
          <tbody>${resRows || '<tr><td colspan="10">No published results yet.</td></tr>'}</tbody></table></div>
          <h3>🧮 Continuous Assessment</h3>
          <div class="scrollx"><table><thead><tr><th>Subject</th><th>Term</th><th>Session</th><th>CA1</th><th>CA2</th><th>Exam</th><th>Total</th><th>Grade</th><th>Pos</th></tr></thead>
          <tbody>${caRows || '<tr><td colspan="9">No CA scores yet.</td></tr>'}</tbody></table></div>
          <div class="actions"><button class="btn gold" onclick="window.print()">🖨 Print / Save PDF</button></div>
          <p class="muted small">Verify any result with the school office using its KLC verify code.</p>
        </div>`;
    } catch (ex) {
      err.textContent = ex.message || "Could not fetch results.";
      err.hidden = false;
    }
  });
});
