# Installer Guide — KLC CBT Suite v1.0 (Windows)

> **OS support:** the shipped v1.0 runtime (Java 17 + JavaFX 17) runs on
> Windows 10/11 x64. Windows 7/8 and x86 remain deployment targets with a
> documented external build dependency — see the authoritative
> "Deployment-target matrix" below. Targets are preserved, never silently
> deleted.

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

## Deployment-target matrix (authoritative)

| Target | Status | How it is produced | External requirement to run the build |
|---|---|---|---|
| Windows x64, Windows 10/11 | **Supported & shipped** | CI `Versioned release` job (tag `v*`): Temurin JDK 17 → `mvn package` → `jpackage --type app-image` → `KLC-CBT-Suite-win.zip` (runtime bundled, zero-JDK). Attached to the GitHub Release. | None — automatic on any `v*` tag |
| Windows x64, Windows 7/8/8.1 | **Compatibility boundary (documented, not removed)** | Java 17 + JavaFX 17 minimum OS is Windows 10; JavaFX 17 does not ship Win7/8 support. The realistic legacy track is Java 8/JavaFX 8 (the v1.0 codebase is written on Java 17 language APIs, so a Java-8 track is a separate build, not a recompile). | A maintained Java 8/JavaFX 8 build track + a Windows 7/8 test machine. Preserved as a target; not silently deleted. |
| Windows x86 (32-bit), Windows 10 | **Buildable, needs an x86 JDK environment** | Same `jpackage` command with a Temurin x86 (32-bit) JDK 17. JavaFX 17 still ships 32-bit Windows artifacts; the app code is arch-neutral. | An x86 (32-bit) Temurin JDK 17 + Windows x64-or-x86 machine able to run it. CI x64 runners cannot execute the x86 JDK, so this is an external build-environment dependency. The target is preserved; exact commands below. |
| Windows x86, Windows 7/8 | Combined legacy boundary above. | Java 8/JavaFX 8 x86 track. | Same as the two rows above. |

### Building the x86 (32-bit) bundle (exact steps, external environment)
On a Windows machine with a 32-bit JDK 17 installed:
```bat
set JAVA_HOME=C:\Program Files (x86)\Eclipse Adoptium\jdk-17.x.x
mvn clean package
jpackage --type app-image --name KLC-CBT-Suite-x86 --input target ^
  --main-jar knowledge-land-cbt-1.0.0.jar --java-options "-Xmx1g"
:: app-image lives in dist\KLC-CBT-Suite-x86; zip it for the x86 lab PCs
```

### Why the app-image is the professional distribution
The Windows zip bundles: the launcher exe, the application JAR, the Java
runtime (no JDK/JRE install), and `config.properties` sits beside the exe
so each lab PC gets the school's own configuration. For a true `.exe`
installer run the same CI job on a Windows machine and add
`--type exe --win-menu` (or use `--type msi`); CI currently publishes the
portable app-image + fat JAR, which covers the "clean install, no IDE /
Maven / JDK required" requirement.
