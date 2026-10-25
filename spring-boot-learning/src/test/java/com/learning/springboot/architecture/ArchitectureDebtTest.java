package com.learning.springboot.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 架构技术债基线测试（S1）。
 *
 * <p><b>与 {@link ArchitectureLayerTest} 的分工</b>：
 * <ul>
 *   <li>LayerTest —— 「零债务」硬规则，现在就是绿的，违规直接红；</li>
 *   <li>DebtTest —— 「已知债务」豁免清单，<b>只允许减少，不许增长</b>。</li>
 * </ul>
 *
 * <p><b>为什么不用 ArchUnit freezing</b>：freezing 需要额外提交一个 {@code archunit_store}
 * 基线文件到版本库，而本项目的债务清单「具体到类名 + 具体到依赖目标」，
 * 写在测试代码里更直观、diff 更清晰、review 时能直接看出「谁新增了违规」。
 *
 * <p><b>守门逻辑</b>：
 * <ol>
 *   <li>清单外的违规 → <b>直接失败</b>（新增债务）；</li>
 *   <li>清单里已不存在的条目 → <b>失败</b>（债务已还清，该删条目了）。</li>
 * </ol>
 *
 * <p>第 2 条是这套机制的关键：**债务只能被清零，不能被遗忘**。
 * 还清一条就从豁免清单里删掉 —— 有人必须记得改，否则 CI 会红。
 *
 * <p>实现说明：这里不用「解析 ArchUnit 失败报告文本」的方式拿违规，
 * 而是用领域 API 直接枚举 {@code JavaClass.getDirectDependenciesFromSelf()}，
 * 得到的「源类 → 目标类」二元组精确无歧义，不受报告格式变动影响。
 */
class ArchitectureDebtTest {

    private static final JavaClasses CLASSES = ArchitectureLayerTest.importProductionClasses();

    // ==================================================================
    //  豁免清单：已知的技术债。每条 = 「源类名 -> 目标类名」
    //  修好一条就从这里删掉（跑测试会验证第 2 条守门提醒你）。
    // ==================================================================

    /**
     * DEBT-A：controller 直连 repository / entity（30 处）。
     *
     * <p>📌 这份清单由 ArchUnit <b>字节码</b>口径生成（{@code getDirectDependenciesFromSelf()}），
     * 不是靠 grep import 文本 —— 后者会漏掉非 import 形式的依赖（如继承、全限定名引用）。
     */
    private static final Set<String> CONTROLLER_DIRECT_PERSISTENCE = Set.of(
            "AuthController -> UserRepository",
            "MyDocController -> MyDocDirectory",
            "MyDocController -> MyDocFile",
            "OnboardingController -> LearningChapter",
            "OnboardingController -> LearningChapterRepository",
            "OnboardingController -> LearningLesson",
            "OnboardingController -> LearningLessonRepository",
            "OnboardingController -> LearningSubject",
            "OnboardingController -> LearningSubjectRepository",
            "OnboardingController -> UserPreference",
            "OnboardingController -> UserPreferenceRepository",
            "OnboardingController -> UserRepository",
            "OnlyOfficeController -> MyDocFile",
            "OnlyOfficeController -> User",
            "OnlyOfficeController -> UserRepository",
            "OperationController -> OperationLog",
            "OperationController -> OperationLogRepository",
            "OperationController -> PageError",
            "OperationController -> PageErrorRepository",
            "OperationController -> PagePerfRepository",
            "OperationController -> PageViewRepository",
            "PermissionController -> Permission",
            "PublicWikiController -> WikiArticle",
            "ReleaseNoteController -> ReleaseNote",
            "SystemSettingController -> SystemSetting",
            "UserDataController -> UserDataRecord",
            "WikiCollaboratorController -> WikiCollaborator",
            "WikiController -> WikiArticle",
            "WopiController -> MyDocFile",
            "WopiController -> MyDocFileRepository"
    );

    /** DEBT-B：farm 模块反向依赖通用 service（2 处）。 */
    private static final Set<String> FARM_DEPENDS_ON_SERVICE = Set.of(
            "FarmPlayService -> GameService",
            "TianDiTuService -> ApiKeyService"
    );

    /**
     * DEBT-C：service 依赖 security 包的具体实现。
     *
     * <p>🔴 2026-10-25 扩充：原守门的 source 匹配用<b>精确包名</b>
     * {@code equals("...service")}，导致 {@code service.impl} 子包的 14 个类
     * <b>完全没被覆盖</b> —— 这是 S1 留下的盲区。改成 startsWith 后暴露出下面 6 条，
     * 逐条核实均为<b>合理且必须</b>的依赖：
     *
     * <ul>
     *   <li>{@code AuthServiceImpl -> JwtUtil / LoginRateLimiter / RsaKeyProvider}
     *       —— 认证服务天然要用这三件（签发 token、登录限流、口令 RSA 加密），
     *           这正是 security 包的核心用途，不是绕过边界；</li>
     *   <li>{@code ForumServiceImpl / SupplementServiceImpl / ReleaseNoteServiceImpl /
     *       MyDocService / PersonalCenterService -> HtmlSanitizer}
     *       —— 富文本写路径的<b>纵深防御</b>（防存储型 XSS）。HtmlSanitizer 是
     *           无状态纯函数工具类，依赖它不构成边界污染。</li>
     *   <li>{@code ApiKeyService -> SecretCryptoService}
     *       —— API Key 主密钥的加解密适配。</li>
     * </ul>
     */
    private static final Set<String> SERVICE_DEPENDS_ON_SECURITY = Set.of(
            "ApiKeyService -> SecretCryptoService",
            "AuthServiceImpl -> JwtUtil",
            "AuthServiceImpl -> LoginRateLimiter",
            "AuthServiceImpl -> RsaKeyProvider",
            "ForumServiceImpl -> HtmlSanitizer",
            "MyDocService -> HtmlSanitizer",
            "PersonalCenterService -> HtmlSanitizer",
            "ReleaseNoteServiceImpl -> HtmlSanitizer",
            "SupplementServiceImpl -> HtmlSanitizer"
    );

    /** DEBT-D：aop 切面调用 service（基础设施适配，登记在册但不算违规）。 */
    private static final Set<String> AOP_DEPENDS_ON_SERVICE = Set.of(
            "OperationLogAspect -> OperationLogService"
    );

    // ==================================================================
    //  守门测试
    // ==================================================================

    @Test
    @DisplayName("DEBT-A: controller 直连 persistence 的违规不得增长")
    void controller_direct_persistence_must_not_grow() {
        assertNoNewDebt(
                sourcePkg -> sourcePkg.equals("com.learning.springboot.controller"),
                targetPkg -> targetPkg.equals("com.learning.springboot.repository")
                        || targetPkg.equals("com.learning.springboot.entity"),
                CONTROLLER_DIRECT_PERSISTENCE,
                "DEBT-A");
    }

    @Test
    @DisplayName("DEBT-B: farm 反向依赖 service 的违规不得增长")
    void farm_depends_on_service_must_not_grow() {
        assertNoNewDebt(
                sourcePkg -> sourcePkg.equals("com.learning.springboot.farm"),
                targetPkg -> targetPkg.equals("com.learning.springboot.service"),
                FARM_DEPENDS_ON_SERVICE,
                "DEBT-B");
    }

    @Test
    @DisplayName("DEBT-C: service 依赖 security 实现类的违规不得增长")
    void service_depends_on_security_must_not_grow() {
        assertNoNewDebt(
                // 🔴 2026-10-25：原来用精确包名 equals(...service)，
                // 导致 service.impl 子包（ForumServiceImpl / SupplementServiceImpl /
                // ReleaseNoteServiceImpl 等 14 个类）**完全没被守门覆盖** ——
                // 它们依赖 HtmlSanitizer 却从不被检查，是 S1 留下的盲区。
                // 现改为 startsWith，覆盖 service 及其全部子包。
                sourcePkg -> sourcePkg.equals("com.learning.springboot.service")
                        || sourcePkg.startsWith("com.learning.springboot.service."),
                targetPkg -> targetPkg.equals("com.learning.springboot.security"),
                SERVICE_DEPENDS_ON_SECURITY,
                "DEBT-C");
    }

    @Test
    @DisplayName("DEBT-D: aop 切面调用 service 的违规不得增长")
    void aop_depends_on_service_must_not_grow() {
        assertNoNewDebt(
                sourcePkg -> sourcePkg.equals("com.learning.springboot.aop"),
                targetPkg -> targetPkg.equals("com.learning.springboot.service"),
                AOP_DEPENDS_ON_SERVICE,
                "DEBT-D");
    }

    // ==================================================================
    //  工具方法
    // ==================================================================

    /**
     * 通用守门：当前实际违规集合必须**恰好等于**豁免清单。
     *
     * <p>- 多出来的 → 新增债务，失败；
     * <p>- 少掉的 → 债务已还清却没从清单删除，失败（提醒清理）。
     */
    private void assertNoNewDebt(Predicate<String> sourceMatch,
                                 Predicate<String> targetMatch,
                                 Set<String> whitelist,
                                 String debtId) {
        Set<String> actual = new LinkedHashSet<>();
        for (JavaClass source : CLASSES) {
            if (!sourceMatch.test(source.getPackageName())) {
                continue;
            }
            for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                if (targetMatch.test(target.getPackageName())) {
                    actual.add(source.getSimpleName() + " -> " + target.getSimpleName());
                }
            }
        }

        Set<String> newDebt = new LinkedHashSet<>(actual);
        newDebt.removeAll(whitelist);

        Set<String> repaid = new LinkedHashSet<>(whitelist);
        repaid.removeAll(actual);

        if (newDebt.isEmpty() && repaid.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        if (!newDebt.isEmpty()) {
            msg.append("\n\n🔴 ").append(debtId).append(" 新增技术债（不允许增长）：");
            for (String d : newDebt) {
                msg.append("\n   + ").append(d);
            }
            msg.append("\n   若这是有意为之，请同步更新 ArchitectureDebtTest 的豁免清单并写明理由。");
        }
        if (!repaid.isEmpty()) {
            msg.append("\n\n🟢 ").append(debtId).append(" 债务已还清，请从豁免清单中删除：");
            for (String d : repaid) {
                msg.append("\n   - ").append(d);
            }
        }
        fail(msg.toString());
    }
}
