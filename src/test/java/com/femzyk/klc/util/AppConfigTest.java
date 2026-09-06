package com.femzyk.klc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * External config.properties override (INSTALLER_GUIDE: "each lab PC gets
 * the school's own configuration"): the embedded classpath defaults are
 * overlaid by {@code config.properties} found beside the packaged jar/exe,
 * so a deployment never needs to rebuild to point a PC at different values.
 * The lookup is restricted to packaged jars - exploded test/IDE class dirs
 * are never treated as deployment folders.
 */
class AppConfigTest {

    @TempDir
    File tmp;

    private static File writeProps(File file, String content)
            throws Exception {
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test
    void laterExternalFileOverridesEarlierAndBase() throws Exception {
        Properties base = new Properties();
        base.setProperty("code.student", "EMBEDDED");
        base.setProperty("smtp.host", "embedded.example.com");

        File dirA = new File(tmp, "a");
        File dirB = new File(tmp, "b");
        writeProps(new File(dirA, "config.properties"),
            "code.student=FROM_A\n");
        writeProps(new File(dirB, "config.properties"),
            "code.student=FROM_B\nsmtp.host=b.example.com\n");

        Properties merged = AppConfig.merge(base, List.of(
            new File(dirA, "config.properties"),
            new File(dirB, "config.properties"),
            new File(tmp, "does-not-exist.properties")));

        assertEquals("FROM_B", merged.getProperty("code.student"),
            "later files must win");
        assertEquals("b.example.com", merged.getProperty("smtp.host"),
            "keys missing from the override must survive from the base");
        assertEquals("EMBEDDED",
            base.getProperty("code.student"),
            "the base Properties object must not be mutated");
    }

    @Test
    void packagedJarAddsJarDirAndParentCandidates() throws Exception {
        URL jarUrl = new URL("file:/C:/KLC/KLC-CBT-Suite/app/klc.jar");
        // Derive expectations from the same URI construction the code uses,
        // so the assertions hold on every OS (on POSIX an absolute file:
        // URI gains a leading '/').
        File jarDir = new File(jarUrl.toURI()).getParentFile();

        List<File> candidates = AppConfig.externalCandidates(jarUrl,
            "C:/workdir");

        assertEquals(3, candidates.size());
        assertEquals(new File("C:/workdir", "config.properties"),
            candidates.get(0));
        assertEquals(new File(jarDir, "config.properties"),
            candidates.get(1));
        // The folder ABOVE app/ is where the launcher .exe lives - the
        // strongest override for a jpackage app image.
        assertEquals(new File(jarDir.getParentFile(),
            "config.properties"), candidates.get(2));
    }

    @Test
    void explodedClassDirIsNotATrustedDeploymentFolder() throws Exception {
        // During `mvn test`/IDE the code source is a directory
        // (target/classes) - only the working folder may be consulted so
        // the injected CI config in target/classes can never leak into tests.
        List<File> candidates = AppConfig.externalCandidates(
            new File(tmp, "target/classes").toURI().toURL(),
            System.getProperty("user.dir"));

        assertEquals(1, candidates.size(),
            "class-dir runs must not add jar-folder candidates");
        assertTrue(candidates.get(0).getPath().endsWith(
            File.separator + "config.properties"));
    }

    @Test
    void jarWithoutParentYieldsOnlyCwdAndJarDir() throws Exception {
        List<File> candidates = AppConfig.externalCandidates(
            new URL("file:/standalone.jar"),
            "/run");

        assertEquals(2, candidates.size());
        assertEquals(new File("/run", "config.properties"),
            candidates.get(0));
        assertEquals(new File("/", "config.properties"),
            candidates.get(1));
    }

    @Test
    void missingExternalFilesLeaveBaseUntouched() throws Exception {
        Properties base = new Properties();
        base.setProperty("h2.url", "jdbc:h2:mem:test");
        Properties merged = AppConfig.merge(base, List.of(
            new File(tmp, "nope/config.properties"),
            new File(tmp, "still-nope.properties")));
        assertEquals("jdbc:h2:mem:test", merged.getProperty("h2.url"));
        assertEquals(1, merged.size(),
            "no extra keys may appear when no external file exists");
    }

    @Test
    void basePropertiesAreNotMutatedByMerge() throws Exception {
        Properties base = new Properties();
        base.setProperty("code.admin", "ORIGINAL");
        File ext = writeProps(new File(tmp, "ext/config.properties"),
            "code.admin=OVERRIDDEN\n");
        AppConfig.merge(base, List.of(ext));
        assertEquals("ORIGINAL", base.getProperty("code.admin"));
    }
}
