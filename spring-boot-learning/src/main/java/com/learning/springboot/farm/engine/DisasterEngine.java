package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 灾害事件（S8 精简版）。
 *
 * <p>只做 v1.0 里**能从逐日气象可靠判定**的四类，不硬凑台风/冰雹（缺风速、缺逐时降水）：
 * <ul>
 *   <li><b>暴雨/山洪</b>：单日 ≥50mm 或 3 日合计 ≥100mm；水稻耐淹，耐性系数 0.4；</li>
 *   <li><b>连阴雨（烂场雨）</b>：连续 ≥5 天日降水 ≥5mm，成熟期 ×1.8（穗发芽）；</li>
 *   <li><b>低温冻害</b>：日最低温 ≤4℃，苗期 ×1.5；</li>
 *   <li><b>高温热浪</b>：连续 3 天 T_max ≥38℃，只做事件提示 —— 减产已由 S7 热害通道计算，<b>不重复扣</b>。</li>
 * </ul>
 *
 * <p>损失口径严格按 S8 §4.1：`base × 物候系数 × 作物耐性 × (1−应对减免)`，单次封顶 0.7。
 */
public final class DisasterEngine {

    private DisasterEngine() {
    }

    public record Event(
            String type,
            String name,
            String date,
            double intensity,
            double loss,
            String note
    ) {
    }

    public record Result(List<Event> events, double totalLoss) {
        public static final Result EMPTY = new Result(List.of(), 0);
    }

    private static final double RICE_TOLERANCE_FLOOD = 0.4;
    private static final double RICE_TOLERANCE_RAIN = 1.0;
    private static final double RICE_TOLERANCE_FREEZE = 1.2;

    /**
     * @param days     与生长同一份天气
     * @param startIdx 播种日下标
     * @param nDays    已生长天数
     * @param dvsTrace 逐日 DVS
     * @param drains   清沟排水（DRAIN）日期
     * @param irrigates 灌水调温（IRRIGATE）日期
     */
    public static Result simulate(List<DayWeather> days,
                                  int startIdx,
                                  int nDays,
                                  List<Double> dvsTrace,
                                  Set<LocalDate> drains,
                                  Set<LocalDate> irrigates) {
        return simulate(days, startIdx, nDays, dvsTrace, drains, irrigates, false, -99);
    }

    /**
     * @param winterHardy 能否越冬：冬小麦在 0℃ 上下是常态，不能按水稻的 4℃ 阈值报冻害
     * @param killTmin    越冬作物的致死温度（低于它才算事件）
     */
    public static Result simulate(List<DayWeather> days,
                                  int startIdx,
                                  int nDays,
                                  List<Double> dvsTrace,
                                  Set<LocalDate> drains,
                                  Set<LocalDate> irrigates,
                                  boolean winterHardy,
                                  double killTmin) {
        if (days == null || nDays <= 0 || dvsTrace == null) {
            return Result.EMPTY;
        }
        List<Event> events = new ArrayList<>();
        double total = 0;
        int rainStreak = 0;
        int heatStreak = 0;
        LocalDate lastFlood = null;

        for (int k = 0; k < nDays; k++) {
            int i = startIdx + k;
            if (i >= days.size()) {
                break;
            }
            DayWeather w = days.get(i);
            LocalDate date = LocalDate.parse(w.date());
            double dvs = k < dvsTrace.size() ? dvsTrace.get(k) : 0;

            // 连阴雨计数
            rainStreak = w.precip() >= 5 ? rainStreak + 1 : 0;
            // 热浪计数
            heatStreak = w.tmax() >= 38 ? heatStreak + 1 : 0;

            // ① 暴雨 / 山洪（同一场雨只报一次）
            boolean flood = w.precip() >= 50 || sum3(days, i) >= 100;
            if (flood && (lastFlood == null || date.toEpochDay() - lastFlood.toEpochDay() > 3)) {
                lastFlood = date;
                double intensity = clamp(w.precip() / 120.0, 0.35, 1.0);
                double stage = switch (stageKey(dvs)) {
                    case "SEEDLING" -> 1.3;
                    case "FILLING", "RIPE" -> 1.15;
                    default -> 1.0;
                };
                double mitigation = hasNear(drains, date, 1) ? 0.45 : 0.0;
                double loss = clamp(0.40 * intensity * stage * RICE_TOLERANCE_FLOOD * (1 - mitigation), 0, 0.7);
                events.add(new Event("FLASH_FLOOD", "暴雨山洪", date.toString(),
                        round(intensity), round(loss),
                        mitigation > 0 ? "已清沟排水，损失减轻" : "田间积水，需及时清沟排水"));
                total += loss;
            }

            // ② 连阴雨（连续 5 天起算，只报一次/段）
            if (rainStreak == 5) {
                double stage = dvs >= 0.92 ? 1.8 : 1.0;
                double loss = clamp(0.30 * 1.0 * stage * RICE_TOLERANCE_RAIN, 0, 0.7);
                events.add(new Event("CONTINUOUS_RAIN", "连阴雨", date.toString(), 1.0, round(loss),
                        stage > 1 ? "成熟期遇连阴雨，谷粒易穗发芽" : "连续阴雨，注意稻瘟病风险"));
                total += loss;
            }

            // ③ 低温冻害
            // 水稻/玉米/大豆：4℃ 以下即有寒害；越冬作物（冬小麦）只有低于致死温度才算事件，
            // 否则整个冬天会天天报"冻害"，把一季的减产额度吃光。
            double freezeAt = winterHardy ? killTmin : 4.0;
            if (w.tmin() <= freezeAt) {
                double intensity = clamp((freezeAt - w.tmin()) / (winterHardy ? 6.0 : 6.0), 0.25, 1.0);
                double stage = switch (stageKey(dvs)) {
                    case "SEEDLING" -> 1.5;
                    case "HEADING" -> 1.3;
                    default -> 1.0;
                };
                double mitigation = hasNear(irrigates, date, 1) ? 0.35 : 0.0;
                double loss = clamp(0.45 * intensity * stage * RICE_TOLERANCE_FREEZE * (1 - mitigation), 0, 0.7);
                events.add(new Event("FREEZE", "低温冻害", date.toString(), round(intensity), round(loss),
                        mitigation > 0 ? "已灌水调温，冻害减轻" : "夜间低温，可灌水调温"));
                total += loss;
            }

            // ④ 高温热浪（只做提示，减产走 S7 热害）
            if (heatStreak == 3) {
                events.add(new Event("HEATWAVE", "持续高温", date.toString(),
                        clamp((w.tmax() - 35) / 5.0, 0.4, 1.0), 0,
                        "连续 3 天 ≥38℃，热害减产已在产量三要素中体现"));
            }
        }

        return new Result(events, Math.min(0.60, total));
    }

    private static String stageKey(double dvs) {
        if (dvs < 0.16) return "SEEDLING";
        if (dvs < 0.41) return "TILLERING";
        if (dvs < 0.63) return "BOOTING";
        if (dvs < 0.72) return "HEADING";
        if (dvs < 0.92) return "FILLING";
        return "RIPE";
    }

    private static double sum3(List<DayWeather> days, int i) {
        double s = 0;
        for (int k = Math.max(0, i - 2); k <= i && k < days.size(); k++) {
            s += days.get(k).precip();
        }
        return s;
    }

    private static boolean hasNear(Set<LocalDate> dates, LocalDate d, int window) {
        if (dates == null) {
            return false;
        }
        for (LocalDate x : dates) {
            if (Math.abs(x.toEpochDay() - d.toEpochDay()) <= window) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
