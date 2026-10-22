package com.learning.springboot.service;

import com.learning.springboot.entity.ApiKey;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.ApiKeyRepository;
import com.learning.springboot.security.SecretCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Key 配置服务
 *
 * <p><b>分层约定</b>:
 * <ul>
 *   <li>{@link #listAll()} —— 给管理界面, <b>只返回元数据 + 掩码</b>, 永远不含明文;</li>
 *   <li>{@link #getSecret(String)} —— 给后端业务代码取真实明文, 只在服务内部调用,
 *       不会出现在任何 Controller 响应里;</li>
 *   <li>业务侧读密钥时优先走「数据库 → 环境变量 → null」的回退链,
 *       这样管理员在界面里配的 key 立刻生效, 同时保留环境变量作为部署期覆盖手段。</li>
 * </ul>
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    /** 同���把 key 的 lastUsedTime 最短回写间隔(分钟) —— 见 {@link #touchLastUsed(ApiKey)} */
    private static final long LAST_USED_MIN_INTERVAL_MINUTES = 5L;

    /** 每个 keyCode 上次回写 lastUsedTime 的时间戳(毫秒), 用于节流 */
    private final Map<String, Long> lastTouchAt = new ConcurrentHashMap<>();

    private final ApiKeyRepository repo;
    private final SecretCryptoService crypto;

    public ApiKeyService(ApiKeyRepository repo, SecretCryptoService crypto) {
        this.repo = repo;
        this.crypto = crypto;
    }

    /** 列表视图: 元数据 + 掩码, 无明文 */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiKey k : repo.findAll()) out.add(toView(k));
        return out;
    }

    /** 分组视图: 按 provider 归拢, 便于界面分区展示 */
    public Map<String, List<Map<String, Object>>> listGrouped() {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (ApiKey k : repo.findAll()) {
            String p = (k.getProvider() == null || k.getProvider().isBlank()) ? "other" : k.getProvider();
            grouped.computeIfAbsent(p, x -> new ArrayList<>()).add(toView(k));
        }
        return grouped;
    }

    /**
     * 取明文密钥(仅供后端业务内部使用)。
     *
     * <p>取不到时返回 {@code null}, 由调用方决定回退策略。
     *
     * <p><b>关键约定：更新 lastUsedTime 失败绝不影响取密钥本身。</b>
     * 早期实现把「解密 + 回写时间戳」放在同一个 try 里, 一旦回写撞上 SQLite 写锁
     * (SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT, 瓦片并发加载时高频发生) 就会连累解密结果一起作废,
     * 对外表现为「key 未配置」——一个完全误导的结论。解密成功后必须先把明文返回,
     * 时间戳只是审计信息, 写不进去就下次再写。
     */
    public String getSecret(String keyCode) {
        if (keyCode == null || keyCode.isBlank()) return null;
        Optional<ApiKey> found = repo.findByKeyCode(keyCode.trim());
        if (found.isEmpty()) return null;
        ApiKey k = found.get();
        if (k.getEnabled() == null || k.getEnabled() != 1) return null;
        if (k.getSecretValue() == null || k.getSecretValue().isBlank()) return null;
        String plain;
        try {
            plain = crypto.decrypt(k.getSecretValue());
        } catch (Exception e) {
            // 不打印密文, 也不打印明文
            log.error("[api-key] 解密失败(keyCode={}): {}", k.getKeyCode(), e.getMessage());
            return null;
        }
        // 时间戳回写是「尽力而为」: 失败只记日志, 不能影响上面已经拿到的明文
        touchLastUsed(k);
        return plain;
    }

    /**
     * 回写「最近使用时间」。
     *
     * <p>单独 try 包裹: SQLite 单写模型下, 并发读密钥时这个 update 很容易撞上写锁。
     * 它的唯一作用是审计「这把 key 最近什么时候被用过」, 失败没有任何业务影响,
     * 因此绝不能把异常往上抛。
     *
     * <p><b>节流</b>: 瓦片加载会并发读同一把 key, 每读一次就写一次库等于自己制造写锁竞争。
     * 这里限制同一把 key 在 {@link #LAST_USED_MIN_INTERVAL_MINUTES} 分钟内最多回写一次,
     * 审计精度降到「分钟级」, 换��写锁压力大幅下降。
     */
    private void touchLastUsed(ApiKey k) {
        try {
            long now = System.currentTimeMillis();
            Long last = lastTouchAt.get(k.getKeyCode());
            if (last != null && now - last < LAST_USED_MIN_INTERVAL_MINUTES * 60_000L) {
                return; // 节流窗口内, 跳过本次回写
            }
            repo.touchLastUsed(k.getId(), LocalDateTime.now());
            lastTouchAt.put(k.getKeyCode(), now);
        } catch (Exception e) {
            log.debug("[api-key] 回写 lastUsedTime 失败(keyCode={}), 忽略: {}",
                    k.getKeyCode(), e.getMessage());
        }
    }

    /**
     * 轻量判断: 库里有没有「启用且已配密钥」的记录。
     *
     * <p>不解密、不更新 lastUsedTime —— 用于 status 这类高频探活接口。
     */
    public boolean hasUsable(String keyCode) {
        if (keyCode == null || keyCode.isBlank()) return false;
        return repo.findByKeyCode(keyCode.trim())
                .map(k -> k.getEnabled() != null && k.getEnabled() == 1
                        && k.getSecretValue() != null && !k.getSecretValue().isBlank())
                .orElse(false);
    }

    /**
     * 业务读取密钥: 数据库优先, 回退到外部提供的默认值(通常是环境变量)。
     *
     * @param keyCode     业务键
     * @param envFallback 环境变量/配置里的兜底值
     */
    public String resolveSecret(String keyCode, String envFallback) {
        String fromDb = getSecret(keyCode);
        if (fromDb != null && !fromDb.isBlank()) return fromDb;
        if (envFallback != null && !envFallback.isBlank()) return envFallback.trim();
        return null;
    }

    /** 新增(管理员) */
    @Transactional
    public Map<String, Object> create(String keyCode, String displayName, String provider,
                                      String secret, String hint, Boolean enabled, String operator) {
        String code = requireCode(keyCode);
        if (repo.existsByKeyCode(code)) {
            throw new BusinessException("密钥标识已存在: " + code);
        }
        ApiKey k = new ApiKey();
        k.setKeyCode(code);
        k.setDisplayName(displayName == null || displayName.isBlank() ? code : displayName.trim());
        k.setProvider(provider == null || provider.isBlank() ? null : provider.trim());
        k.setSecretHint(hint == null ? null : hint.trim());
        k.setEnabled(enabled == null || enabled ? 1 : 0);
        k.setUpdateBy(operator);
        applySecret(k, secret);
        return toView(repo.save(k));
    }

    /**
     * 修改(管理员)。
     *
     * <p>secret 传 null / 空串表示「不改密钥本体」, 只改元数据——
     * 这样界面上的「保存备注」不会意外把密钥清空。
     */
    @Transactional
    public Map<String, Object> update(Long id, String displayName, String provider,
                                      String secret, String hint, Boolean enabled, String operator) {
        ApiKey k = repo.findById(id).orElseThrow(() -> new BusinessException("密钥不存在: " + id));
        if (displayName != null && !displayName.isBlank()) k.setDisplayName(displayName.trim());
        if (provider != null) k.setProvider(provider.trim().isEmpty() ? null : provider.trim());
        if (hint != null) k.setSecretHint(hint.trim().isEmpty() ? null : hint.trim());
        if (enabled != null) k.setEnabled(enabled ? 1 : 0);
        k.setUpdateBy(operator);
        if (secret != null && !secret.isBlank()) {
            applySecret(k, secret);
        }
        return toView(repo.save(k));
    }

    /** 删除(管理员) */
    @Transactional
    public void delete(Long id) {
        ApiKey k = repo.findById(id).orElseThrow(() -> new BusinessException("密钥不存在: " + id));
        repo.delete(k);
    }

    /**
     * 探活: 用保存的凭据试调用一次, 让管理员知道「配了但用不了」还是「能用」。
     *
     * <p>当前只支持天地图这一种可探测的服务; 不支持的类型返回 UNKNOWN。
     */
    public Map<String, Object> test(Long id) {
        ApiKey k = repo.findById(id).orElseThrow(() -> new BusinessException("密钥不存在: " + id));
        String code = k.getKeyCode();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("keyCode", code);
        if (!code.startsWith("tianditu.")) {
            res.put("status", "UNKNOWN");
            res.put("message", "该服务暂不支持在线探活");
            return res;
        }
        if (k.getEnabled() == null || k.getEnabled() != 1) {
            res.put("status", "DISABLED");
            res.put("message", "密钥已停用");
            return res;
        }
        String secret = getSecret(code);
        if (secret == null) {
            res.put("status", "NO_SECRET");
            res.put("message", "没有可用的密钥明文");
            return res;
        }
        // 天地图服务端 key 直接打一次 status 接口验证
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.tianditu.gov.cn/v2/search?postStr=%7B%22keyWord%22%3A%22%E5%8C%97%E4%BA%AC%22%2C%22level%22%3A%2212%22%2C%22mapBound%22%3A%2273.66%2C3.86%2C135.05%2C53.55%22%2C%22queryType%22%3A%221%22%2C%22start%22%3A%220%22%2C%22count%22%3A%221%22%7D&type=query&tk=" + secret))
                    .header("User-Agent", "SpringBootApiKeyProbe/1.0")
                    .GET().build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                res.put("status", "OK");
                res.put("message", "凭据可用");
            } else {
                res.put("status", "FAIL");
                res.put("message", "上游返回 HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            res.put("status", "ERROR");
            res.put("message", "探测失败: " + e.getMessage());
        }
        return res;
    }

    private void applySecret(ApiKey k, String secret) {
        if (secret == null || secret.isBlank()) {
            // 新增时没给密钥 → 允许(可能先建占位再补)
            k.setSecretValue(null);
            k.setSecretMask(null);
            return;
        }
        String plain = secret.trim();
        k.setSecretValue(crypto.encrypt(plain));
        k.setSecretMask(SecretCryptoService.mask(plain));
    }

    private static String requireCode(String keyCode) {
        if (keyCode == null || keyCode.trim().isEmpty()) {
            throw new BusinessException("密钥标识不能为空");
        }
        String code = keyCode.trim();
        // 标识会拼进日志和 URL, 限制字符集, 避免注入 / 路径穿越
        if (!code.matches("^[A-Za-z0-9._-]{2,100}$")) {
            throw new BusinessException("密钥标识只能包含字母、数字、点、下划线和中划线(2~100 字符)");
        }
        return code;
    }

    /** 视图对象: 只含元数据与掩码, 不含密文更不含明文 */
    private static Map<String, Object> toView(ApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("keyCode", k.getKeyCode());
        m.put("displayName", k.getDisplayName());
        m.put("provider", k.getProvider());
        m.put("secretMask", k.getSecretMask());
        m.put("secretHint", k.getSecretHint());
        m.put("enabled", k.getEnabled() != null && k.getEnabled() == 1);
        m.put("hasSecret", k.getSecretValue() != null && !k.getSecretValue().isBlank());
        m.put("lastUsedTime", k.getLastUsedTime());
        m.put("updateTime", k.getUpdateTime());
        m.put("createTime", k.getCreateTime());
        m.put("updateBy", k.getUpdateBy());
        return m;
    }
}
