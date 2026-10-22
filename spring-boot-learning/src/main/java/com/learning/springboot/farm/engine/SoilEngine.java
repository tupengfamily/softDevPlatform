package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 稻田土壤水分与地力（S6 精简实现）。
 *
 * <p>只做水稻田这一条主线，但保留三个真实机制：
 * <ol>
 *   <li><b>水层平衡</b>：水层 = 昨日水层 + 降雨 + 灌溉 − 蒸散 − 渗漏，超过田埂高度算径流；</li>
 *   <li><b>犁底层渗漏</b>：完好的水田日渗漏 ~6mm；晒田（DRAIN）把水层排空，复水靠灌溉/降雨；</li>
 *   <li><b>地力 SFI</b>：0–100，施肥提升、连作衰减；产量系数 = 0.60 + 0.40 × SFI/100（保底 60%）。</li>
 * </ol>
 *
 * <p>⚠️ 与 S7 的分工：这里只输出 RWC / 干旱-渍水损失系数，产量换算在 Service 里统一做，
 * 避免"土壤扣一次、S7 又扣一次"。
 */
public final class SoilEngine {

    private SoilEngine() {
    }

    public record Result(
            double waterMm,        // 当前水层
            double rwc,            // 相对含水量 0..1
            String status,         // FLOODED/WET/MOIST/DRY/DROUGHT
            int dryDays,           // 累计受旱天数
            int waterlogDays,      // 累计深水（>100mm）天数
            double sfi,            // 地力 0..100
            double soilCoef,       // 产量系数 0.6..1.0
            double droughtLoss,    // 干旱减产（0..1）
            double waterlogLoss,   // 渍水减产（0..1）
            double etToday,        // 今日蒸散 mm
            double percToday,      // 今日渗漏 mm
            String lastDrainDate,
            double nApplied,       // 本季施入的折纯氮 kg/亩
            double nNeed,          // 本季需氮 kg/亩
            double nSatisfaction   // 氮素满足度 0..1（Mitscherlich）
    ) {
        public static final Result EMPTY =
                new Result(0, 1, "MOIST", 0, 0, 68, 0.87, 0, 0, 0, 0, null, 0, 11, 0);
    }

    /** 田埂高度（水层超过即径流外溢），mm */
    private static final double BUND_HEIGHT = 100;
    /** 犁底层完好时的日渗漏，mm */
    private static final double PERCOLATION = 6;
    /** 一次灌溉的补水量，mm */
    private static final double IRRIGATION_MM = 50;
    /** 水稻土有效水容量（30cm 土层），mm */
    private static final double TAW = 50;
    /** 旱地土壤有效水容量（60cm 根层），mm */
    private static final double TAW_DRY = 120;

    /**
     * @param days       天气序列
     * @param startIdx   播种日下标
     * @param nDays      已生长天数
     * @param dvsTrace   逐日 DVS
     * @param drains     晒田/排水日期
     * @param irrigates  灌溉日期
     * @param nByDate    施肥日期 → 当日施入的折纯氮（kg/亩，S12 由「用量 × 含量」折算）
     * @param seasons    已种季数（连作会让地力缓慢下降）
     */
    public static Result simulate(List<DayWeather> days,
                                  int startIdx,
                                  int nDays,
                                  List<Double> dvsTrace,
                                  Set<LocalDate> drains,
                                  Set<LocalDate> irrigates,
                                  Map<LocalDate, Double> nByDate,
                                  int seasons,
                                  double nNeed,
                                  boolean legume) {
        return simulate(days, startIdx, nDays, dvsTrace, drains, irrigates, nByDate, seasons,
                nNeed, legume, true);
    }

    /**
     * @param paddy true = 水田（有水层、犁底层渗漏、田埂）；false = 旱地（土壤有效水，无明水）
     */
    public static Result simulate(List<DayWeather> days,
                                  int startIdx,
                                  int nDays,
                                  List<Double> dvsTrace,
                                  Set<LocalDate> drains,
                                  Set<LocalDate> irrigates,
                                  Map<LocalDate, Double> nByDate,
                                  int seasons,
                                  double nNeed,
                                  boolean legume,
                                  boolean paddy) {
        if (days == null || nDays <= 0 || dvsTrace == null) {
            return Result.EMPTY;
        }

        double water = paddy ? 40 : 90;   // 水田：30–40mm 浅水层；旱地：约 75% 有效水
        double rwc = 1.0;
        int dryDays = 0;
        int waterlogDays = 0;
        double droughtLoss = 0;
        double waterlogLoss = 0;
        double etToday = 0;
        double percToday = 0;
        String lastDrain = null;

        for (int k = 0; k < nDays; k++) {
            int i = startIdx + k;
            if (i >= days.size()) {
                break;
            }
            DayWeather w = days.get(i);
            LocalDate date = LocalDate.parse(w.date());
            double dvs = k < dvsTrace.size() ? dvsTrace.get(k) : 0;

            // ===== 旱地：土壤有效水（无明水层、无犁底层渗漏） =====
            if (!paddy) {
                water += w.precip() * 0.85;
                if (irrigates != null && irrigates.contains(date)) {
                    water += 45;
                }
                if (drains != null && drains.contains(date)) {
                    water = Math.min(water, TAW_DRY * 0.45);
                    lastDrain = date.toString();
                }
                rwc = clamp(water / TAW_DRY, 0, 1);
                double et0d = et0(w.tmax(), w.tmin());
                double kcd = kcOf(dvs);
                double ksd = rwc >= 0.55 ? 1.0 : clamp(rwc / 0.55, 0, 1);
                etToday = et0d * kcd * ksd;
                water -= etToday;
                if (water > TAW_DRY) {
                    water = TAW_DRY;   // 超过田间持水量：径流 + 深层渗漏
                }
                if (water < 0) {
                    water = 0;
                }
                rwc = clamp(water / TAW_DRY, 0, 1);
                percToday = 0;
                if (rwc < 0.45) {
                    dryDays++;
                    double sev = (0.45 - rwc) / 0.45;
                    if (dvs >= 0.55 && dvs < 0.80) {
                        droughtLoss += sev * 0.010;
                    } else {
                        droughtLoss += sev * 0.004;
                    }
                }
                if (rwc > 0.98) {
                    waterlogDays++;
                    if (dvs < 0.30) {
                        waterlogLoss += 0.004;
                    }
                }
                continue;
            }

            // 1) 收入：降雨 + 灌溉 − 排水
            water += w.precip();
            if (irrigates != null && irrigates.contains(date)) {
                water += IRRIGATION_MM;
            }
            if (drains != null && drains.contains(date)) {
                water = 0;
                rwc = Math.min(rwc, 0.85);
                lastDrain = date.toString();
            }

            // 2) 蒸散（FAO-56 简化：ET0 由温差估算，Kc 随物候变化）
            double et0 = et0(w.tmax(), w.tmin());
            double kc = kcOf(dvs);
            double ks = rwc >= 0.55 ? 1.0 : clamp(rwc / 0.55, 0, 1);
            etToday = et0 * kc * ks;
            water -= etToday;

            // 3) 渗漏 + 径流
            percToday = water > 0 ? PERCOLATION : 0;
            water -= percToday;
            if (water > BUND_HEIGHT) {
                water = BUND_HEIGHT;
            }

            // 4) 水分状态
            if (water > 10) {
                rwc = 1.0;
            } else if (water > 0) {
                rwc = Math.max(rwc, 0.92);
            } else {
                rwc = clamp(rwc - Math.max(0, -water) / TAW, 0, 1);
                water = 0;
            }

            if (water > BUND_HEIGHT - 1) {
                waterlogDays++;
            }
            if (rwc < 0.55) {
                dryDays++;
            }

            // 5) 减产：抽穗开花期受旱最敏感；分蘖期渍水伤根
            if (rwc < 0.55) {
                double sev = (0.55 - rwc) / 0.55;          // 0..1
                if (dvs >= 0.63 && dvs < 0.72) {
                    droughtLoss += sev * 0.012;             // 抽穗期：每天最多 1.2%
                } else if (dvs >= 0.41 && dvs < 0.63) {
                    droughtLoss += sev * 0.006;
                } else {
                    droughtLoss += sev * 0.003;
                }
            }
            if (water > BUND_HEIGHT - 1 && dvs < 0.63) {
                waterlogLoss += 0.004;                      // 分蘖期深水伤根
            }
        }

        // 氮素账本（S12 §4.1.1）：只用当季施入量，乘以当季利用率由养分满足度体现。
        double totalN = 0;
        if (nByDate != null) {
            for (double v : nByDate.values()) {
                totalN += v;
            }
        }
        // 豆科：外源氮会抑制根瘤（"氮阻遏"）。固氮量随施氮量线性下降，
        // 所以大豆的最优施氮是"少量启动氮"，不是越多越好——本作最反直觉的一课。
        double fixation = 0;
        double effectiveN = totalN;
        if (legume) {
            fixation = 0.65 * (1 - Math.min(1.0, totalN / 8.0));
            effectiveN = totalN * 0.5 + fixation * nNeed;
        }
        double nSat = InputCatalog.nSatisfaction(effectiveN, nNeed);
        // Mitscherlich：不施肥 52 分，施到需求量约 73，过量再涨一分钱都难
        double sfi = 52 + 26.0 * nSat - Math.max(0, seasons - 1) * 1.0;
        if (totalN > 1.3 * nNeed) {
            // 过量：禾谷徒长/酸化；豆科还多一层——根瘤被压制，后期反而缺氮
            sfi -= legume ? 12 : 8;
        }
        sfi = clamp(sfi, 40, 95);
        double soilCoef = 0.60 + 0.40 * (sfi / 100.0);
        String status = paddy ? statusOf(water, rwc) : statusOfDry(rwc);

        return new Result(
                round(water), round(rwc), status, dryDays, waterlogDays,
                round(sfi), round(soilCoef),
                round(Math.min(0.25, droughtLoss)),
                round(Math.min(0.15, waterlogLoss)),
                round(etToday), round(percToday), lastDrain,
                round(totalN), nNeed, round(nSat * 100) / 100.0);
    }

    /** 旱地墒情分档（无明水，不出现 FLOODED）。 */
    private static String statusOfDry(double rwc) {
        if (rwc >= 0.85) return "WET";
        if (rwc >= 0.55) return "MOIST";
        if (rwc >= 0.40) return "DRY";
        return "DROUGHT";
    }

    /** 水稻 Kc：初期 1.05 → 中期 1.20 → 后期 0.90（含渗漏的简化）。 */
    private static double kcOf(double dvs) {
        if (dvs < 0.16) return 1.05;
        if (dvs < 0.92) return 1.20;
        return 0.90;
    }

    /** 简化 ET0（Hargreaves 量级）：温度越高、温差越大，蒸散越强。 */
    private static double et0(double tmax, double tmin) {
        double tmean = (tmax + tmin) / 2.0;
        double range = Math.max(2, tmax - tmin);
        double v = 0.0023 * (tmean + 17.8) * Math.sqrt(range) * 15.0;
        return clamp(v, 0.8, 9.5);
    }

    public static String statusOf(double water, double rwc) {
        if (water >= 30) return "FLOODED";
        if (water > 10) return "WET";
        if (rwc >= 0.80) return "MOIST";
        if (rwc >= 0.55) return "DRY";
        return "DROUGHT";
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
