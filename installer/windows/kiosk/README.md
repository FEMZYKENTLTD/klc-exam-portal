# KLC CBT v1.0 — Windows Exam Kiosk Pack

True OS lockdown cannot be done from inside any Java app (Windows reserves
Alt+Tab / Win-key / Task Manager handling). KLC therefore ships lockdown in
two layers that work together:

| Layer | What it does | Where |
|---|---|---|
| **In-app** (built in, automatic) | Fullscreen kiosk stage with exit key/hint disabled, always-on-top, instant re-fullscreen on iconify, clipboard/shortcut blocking, 3-strike focus-loss proctoring → auto-submit + 15-min account lockout + audit with PC/IP | `util.KioskMode`, `proctoring.FocusLossDetector`, `student.ExamController` |
| **OS policy** (this pack, per exam PC) | Dedicated `KLCExam` account: Task Manager / lock / password-change / fast-switching / Run / Control Panel disabled, CBT auto-launches at logon; optional **Assigned Access** single-app kiosk (boots straight into the exam app, no desktop) | `Enable-KlcKiosk.ps1` / `Disable-KlcKiosk.ps1` |

## Use
```powershell
# once per exam PC, as Administrator
powershell -ExecutionPolicy Bypass -File Enable-KlcKiosk.ps1 -AppPath "C:\Program Files\KLC-CBT-Suite\KLC-CBT-Suite.exe" -AssignedAccess
# undo
powershell -ExecutionPolicy Bypass -File Disable-KlcKiosk.ps1
```
Exam-day flow: reboot → sign in as `KLCExam` → app is already running →
student logs in with their normal KLC account → official exams open in
kiosk mode automatically.
