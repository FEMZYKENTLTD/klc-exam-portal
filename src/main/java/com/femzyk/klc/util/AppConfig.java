package com.femzyk.klc.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Central configuration source for the KLC CBT Suite.
 *
 * <p>Every app module (ConfigService, AuthService, DatabaseManager,
 * DatabaseInitializer, admin realtime client) reads its settings from one
 * merged property set so a deployment can never observe half-old /
 * half-new configuration.
 *
 * <p>Precedence, later wins:
 * <ol>
 *   <li>the packaged default — {@code /config.properties} embedded on the
 *       classpath (CI injects it from repo secrets; never committed);</li>
 *   <li>{@code ./config.properties} in the current working folder (classic
 *       {@code java -jar} deployments);</li>
 *   <li>{@code config.properties} in the folder of the packaged JAR;</li>
 *   <li>{@code config.properties} beside the launcher {@code .exe} of a
 *       jpackage app image — the folder ABOVE the image's {@code app/} JAR
 *       folder — <em>strongest override</em>, so each lab PC can carry its
 *       own school configuration without touching the embedded defaults.</li>
 * </ol>
 *
 * <p>External files are only consulted when the code source is an actual
 * packaged {@code .jar}. During {@code mvn test} / IDE runs the exploded
 * {@code target/classes} is never treated as a deployment folder, so test
 * isolation (classpath test config) is preserved.
 */
public final class AppConfig {

    private static volatile Properties cached;

    private AppConfig() {
    }

    /** Effective merged configuration, loaded once per JVM. */
    public static Properties properties() {
        Properties p = cached;
        if (p == null) {
            synchronized (AppConfig.class) {
                if (cached == null) {
                    cached = load();
                }
                p = cached;
            }
        }
        return p;
    }

    private static Properties load() {
        Properties base = new Properties();
        boolean embedded = false;
        try (InputStream in =
                 AppConfig.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                base.load(in);
                embedded = true;
            }
        } catch (IOException e) {
            System.err.println("[cfg] could not read embedded "
                + "config.properties: " + e.getMessage());
        }

        URL codeSource = null;
        try {
            codeSource = AppConfig.class.getProtectionDomain()
                .getCodeSource().getLocation();
        } catch (Exception ignore) {
            // security manager or odd classloader - fall through
        }
        List<File> present = new ArrayList<>();
        for (File f : externalCandidates(
                codeSource, System.getProperty("user.dir", "."))) {
            if (f.isFile()) {
                present.add(f);
            }
        }
        if (!present.isEmpty()) {
            // merge() copies, so the embedded base is not mutated.
            base = merge(base, present);
            System.out.println("[cfg] external config.properties overrides "
                + "embedded defaults: " + present);
        }
        if (!embedded && present.isEmpty()) {
            System.err.println("[cfg] config.properties not found (no "
                + "embedded default and no external file).");
        }
        return base;
    }

    /**
     * Pure overlay helper: returns a copy of {@code base} with every
     * existing file loaded over it in list order (later files win). The
     * caller's {@code base} is never mutated. Files that do not exist are
     * skipped. Package-private for unit tests.
     */
    static Properties merge(Properties base, List<File> files) {
        Properties out = new Properties();
        out.putAll(base);
        for (File f : files) {
            if (f == null || !f.isFile()) {
                continue;
            }
            try (InputStream in = new java.io.FileInputStream(f)) {
                out.load(in);
            } catch (IOException e) {
                System.err.println("[cfg] could not read external "
                    + "config.properties " + f + ": " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * Where to look for an external {@code config.properties}. Only the
     * current working folder is returned when the code source is not a
     * packaged {@code .jar} (test/IDE runs). For a packaged jar the folder
     * of the jar and its parent (the "beside the .exe" folder of a jpackage
     * app image) are also consulted, parent last = strongest.
     * Package-private for unit tests.
     */
    static List<File> externalCandidates(URL codeSource, String userDir) {
        List<File> out = new ArrayList<>();
        if (userDir != null && !userDir.isBlank()) {
            out.add(new File(userDir, "config.properties"));
        }
        if (codeSource == null) {
            return out;
        }
        String loc = codeSource.toExternalForm();
        if (!loc.endsWith(".jar")) {
            return out; // exploded classes (mvn test / IDE) - not a deployment
        }
        try {
            File jarDir = new File(codeSource.toURI()).getParentFile();
            if (jarDir != null) {
                out.add(new File(jarDir, "config.properties"));
                File above = jarDir.getParentFile();
                if (above != null) {
                    out.add(new File(above, "config.properties"));
                }
            }
        } catch (Exception ignore) {
            // unhandled URL form - keep the candidates collected so far
        }
        return out;
    }
}
