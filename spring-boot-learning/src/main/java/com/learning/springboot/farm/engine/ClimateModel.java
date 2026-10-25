package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 农业区划（S2 最小版）+ 气候序列生成器。
 *
 * <p><b>为什么需要它</b>：S23 的适区评级主判据是「近 10 年逐年回放的达标概率」，
 * 而现在游戏里的天气只有一份「南昌 2019 年实测按月日循环重放」——只有一年，
 * 算不出概率，也支持不了其他区域。
 *
 * <p><b>两种数据来源，必须区分口径</b>：
 * <ul>
 *   <li><b>江南</b>：以 {@code farm/weather-nanchang.json} 里 2019 年的真实观测为基线年，
 *       其余年份在基线上叠加 AR(1) 气温距平与年降水系数。这样「中位年」与游戏内
 *       实际跑的天气完全一致，评级不会和体感打架。</li>
 *   <li><b>华南 / 黄淮海 / 东北</b>：按各站点月均值（气温 / 日较差 / 降水 / 雨日频率）
 *       做随机天气发生器，逐年独立播种。这是<b>气候推算</b>，不是实测——UI 必须如实标注。</li>
 * </ul>
 *
 * <p>口径与 {@link DayWeather} 一致：百叶箱 T_max / T_min，日平均 = (max + min) / 2。
 */
public final class ClimateModel {

    private ClimateModel() {
    }

    // ==================================================================
    //  区域定义
    // ==================================================================

    /**
     * 一个农业区的最小气候画像。
     *
     * @param tmean    12 个月日平均气温（℃），下标 0 = 1 月
     * @param range    12 个月平均日较差（℃）
     * @param precip   12 个月降水量（mm）
     * @param wetProb  12 个月雨日频率（0–1）
     * @param z        土壤 / 灌溉参数（S6 透传给 S23 的建田输入）
     */
    public record Zone(
            String id,
            String name,
            String site,
            String region,
            double lat,
            double[] tmean,
            double[] range,
            double[] precip,
            double[] wetProb,
            double ph,
            double ec,
            double depthCm,
            double irrigationMm,
            double reliability,
            double etcSeasonMm,
            boolean open,
            String seasonHint) {
    }

    /**
     * 首发四区（S23 §1.5）。数值取自各站点多年月均值量级，用于演示与教学，
     * 不作气象业务用途——UI 上必须写「气候推算」。
     */
    private static final Map<String, Zone> ZONES = new LinkedHashMap<>();

    static {
        ZONES.put("jiangnan", new Zone(
                "jiangnan", "江南", "江西·南昌", "长江中下游",
                28.7,
                new double[]{5.1, 6.8, 11.4, 17.4, 22.6, 25.9, 29.4, 29.0, 25.2, 19.4, 12.8, 7.2},
                new double[]{7.8, 7.6, 8.0, 8.4, 8.2, 8.0, 8.6, 8.4, 8.0, 8.0, 7.8, 7.6},
                new double[]{78, 105, 165, 200, 220, 258, 155, 125, 95, 65, 62, 48},
                new double[]{0.42, 0.46, 0.55, 0.52, 0.51, 0.46, 0.33, 0.32, 0.28, 0.24, 0.24, 0.22},
                5.9, 0.25, 90, 520, 0.90, 700, true,
                "梅雨 + 伏旱，中稻一年一季；7–8 月高温是抽穗期的主要风险"));

        ZONES.put("huanan", new Zone(
                "huanan", "华南", "广东·广州", "华南",
                23.1,
                new double[]{13.6, 15.2, 18.1, 22.4, 25.8, 27.8, 29.1, 28.9, 27.4, 24.3, 19.8, 15.2},
                new double[]{7.6, 7.0, 6.6, 6.8, 7.0, 7.2, 7.6, 7.4, 7.4, 7.6, 7.4, 7.4},
                new double[]{43, 66, 85, 180, 290, 290, 235, 235, 175, 85, 40, 30},
                new double[]{0.26, 0.34, 0.42, 0.50, 0.56, 0.58, 0.54, 0.53, 0.44, 0.28, 0.22, 0.20},
                5.6, 0.30, 85, 500, 0.85, 720, true,
                "热量足，可双季；雨季长，抽穗期撞台风与暴雨"));

        ZONES.put("huanghuaihai", new Zone(
                "huanghuaihai", "黄淮海", "山东·济南", "黄淮海平原",
                36.7,
                new double[]{-0.4, 2.2, 8.4, 15.5, 21.2, 25.9, 27.4, 26.3, 21.8, 15.7, 7.9, 1.6},
                new double[]{9.5, 10.0, 10.5, 10.8, 10.5, 9.8, 9.0, 9.2, 9.8, 10.2, 9.8, 9.5},
                new double[]{6, 10, 20, 30, 50, 75, 175, 145, 60, 30, 15, 6},
                new double[]{0.14, 0.16, 0.18, 0.20, 0.24, 0.30, 0.44, 0.40, 0.28, 0.20, 0.16, 0.14},
                7.2, 0.55, 80, 480, 0.75, 660, true,
                "稻麦两熟，中稻是「抢积温」的临界区；秋季降温快，晚播风险陡增"));

        ZONES.put("dongbei", new Zone(
                "dongbei", "东北", "黑龙江·哈尔滨", "东北平原",
                45.8,
                new double[]{-17.6, -13.2, -3.9, 7.0, 14.7, 20.4, 22.9, 21.1, 14.5, 5.6, -5.7, -14.6},
                new double[]{11.0, 11.0, 11.0, 11.0, 10.5, 10.0, 9.5, 10.0, 10.5, 10.5, 11.0, 11.0},
                new double[]{4, 5, 11, 24, 45, 95, 155, 125, 60, 25, 10, 6},
                new double[]{0.20, 0.18, 0.20, 0.22, 0.28, 0.40, 0.46, 0.42, 0.32, 0.24, 0.22, 0.20},
                6.6, 0.45, 95, 460, 0.80, 560, true,
                "一年一季粳稻，无霜期只有 130–150 天；初霜来得早，晚熟品种直接不熟"));
    }

    public static Map<String, Zone> zones() {
        return ZONES;
    }

    public static Zone zone(String id) {
        Zone z = ZONES.get(id);
        return z == null ? ZONES.get("jiangnan") : z;
    }

    // ==================================================================
    //  序列生成
    // ==================================================================

    private static final Map<String, List<DayWeather>> CACHE = new ConcurrentHashMap<>();
    private static volatile Map<Integer, DayWeather> nanchang2019;

    /** 该区该年的逐日天气（365/366 天，按日历日顺序）。 */
    public static List<DayWeather> series(String zoneId, int year) {
        String key = zoneId + ":" + year;
        return CACHE.computeIfAbsent(key, k -> "jiangnan".equals(zoneId)
                ? fromBaseline(year)
                : synthetic(zone(zoneId), year));
    }

    public static DayWeather day(String zoneId, LocalDate date) {
        List<DayWeather> s = series(zoneId, date.getYear());
        int idx = date.getDayOfYear() - 1;
        if (idx >= s.size()) {
            idx = s.size() - 1;
        }
        DayWeather w = s.get(idx);
        // 日期字段换成调用方要的那一天，其余用该区该年的值
        return new DayWeather(date.toString(), w.tmax(), w.tmin(), w.precip());
    }

    /** 随机天气发生器（逐年独立播种，保证同一区同一年永远同一结果）。 */
    private static List<DayWeather> synthetic(Zone z, int year) {
        int n = LocalDate.of(year, 1, 1).lengthOfYear();
        List<DayWeather> out = new ArrayList<>(n);
        Random rnd = new Random(seed(z.id(), year));
        double ar = 0;
        for (int doy = 1; doy <= n; doy++) {
            LocalDate date = LocalDate.ofYearDay(year, doy);
            double base = monthAt(doy, z.tmean());
            double range = monthAt(doy, z.range());
            double sigma = Math.max(1.2, range * 0.33);

            ar = 0.74 * ar + Math.sqrt(1 - 0.74 * 0.74) * sigma * rnd.nextGaussian();
            double mean = base + ar;
            double diurnal = range * (0.86 + rnd.nextDouble() * 0.30);

            double pWet = monthAt(doy, z.wetProb());
            double precip = 0;
            if (rnd.nextDouble() < pWet) {
                double lam = Math.max(0.5, monthAt(doy, z.precip()) / Math.max(1, pWet * 30.4));
                precip = Math.min(180, -lam * Math.log(Math.max(1e-6, rnd.nextDouble())));
                mean -= 0.9;
                diurnal *= 0.66;
            }
            out.add(new DayWeather(date.toString(),
                    round1(mean + diurnal / 2), round1(mean - diurnal / 2), round1(precip)));
        }
        return out;
    }

    /**
     * 江南：以 2019 年南昌实测为基线，其余年份叠加 AR(1) 气温距平 + 年降水系数。
     * 这样「中位年」与游戏里真正跑的天气是同一份，评级和体感不会两张皮。
     */
    private static List<DayWeather> fromBaseline(int year) {
        Map<Integer, DayWeather> base = baseline();
        int n = LocalDate.of(year, 1, 1).lengthOfYear();
        List<DayWeather> out = new ArrayList<>(n);
        Random rnd = new Random(seed("jiangnan", year) ^ 0x5f3759dfL);
        double ar = 0;
        double wetFactor = 0.78 + rnd.nextDouble() * 0.5;
        for (int doy = 1; doy <= n; doy++) {
            LocalDate date = LocalDate.ofYearDay(year, doy);
            DayWeather b = base.get(doy);
            if (b == null) {
                b = base.get(Math.min(365, doy));
            }
            if (year == 2019 || b == null) {
                out.add(new DayWeather(date.toString(), b.tmax(), b.tmin(), b.precip()));
                continue;
            }
            ar = 0.72 * ar + Math.sqrt(1 - 0.72 * 0.72) * 2.1 * rnd.nextGaussian();
            out.add(new DayWeather(date.toString(),
                    round1(b.tmax() + ar), round1(b.tmin() + ar), round1(b.precip() * wetFactor)));
        }
        return out;
    }

    private static Map<Integer, DayWeather> baseline() {
        Map<Integer, DayWeather> m = nanchang2019;
        if (m != null) {
            return m;
        }
        synchronized (ClimateModel.class) {
            if (nanchang2019 != null) {
                return nanchang2019;
            }
            Map<Integer, DayWeather> tmp = new java.util.HashMap<>();
            try {
                for (DayWeather d : WeatherSeries.loadFromClasspath("farm/weather-nanchang.json")) {
                    LocalDate date = LocalDate.parse(d.date());
                    if (date.getYear() == 2019) {
                        tmp.put(date.getDayOfYear(), d);
                    }
                }
            } catch (Exception e) {
                // 基线缺失时退化为纯发生器，不让整个游戏起不来
                tmp.clear();
            }
            nanchang2019 = tmp;
            return tmp;
        }
    }

    // ==================================================================
    //  统计（十年）
    // ==================================================================

    public record Stats(
            double aatP50,
            double aatP10,
            int frostFreeP50,
            int frostFreeP10,
            double winterTminP50,
            double winterTminP10,
            int firstFrostP50,
            int lastFrostP50,
            double annualPrecip,
            int years) {
    }

    private static final Map<String, Stats> STATS = new ConcurrentHashMap<>();
    private static final int FIRST_YEAR = 2016;
    private static final int NYEARS = 10;

    public static Stats stats(String zoneId) {
        return STATS.computeIfAbsent(zoneId, ClimateModel::computeStats);
    }

    public static int firstYear() {
        return FIRST_YEAR;
    }

    public static int years() {
        return NYEARS;
    }

    private static Stats computeStats(String zoneId) {
        List<Double> aats = new ArrayList<>();
        List<Integer> ffd = new ArrayList<>();
        List<Double> wtmin = new ArrayList<>();
        List<Integer> firstFrost = new ArrayList<>();
        List<Integer> lastFrost = new ArrayList<>();
        double precipSum = 0;

        for (int i = 0; i < NYEARS; i++) {
            int year = FIRST_YEAR + i;
            List<DayWeather> s = series(zoneId, year);
            double aat = 0;
            double precip = 0;
            for (DayWeather d : s) {
                aat += aatDay(d.tmean());
                precip += d.precip();
            }
            aats.add(aat);
            precipSum += precip;
            firstFrost.add(firstAutumnFrost(s));
            lastFrost.add(lastSpringFrost(s));
            ffd.add(Math.max(0, firstAutumnFrost(s) - lastSpringFrost(s)));
            wtmin.add(winterTmin(s));
        }
        Stats st = new Stats(
                percentile(aats, 50), percentile(aats, 10),
                (int) Math.round(percentileInt(ffd, 50)), (int) Math.round(percentileInt(ffd, 10)),
                round1(percentile(wtmin, 50)), round1(percentile(wtmin, 10)),
                (int) Math.round(percentileInt(firstFrost, 50)),
                (int) Math.round(percentileInt(lastFrost, 50)),
                Math.round(precipSum / NYEARS), NYEARS);
        return st;
    }

    /** 单日活动积温，与 S7 完全同口径（日均 ≥10℃ 时计入，30℃ 截断）。 */
    public static double aatDay(double tmean) {
        return tmean >= 10.0 ? Math.min(tmean, 30.0) : 0.0;
    }

    /**
     * 单日活动积温（<b>作物基准</b>口径）。
     *
     * <p>🔴 2026-10-26 修复 M6：{@link #aatDay(double)} 写死了 10℃ 基准，
     * 但各作物的基准温度并不相同（见 {@link CropConfig#aatRef()}）：
     * <pre>
     *   cabbage / tulip  → 5℃   （喜凉，越冬作物）
     *   potato           → 7℃
     *   tomato / rice    → 10℃  （禾谷与喜温作物）
     * </pre>
     * 拿 10℃ 基准去算白菜的积温供给，会**低估**白菜的有效积温
     * （10–16℃ 的那些天本该计入，被全砍了），
     * 于是「江南种白菜」被误判成「热量不足」。
     *
     * <p>正确范式已存在于 {@link SimEngine#aatDay(double, CropConfig)}：
     * <pre>
     *   tmean &gt;= c.aatRef() ? min(tmean, c.tOptUpper()) : 0
     * </pre>
     * 本方法与之<b>同式</b>，只是把基准参数化 —— 引擎回放与适区归因用同一套口径。
     *
     * @param tmean  日均温（℃）
     * @param aatRef 作物基准温度（℃，即 {@code crop.aatRef()}）
     * @param tOptUpper 有效积温上限（℃，即 {@code crop.tOptUpper()}）
     */
    public static double aatDay(double tmean, double aatRef, double tOptUpper) {
        double base = aatRef <= 0 ? 10.0 : aatRef;
        double cap = tOptUpper <= 0 ? 30.0 : tOptUpper;
        return tmean >= base ? Math.min(tmean, cap) : 0.0;
    }

    /** 从播种日（DOY）到该年初霜日的可获积温。 */
    public static double aatSupply(String zoneId, int year, int sowDoy) {
        List<DayWeather> s = series(zoneId, year);
        int end = firstAutumnFrost(s);
        double sum = 0;
        for (int i = Math.max(0, sowDoy - 1); i < Math.min(s.size(), end); i++) {
            sum += aatDay(s.get(i).tmean());
        }
        return sum;
    }

    /**
     * 从播种日（DOY）到该年初霜日的可获积温（<b>作物基准</b>口径）。
     *
     * <p>🔴 2026-10-26 修复 M6：见 {@link #aatDay(double, double, double)} 的说明。
     * 这里与 {@code SimEngine} 的回放用<b>同一个基准</b>，
     * 于是「归因说热量够」与「回放算出来确实熟得了」不会再互相打架。
     */
    public static double aatSupply(String zoneId, int year, int sowDoy, CropConfig crop) {
        List<DayWeather> s = series(zoneId, year);
        int end = firstAutumnFrost(s);
        double aatRef = crop == null ? 10.0 : crop.aatRef();
        double tOpt = crop == null ? 30.0 : crop.tOptUpper();
        double sum = 0;
        for (int i = Math.max(0, sowDoy - 1); i < Math.min(s.size(), end); i++) {
            sum += aatDay(s.get(i).tmean(), aatRef, tOpt);
        }
        return sum;
    }

    /** 秋季初霜（7–12 月第一个 T_min ≤ 0 的日子，DOY）；无霜区返回当年最后一天。 */
    public static int firstAutumnFrost(List<DayWeather> s) {
        for (int i = 180; i < s.size(); i++) {
            if (s.get(i).tmin() <= 0) {
                return i + 1;
            }
        }
        return s.size();
    }

    /** 春季终霜（1–6 月最后一个 T_min ≤ 0 的日子，DOY）；无霜区返回 1。 */
    public static int lastSpringFrost(List<DayWeather> s) {
        int last = 1;
        for (int i = 0; i < Math.min(180, s.size()); i++) {
            if (s.get(i).tmin() <= 0) {
                last = i + 1;
            }
        }
        return last;
    }

    /** 冬季（12/1–2/28，取跨年窗口的后半段近似）极端最低气温。 */
    public static double winterTmin(List<DayWeather> s) {
        double min = 99;
        for (int i = 0; i < Math.min(60, s.size()); i++) {
            min = Math.min(min, s.get(i).tmin());
        }
        for (int i = 334; i < s.size(); i++) {
            min = Math.min(min, s.get(i).tmin());
        }
        return min;
    }

    // ==================================================================
    //  工具
    // ==================================================================

    /** 把 DOY 视作「月中的连续位置」做环形线性插值，得到平滑的季节曲线。 */
    private static double monthAt(int doy, double[] m) {
        double pos = ((doy - 15.0) / 30.44 + 12) % 12;
        int i = (int) Math.floor(pos) % 12;
        double f = pos - Math.floor(pos);
        return m[i] * (1 - f) + m[(i + 1) % 12] * f;
    }

    private static long seed(String zoneId, int year) {
        long h = 1125899906842597L;
        for (int i = 0; i < zoneId.length(); i++) {
            h = h * 31 + zoneId.charAt(i);
        }
        return h * 1_000_003L + year * 7919L;
    }

    private static double percentile(List<Double> v, int p) {
        if (v.isEmpty()) {
            return 0;
        }
        List<Double> c = new ArrayList<>(v);
        c.sort(Double::compare);
        double pos = (p / 100.0) * (c.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return c.get(lo);
        }
        double f = pos - lo;
        return c.get(lo) * (1 - f) + c.get(hi) * f;
    }

    private static double percentileInt(List<Integer> v, int p) {
        List<Double> d = new ArrayList<>();
        for (Integer i : v) {
            d.add(i.doubleValue());
        }
        return percentile(d, p);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
