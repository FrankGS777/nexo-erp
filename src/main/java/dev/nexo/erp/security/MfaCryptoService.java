package dev.nexo.erp.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MfaCryptoService {

    private static final byte[] AAD = "nexo-mfa-v1".getBytes(StandardCharsets.UTF_8);
    private final String masterKey;
    private final SecureRandom random = new SecureRandom();

    public MfaCryptoService(@Value("${app.security.mfa-master-key:}") String masterKey) {
        this.masterKey = masterKey == null ? "" : masterKey;
    }

    public boolean available() {
        return masterKey.length() >= 32;
    }

    public String encrypt(String plainText) {
        requireConfigured();
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return "v1:" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo proteger el secreto MFA", exception);
        }
    }

    public String decrypt(String value) {
        requireConfigured();
        if (value == null || !value.startsWith("v1:")) {
            throw new IllegalStateException("El secreto MFA almacenado no tiene un formato válido");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(3));
            if (payload.length < 29) {
                throw new IllegalStateException("El secreto MFA almacenado está incompleto");
            }
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("No se pudo abrir el secreto MFA; revisa la clave maestra", exception);
        }
    }

    private SecretKeySpec key() throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private void requireConfigured() {
        if (!available()) {
            throw new IllegalStateException(
                "MFA requiere NEXO_MFA_MASTER_KEY con al menos 32 caracteres");
        }
    }
}
