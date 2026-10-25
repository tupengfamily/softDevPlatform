package com.learning.springboot.farm;

import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.farm.engine.ClimateModel;
import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.SuitabilityEngine;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 农艺目录服务（S3，从 {@code FarmPlayService} 抽出）。
 *
 * <p><b>抽的是什么</b>：建田选地页需要的**全部只读目录与评级** ——
 * 可选农业区列表、作物库、指定区+播期的适宜性评级、种植风险提示。
 *
 * <p><b>为什么这组最适合第一个抽</b>：
 * <ol>
 *   <li><b>零副作用</b>：不写库、不改内存状态，纯计算 + 缓存；</li>
 *   <li><b>依赖最少</b>：只需 {@link FarmPlotRepository}（算「今天」）+ 引擎；</li>
 *   <li><b>可纯单元测试</b>：不启 Spring 容器就能跑完 22 种作物 × 8 个区的评级；</li>
 *   <li><b>业务上独立</b>：它回答的是「哪里能种、什么时候种、种了会怎样」，
 *       与「这块地现在长到哪一步」是两条线。</li>
 * </ol>
 *
 * <p><b>与 {@code FarmPlayService} 的分工</b>：后者是「玩家这块地怎么样了」（读己方状态），
 * 本类是「农业知识是什么」（读公共知识）。前者依赖地块，后者依赖区域与播期。
 *
 * <p>📌 <b>口径纪律</b>：所有「今天」都必须走 {@link #userToday(Long)}（世界日期），
 * 绝不能直接用 {@code LocalDate.now()} —— 演示模式下玩家已推进过时间轴，
 * 用现实日期会算出「已经过去的推荐播期」。这是 2026-10-23 修过的坑，别退回。
 */
@Service
public class FarmCatalogService {

    /**
     * 用户级世界时钟（2026-10-26）：「今天」的唯一来源。
     *
     * <p>🔴 演示模式开关由 {@link FarmUserClockService} 自己持有（它决定推进量是否生效），
     * 本类不再重复持有一份 —— 两处各读一个开关最容易出现「一处认演示、一处不认」的口径分裂。
     */
    private final FarmUserClockService clock;

    /**
     * 评级结果按「区 + 作物 + 播期」缓存。
     *
     * <p>为什么值得缓存：单次评级要跑 10 年回放，约 40ms；
     * 建田页会同时请求「区域列表」和「评级」，两者的 (区, 播期) 组合大量重叠。
     */
    private final Map<String, SuitabilityEngine.Report> suitCache = new ConcurrentHashMap<>();

    public FarmCatalogService(FarmUserClockService clock) {
        this.clock = clock;
    }

    // ==================================================================
    //  「今天」的口径（世界日期）
    // ==================================================================

    /**
     * 用户级「今天」= 世界日期。
     *
     * <p>🔴 2026-10-23 修复：<b>播期/推荐日期的一切「今天」都必须走这里</b>。
     *
     * <p>原来的 bug：{@code zones()} / {@code plantingRisk()} 用 {@code LocalDate.now()}（现实日期），
     * 而演示模式下玩家的世界日期已被推进（实测 2027-11-21，现实是 2026-10-23）。
     * 于是：
     * <pre>
     *   defaultSow(冬小麦, 东北, 现实今天 2026-10-23)
     *     → 9/20 距今 33 天 &gt; 20 天阈值 → 顺延到 2027-09-20
     *   玩家世界日期 2027-11-21 → 跟「2027-09-20」比 → 偏移 +62 天
     *   面板显示：推荐播期 2027-09-20、偏移 +62 天
     * </pre>
     * 推荐播期比「今天」早了 62 天 —— 玩家看到的是一个<b>已经过去</b>的推荐日期，
     * 点「回到推荐播期」还会把播期设到过去。
     *
     * <p>修法：改用世界日期做基准。2027-11-21 之后冬小麦的推荐播期
     * 应当是 2028-09-20（顺延到下一年），而不是 2027-09-20。
     *
     * <p>🔴 2026-10-26：改读 {@link FarmUserClockService}（用户级时钟），
     * 不再从地块反推「最靠后的那个」——那会被无关地块的偏移污染。
     */
    public LocalDate userToday(Long userId) {
        if (userId == null) {
            return LocalDate.now();
        }
        return clock.today(userId);
    }

    // ==================================================================
    //  适宜性评级
    // ==================================================================

    /** 指定区 + 播期的评级（默认水稻）。 */
    public SuitabilityEngine.Report suitabilityOf(String zoneId, LocalDate sow) {
        return suitabilityOf(zoneId, sow, "rice_mid");
    }

    /** 指定区 + 播期 + 作物的评级。 */
    public SuitabilityEngine.Report suitabilityOf(String zoneId, LocalDate sow, String cropId) {
        String z = (zoneId == null || zoneId.isBlank()) ? "jiangnan" : zoneId;
        String c = cropId == null || cropId.isBlank() ? "rice_mid" : cropId;
        return suitCache.computeIfAbsent(z + ":" + c + ":" + sow,
                k -> SuitabilityEngine.evaluate(z, sow, CropCatalog.get(c).config()));
    }

    /** 指定区 + 播期的完整评级报告（建田页随播期拖动实时刷新）。 */
    public SuitabilityEngine.Report suitability(String zoneId, LocalDate sow) {
        return suitability(zoneId, sow, "rice_mid");
    }

    /** 指定区 + 播期 + 作物的完整评级报告。 */
    public SuitabilityEngine.Report suitability(String zoneId, LocalDate sow, String cropId) {
        if (!ClimateModel.zones().containsKey(zoneId)) {
            throw new BusinessException("未知农业区：" + zoneId);
        }
        return suitabilityOf(zoneId, sow, cropId);
    }

    // ==================================================================
    //  区域列表
    // ==================================================================

    /** 可选区域列表 + 各区在「推荐播期」下的水稻评级摘要，用于建田选地页。 */
    public List<Map<String, Object>> zones() {
        return zones(null, "rice_mid");
    }

    /** 可选区域列表（指定作物）。 */
    public List<Map<String, Object>> zones(String cropId) {
        return zones(null, cropId);
    }

    /**
     * 可选区域列表。
     *
     * @param userId 用户（决定「今天」用世界日期还是现实日期；null = 现实日期）
     */
    public List<Map<String, Object>> zones(Long userId, String cropId) {
        CropCatalog.Crop cc = CropCatalog.get(cropId);
        LocalDate today = userToday(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        // 先扫一遍找出该作物的最佳区（评级最低 = 最适），用于卡片打「最适区」徽标。
        // 判定基准与 /planting-risk 保持一致：各区在**同期推荐播期**下的评级。
        String bestZoneId = null;
        int bestLevel = 99;
        for (ClimateModel.Zone z : ClimateModel.zones().values()) {
            LocalDate rec = cc.sowWindow(z.id(), CropCatalog.defaultSow(cc.id(), z.id(), today),
                    today).recommended();
            SuitabilityEngine.Report r = suitabilityOf(z.id(), rec, cc.id());
            if (r.level() < bestLevel) {
                bestLevel = r.level();
                bestZoneId = z.id();
            }
        }
        for (ClimateModel.Zone z : ClimateModel.zones().values()) {
            ClimateModel.Stats st = ClimateModel.stats(z.id());
            // 🔴 2026-10-23：这里用 **nearestWindow**（距今最近的适合日期）而不是 defaultSow。
            //
            // 原因：这个 `sowDate` 是前端**直接填进日期选择器**的默认值。
            // defaultSow 是「这一季的代表播期」，允许早于今天（冬小麦江南 10/25，
            // 世界日期 2027-11-21 时就是 2027-10-25 —— 那一茬是上一季播的，
            // 现在正长着）。前端拿到一个过去的日期，日期选择器会显示成禁用态的值
            // （`disabledDate` 禁掉今天之前），玩家看着莫名其妙。
            //
            // 而「这一季还在长」这件事由地块自己的 sowDate 表达，不由这里表达。
            //
            // 🔴 2026-10-23 再修正（用户反馈「推荐窗口期应该是距今最近的适合日期」）：
            // nearestWindow 的锚点是「窗口末端还没过」的那次该月日 ——
            // 本期窗口尾巴还剩几天时用它（今天就能种），否则用下一季。
            // 比 nextSow 更贴近「玩家此刻能选什么」。
            CropCatalog.Crop.SowWindow near = cc.nearestWindow(z.id(), today);
            LocalDate rec = near.recommended();
            SuitabilityEngine.Report rep = suitabilityOf(z.id(), rec, cc.id());
            CropCatalog.Crop.SowWindow win = cc.sowWindow(z.id(), rec, today);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", z.id());
            m.put("cropId", cc.id());
            m.put("cropName", cc.name());
            m.put("name", z.name());
            m.put("site", z.site());
            m.put("region", z.region());
            m.put("lat", z.lat());
            m.put("open", z.open());
            m.put("seasonHint", z.seasonHint());
            m.put("dataSource", rep.dataSource());
            m.put("sowDate", rep.sowDate());
            m.put("level", rep.level());
            m.put("levelCode", rep.levelCode());
            m.put("levelName", rep.levelName());
            m.put("pPass", rep.pPass());
            m.put("samples", rep.samples());
            m.put("yieldBandLo", rep.yieldBandLo());
            m.put("yieldBandHi", rep.yieldBandHi());
            m.put("yieldMedian", rep.yieldMedian());
            m.put("primaryFactor", rep.primaryFactor());
            m.put("primaryFactorName", rep.primaryFactorName());
            m.put("sowCost", rep.sowCost());
            m.put("aatP50", Math.round(st.aatP50()));
            m.put("aatP10", Math.round(st.aatP10()));
            m.put("frostFreeP50", st.frostFreeP50());
            m.put("winterTminP50", st.winterTminP50());
            m.put("annualPrecip", st.annualPrecip());
            m.put("ph", z.ph());
            m.put("etcSeasonMm", z.etcSeasonMm());
            // ---- 风险提示用（2026-10-22）----
            m.put("isBestZone", z.id().equals(bestZoneId));
            m.put("bestZoneId", bestZoneId);
            m.put("sowWindowFrom", win.from().toString());
            m.put("sowWindowTo", win.to().toString());
            // 🔴 2026-10-23：额外给出「距今最近的适合窗口」——
            // sowWindowFrom/To 围绕的是**同期锚点**（可能落在过去），
            // 而这个是玩家此刻真正能选的那段日子。
            m.put("nearestWindowFrom", near.from().toString());
            m.put("nearestWindowTo", near.to().toString());
            out.add(m);
        }
        return out;
    }

    // ==================================================================
    //  种植风险
    // ==================================================================

    /**
     * 种植风险提示（2026-10-22）。
     *
     * <p>把「选错区」与「选错时机」<b>分开</b>报，各自给出针对性说明与可操作建议。
     * 数据全部来自十年回放：AREA = 与最佳区对比，TIMING = 与本区推荐播期对比，
     * STRUCTURE = 结构性硬闸。
     *
     * <p><b>不拦</b>（S23 §2.1）：任何区任何播期都能建，这只是把「大概率会发生什么」说清楚。
     */
    public SuitabilityEngine.PlantingRisk plantingRisk(String zoneId, LocalDate sow, String cropId) {
        return plantingRisk(null, zoneId, sow, cropId);
    }

    /**
     * 种植风险提示（带用户上下文）。
     *
     * <p>🔴 2026-10-23 修复：{@code today} 原来写死 {@code LocalDate.now()}，
     * 演示模式下会算出「已经过去的推荐播期」。改用世界日期。详见 {@link #userToday(Long)}。
     *
     * @param userId 用户（决定「今天」的口径；null = 现实日期）
     */
    public SuitabilityEngine.PlantingRisk plantingRisk(Long userId, String zoneId, LocalDate sow, String cropId) {
        String z = (zoneId == null || zoneId.isBlank()) ? "jiangnan" : zoneId;
        String c = (cropId == null || cropId.isBlank()) ? "rice_mid" : cropId;
        if (!ClimateModel.zones().containsKey(z)) {
            throw new BusinessException("未知农业区：" + z);
        }
        LocalDate today = userToday(userId);
        SuitabilityEngine.Report rep = suitabilityOf(z, sow, c);
        return SuitabilityEngine.plantingRisk(z, sow, CropCatalog.get(c), today, rep);
    }

    // ==================================================================
    //  作物库
    // ==================================================================

    /** 作物库：给建田页的作物选择器。 */
    public List<Map<String, Object>> crops() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CropCatalog.Crop c : CropCatalog.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("shortName", c.shortName());
            m.put("category", c.category());
            m.put("desc", c.desc());
            m.put("visual", c.config().visual());
            m.put("nNeed", c.config().nNeed());
            m.put("yieldRef", c.config().yieldRef());
            m.put("winterHardy", c.config().winterHardy());
            m.put("vernalizationDays", c.config().vernalizationDays());
            m.put("sowByZone", c.sowByZone().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, e -> e.getValue().toString(),
                            (a, b) -> a, LinkedHashMap::new)));
            // 播期窗口（相对推荐播期可早/可晚的天数）—— 风险提示的判定依据
            m.put("sowEarlyDays", c.earlyDays());
            m.put("sowLateDays", c.lateDays());
            out.add(m);
        }
        return out;
    }
}
