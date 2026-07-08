// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.dbt.ndtp.federator.exceptions.AesCryptographicOperationException;

/**
 * Unit tests for AesCryptoUtil - previously had zero coverage despite being real, security-
 * relevant logic (AES-GCM encrypt/decrypt used for at-rest secret handling elsewhere in the
 * codebase).
 */
class AesCryptoUtilTest {

    private static final SecureRandom RNG = new SecureRandom();

    private static String randomBase64Key(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RNG.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 24, 32})
    void encryptThenDecrypt_roundTripsToOriginalPlaintext(int keyLength) {
        String key = randomBase64Key(keyLength);
        String plainText = "the quick brown fox jumps over the lazy dog";

        String cipherText = AesCryptoUtil.encrypt(plainText, key);
        String decrypted = AesCryptoUtil.decrypt(cipherText, key);

        assertEquals(plainText, decrypted);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachTime_dueToRandomIv() {
        String key = randomBase64Key(32);
        String plainText = "same plaintext";

        String first = AesCryptoUtil.encrypt(plainText, key);
        String second = AesCryptoUtil.encrypt(plainText, key);

        assertNotEquals(first, second, "random IV should make repeated encryptions differ");
        // but both must still decrypt back to the same plaintext
        assertEquals(plainText, AesCryptoUtil.decrypt(first, key));
        assertEquals(plainText, AesCryptoUtil.decrypt(second, key));
    }

    @Test
    void encrypt_nullKey_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> AesCryptoUtil.encrypt("text", null));
    }

    @Test
    void encrypt_blankKey_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> AesCryptoUtil.encrypt("text", "   "));
    }

    @Test
    void encrypt_invalidKeyLength_throwsIllegalArgumentException() {
        // 10 bytes - not 16, 24, or 32
        String badKey = Base64.getEncoder().encodeToString(new byte[10]);

        assertThrows(IllegalArgumentException.class, () -> AesCryptoUtil.encrypt("text", badKey));
    }

    @Test
    void decrypt_withWrongKey_throwsAesCryptographicOperationException() {
        String correctKey = randomBase64Key(32);
        String wrongKey = randomBase64Key(32);
        String cipherText = AesCryptoUtil.encrypt("secret message", correctKey);

        assertThrows(
                AesCryptographicOperationException.class,
                () -> AesCryptoUtil.decrypt(cipherText, wrongKey));
    }

    @Test
    void decrypt_tooShortCiphertext_throwsIllegalArgumentExceptionWrappedInOperationException() {
        // decryptFromBase64 wraps its own IllegalArgumentException in a try/catch(Exception),
        // so the input-too-short check surfaces as AesCryptographicOperationException, not
        // IllegalArgumentException directly - matches the actual code path, not just the intent.
        String key = randomBase64Key(32);
        String tooShort = Base64.getEncoder().encodeToString(new byte[5]);

        assertThrows(
                AesCryptographicOperationException.class,
                () -> AesCryptoUtil.decrypt(tooShort, key));
    }

    @Test
    void decrypt_tamperedCiphertext_throwsAesCryptographicOperationException() {
        String key = randomBase64Key(32);
        String cipherText = AesCryptoUtil.encrypt("integrity matters", key);

        byte[] raw = Base64.getDecoder().decode(cipherText);
        raw[raw.length - 1] ^= 0x01; // flip the last byte, inside the GCM tag
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThrows(
                AesCryptographicOperationException.class, () -> AesCryptoUtil.decrypt(tampered, key));
    }

    @Test
    void encryptThenDecrypt_handlesEmptyString() {
        String key = randomBase64Key(16);

        String cipherText = AesCryptoUtil.encrypt("", key);

        assertEquals("", AesCryptoUtil.decrypt(cipherText, key));
    }
}
