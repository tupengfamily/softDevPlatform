package com.learning.springboot.security;

import com.learning.springboot.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JWT 工具类
 * <p>
 * - 生成 Token(含用户 ID、用户名、角色列表)
 * - 解析与校验 Token
 * - 从 HttpServletRequest 中提取 Bearer Token
 */
@Component
public class JwtUtil {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtUtil.class);

    @Autowired
    private JwtProperties props;

    private SecretKey key;

    @PostConstruct
    public void init() {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            // JWT_SECRET 未配置: 生成临时随机密钥(每次重启变化, 仅限本地开发)
            byte[] raw = new byte[32];
            new java.security.SecureRandom().nextBytes(raw);
            secret = Base64.getEncoder().encodeToString(raw);
            log.warn("JWT_SECRET 未配置,已生成临时随机密钥,重启后 token 失效,生产环境务必配置 JWT_SECRET");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(secret);
            this.key = Keys.hmacShaKeyFor(bytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT 密钥配置错误(需 Base64 编码且 >= 32 字节)", e);
        }
    }

    /**
     * 生成 Token
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param roles    角色编码列表
     * @return 签名的 JWT 字符串
     */
    public String generate(Long userId, String username, Collection<String> roles) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + props.getExpirationMillis());
        return Jwts.builder()
            .subject(username)
            .claim("uid", userId)
            .claim("roles", roles == null ? List.of() : List.copyOf(roles))
            .issuedAt(now)
            .expiration(exp)
            .signWith(key)
            .compact();
    }

    /**
     * 解析 Token
     *
     * @throws JwtException 签名错误或过期
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (DecodingException e) {
            throw new JwtException("Token 编码错误: " + e.getMessage(), e);
        }
    }

    /**
     * 校验 Token 是否有效
     */
    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    /**
     * 从请求中提取 Bearer Token
     *
     * @return 去除 "Bearer " 前缀的 token;无则返回 null
     */
    public String resolveToken(jakarta.servlet.http.HttpServletRequest req) {
        String bearer = req.getHeader(props.getHeader());
        if (bearer == null || bearer.isEmpty()) return null;
        String prefix = props.getPrefix();
        if (prefix != null && !prefix.isEmpty() && bearer.startsWith(prefix)) {
            return bearer.substring(prefix.length()).trim();
        }
        return bearer.trim();
    }

    /** 从 Claims 中提取角色集合 */
    @SuppressWarnings("unchecked")
    public Set<String> extractRoles(Claims claims) {
        Object raw = claims.get("roles");
        if (raw instanceof List<?> list) {
            Set<String> set = new HashSet<>();
            for (Object o : list) if (o != null) set.add(o.toString());
            return set;
        }
        return new HashSet<>();
    }

    /** 从 Claims 中提取用户 ID */
    public Long extractUserId(Claims claims) {
        Object uid = claims.get("uid");
        if (uid == null) return null;
        if (uid instanceof Number n) return n.longValue();
        return Long.parseLong(uid.toString());
    }
}