// Parent Result Checker logic - admission no + result PIN via secure RPC
let lastRows = [];

document.getElementById("lookupForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const adm = document.getElementById("adm").value.trim();
  const pin = document.getElementById("pin").value.trim().toUpperCase();
  const err = document.getElementById("lookupError");
  err.hidden = true;
  try {
    lastRows = await klcRpc("parent_lookup_results", {
      p_admission: adm, p_pin: pin,
    });
    if (!Array.isArray(lastRows) || lastRows.length === 0) {
      throw new Error("No published results found for this ward yet.");
    }
    render(lastRows, `Results — ${adm}`);
    document.getElementById("lookupCard").hidden = true;
    document.getElementById("resultCard").hidden = false;
  } catch (ex) {
    err.textContent = ex.message || "Lookup failed.";
    err.hidden = false;
  }
});

function render(rows, title) {
  document.getElementById("wardTitle").textContent = title;
  const tb = document.querySelector("#resultTable tbody");
  tb.innerHTML = "";
  rows.sort((a, b) => (b.result_date || "").localeCompare(a.result_date || ""));
  for (const r of rows) {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td>${(r.result_date || "").slice(0, 10)}</td>
      <td>${esc(r.subject_code)}</td><td>${esc(r.class_level)}</td>
      <td>${esc(r.term)}</td><td>${esc(r.session)}</td>
      <td>${r.score ?? "-"} / ${r.total_questions ?? "-"}</td>
      <td><b>${Number(r.percentage || 0).toFixed(1)}%</b></td>`;
    tb.appendChild(tr);
  }
}

document.getElementById("exportCsv").addEventListener("click", () => {
  const head = "Date,Subject,Class,Term,Session,Score,Total,Percentage";
  const body = lastRows.map(r =>
    [(r.result_date || "").slice(0, 10), r.subject_code, r.class_level,
     r.term, r.session, r.score, r.total_questions,
     Number(r.percentage || 0).toFixed(1)].join(",")).join("\n");
  const blob = new Blob([head + "\n" + body], { type: "text/csv" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "klc-ward-results.csv";
  a.click();
});

document.getElementById("logout").addEventListener("click", () => {
  document.getElementById("resultCard").hidden = true;
  document.getElementById("lookupCard").hidden = false;
  document.getElementById("pin").value = "";
});

function esc(s) {
  return String(s ?? "-").replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
