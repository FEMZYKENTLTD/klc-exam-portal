package com.femzyk.klc.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Regression for the Windows AES-migration bug (directive §4: real H2 AES
 * at rest): the temporary encrypted-cache URL is built from the URL text,
 * never via File.getPath(). On Windows File.getPath() returns '\'
 * separators while the JDBC URL keeps '/', which made the old
 * String.replace(filePath, ...) a silent no-op - H2 then re-opened the
 * PLAINTEXT cache with CIPHER=AES and failed with error 90049
 * ("Encryption error in file"). These tests pin the URL-suffix helper on
 * every platform (Linux CI included).
 */
class DatabaseManagerUrlTest {

    @Test
    void appendsSuffixBeforeParameters() {
        assertEquals(
            "jdbc:h2:file:C:/Users/lab/AppData/Local/Temp/x/klc_aes_mig"
                + ";AUTO_SERVER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            DatabaseManager.h2UrlWithBaseSuffix(
                "jdbc:h2:file:C:/Users/lab/AppData/Local/Temp/x/klc"
                    + ";AUTO_SERVER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "_aes_mig"));
    }

    @Test
    void windowsStyleBackslashPathInsideUrlIsSuffixedToo() {
        // The exact separator mix File.getPath() would hand us on Windows:
        // the helper must not depend on which separator the URL uses.
        assertEquals(
            "jdbc:h2:file:C:\\Users\\lab\\Temp\\klc_aes_mig;MODE=PostgreSQL",
            DatabaseManager.h2UrlWithBaseSuffix(
                "jdbc:h2:file:C:\\Users\\lab\\Temp\\klc;MODE=PostgreSQL",
                "_aes_mig"));
    }

    @Test
    void fileUrlWithoutParametersGetsSuffix() {
        assertEquals("jdbc:h2:file:./klc_cache/klc_aes_mig",
            DatabaseManager.h2UrlWithBaseSuffix(
                "jdbc:h2:file:./klc_cache/klc", "_aes_mig"));
    }

    @Test
    void nonFileUrlIsReturnedUntouched() {
        assertEquals("jdbc:h2:mem:test",
            DatabaseManager.h2UrlWithBaseSuffix("jdbc:h2:mem:test",
                "_aes_mig"));
    }
}
