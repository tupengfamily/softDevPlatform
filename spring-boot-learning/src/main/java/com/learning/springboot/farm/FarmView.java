package com.learning.springboot.farm;

import com.learning.springboot.farm.insight.FarmInsightService;

import java.util.List;
import java.util.Map;

/**
 * 前端消费的农场视图。
 *
 * <p>字段刻意做成"可直接渲染"的形状：每个数都能直接变成一个 UI 元素，
 * 前端不再复算（前端复算 = 两份口径 = 迟早不一致）。
 */
public record FarmView(
        String worldDate,
        boolean demoMode,
        String weatherSource,
        PlotView plot,
        CropView crop,
        WeatherView weather,
        YieldView yield,
        List<PestView> pests,
        double pestLossPct,
        List<EventView> events,
        double disasterLossPct,
        SoilView soil,
        /** 杂草压力（2026-10-22 需求 O） */
        WeedView weed,
        List<OpView> chain,
        List<InputView> inputs,
        InputSummary inputSummary,
        int seasons,
        double bestYield,
        String lastHarvestDate,
        Double lastYield,
        List<String> todayChanges,
        List<Map<String, Object>> recentScores,
        /**
         * 科学解释层（2026-10-27 P1）。
         *
         * <p>🔴 <b>为什么放在 {@code FarmView} 里而不是单独一个端点</b>：
         * 农场实时面板本来就是「一次性拉全部当前状态」的地方（天气/土壤/产量/农事都在这儿），
         * 解释层是这些数据的<b>解读</b>，不是另一份数据。单独开端点会让前端多一次请求，
         * 而且两者可能来自不同时刻（面板显示「积温 80%」但解释说「已成熟」）。
         *
         * <p>🔴 <b>文案在后端</b>：本字段是结构化数据（conceptId + 四段文案 + metrics），
         * 英文界面由前端按 {@code conceptId} 查 i18n —— 与 {@code FarmAttribution} 同一模式。
         *
         * <p>🔴 <b>缺数据不编造</b>：拿不到引擎数据的概念<b>整条不出现在列表里</b>，
         * 空列表 = 本季没有可解释的概念（待播种地块就是这种情况）。
         */
        List<FarmInsightService.Insight> insights) {

    /** 地块与适区（S23）：建田信息 + 当前季的评级快照 */
    public record PlotView(
            String id,
            String cropId,
            String cropName,
            String visual,
            String name,
            String zoneId,
            String zoneName,
            String site,
            boolean chosen,
            String sowDate,
            int level,
            String levelCode,
            String levelName,
            double pPass,
            String dataSource,
            boolean demoMode) {
    }

    public record CropView(
            String cropId,
            String cropName,
            double dvs,
            String stage,
            /**
             * 物候段索引（0-based，指向 {@link #stageBounds} 里的某一段）。
             *
             * <p>🔴 **2026-10-26 新增**：这是「当前处于第几段物候」的**唯一权威口径**。
             *
             * <p><b>为什么必须由后端下发</b>：每种作物的物候段数与切点都不同
             * （水稻 7 段、冬小麦 7 段、玉米 6 段、其余 19 种均为 5 段），
             * 前端如果自己拿一套固定切点去切 DVS，等于**用水稻的口径去描述番茄**——
             * 番茄 DVS 0.45 的真实阶段是「开花坐果」，前端却会算成「拔节」。
             * 前端复算 = 两份口径 = 迟早不一致（这正是本字段要消灭的问题）。
             *
             * <p><b>取值范围</b>：0 .. (stageBounds.size() - 1)。
             * 未出苗（dvs ≤ 0）时为 -1，表示「还没有进入任何物候段」。
             */
            int stageIndex,
            /**
             * 该作物的物候段边界数组（每个元素是 [lo, hi] 两元素数组）。
             *
             * <p>🔴 **2026-10-26 新增**：前端的时间轴分段、3D 形态分段都以它为准。
             * 长度 = 该作物的物候段数，与 {@code stageIndex} 严格对应。
             */
            List<List<Double>> stageBounds,
            /**
             * 该作物的物候段中文名数组（与 {@link #stageBounds} 一一对应）。
             *
             * <p>🔴 **2026-10-26 新增**：前端时间轴要显示每段的名字。
             * 以前前端用一套写死的 key（seedling/tiller/dry/...）渲染时间轴 ——
             * 那是**水稻**的 7 段，套到番茄上会显示「晒田」这种番茄根本没有的阶段。
             *
             * <p>本字段把「每种作物自己的段名」下发下去，前端只负责翻译与排版。
             * 中文直接可用；英文界面由前端按段名查翻译表。
             */
            List<String> stageNames,
            String nextMilestone,
            int daysToNextMilestone,
            double aatCum,
            double aatReq,
            String headingDate,
            String matureDate,
            String sowDate,
            boolean harvestable,
            /**
             * 🔴 **2026-10-27 新增**：该作物的收割 DVS 阈值。
             *
             * <p>以前只有 {@code harvestable}（是/否），玩家看不到「还差多少」，
             * 也不知道**为什么**还不能收 —— 而这恰恰是农业科学模块最该讲清楚的事。
             * 现在把阈值本身下发，前端可显示「当前 DVS 0.75 / 成熟阈值 0.82」。
             *
             * <p>唯一真源是 {@code OpChain.harvestDvsOf(cropId)} —— 工序链的 HARVEST 步骤下界。
             */
            double harvestDvs,
            boolean frostStop,
            /** 出苗率 0..1（2026-10-22）。缺苗直接反映在产量上 */
            double germination,
            /** 苗期是否枯死（枯死则本季绝收） */
            boolean withered,
            /** 枯死/异常原因：FROST / HEAT / DROUGHT / null */
            String failureType,
            /** 异常说明（人类可读） */
            String failureNote) {
    }

    public record WeatherView(
            String date,
            double tmax,
            double tmin,
            double precip,
            int heatTier) {
    }

    /** 一个病虫害实例（UI 直接渲染，不再复算严重度） */
    public record PestView(
            String id,
            String name,
            String type,
            double severity,
            double incidence,
            boolean detected,
            String onsetDate,
            String status,
            String advice,
            String target) {
    }

    /** 一次灾害事件（UI 直接渲染） */
    public record EventView(
            String type,
            String name,
            String date,
            double intensity,
            double loss,
            String note) {
    }

    /** 工序链的一步（S10） */
    public record OpView(
            String id,
            String name,
            String group,
            double from,
            double to,
            boolean repeatable,
            String status,
            String date,
            double quality,
            int count,
            String hint) {
    }

    /** 一件农资的货架视图（S12） */
    public record InputView(
            String id,
            String name,
            String kind,
            String category,
            String unit,
            double doseMin,
            double doseOpt,
            double doseMax,
            double price,
            double nContent,
            String element,
            double dvsLo,
            double dvsHi,
            List<String> targets,
            List<String> targetNames,
            String modeGroup,
            int phiDays,
            int protectDays,
            String note,
            int usedTimes,
            double resistanceRisk,
            boolean inWindow,
            double power,
            boolean phiRisk,
            double costAtOpt) {
    }

    /** 本季投入汇总（S12） */
    public record InputSummary(
            double cost,
            int fertApplications,
            int pestApplications,
            double nApplied,
            double nNeed,
            int phiLeft,
            String lastSprayDate,
            double resistanceRisk,
            boolean phiViolated,
            double revenue,
            double net) {
    }

    /** 土壤水分与地力（S6） */
    public record SoilView(
            double waterMm,
            double rwc,
            String status,
            int dryDays,
            int waterlogDays,
            double sfi,
            double soilCoef,
            double droughtLoss,
            double waterlogLoss,
            double etToday,
            double percToday,
            double nApplied,
            double nNeed,
            double nSatisfaction) {
    }

    /** 杂草压力（2026-10-22 需求 O） */
    public record WeedView(
            double pressure,
            String level,
            String levelName,
            double lossPct,
            String onsetDate,
            int manualWeeds,
            int herbicideDays,
            String advice) {
    }

    public record YieldView(
            double panicles,
            double grainsPerPanicle,
            double fertility,
            double thousandGrainWeight,
            double estimatedYieldKgPerMu,
            double dmgPanicle,
            double dmgFloret,
            double dmgFertility,
            double dmgWeight) {
    }
}
