package com.learning.springboot.security;

import com.learning.springboot.config.OnlyOfficeProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OnlyOffice Document Server JWT 工具.
 * <p>
 * 与项目自身的 JwtUtil / JwtAuthFilter 隔离,原因:
 * <ul>
 *   <li>密钥不同(OO 容器单独配)</li>
 *   <li>载荷结构不同(OO 用 nested payload)</li>
 *   <li>Header 名默认是 Authorization(可改),我们默认 Bearer 已占用</li>
 * </ul>
 * <p>
 * OnlyOffice 期望的 JWT 形如:
 * <pre>
 * {
 *   "payload": {
 *     "document": { "fileType": "docx", "key": "...", "url": "http://spring/wopi/.../contents" },
 *     "editorConfig": { "user": { "id": "u-1", "name": "admin" }, "mode": "edit" }
 *   }
 * }
 * </pre>
 * 头: alg=HS256, typ=JWT (容器会验证).
 *
 * <p>WOPI callback 的 JWT 也走这里校验,确保请求来自合法的 OO 容器.
 */
@Component
public class OnlyOfficeJwtUtil {

    @Autowired private OnlyOfficeProperties props;

    private SecretKey signKey() {
        String s = props.getJwtSecret();
        if (s == null || s.isEmpty()) {
            throw new IllegalStateException("onlyoffice.jwt-secret 未配置");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException ex) {
            raw = s.getBytes(StandardCharsets.UTF_8);
        }
        if (raw.length < 32) {
            throw new IllegalStateException("onlyoffice.jwt-secret 至少 32 字节");
        }
        return Keys.hmacShaKeyFor(raw);
    }

    /** 生成给前端用的 JWT (iframe src 用) */
    public String generateForClient(Map<String, Object> payload) {
        if (!props.isJwtEnabled()) return "";
        return Jwts.builder()
                .header().add("typ", "JWT").and()
                .claims(payload)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))  // 1h
                .signWith(signKey(), Jwts.SIG.HS256)
                .compact();
    }

    /** 校验 Document Server 回调过来的 JWT */
    public boolean validate(String token) {
        if (!props.isJwtEnabled()) return true;  // 没启用时不做校验
        if (token == null || token.isEmpty()) return false;
        try {
            Jwts.parser().verifyWith(signKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException ex) {
            return false;
        }
    }

    /** 校验并返回 payload,失败抛异常 */
    public Map<String, Object> parseRequired(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(signKey()).build().parseSignedClaims(token).getPayload();
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : claims.entrySet()) {
                out.put(e.getKey(), e.getValue());
            }
            return out;
        } catch (JwtException ex) {
            throw new IllegalArgumentException("Invalid OnlyOffice JWT: " + ex.getMessage());
        }
    }
}
