/**
 * @author melon
 */
package com.melon.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密钥存储. 使用 AES-GCM 加密/解密敏感数据, 密钥文件存储在本地.
 * Corresponds to Python secret_store.py.
 */
public class SecretStore {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int KEY_BITS = 256;

    private final Path keyFile;
    private SecretKey secretKey;

    public SecretStore() {
        this(Path.of(System.getProperty("user.home"), ".melon", "secret.key"));
    }

    public SecretStore(Path keyFile) {
        this.keyFile = keyFile;
        this.secretKey = loadOrGenerateKey();
    }

    /**
     * 加密明文字符串, 返回 Base64 编码的密文.
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Failed to encrypt data", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * 解密 Base64 编码的密文, 返回明文字符串.
     */
    public String decrypt(String encryptedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] ciphertext = new byte[combined.length - GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
            System.arraycopy(combined, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt data", e);
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * 加密并存储一个密钥值到密钥文件中.
     */
    public void storeSecret(String name, String value) {
        // Store as key=value entries in an encrypted properties file
        Path secretsFile = keyFile.resolveSibling("secrets.enc");
        java.util.Map<String, String> secrets = loadSecrets(secretsFile);
        secrets.put(name, encrypt(value));
        saveSecrets(secretsFile, secrets);
        log.info("Secret '{}' stored", name);
    }

    /**
     * 从密钥文件中读取并解密一个密钥值.
     */
    public String retrieveSecret(String name) {
        Path secretsFile = keyFile.resolveSibling("secrets.enc");
        java.util.Map<String, String> secrets = loadSecrets(secretsFile);
        String encrypted = secrets.get(name);
        if (encrypted == null) {
            return null;
        }
        return decrypt(encrypted);
    }

    /**
     * 删除一个密钥值.
     */
    public void removeSecret(String name) {
        Path secretsFile = keyFile.resolveSibling("secrets.enc");
        java.util.Map<String, String> secrets = loadSecrets(secretsFile);
        if (secrets.remove(name) != null) {
            saveSecrets(secretsFile, secrets);
            log.info("Secret '{}' removed", name);
        }
    }

    private java.util.Map<String, String> loadSecrets(Path secretsFile) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        if (!Files.exists(secretsFile)) {
            return map;
        }
        try {
            for (String line : Files.readAllLines(secretsFile)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq);
                    String val = line.substring(eq + 1);
                    map.put(key, val);
                }
            }
        } catch (IOException e) {
            log.error("Failed to load secrets file", e);
        }
        return map;
    }

    private void saveSecrets(Path secretsFile, java.util.Map<String, String> secrets) {
        try {
            Files.createDirectories(secretsFile.getParent());
            StringBuilder sb = new StringBuilder();
            for (var entry : secrets.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            Path tmp = secretsFile.resolveSibling(secretsFile.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, secretsFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to save secrets file", e);
        }
    }

    private SecretKey loadOrGenerateKey() {
        if (Files.exists(keyFile)) {
            try {
                byte[] keyBytes = Files.readAllBytes(keyFile);
                return new SecretKeySpec(keyBytes, ALGORITHM);
            } catch (IOException e) {
                log.error("Failed to load secret key, generating new one", e);
            }
        }
        return generateAndStoreKey();
    }

    private SecretKey generateAndStoreKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            keyGen.init(KEY_BITS, new SecureRandom());
            SecretKey key = keyGen.generateKey();

            Files.createDirectories(keyFile.getParent());
            Path tmp = keyFile.resolveSibling(keyFile.getFileName() + ".tmp");
            Files.write(tmp, key.getEncoded());
            Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING);

            log.info("New secret key generated and stored at {}", keyFile);
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate secret key", e);
        }
    }
}
