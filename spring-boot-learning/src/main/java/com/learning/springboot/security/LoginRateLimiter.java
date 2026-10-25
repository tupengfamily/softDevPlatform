package com.learning.springboot.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流器(防暴力破解)
 * <p>
 * - 维度: 用户名 + 客户端 IP(任一维度超限都拒绝)
 * - 策略: 窗口期内(15 分钟)失败 5 次即锁定, 锁 5 分钟(缩短)
 * - 成功登录后只清零该<b>用户名</b>的失败计数(🔴 2026-10-26 修复 M11: 不再清 IP 维度,
 *   否则任意一次成功登录就能替攻击者抹掉整个 IP 的撞库进度)
 * - 注册等场景走通用次数限流({@code reg:ip:xxx} 前缀)
 * - 进程内内存实现(教学项目够用);多实例部署应换 Redis/数据库计数
 * - 内存 Map 设上限(5000 条): 超限先清理过期项,仍超限清理最老条目,防止垃圾键撑爆内存
 * - 启动时 @PostConstruct 清空所有计数(防止上次服务进程的"幽灵锁定"把用户锁到服务重启)
 * - 管理员可手动调 {@link #unlockUser(String)} 解锁特定用户
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /** 计数窗口: 15 分钟 */
    private static final long WINDOW_MS = 15 * 60 * 1000L;
    /** 窗口期内允许的最大失败次数 */
    private static final int MAX_FAILURES = 5;
    /** 失败 5 次后锁定时长: 5 分钟(原 15 分钟太长, 用户经常忘记密码就被永久锁) */
    private static final long LOCK_MS = 5 * 60 * 1000L;
    /** 注册场景: 窗口期内每 IP 最大次数 */
    private static final int MAX_REG_PER_WINDOW = 5;
    /** 内存 Map 条目上限(防垃圾键撑爆内存) */
    private static final int MAX_KEYS = 5000;

    /** key -> 失败记录 */
    private record Failure(int count, long firstAt, long lockedUntil) {
        boolean expired(long now) {
            return now - firstAt > WINDOW_MS && now > lockedUntil;
        }
    }

    /**
     * 启动时清空所有内存计数
     * <p>
     * 防止上次服务进程退出时仍处于锁定状态的用户, 在重启后仍被锁(他们要再等 15 分钟)
     */
    @jakarta.annotation.PostConstruct
    public void clearOnStart() {
        int n = failures.size() + attempts.size();
        failures.clear();
        attempts.clear();
        if (n > 0) log.info("[LoginRateLimiter] 启动清空 {} 条历史限流记录", n);
    }

    /**
     * 管理员手动解锁某用户
     * <p>
     * 删掉该用户名相关的所有锁定键;IP 维度不解锁(攻击者可能持续攻击, 解 IP 会让所有用户失防)
     */
    public int unlockUser(String username) {
        if (username == null) return 0;
        String key = "u:" + username.toLowerCase();
        return failures.remove(key) == null ? 0 : 1;
    }

    private final Map<String, Failure> failures = new ConcurrentHashMap<>();

    /** 通用次数计数: key -> [窗口起始时间, 窗口内次数] */
    private final Map<String, long[]> attempts = new ConcurrentHashMap<>();

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
     *
     * <p>🔴 2026-10-26 修复 M11：原实现是「读 → 算 → 写回」的<b>非原子读改写</b>：
     * <pre>{@code
     * Failure prev = failures.get(key);          // 读
     * int count = prev.count() + 1;              // 算
     * failures.put(key, new Failure(count, ...)); // 写
     * }</pre>
     * 50 个并发失败请求时，各自读到的都是同一个旧值 {@code prev}，
     * 写回时互相覆盖 → 计数只涨到 1~2，<b>限流形同虚设</b>。
     *
     * <p>现在改成 {@code ConcurrentHashMap.compute} 的原子读改写
     * （与同文件 {@link #checkRegisterAllowed} 的 {@code attempts.compute} 同一范式）。
     */
    public void recordFailure(String username, String clientIp) {
        long now = System.currentTimeMillis();
        for (String key : keys(username, clientIp)) {
            ensureCapacity(failures, now);
            failures.compute(key, (k, prev) -> {
                int count = (prev == null || prev.expired(now)) ? 1 : prev.count() + 1;
                long firstAt = (prev == null || prev.expired(now)) ? now : prev.firstAt();
                long lockedUntil = count >= MAX_FAILURES ? now + LOCK_MS : 0L;
                return new Failure(count, firstAt, lockedUntil);
            });
        }
    }

    /**
     * 登录成功: 清零该<b>用户名</b>的失败计数。
     *
     * <p>🔴 2026-10-26 修复 M11：原实现把 <b>IP 维度</b>的计数也一起清了。
     * 后果是：攻击者用一个账号疯狂撞库（IP 维度计数飙到锁定阈值），
     * 另一个正常用户只要登录成功一次，就把整个 IP 的失败计数清零 ——
     * 攻击者的进度被抹掉，限流形同虚设。
     *
     * <p>现在只清用户名维度（{@code u:}）。IP 维度保留，
     * 由窗口过期（15 分钟）或管理员 {@link #unlockUser} 处理。
     */
    public void recordSuccess(String username, String clientIp) {
        if (username == null) return;
        failures.remove("u:" + username.toLowerCase());
    }

    /**
     * 通用次数限流(注册等场景): 窗口期内每 IP 超限抛 429
     */
    public void checkRegisterAllowed(String clientIp) {
        String key = "reg:ip:" + (clientIp == null ? "" : clientIp);
        long now = System.currentTimeMillis();
        ensureCapacity(attempts, now);
        long[] w = attempts.compute(key, (k, prev) -> {
            if (prev == null || now - prev[0] > WINDOW_MS) {
                return new long[] { now, 1 };
            }
            prev[1]++;
            return prev;
        });
        if (w[1] > MAX_REG_PER_WINDOW) {
            throw new com.learning.springboot.exception.BusinessException(429, "操作过于频繁");
        }
    }

    private String[] keys(String username, String clientIp) {
        String u = username == null ? "" : username.toLowerCase();
        String ip = clientIp == null ? "" : clientIp;
        return new String[] { "u:" + u, "ip:" + ip };
    }

    /**
     * 容量保障: 超上限先清理过期项, 仍超限则清理最老条目腾位置
     */
    private void ensureCapacity(Map<String, ?> map, long now) {
        if (map.size() < MAX_KEYS) return;
        // 1. 先清理过期项
        map.entrySet().removeIf(e -> isExpired(e.getValue(), now));
        log.debug("[RateLimiter] 清理过期项后 size={}", map.size());
        // 2. 仍超限: 清理最老条目, 防止垃圾键撑爆内存
        while (map.size() >= MAX_KEYS) {
            String oldest = findOldest(map);
            if (oldest == null) break;
            map.remove(oldest);
            log.debug("[RateLimiter] 容量超限, 清理最老条目 key={}", oldest);
        }
    }

    /** 记录是否过期(兼容 Failure / [窗口起始, 次数] 两种值) */
    private boolean isExpired(Object v, long now) {
        if (v instanceof Failure f) return f.expired(now);
        if (v instanceof long[] arr) return now - arr[0] > WINDOW_MS;
        return true;
    }

    /** 找出最早写入的 key(用于容量超限时淘汰) */
    private String findOldest(Map<String, ?> map) {
        String oldest = null;
        long oldestAt = Long.MAX_VALUE;
        for (Map.Entry<String, ?> e : map.entrySet()) {
            long at;
            Object v = e.getValue();
            if (v instanceof Failure f) {
                at = f.firstAt();
            } else if (v instanceof long[] arr) {
                at = arr[0];
            } else {
                continue;
            }
            if (at < oldestAt) {
                oldestAt = at;
                oldest = e.getKey();
            }
        }
        return oldest;
    }
}
