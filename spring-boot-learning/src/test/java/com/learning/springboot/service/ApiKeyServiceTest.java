package com.learning.springboot.service;

import com.learning.springboot.entity.ApiKey;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.ApiKeyRepository;
import com.learning.springboot.security.SecretCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link ApiKeyService} 单元测试（Mockito 仓储替身，不依赖数据库）。
 *
 * <p>核心断言是<b>「明文绝不外泄」</b>：
 * <ul>
 *   <li>列表 / 分组视图里没有明文, 也没有密文;</li>
 *   <li>实体里存的是密文;</li>
 *   <li>明文只在服务内部的 {@code getSecret} 里能拿到。</li>
 * </ul>
 */
class ApiKeyServiceTest {

    private ApiKeyRepository repo;
    private ApiKeyService service;
    /** 仓储里「已保存」的实体, 模拟真实落库 */
    private final List<ApiKey> store = new ArrayList<>();
    private Long seq = 0L;

    @BeforeEach
    void setUp() throws Exception {
        repo = org.mockito.Mockito.mock(ApiKeyRepository.class);

        when(repo.save(any(ApiKey.class))).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            if (k.getId() == null) k.setId(++seq);
            store.removeIf(x -> x.getId().equals(k.getId()));
            store.add(k);
            return k;
        });
        when(repo.findByKeyCode(any())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return store.stream().filter(x -> x.getKeyCode().equals(code)).findFirst();
        });
        when(repo.existsByKeyCode(any())).thenAnswer(inv ->
                store.stream().anyMatch(x -> x.getKeyCode().equals(inv.getArgument(0))));
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(store));
        when(repo.findById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return store.stream().filter(x -> x.getId().equals(id)).findFirst();
        });
        org.mockito.Mockito.doAnswer(inv -> { store.remove(inv.getArgument(0)); return null; })
                .when(repo).delete(any(ApiKey.class));

        SecretCryptoService crypto = new SecretCryptoService();
        Field f = SecretCryptoService.class.getDeclaredField("masterKey");
        f.setAccessible(true);
        f.set(crypto, new javax.crypto.spec.SecretKeySpec(
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8), "AES"));
        service = new ApiKeyService(repo, crypto);
    }

    @Test
    @DisplayName("新增后实体里存的是密文, 不是明文")
    void storesCipherTextNotPlain() {
        String plain = "my-super-secret-key-value";
        service.create("demo.key", "演示密钥", "测试", plain, "备注", true, "admin");

        ApiKey stored = store.stream().filter(x -> x.getKeyCode().equals("demo.key")).findFirst().orElseThrow();
        assertNotNull(stored.getSecretValue());
        assertFalse(stored.getSecretValue().contains(plain));
        assertFalse(stored.getSecretValue().contains("super-secret"));
    }

    @Test
    @DisplayName("列表视图不含明文也不含密文")
    void listViewHasNoSecret() {
        String plain = "plaintext-should-never-appear";
        service.create("demo.key", "演示密钥", "测试", plain, "备注", true, "admin");

        List<Map<String, Object>> list = service.listAll();
        assertEquals(1, list.size());
        Map<String, Object> view = list.get(0);

        String json = view.toString();
        assertFalse(json.contains(plain));
        assertFalse(view.containsKey("secretValue"));
        assertFalse(view.containsKey("secret"));
        assertTrue(view.containsKey("secretMask"));
        assertTrue(view.get("secretMask").toString().contains("*"));
    }

    @Test
    @DisplayName("分组视图同样不含明文")
    void groupedViewHasNoSecret() {
        service.create("a.key", "A", "地图", "secret-a-value-1234", null, true, "admin");
        service.create("b.key", "B", "地图", "secret-b-value-1234", null, true, "admin");

        Map<String, List<Map<String, Object>>> grouped = service.listGrouped();
        assertTrue(grouped.containsKey("地图"));
        for (List<Map<String, Object>> items : grouped.values()) {
            for (Map<String, Object> v : items) {
                assertFalse(v.toString().contains("secret-a-value-1234"));
                assertFalse(v.toString().contains("secret-b-value-1234"));
            }
        }
    }

    @Test
    @DisplayName("getSecret 能取回明文, 且只对启用状态生效")
    void getSecretReturnsPlainWhenEnabled() {
        String plain = "retrieve-me-12345";
        service.create("demo.key", "演示", "测试", plain, null, true, "admin");
        assertEquals(plain, service.getSecret("demo.key"));

        service.update(store.stream().filter(x -> x.getKeyCode().equals("demo.key")).findFirst().orElseThrow().getId(),
                "演示", "测试", null, null, false, "admin");
        assertNull(service.getSecret("demo.key"));
    }

    @Test
    @DisplayName("resolveSecret 优先数据库, 回退环境变量")
    void resolveSecretPrefersDatabase() {
        service.create("demo.key", "演示", "测试", "from-db", null, true, "admin");
        assertEquals("from-db", service.resolveSecret("demo.key", "from-env"));
        assertEquals("from-env", service.resolveSecret("other.key", "from-env"));
        assertNull(service.resolveSecret("other.key", null));
    }

    @Test
    @DisplayName("修改时 secret 留空表示保留原值")
    void updateWithoutSecretKeepsOriginal() {
        String plain = "keep-me-original";
        service.create("demo.key", "演示", "测试", plain, "旧备注", true, "admin");
        Long id = store.stream().filter(x -> x.getKeyCode().equals("demo.key")).findFirst().orElseThrow().getId();

        service.update(id, "新名字", "新分组", null, "新备注", true, "admin2");

        assertEquals(plain, service.getSecret("demo.key"));
        ApiKey stored = store.stream().filter(x -> x.getKeyCode().equals("demo.key")).findFirst().orElseThrow();
        assertEquals("新名字", stored.getDisplayName());
        assertEquals("新备注", stored.getSecretHint());
    }

    @Test
    @DisplayName("修改时传新 secret 会覆盖旧值")
    void updateWithSecretReplaces() {
        service.create("demo.key", "演示", "测试", "old-value", null, true, "admin");
        Long id = store.stream().filter(x -> x.getKeyCode().equals("demo.key")).findFirst().orElseThrow().getId();

        service.update(id, "演示", "测试", "new-value", null, true, "admin");
        assertEquals("new-value", service.getSecret("demo.key"));
    }

    @Test
    @DisplayName("重复标识报错")
    void duplicateKeyCodeRejected() {
        service.create("demo.key", "演示", "测试", "v", null, true, "admin");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create("demo.key", "重复", "测试", "v2", null, true, "admin"));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("非法标识被拒(防注入/路径穿越)")
    void illegalKeyCodeRejected() {
        assertThrows(BusinessException.class,
                () -> service.create("../etc/passwd", "x", "y", "v", null, true, "admin"));
        assertThrows(BusinessException.class,
                () -> service.create("a b", "x", "y", "v", null, true, "admin"));
        assertThrows(BusinessException.class,
                () -> service.create("", "x", "y", "v", null, true, "admin"));
    }

    @Test
    @DisplayName("删除后取不到密钥")
    void deleteRemovesKey() {
        service.create("demo.key", "演示", "测试", "v", null, true, "admin");
        Long id = store.stream().filter(x -> x.getKeyCode().equals("demo.key")).findFirst().orElseThrow().getId();
        service.delete(id);
        assertNull(service.getSecret("demo.key"));
    }

    @Test
    @DisplayName("hasUsable 不解密也能判断可用性")
    void hasUsable() {
        service.create("demo.key", "演示", "测试", "v", null, true, "admin");
        assertTrue(service.hasUsable("demo.key"));
        assertFalse(service.hasUsable("nope.key"));
        assertFalse(service.hasUsable(null));
    }
}
