package uk.gov.dbt.ndtp.federator.common.service.idp;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Test-only helper that generates a throwaway self-signed RSA key pair and
 * wraps it in a PKCS12 keystore, using the JDK's bundled {@code keytool}.
 *
 * <p>Building the keystore via {@code keytool} (rather than hand-rolling a
 * certificate with internal {@code sun.security} APIs) keeps the fixture
 * portable across JDK versions and avoids depending on non-public APIs in
 * test code.</p>
 *
 * <p>Each call creates a brand-new keystore file under the supplied
 * {@code @TempDir}, so tests are fully isolated from one another.</p>
 */
public final class KeystoreTestFixture {

    private static final String TEST_PASSWORD = "changeit";
    private static final String DEFAULT_DNAME = "CN=federator-test,OU=NDTP,O=DBT,L=London,ST=London,C=GB";

    private final Path keystorePath;
    private final String alias;
    private final String password;

    private KeystoreTestFixture(Path keystorePath, String alias, String password) {
        this.keystorePath = keystorePath;
        this.alias = alias;
        this.password = password;
    }

    public Path keystorePath() {
        return keystorePath;
    }

    public String alias() {
        return alias;
    }

    public String password() {
        return password;
    }

    /**
     * Generates a PKCS12 keystore containing a self-signed RSA key pair under
     * the given alias, at {@code <tempDir>/<alias>-keystore.p12}.
     *
     * @param tempDir a JUnit {@code @TempDir}-managed directory
     * @param alias   the alias under which the key/cert pair will be stored
     * @return a fixture exposing the keystore path, alias, and password
     */
    public static KeystoreTestFixture create(Path tempDir, String alias) throws Exception {
        Path keystorePath = tempDir.resolve(alias + "-keystore.p12");

        ProcessBuilder pb = new ProcessBuilder(
                resolveKeytool(),
                "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-validity", "3650",
                "-dname", DEFAULT_DNAME,
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", TEST_PASSWORD,
                "-keypass", TEST_PASSWORD);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("keytool did not finish within timeout. Output: " + output);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed with exit code " + process.exitValue()
                    + ". Output: " + output);
        }

        return new KeystoreTestFixture(keystorePath, alias, TEST_PASSWORD);
    }

    private static String resolveKeytool() {
        String javaHome = System.getProperty("java.home");
        Path candidate = Path.of(javaHome, "bin", "keytool");
        if (candidate.toFile().exists()) {
            return candidate.toString();
        }
        // Fall back to PATH resolution (covers Windows keytool.exe and unusual JDK layouts).
        return "keytool";
    }
}