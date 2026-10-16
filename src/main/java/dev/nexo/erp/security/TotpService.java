package dev.nexo.erp.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long STEP_SECONDS = 30;
    private final SecureRandom random = new SecureRandom();

    public String generateSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public Verification verify(String secret, String submittedCode) {
        return verifyAt(secret, submittedCode, Instant.now());
    }

    Verification verifyAt(String secret, String submittedCode, Instant instant) {
        String normalized = submittedCode == null ? "" : submittedCode.replaceAll("\\s", "");
        if (!normalized.matches("\\d{6}")) {
            return Verification.invalid();
        }
        long currentStep = instant.getEpochSecond() / STEP_SECONDS;
        for (long offset = -1; offset <= 1; offset++) {
            long step = currentStep + offset;
            if (constantTimeEquals(code(secret, step), normalized)) {
                return new Verification(true, step);
            }
        }
        return Verification.invalid();
    }

    public String codeAt(String secret, Instant instant) {
        return code(secret, instant.getEpochSecond() / STEP_SECONDS);
    }

    public String provisioningUri(String issuer, String username, String secret) {
        String label = encode(issuer + ":" + username);
        return "otpauth://totp/" + label + "?secret=" + encode(secret)
            + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits=6&period=30";
    }

    public String grouped(String secret) {
        return secret.replaceAll("(.{4})(?!$)", "$1 ");
    }

    private String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo calcular el código MFA", exception);
        }
    }

    private String encodeBase32(byte[] bytes) {
        StringBuilder result = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 31));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            result.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 31));
        }
        return result.toString();
    }

    private byte[] decodeBase32(String value) {
        String normalized = value == null ? "" : value.replace(" ", "")
            .replace("-", "").toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Secreto MFA vacío");
        }
        byte[] output = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (char character : normalized.toCharArray()) {
            int digit = BASE32.indexOf(character);
            if (digit < 0) throw new IllegalArgumentException("Secreto MFA inválido");
            buffer = (buffer << 5) | digit;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return output;
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record Verification(boolean valid, long step) {
        static Verification invalid() { return new Verification(false, -1); }
    }
}
