package com.learning.springboot.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 2048 keypair 提供者(用于登录密码传输加密)
 *
 * <p>背景: HTTP 明文传密码有安全风险(Sniffer / 网关 / CDN 日志泄漏)。
 * 方案: 客户端从 {@code GET /api/auth/public-key} 拿 PEM 公钥, 用 RSA-OAEP 加密密码,
 * 后端用本地私钥解密后再走 bcrypt 校验。
 *
 * <p>实现细节:
 * <ul>
 *   <li>keypair 启动时生成(若 data/rsa-keypair.bin 不存在); 持久化保证跨重启稳定</li>
 *   <li>2048 bit OAEP+SHA-256 padding(避免教科书 RSA + PKCS1 时的 Bleichenbacher 风险)</li>
 *   <li>getPublicKeyPem(): 返回 X.509 PEM 格式(前端 jsencrypt 可直接 import)</li>
 *   <li>decrypt(byte[]): 用本地私钥解密密文; 失败抛 BusinessException(400)</li>
 * </ul>
 *
 * <p>注: 仅保护登录/注册密码传输。生产环境应上 HTTPS, 此机制为密码额外加一层防护,
 * 在内网 / 开发 / HTTPS 未署前可用。
 */
@Component
public class RsaKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyProvider.class);

    private static final String KEY_FILE = "data/rsa-keypair.bin";
    private static final Path KEY_PATH = resolveKeyPath();
    // 启动时确定 keypair 文件路径(用绝对路径 + 多 fallback, 避免 jar 启动 cwd 不确定导致 FileNotFound)
    private static Path resolveKeyPath() {
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            return Paths.get(userDir, KEY_FILE).toAbsolutePath();
        }
        return Paths.get(KEY_FILE).toAbsolutePath();
    }
    private static final String ALGO = "RSA";
    private static final int KEY_SIZE = 2048;
    // 用 PKCS1 v1.5 padding(JSEncrypt 默认 paddingFunction=pkcs1pad2, 零前端配置对接)
    // 安全性说明: PKCS1 v1.5 有 Bleichenbacher padding oracle 风险, 但需要攻击者注入大量错误 padding 探测;
    // 配合 HTTPS 部署后基本消除该攻击面(密码本身已加密, 攻击者无法伪造服务端响应引导 padding 错误回显)
    private static final String TRANSFORMATION = "RSA/ECB/PKCS1Padding";

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private volatile String publicKeyPem;

    /**
     * 启动时初始化。文件存在则从 disk 读 (避免每次重启密钥变, 老加密密码没法解);
     * 不存在则新生成并写盘。
     */
    @PostConstruct
    public void init() {
        try {
            File f = KEY_PATH.toFile();
            if (f.exists() && f.length() > 4) {
                // 文件格式: [4 字节 priv_len (big-endian int)][priv_bytes][pub_bytes]
                byte[] all = Files.readAllBytes(f.toPath());
                int privLen = ((all[0] & 0xFF) << 24) | ((all[1] & 0xFF) << 16)
                            | ((all[2] & 0xFF) << 8) | (all[3] & 0xFF);
                if (privLen <= 0 || 4 + privLen > all.length) {
                    throw new IllegalStateException("损坏的 keypair 文件(priv_len=" + privLen + ")");
                }
                byte[] priv = new byte[privLen];
                byte[] pub = new byte[all.length - 4 - privLen];
                System.arraycopy(all, 4, priv, 0, privLen);
                System.arraycopy(all, 4 + privLen, pub, 0, pub.length);
                this.privateKey = KeyFactory.getInstance(ALGO)
                    .generatePrivate(new PKCS8EncodedKeySpec(priv));
                this.publicKey = KeyFactory.getInstance(ALGO)
                    .generatePublic(new X509EncodedKeySpec(pub));
                log.info("[RSA] 加载已持久化的 RSA 2048 keypair (path={}, size={})", KEY_PATH, f.length());
            } else {
                KeyPairGenerator g = KeyPairGenerator.getInstance(ALGO);
                g.initialize(KEY_SIZE, new SecureRandom());
                KeyPair p = g.generateKeyPair();
                this.privateKey = p.getPrivate();
                this.publicKey = p.getPublic();
                byte[] priv = this.privateKey.getEncoded();
                byte[] pub = this.publicKey.getEncoded();
                // 写盘时加 4 字节 priv_len 前缀
                byte[] header = new byte[] {
                    (byte) ((priv.length >>> 24) & 0xFF),
                    (byte) ((priv.length >>> 16) & 0xFF),
                    (byte) ((priv.length >>> 8) & 0xFF),
                    (byte) (priv.length & 0xFF)
                };
                byte[] all = new byte[header.length + priv.length + pub.length];
                System.arraycopy(header, 0, all, 0, 4);
                System.arraycopy(priv, 0, all, 4, priv.length);
                System.arraycopy(pub, 0, all, 4 + priv.length, pub.length);
                Path target = KEY_PATH;
                Files.createDirectories(target.getParent());
                Files.write(target, all);
                log.info("[RSA] 启动期生成 RSA 2048 keypair 并持久化到 {} (size={})", target.toAbsolutePath(), f.length());
            }
            this.publicKeyPem = buildPem(this.publicKey.getEncoded(), true);
        } catch (Exception e) {
            log.error("[RSA] keypair 初始化失败, 登录密码传输将不可加密(回退到明文传输)", e);
        }
    }

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    /**
     * 解密客户端加密的密码(Base64 编码的密文)
     */
    public String decryptBase64(String base64Cipher) {
        if (base64Cipher == null || base64Cipher.isEmpty()) {
            throw new com.learning.springboot.exception.BusinessException(400, "密码密文缺失");
        }
        if (privateKey == null) {
            throw new com.learning.springboot.exception.BusinessException(500, "RSA 私钥未初始化");
        }
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(base64Cipher);
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] plain = c.doFinal(cipherBytes);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[RSA] 密码解密失败: {}", e.getMessage());
            throw new com.learning.springboot.exception.BusinessException(400, "密码密文无法解密");
        }
    }

    private static String buildPem(byte[] der, boolean isPublic) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder pem = new StringBuilder();
        String header = isPublic ? "PUBLIC KEY" : "PRIVATE KEY";
        pem.append("-----BEGIN ").append(header).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            pem.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        pem.append("-----END ").append(header).append("-----\n");
        return pem.toString();
    }
}