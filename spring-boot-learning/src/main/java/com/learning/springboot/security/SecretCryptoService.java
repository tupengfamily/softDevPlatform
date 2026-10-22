package com.learning.springboot.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密钥静态加密服务(AES-256-GCM)
 *
 * <p>系统里保存的第三方 API Key / Secret 属于高敏感凭据, 不能明文落库。
 * 这里用 AES-256-GCM 做「带认证的对称加密」:
 * <ul>
 *   <li>GCM 带认证标签, 密文被篡改会在解密时直接抛错, 不会解出垃圾明文;</li>
 *   <li>每次加密都用随机 12 字节 IV, 相同明文产出不同密文(防止从密文反推相同密钥);</li>
 *   <li>存储格式: {@code base64(iv ‖ ciphertext ‖ tag)}, 自描述, 解密不需要额外元数据。</li>
 * </ul>
 *
 * <p><b>主密钥来源优先级</b>:
 * <ol>
 *   <li>环境变量 {@code API_KEY_MASTER_KEY}(生产推荐, 16/24/32 字节原文或 Base64);</li>
 *   <li>{@code data/api-key-master.key} 文件(开发环境自动生成, 权限 600);</li>
 *   <li>都没有 → 启动失败(绝不能退化成明文存储或固定密钥)。</li>
 * </ol>
 *
 * <p><b>为什么主密钥文件不会被提交</b>: {@code data/} 目录已在 {@code .gitignore} 中
 * (见仓库根 .gitignore 的 {@code /data/}), 所以自动生成的主密钥与数据库都在版本控制之外。
 *
 * <p><b>主密钥丢失的后果</b>: 已加密的密钥无法恢复, 只能重新配置。
 * 这是刻意接受的设计——密钥文件本身也需要被当作机密来保管。
 */
@Component
public class SecretCryptoService {

    private static final Logger log = LoggerFactory.getLogger(SecretCryptoService.class);

    private static final String ALGO = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    /** GCM 推荐 96 位 IV(12 字节) */
    private static final int IV_BYTES = 12;
    /** GCM 认证标签长度(比特) */
    private static final int TAG_BITS = 128;

    /** 环境变量名 */
    public static final String MASTER_KEY_ENV = "API_KEY_MASTER_KEY";
    /** 主密钥文件相对路径(位于 data/, 已被 .gitignore 覆盖) */
    private static final String MASTER_KEY_FILE = "data/api-key-master.key";

    private SecretKey masterKey;
    private final SecureRandom random = new SecureRandom();

    private static Path resolveMasterKeyPath() {
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            return Paths.get(userDir, MASTER_KEY_FILE).toAbsolutePath();
        }
        return Paths.get(MASTER_KEY_FILE).toAbsolutePath();
    }

    /**
     * 初始化主密钥。
     *
     * <p>失败直接抛异常终止启动——这是刻意的: 拿不到主密钥就意味着无法安全存储密钥,
     * 此时若继续启动, 管理员会以为「配置好了」, 实际要么明文落库要么启动后大面积报错。
     * 宁可启动失败并给出明确指引。
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        String env = System.getenv(MASTER_KEY_ENV);
        byte[] raw = null;
        String source;
        if (env != null && !env.isBlank()) {
            raw = decodeMasterKey(env.trim());
            source = "环境变量 " + MASTER_KEY_ENV;
        } else {
            Path path = resolveMasterKeyPath();
            try {
                if (Files.exists(path) && Files.size(path) > 0) {
                    raw = Base64.getDecoder().decode(stripWhitespace(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)));
                } else {
                    byte[] generated = new byte[KEY_BITS / 8];
                    new SecureRandom().nextBytes(generated);
                    String b64 = Base64.getEncoder().encodeToString(generated);
                    Files.createDirectories(path.getParent());
                    Files.write(path, b64.getBytes(StandardCharsets.UTF_8));
                    tightenPermissions(path);
                    raw = generated;
                    log.warn("[secret] 未设置环境变量 {}，已自动生成主密钥并写入 {}（该目录不在版本控制中）",
                            MASTER_KEY_ENV, path);
                }
                source = "本地文件 " + path;
            } catch (Exception e) {
                throw new IllegalStateException(
                        "[secret] 读取/生成主密钥文件失败: " + resolveMasterKeyPath() + "。" +
                        "请设置环境变量 " + MASTER_KEY_ENV + " 提供主密钥(16/24/32 字节或 Base64)", e);
            }
        }

        if (raw == null || (raw.length != 16 && raw.length != 24 && raw.length != 32)) {
            throw new IllegalStateException(
                    "[secret] 主密钥长度非法(" + (raw == null ? 0 : raw.length) + " 字节)，" +
                    "必须是 16 / 24 / 32 字节(AES-128/192/256)，或对应长度的 Base64");
        }
        this.masterKey = new SecretKeySpec(raw, ALGO);
        log.info("[secret] 密钥加密服务已就绪(主密钥来源: {})", source);
    }

    /**
     * 加密明文密钥。
     *
     * @return {@code base64(iv ‖ ciphertext ‖ tag)}
     */
    public String encrypt(String plain) {
        if (plain == null) throw new IllegalArgumentException("待加密内容不能为 null");
        if (masterKey == null) throw new IllegalStateException("主密钥未初始化");
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("密钥加密失败", e);
        }
    }

    /**
     * 解密。
     *
     * @throws IllegalStateException 密文损坏 / 被篡改 / 主密钥不匹配
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) return null;
        if (masterKey == null) throw new IllegalStateException("主密钥未初始化");
        try {
            byte[] all = Base64.getDecoder().decode(cipherText.trim());
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("密文长度非法(可能不是本系统加密的数据)");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            byte[] ct = new byte[all.length - IV_BYTES];
            System.arraycopy(all, IV_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("密钥解密失败(密文损坏或主密钥不匹配)", e);
        }
    }

    /**
     * 生成掩码: 只暴露头尾少量字符, 中间用星号。
     *
     * <p>用于前端展示「这个 key 是哪一把」——管理员能认出来, 但从界面上抄不出完整密钥。
     */
    public static String mask(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        String s = plain.trim();
        int len = s.length();
        // 太短的密钥整体打码, 避免「4 位密钥全露」这种等于没打码
        if (len <= 8) return "*".repeat(len);
        int head = 4;
        int tail = 4;
        return s.substring(0, head) + "*".repeat(Math.min(16, len - head - tail)) + s.substring(len - tail);
    }

    private static String stripWhitespace(String s) {
        return s.replaceAll("\\s", "");
    }

    /** 主密钥优先按 Base64 解析, 失败则按原文字节(便于运维直接填 32 位随机串) */
    private static byte[] decodeMasterKey(String s) {
        try {
            byte[] decoded = Base64.getDecoder().decode(s);
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) return decoded;
        } catch (IllegalArgumentException ignored) {
            // 不是合法 Base64, 走原文分支
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 把文件权限收紧到仅属主可读写 */
    private static void tightenPermissions(Path path) {
        try {
            File f = path.toFile();
            if (!f.setReadable(false, false) || !f.setWritable(false, false)) {
                log.warn("[secret] 收紧 {} 权限失败, 请手工执行 chmod 600 {}", path, path);
                return;
            }
            f.setReadable(true, true);
            f.setWritable(true, true);
        } catch (Exception e) {
            log.warn("[secret] 设置 {} 文件权限失败: {}", path, e.getMessage());
        }
    }
}
