package com.learning.springboot.farm;

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
        List<Map<String, Object>> recentScores) {

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
            String nextMilestone,
            int daysToNextMilestone,
            double aatCum,
            double aatReq,
            String headingDate,
            String matureDate,
            String sowDate,
            boolean harvestable,
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
