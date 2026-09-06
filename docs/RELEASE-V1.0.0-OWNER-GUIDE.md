# Release v1.0.0 — Owner Guide (install on lab PCs + safe repo pull)

> Status: **LIVE**. GitHub Release `v1.0.0` carries the two artifacts below.
> Written 2026-09-06 by the CI/release session.

## 1. What is released

| Artifact | Size | Use |
|---|---|---|
| `KLC-CBT-Suite-win.zip` | ~151 MB | **Lab PCs.** Zero-JDK Windows app (Java runtime bundled). Extract & run the exe — no Java installation anywhere. |
| `knowledge-land-cbt-1.0.0.jar` | ~105 MB | Portable fat JAR for any OS **with Java 17+** installed: `java -jar knowledge-land-cbt-1.0.0.jar` |

Both come from one green CI run on tag `v1.0.0`: Ubuntu compile + schema
migration check, Windows `mvn package` (full test suite), then `jpackage`
on Windows and upload. A rolling pre-release (`latest-build`) is also
maintained for every green `main` push.

Download (owner): https://github.com/FEMZYKENTLTD/klc-exam-portal/releases/tag/v1.0.0

### How configuration reaches a PC
The JAR embeds `config.properties` built from the repo secrets
(`KLC_SUPABASE_URL`, `KLC_DB_*`, `KLC_CODE_*`, `KLC_SMTP_*`) — it is never
committed to git. A lab PC can **override** any of those values by placing
a `config.properties` beside the exe (see `docs/INSTALLER_GUIDE.md`,
"Lab PC install"). Keep override files private — they contain the school's
codes/keys.

## 2. Installing on lab PCs (no JDK needed)
1. Copy `KLC-CBT-Suite-win.zip` to each PC (USB is fine).
2. Extract anywhere, e.g. `C:\KLC` → you get `C:\KLC\KLC-CBT-Suite\…`
   with `KLC-CBT-Suite.exe` at the top.
3. (Optional) place the school's `config.properties` beside
   `KLC-CBT-Suite.exe` to override the embedded config.
4. Run `KLC-CBT-Suite.exe`. First start on a fresh PC creates the local
   offline cache automatically; the app connects to the school cloud
   (Supabase) when online and keeps working offline via H2.

Notes:
- Windows 10/11 **x64** supported by the shipped bundle. Windows 7/8 and
  x86 remain documented targets that need an external legacy JDK build —
  see `docs/INSTALLER_GUIDE.md` "Deployment-target matrix". Not silently
  dropped.
- Restrict exam PCs with `proctor.allowed_ips` in config if desired.

## 3. Pulling the repository so it overrides your local files
Your one file that must **never** be overwritten is your local
`src/main/resources/config.properties` (gitignored — git never tracks it,
so a pull/reset cannot delete it *unless* you use `git clean` or delete
the folder).

Three safe ways, from **PowerShell**:

### A. Simplest — pull main into your existing folder (recommended)
```powershell
# save your config first (belt and braces)
Copy-Item src\main\resources\config.properties "$env:USERPROFILE\klc-config-backup.properties"

git fetch origin
git checkout main
git pull origin main
git reset --hard origin/main      # your working tree now EXACTLY matches origin/main
```
`reset --hard` only touches files git tracks. Your untracked
`config.properties` stays put. Do **not** run `git clean -fdx` (it would
delete untracked files, including that config).

### B. Get a specific tag/branch explicitly
```powershell
git fetch origin
git checkout v1.0.0               # or: git checkout origin/main
git reset --hard origin/v1.0.0    # exact tree of the release tag
```

### C. Fresh clone into a new folder (cleanest when your old copy is messy)
```powershell
git clone https://github.com/FEMZYKENTLTD/klc-exam-portal.git C:\KLC-repo-new
cd C:\KLC-repo-new
Copy-Item "$env:USERPROFILE\klc-config-backup.properties" src\main\resources\config.properties
```
Then run from the new folder. Your old folder is untouched and can be
deleted once you are happy.

### Before any of these
- Commit or stash anything you still want from the old tree
  (`git status` first). The default is: your local `config.properties` is
  preserved; everything else becomes exactly `origin/main`.

## 4. How this release was verified (evidence, not claims)
- PR runs + main runs green on both Ubuntu (compile, schema migration
  check against local PostgreSQL, live Supabase migration) and Windows
  (compile + full JUnit suite + jpackage).
- Windows-only bug fixed during the release: H2 AES plaintext→encrypted
  migration built its temp file URL via `File.getPath()` (backslashes),
  so `String.replace` no-op'd and H2 tried to AES-open the plaintext file
  (error 90049). Fixed with URL-string surgery + regression tests
  (`DatabaseManagerUrlTest`) — see commit history.
- The new Windows package & tests CI job keeps every future PR honest on
  the actual release OS.

## 5. Remaining honest boundaries
- Desktop pixel-level flows (dark theme rendering etc.) are compiled and
  wired; a quick visual pass on one lab PC is recommended before exam day.
- Windows x86/Win7-8 bundles require the external JDK build documented in
  `docs/INSTALLER_GUIDE.md`.
