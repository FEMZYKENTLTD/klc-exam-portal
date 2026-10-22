// ============================================================================
// KLC Exam Gate — the write-ups and dialog shown whenever anyone clicks an
// exam on the web. Exams are VISIBLE on the web (so students can see what is
// scheduled for them, when it opens, and how many attempts they have left)
// but they can only be SAT in the KLC CBT desktop app. There is deliberately
// no web API that can start an exam or submit answers.
// ============================================================================
(function () {
  const RELEASES = "https://github.com/FEMZYKENTLTD/klc-exam-portal/releases/latest";
  const esc = (s) => String(s ?? "-").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const fmt = (v) => v ? String(v).slice(0, 16).replace("T", " ") : "—";

  function examFacts(exam) {
    if (!exam) return "";
    const mode = exam.is_practice ? "Practice" : exam.is_mock ? "Mock" : "Official";
    const left = Math.max(0, (exam.attempt_limit ?? 1) - (exam.attempts_used ?? 0));
    return `<div class="gate-facts">
      <div><span>Subject</span><b>${esc(exam.subject_code)} — ${esc(exam.subject_name || "")}</b></div>
      <div><span>Exam</span><b>${esc(exam.title)}</b></div>
      <div><span>Mode</span><b>${mode}</b></div>
      <div><span>Duration</span><b>${esc(exam.duration_minutes)} minutes</b></div>
      <div><span>Questions</span><b>${esc(exam.question_count ?? "—")}</b></div>
      <div><span>Opens</span><b>${fmt(exam.start_at)}</b></div>
      <div><span>Closes</span><b>${fmt(exam.end_at)}</b></div>
      <div><span>Attempts left</span><b>${left} of ${esc(exam.attempt_limit ?? 1)}</b></div>
    </div>`;
  }

  window.showExamGate = function (exam) {
    document.querySelector(".gate-overlay")?.remove();
    const overlay = document.createElement("div");
    overlay.className = "gate-overlay";
    overlay.innerHTML = `
    <div class="gate-dialog" role="dialog" aria-modal="true" aria-labelledby="gateTitle">
      <button class="gate-close" aria-label="Close">×</button>
      <div class="gate-icon">🖥️</div>
      <h2 id="gateTitle">This exam is taken in the KLC CBT Desktop App</h2>
      <p class="gate-lead">You can see this exam here so you know exactly what is
        scheduled for you — but <b>writing it happens only in the desktop app</b>,
        on a school computer or your own Windows PC. There is no “start” button
        on the web, for any exam, for anyone.</p>
      ${examFacts(exam)}

      <h3>Why exams live in the app 📌</h3>
      <ul class="gate-list">
        <li><b>Real proctoring.</b> The app locks the screen, watches for
          switching windows (three strikes and the exam submits itself and is
          flagged), blocks copy-and-paste, and records a malpractice log.</li>
        <li><b>It works without internet.</b> Questions are cached on the
          computer and answers auto-save every 30 seconds, so a network or
          power hiccup can never cost you an exam.</li>
        <li><b>Same rules for everybody.</b> One proctored environment for
          every candidate keeps results fair — and keeps your result
          trustworthy when it carries your name.</li>
      </ul>

      <h3>How to sit this exam ✅</h3>
      <ol class="gate-list">
        <li>Download and install the <b>KLC CBT Suite desktop app</b> below
          (Windows 10/11, 64-bit or 32-bit — no Java to install, it is built in).</li>
        <li>Open the app and log in with the <b>same email and password</b>
          you use on this portal.</li>
        <li>On the student dashboard, open <b>Available Exams</b>, read the
          instructions, confirm your admission number, accept the malpractice
          declaration, and start.</li>
        <li>Submit (or let the timer submit for you). Your result, CA scores
          and position appear <b>right here in this portal</b> automatically —
          usually within moments of submitting.</li>
      </ol>

      <div class="gate-downloads">
        <a class="btn gold big" href="${RELEASES}" target="_blank" rel="noopener">⬇ Download the Desktop App (Windows)</a>
        <a class="btn big" href="${RELEASES}" target="_blank" rel="noopener">All versions &amp; release notes</a>
      </div>
      <p class="gate-note">No Windows computer? Ask the school ICT unit — the
        app is installed in the ICT lab, and your exam can be scheduled there.
        Your portal login and your app login are the same account; if you have
        forgotten your password, a teacher can reset it from the Staff Console.</p>
      <button class="btn subtle gate-ok">I understand — take me back</button>
    </div>`;
    document.body.appendChild(overlay);
    const close = () => overlay.remove();
    overlay.querySelector(".gate-close").onclick = close;
    overlay.querySelector(".gate-ok").onclick = close;
    overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
    document.addEventListener("keydown", function onKey(e) {
      if (e.key === "Escape") { close(); document.removeEventListener("keydown", onKey); }
    });
    overlay.querySelector(".gate-close").focus();
  };
})();
