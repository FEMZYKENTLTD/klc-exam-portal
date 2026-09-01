# Installer Guide — KLC CBT Suite v1.0 (Windows 7–11)

## What CI produces
| Artifact | File | Needs Java installed? |
|---|---|---|
| Fat JAR (all OS) | `knowledge-land-cbt-1.0.0.jar` | Java 17+ required |
| Windows app image (x64) | `KLC-CBT-Suite-win.zip` | **No** — bundled runtime |
| Rolling builds | GitHub Releases → `latest-build` | — |

Tag a release (`git tag v1.0.1 && git push --tags`) and CI also builds the
zero-JDK Windows bundle via `jpackage`.

## Lab PC install (zero-JDK bundle)
1. Copy `KLC-CBT-Suite-win.zip` to the PC (USB is fine — it **is** the
   portable version).
2. Extract anywhere (e.g. `C:\KLC`).
3. Put `config.properties` (school values) next to the exe/JAR.
4. Run `KLC-CBT-Suite.exe` (or the JAR with the bundled runtime).

## Building x86 (32-bit) installers
CI runners are x64; for a 32-bit bundle install a Temurin **x86** JDK 17 on
a Windows machine and run:
```
jpackage --type app-image --name KLC-CBT-Suite-x86 --input target ^
  --main-jar knowledge-land-cbt-1.0.0.jar --java-options "-Xmx1g"
```
(JavaFX 17 still ships win32 natives — the Maven classifier picks them via
the standard javafx-maven-plugin build.)

## Offline exam labs
The app runs fully offline (H2 cache) and syncs when internet returns.
Restrict exam PCs with `proctor.allowed_ips` (CIDR list) in config.
