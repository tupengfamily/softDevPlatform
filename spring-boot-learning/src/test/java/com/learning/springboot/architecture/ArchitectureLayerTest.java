package com.learning.springboot.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构分层边界测试（S1）。
 *
 * <p><b>为什么要有这个</b>：本项目走「模块化单体（Modular Monolith）」路线 —— 不拆进程，
 * 但要把代码边界切干净，将来真要拆服务时能无缝拉出。
 * 边界靠自觉必然腐化，所以写成可执行断言：<b>任何人都不能随手引入反向依赖</b>。
 *
 * <p><b>分层方向（依赖只能自上而下）</b>：
 * <pre>
 *   controller  →  service  →  repository  →  entity
 *                         ↘  dto
 *   横切:  common / util / exception / annotation / config / aop / security / bootstrap
 * </pre>
 *
 * <p><b>本类只放「零债务」硬规则</b>：现在就是绿的，违规直接红。
 * 尚未还清的技术债放在 {@link ArchitectureDebtTest}，用白名单豁免 + 规模守门逐步收紧。
 *
 * <p>📌 加新规则前先实测：某条规则当前违规数 &gt; 0 时不要写成硬规则，
 * 放进 {@code ArchitectureDebtTest} 走「白名单 + 不许增长」。
 *
 * @see ArchitectureDebtTest
 */
@AnalyzeClasses(packages = "com.learning.springboot",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureLayerTest {

    /** 项目内业务层包名集合（被底层包禁止依赖）。 */
    private static final String[] BUSINESS_LAYER = {
            "..service..", "..farm..", "..controller..", "..sandbox..", "..task..", "..dto.."
    };

    /**
     * R1 — {@code common} / {@code util} 是最底层的公共代码，**不许依赖项目内其它包**。
     *
     * <p>它们被所有层引用，一旦反向依赖就形成环。
     *
     * <p>注意：同包内的类互相引用（如 {@code UserAgentParser} 用自己的内部类 {@code Result}）是正常的，
     * 所以这里排除「源类与目标类同包」的情况。
     */
    @ArchTest
    static final ArchRule common_and_util_must_not_depend_on_project_packages =
            noClasses()
                    .that().resideInAnyPackage("com.learning.springboot.common..",
                            "com.learning.springboot.util..")
                    .should().dependOnClassesThat(
                            DescribedPredicate.describe("位于项目内其它包",
                                    javaClass -> javaClass.getPackageName().startsWith("com.learning.springboot")
                                            && !javaClass.getPackageName().startsWith("com.learning.springboot.common")
                                            && !javaClass.getPackageName().startsWith("com.learning.springboot.util")))
                    .because("common/util 是最底层公共代码，反向依赖会形成环。新的通用能力请放 common，"
                            + "但 common 不得 import 任何业务包。");

    /**
     * R2 — {@code entity} / {@code repository} 只做数据存取，**不得依赖业务层**。
     *
     * <p>否则 JPA 实体里会长出业务逻辑，重构时无法单独替换持久化实现。
     */
    @ArchTest
    static final ArchRule data_access_layer_must_not_depend_on_business_layer =
            noClasses()
                    .that().resideInAnyPackage("com.learning.springboot.entity..",
                            "com.learning.springboot.repository..")
                    .should().dependOnClassesThat().resideInAnyPackage(BUSINESS_LAYER)
                    .because("entity/repository 是数据存取层，依赖业务层会让持久化实现无法独立替换。");

    /**
     * R3 — {@code farm.engine} 是**纯计算引擎**：不依赖 Spring、不依赖任何业务/持久化包。
     *
     * <p>这是引擎能被纯单元测试（不启容器、几十毫秒跑完 22 种作物 × 多年回放）的前提。
     * 一旦引擎里出现 {@code @Service} 或 repository 调用，测试就得启 Spring，慢 50 倍且状态不可控。
     *
     * <p>实测基线：engine 共 18 个类，对外 import 只有 {@code java.*} 与 {@code jackson.databind.*}。
     */
    @ArchTest
    static final ArchRule farm_engine_must_not_depend_on_spring_or_business =
            noClasses()
                    .that().resideInAPackage("com.learning.springboot.farm.engine..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.learning.springboot.service..",
                            "com.learning.springboot.entity..",
                            "com.learning.springboot.repository..",
                            "com.learning.springboot.controller..",
                            "com.learning.springboot.dto..",
                            "com.learning.springboot.security..",
                            "com.learning.springboot.config..",
                            "com.learning.springboot.farm.FarmController",
                            "com.learning.springboot.farm.FarmPlayService",
                            "com.learning.springboot.farm.FarmWeather",
                            "com.learning.springboot.farm.FarmWeatherMock",
                            "com.learning.springboot.farm.FarmView")
                    .because("farm.engine 必须保持纯计算：注入 Spring 后单元测试要启容器，"
                            + "且引擎结果将不再可复现。需要 IO 时请在 service 层调用引擎。");

    /**
     * R4 — {@code exception} / {@code annotation} 是最轻量的横切标记，**不得依赖业务层**。
     */
    @ArchTest
    static final ArchRule exception_and_annotation_must_not_depend_on_business_layer =
            noClasses()
                    .that().resideInAnyPackage("com.learning.springboot.exception..",
                            "com.learning.springboot.annotation..")
                    .should().dependOnClassesThat().resideInAnyPackage(BUSINESS_LAYER)
                    .because("异常与注解会被全项目引用，依赖业务层会造成大范围耦合环。");

    /**
     * R5 — {@code security} 是横切鉴权层，**不得依赖具体业务包**。
     *
     * <p>它要被所有业务包引用，一旦反向依赖某个业务包，其它业务包再依赖 security 就成环。
     * 业务侧需要的安全能力请暴露成接口，由业务包反向依赖 security 的**接口**。
     */
    @ArchTest
    static final ArchRule security_must_not_depend_on_business_packages =
            noClasses()
                    .that().resideInAPackage("com.learning.springboot.security..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.learning.springboot.farm..",
                            "com.learning.springboot.controller..",
                            "com.learning.springboot.service..",
                            "com.learning.springboot.dto..")
                    .because("security 是横切层，被所有业务包引用，反向依赖会形成环。");

    /**
     * R6 — {@code sandbox}（代码执行沙箱）是独立能力，**不得依赖其它业务包**。
     *
     * <p>沙箱把用户提交的代码丢进子进程跑，与农场/文档等业务完全无关。
     */
    @ArchTest
    static final ArchRule sandbox_must_not_depend_on_other_business_packages =
            noClasses()
                    .that().resideInAPackage("com.learning.springboot.sandbox..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.learning.springboot.farm..",
                            "com.learning.springboot.controller..",
                            "com.learning.springboot.service..",
                            "com.learning.springboot.repository..",
                            "com.learning.springboot.entity..",
                            "com.learning.springboot.dto..",
                            "com.learning.springboot.config..")
                    .because("sandbox 是独立可复用能力，沾上业务包就无法单独抽出。");

    /**
     * R7 — {@code service} 不得依赖 {@code controller}（反向依赖）。
     *
     * <p>Controller 是入站适配器，Service 是业务层。业务层回调控制器意味着 HTTP 层与业务层
     * 互相渗透，模块边界随之失效。
     */
    @ArchTest
    static final ArchRule service_must_not_depend_on_controller =
            noClasses()
                    .that().resideInAPackage("com.learning.springboot.service..")
                    .should().dependOnClassesThat().resideInAPackage("com.learning.springboot.controller..")
                    .because("Service 是业务层，Controller 是入站适配器；业务层反向依赖控制器会破坏分层。");

    /**
     * R8 — {@code farm.engine} 不得依赖 {@code farm} 包内的**非 engine** 类。
     *
     * <p>方向必须是「外层调用引擎」，引擎不知道外层存在。反过来会让引擎被业务逻辑污染，
     * 最终退化成第二个 {@code FarmPlayService}。
     *
     * <p>实测基线：engine 18 个类对项目内依赖数为 <b>0</b>（只 import {@code java.*} 与 {@code jackson.*}）。
     */
    @ArchTest
    static final ArchRule farm_engine_must_not_depend_on_farm_outer_layer =
            noClasses()
                    .that().resideInAPackage("com.learning.springboot.farm.engine..")
                    .should().dependOnClassesThat(
                            DescribedPredicate.describe("位于 farm 包但不在 engine 子包内",
                                    javaClass -> javaClass.getPackageName().startsWith("com.learning.springboot.farm")
                                            && !javaClass.getPackageName().equals("com.learning.springboot.farm.engine")))
                    .because("engine 是被调用的纯计算层，外层依赖内层是正确方向；反向会让引擎被业务污染。");

    /** 供 {@link ArchitectureDebtTest} 复用的类导入（排除测试类自身）。 */
    static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.learning.springboot");
    }
}
