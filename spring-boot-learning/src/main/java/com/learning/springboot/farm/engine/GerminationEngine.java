package com.learning.springboot.farm.engine;

import java.util.List;

/**
 * 出苗（发芽）环节引擎（2026-10-22 新增）。
 *
 * <p><b>要解决的问题</b>：S23 §2.1 定了「任何区、任何时间都能种任何作物，系统不拦你」，
 * 但如果出苗环节缺失，玩家在海南种冬小麦、在东北种水稻，
 * 只要积温够就照样满产——<b>「不合规律」这件事没有任何实际反馈</b>。
 *
 * <p><b>本引擎的定位</b>：把「不适宜」翻译成**看得见的田间表现**，而不是弹窗警告：
 * <ul>
 *   <li><b>出苗率下降</b>——播期太冷 / 太热 / 太干 → 出苗率打折，直接反映为缺苗减产；</li>
 *   <li><b>苗期枯死</b>——出苗后遇极端低温（不耐寒作物）/ 高温 / 干旱 → 整季报废；</li>
 *   <li><b>越冬作物春化不足</b>——华南种冬小麦只长草不抽穗（SimEngine 已有，这里只做提示）。</li>
 * </ul>
 *
 * <p><b>模型口径</b>（与 SimEngine 严格分离，仿照「发育 vs 损伤」的分离原则）：
 * <ul>
 *   <li>只看<b>播种后的前 {@value #EMERGENCE_WINDOW} 天</b>（出苗窗口）；</li>
 *   <li>出苗率 = 各日「出苗适宜度」的乘积（乘法 → 任何一天严重不适都会显著拉低）；</li>
 *   <li>出苗后到「苗期结束」（DVS 0.15）的极端天气判枯死；</li>
 *   <li>全部用<b>日均温 / 日最低温 / 降水</b>三个量，不引入新的物理口径。</li>
 * </ul>
 *
 * <p><b>为什么不做「禁止」</b>：本引擎只产出数字（出苗率、枯死与否、原因文案），
 * 由调用方决定怎么表现。规则是「可以种，但会长成那样」。
 */
public final class GerminationEngine {

    /** 出苗窗口天数：播种后这么多天内累计出苗率。 */
    public static final int EMERGENCE_WINDOW = 12;

    /** 苗期观察窗：出苗窗口结束后，额外观察多少天判枯死。 */
    public static final int SEEDLING_WINDOW = 21;

    /** 出苗适宜的日均温下限（℃）。低于此温度种子不发芽。 */
    private static final double TMIN_EMERGE = 8.0;

    /** 出苗适宜的日均温上限（℃）。高于此温度种子受抑制。 */
    private static final double TMAX_EMERGE = 32.0;

    /** 出苗期最低降水需求（mm/日累计窗）。低于此值按缺水打折。 */
    private static final double WATER_NEED = 12.0;

    /** 枯死判定的日最低温（℃）。不耐寒作物低于此温度直接冻死。 */
    private static final double KILL_TMIN = 2.0;

    /** 枯死判定的日最高温（℃）。超过此温度（热死）苗。 */
    private static final double KILL_TMAX = 42.0;

    /** 枯死判定的连续无有效降水天数与阈值（mm/日）。 */
    private static final double DROUGHT_MM = 1.0;
    private static final int DROUGHT_DAYS = 12;

    /**
     * 出苗结果。
     *
     * @param rate        出苗率 0..1（1 = 全苗；0.3 = 缺苗七成）
     * @param emergedDays 出苗窗口内累计的有效积温天数（诊断用）
     * @param waterMm     出苗窗口内累计降水 mm
     * @param withered    是否苗期枯死（枯死后整季产量为 0）
     * @param failureType 枯死原因：FROST / HEAT / DROUGHT / null
     * @param note        人类可读说明（用于历史记录与界面提示）
     * @param cautions    风险提示列表（不阻断，只提醒）
     */
    public record Result(
            double rate,
            int emergedDays,
            double waterMm,
            boolean withered,
            String failureType,
            String note,
            List<String> cautions) {
    }

    private GerminationEngine() {
    }

    /**
     * 评估一季的出苗与苗期风险。
     *
     * @param days  播种日起的逐日天气（至少覆盖出苗窗口 + 苗期窗口）
     * @param crop  作物配置
     */
    public static Result evaluate(List<DayWeather> days, CropConfig crop) {
        List<String> cautions = new java.util.ArrayList<>();

        if (days == null || days.isEmpty()) {
            return new Result(1.0, 0, 0, false, null, null, cautions);
        }

        // —— ① 出苗窗口：逐日适宜度连乘 ——
        double rate = 1.0;
        int emergedDays = 0;
        double waterMm = 0;
        int n = Math.min(EMERGENCE_WINDOW, days.size());
        for (int i = 0; i < n; i++) {
            DayWeather d = days.get(i);
            double tmean = d.tmean();
            double tmin = d.tmin();
            waterMm += d.precip();

            // 温度适宜度：太冷 0（不发芽）；偏冷/偏热在 [0,1] 线性折减
            double fTemp;
            if (tmean < TMIN_EMERGE) {
                fTemp = 0.0;
            } else if (tmean > TMAX_EMERGE) {
                // 超温抑制：每超 1℃ 打 15% 折，最多打到 0.2
                fTemp = Math.max(0.2, 1.0 - (tmean - TMAX_EMERGE) * 0.15);
            } else {
                // 边缘带：8~12℃ 与 28~32℃ 略微打折，中间满分
                fTemp = 1.0;
                if (tmean < 12.0) {
                    fTemp = 0.55 + 0.45 * (tmean - TMIN_EMERGE) / 4.0;
                } else if (tmean > 28.0) {
                    fTemp = 1.0 - 0.8 * (tmean - 28.0) / 4.0;
                }
                // 极端低温日（接近 0℃）即使日均不低，也会伤苗
                if (tmin < TMIN_EMERGE - 4.0) {
                    fTemp *= 0.5;
                }
            }
            if (fTemp >= 0.99) {
                emergedDays++;
            }
            rate *= fTemp;

            // 积水风险：出苗期连续强降水会沤苗（稻麦类不耐沤）
            if (d.precip() > 60.0) {
                rate *= 0.92;
                cautions.add("出苗期有强降水（" + Math.round(d.precip()) + "mm），土壤过湿影响出苗");
            }
        }

        // —— ② 出苗期水分：累计降水不足按缺苗打折 ——
        if (waterMm < WATER_NEED) {
            double fWater = Math.max(0.15, waterMm / WATER_NEED);
            rate *= fWater;
            cautions.add("出苗期降水仅 " + Math.round(waterMm) + "mm（需 " + (int) WATER_NEED
                    + "mm 才有保障），出苗可能不整齐");
        }

        rate = Math.max(0.0, Math.min(1.0, rate));

        // —— ③ 苗期极端天气：判枯死 ——
        boolean withered = false;
        String failureType = null;
        String note = null;
        int dryRun = 0;
        int maxDryRun = 0;
        int seedlingEnd = Math.min(days.size(), n + SEEDLING_WINDOW);
        for (int i = n; i < seedlingEnd; i++) {
            DayWeather d = days.get(i);
            if (!crop.winterHardy() && d.tmin() <= KILL_TMIN) {
                withered = true;
                failureType = "FROST";
                note = "苗期遭遇 " + Math.round(d.tmin()) + "℃ 低温，幼苗冻死（该作物不耐寒）";
                break;
            }
            if (d.tmax() >= KILL_TMAX) {
                withered = true;
                failureType = "HEAT";
                note = "苗期遭遇 " + Math.round(d.tmax()) + "℃ 高温，幼苗被热死";
                break;
            }
            if (d.precip() < DROUGHT_MM) {
                dryRun++;
                maxDryRun = Math.max(maxDryRun, dryRun);
            } else {
                dryRun = 0;
            }
            if (maxDryRun >= DROUGHT_DAYS) {
                withered = true;
                failureType = "DROUGHT";
                note = "苗期连续 " + maxDryRun + " 天无有效降水，幼苗旱死";
                break;
            }
        }

        // —— ④ 提示 ——
        if (!withered) {
            if (rate < 0.6) {
                cautions.add("出苗率仅 " + Math.round(rate * 100) + "%，缺苗明显，产量会相应打折");
            } else if (rate < 0.85) {
                cautions.add("出苗率 " + Math.round(rate * 100) + "%，有一定缺苗");
            }
            if (crop.vernalizationDays() > 0) {
                cautions.add("该品种需春化 " + crop.vernalizationDays()
                        + " 天（0–7℃），暖地可能只长草不抽穗");
            }
        }

        return new Result(rate, emergedDays, waterMm, withered, failureType, note, cautions);
    }
}
