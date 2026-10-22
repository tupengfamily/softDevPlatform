package com.learning.springboot.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretCryptoService} 单元测试。
 *
 * <p>锁死三条不变量：
 * <ul>
 *   <li>密文里<b>不能出现明文</b>（哪怕只是片段）；</li>
 *   <li>同一明文两次加密产出<b>不同密文</b>（随机 IV）；</li>
 *   <li>掩码<b>不能还原出完整密钥</b>。</li>
 * </ul>
 */
class SecretCryptoServiceTest {

    private SecretCryptoService crypto;

    @BeforeEach
    void setUp() throws Exception {
        crypto = new SecretCryptoService();
        // 直接塞主密钥，绕过 @PostConstruct 的文件/环境变量读取（单测不该碰磁盘）
        Field f = SecretCryptoService.class.getDeclaredField("masterKey");
        f.setAccessible(true);
        f.set(crypto, new javax.crypto.spec.SecretKeySpec(
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "AES"));
    }

    @Test
    @DisplayName("加密→解密能还原原文")
    void encryptThenDecrypt() {
        String plain = "bfed05dfdeb2325b1462be93eb1efb48";
        String cipher = crypto.encrypt(plain);
        assertNotNull(cipher);
        assertEquals(plain, crypto.decrypt(cipher));
    }

    @Test
    @DisplayName("密文里不含明文片段")
    void cipherDoesNotLeakPlainText() {
        String plain = "bfed05dfdeb2325b1462be93eb1efb48";
        String cipher = crypto.encrypt(plain);
        assertFalse(cipher.contains(plain));
        assertFalse(cipher.contains("bfed05df"));
        // 也不能包含明文的 Base64 形式
        assertFalse(cipher.contains(java.util.Base64.getEncoder().encodeToString(plain.getBytes())));
    }

    @Test
    @DisplayName("相同明文两次加密产出不同密文(随机 IV)")
    void samePlainDifferentCipher() {
        String plain = "same-secret-value-1234";
        String c1 = crypto.encrypt(plain);
        String c2 = crypto.encrypt(plain);
        assertNotEquals(c1, c2);
        // 但都能解回原文
        assertEquals(plain, crypto.decrypt(c1));
        assertEquals(plain, crypto.decrypt(c2));
    }

    @Test
    @DisplayName("密文被篡改时解密抛异常(GCM 认证)")
    void tamperedCipherFails() {
        String cipher = crypto.encrypt("hello-secret");
        // 翻转最后一个字节(GCM tag)
        char[] arr = cipher.toCharArray();
        char last = arr[arr.length - 1];
        arr[arr.length - 1] = (last == 'A') ? 'B' : 'A';
        String tampered = new String(arr);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    @DisplayName("空值处理")
    void nullAndBlank() {
        assertEquals(null, crypto.decrypt(null));
        assertEquals(null, crypto.decrypt("   "));
        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt(null));
    }

    @Test
    @DisplayName("掩码保留头尾, 中间打码")
    void maskKeepsHeadAndTail() {
        String mask = SecretCryptoService.mask("abcdefghijklmnopqrstuvwxyz");
        assertTrue(mask.startsWith("abcd"));
        assertTrue(mask.endsWith("wxyz"));
        assertFalse(mask.contains("efghijklmnopqrstuv"));
    }

    @Test
    @DisplayName("短密钥整体打码, 不露头尾")
    void maskShortKeyFullyHidden() {
        String mask = SecretCryptoService.mask("12345678");
        assertEquals("********", mask);
        assertFalse(mask.contains("1"));
        assertFalse(mask.contains("8"));
    }

    @Test
    @DisplayName("空密钥的掩码是空串")
    void maskEmpty() {
        assertEquals("", SecretCryptoService.mask(null));
        assertEquals("", SecretCryptoService.mask(""));
    }
}
