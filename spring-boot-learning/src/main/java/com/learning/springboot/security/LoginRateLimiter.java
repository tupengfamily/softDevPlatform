package com.learning.springboot.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流器(防暴力破解)
 * <p>
 * - 维度: 用户名 + 客户端 IP(任一维度超限都拒绝)
 * - 策略: 窗口期内(15 分钟)失败 5 次即锁定,锁定期内登录请求直接 429
 * - 成功登录后清零该用户名/该 IP 的失败计数
 * - 进程内内存实现(教学项目够用);多实例部署应换 Redis/数据库计数
 */
@Component
public class LoginRateLimiter {

    /** 窗口期: 15 分钟 */
    private static final long WINDOW_MS = 15 * 60 * 1000L;
    /** 窗口期内允许的最大失败次数 */
    private static final int MAX_FAILURES = 5;

    /** key -> 失败记录 */
    private record Failure(int count, long firstAt, long lockedUntil) {
        boolean expired(long now) {
            return now - firstAt > WINDOW_MS && now > lockedUntil;
        }
    }

    private final Map<String, Failure> failures = new ConcurrentHashMap<>();

    /**
     * 检查是否处于锁定期(是则抛 429)
     */
    public void checkAllowed(String username, String clientIp) {
        long now = System.currentTimeMillis();
        for (String key : keys(username, clientIp)) {
            Failure f = failures.get(key);
            if (f == null) continue;
            if (f.expired(now)) {
                failures.remove(key);
                continue;
            }
            if (now < f.lockedUntil()) {
                long waitSec = (f.lockedUntil() - now) / 1000 + 1;
                throw new com.learning.springboot.exception.BusinessException(
                    429, "登录失败次数过多,请 " + waitSec + " 秒后再试");
            }
        }
    }

    /**
     * 记录一次登录失败
     */
    public void recordFailure(String username, String clientIp) {
        long now = System.currentTimeMillis();
        for (String key : keys(username, clientIp)) {
            Failure prev = failures.get(key);
            int count = (prev == null || prev.expired(now)) ? 1 : prev.count() + 1;
            long firstAt = (prev == null || prev.expired(now)) ? now : prev.firstAt();
            long lockedUntil = count >= MAX_FAILURES ? now + WINDOW_MS : 0L;
            failures.put(key, new Failure(count, firstAt, lockedUntil));
        }
    }

    /**
     * 登录成功: 清零相关计数
     */
    public void recordSuccess(String username, String clientIp) {
        for (String key : keys(username, clientIp)) {
            failures.remove(key);
        }
    }

    private String[] keys(String username, String clientIp) {
        String u = username == null ? "" : username.toLowerCase();
        String ip = clientIp == null ? "" : clientIp;
        return new String[] { "u:" + u, "ip:" + ip };
    }
}
