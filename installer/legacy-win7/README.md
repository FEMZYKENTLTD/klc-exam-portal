# KLC CBT v1.0 — Legacy Windows 7/8 & 32-bit (x86) Track

v1.0's desktop suite targets Windows 10/11 x64 (Java 17 + JavaFX 21 have no
Windows 7 or 32-bit runtime — that platform support ended upstream, so no
build of this codebase can run there). Legacy machines are **not** locked
out of KLC v1.0; they are served by two supported tracks:

## Track A — Legacy machines as WEB clients (any Win7/8 PC with a browser)
The shipped web portal (`klc-web-admin/`) runs in the last Win7 browsers
(Firefox 115 ESR / Chrome 109) and covers the roles a legacy PC is actually
used for in an exam centre:

| Page | Use on a legacy PC |
|---|---|
| `parent.html` | Parent result checker (admission no + PIN) |
| `invigilator.html` | Invigilator live exam room (15 s refresh) |
| `question_upload.html` | Teacher question upload (approval queue) |
| `index.html` | Staff results / broadsheet / subjects + CSV export |

Deploy: copy `klc-web-admin/` to any static host (or the school's LAN
server), keep `config.js` pointed at the school's Supabase project.
`Make-LegacyShortcuts.ps1` drops locked-down desktop shortcuts (browser
kiosk mode) for these pages on a Win7 PC.

## Track B — Legacy machines as EXAM terminals via Windows 10 thin image
For actual exam writing, the supported route is the zero-JDK Windows
bundle (Win10+). Schools with Win7-only labs should run the exam room on
the Win10 image for exam PCs and keep Win7 machines on Track A duties
(invigilation, parent checking, uploads) — no student is blocked from
results or staff from monitoring because of an old PC.

## 32-bit (x86) Windows 10
The release workflow has a manually-triggered **Windows x86 bundle** job
(Actions → Build KLC CBT Suite → Run workflow → `windows-x86-bundle`,
or any `v*` tag): it builds the same fat JAR on a 32-bit Zulu FX JDK 17
and packages a 32-bit jpackage app-image (`KLC-CBT-Suite-win-x86.zip`).
That covers 32-bit Windows 10; 32-bit Windows 7/8 remains Track A above.
