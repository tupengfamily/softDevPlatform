package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.DisasterEngine;
import com.learning.springboot.farm.engine.PestEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收割归因明细（{@code extra_json}）的<b>唯一构造实现</b>。
 *
 * <p><b>为什么单独抽出来（2026-10-26）</b>：归因明细原先在 {@link FarmPlayService}
 * 里内联构造，而且<b>构造时机错了</b>：
 * <ol>
 *   <li>出苗信息（{@code germination} / {@code withered}）是在 {@code saveScore}
 *       <b>之后</b>才补写进 {@code extra} 的 —— 也就是说<b>成绩表里永远没有</b>
 *       出苗率（实测：14 局 farm 成绩命中 0 条），玩家在「游戏历史」里
 *       看不到「这季是缺苗导致的减产」。</li>
 *   <li>{@code dmgPanicle}（穗数受损）压根没写 —— 但它是产量四要素之一，
 *       {@code FarmView.YieldView} 里有，前端「产量归因」表也画它。</li>
 *   <li>病虫害与灾害被拼成 {@code "名称:数值"} 字符串，<b>无法结构化消费</b>
 *       —— 前端只能整串显示，排不了序、算不了合计。</li>
 * </ol>
 *
 * <p><b>修法不是「在三处各补一个字段」，而是抽唯一实现</b>（本项目铁律：
 * 同型代码只修一处 = 分叉）。现在收割与绝收两条路径都走
 * {@link #harvest} / {@link #failed} 这两个工厂方法，字段集合由同一处定义。
 *
 * <p><b>字段契约（前端 {@code GameHistory.vue} 按此消费）</b>：
 * <pre>
 *   基础      crop / cropId / visual / sowDate / harvestDate / days / dvs
 *   产量      yieldRaw / yieldCap / yieldCapped / fertility / thousandGrainWeight
 *             dmgPanicle / dmgFloret / dmgFertility / dmgWeight
 *   出苗      germination / withered / failureType / failureNote
 *   损失      pestLoss / disasterLoss / pests[] / disasters[]
 *   投入      inputCost / revenue / net / nApplied
 *   合规      phiViolated / phiViolations / phiItem / overdose
 *   天气      weatherSource
 * </pre>
 *
 * <p>🔴 <b>结构化 pests / disasters</b>：病虫害项用
 * {@code {id, name, severity}}，灾害项用 {@code {type, name, date, intensity, loss, note}}。
 * {@code name} 仍是后端中文名 —— 英文界面由前端按 {@code id} 映射（前端已有
 * {@code farm.pestName.*} 映射表，见 {@code GameFarm.vue}）。
 * {@code id} 才是稳定的跨语言标识。
 */
public final class FarmAttribution {

    private FarmAttribution() {
    }

    /** 一条病虫害记录（结构化，不再是拼接字符串）。 */
    public record PestHit(String id, String name, String type, double severity) {
    }

    /** 一条灾害记录（结构化）。 */
    public record DisasterHit(String type, String name, String date,
                              double intensity, double loss, String note) {
    }

    /**
     * 正常收割的归因明细。
     *
     * @param cropId     作物 id（跨语言稳定标识）
     * @param visual     形态族（{@code CropVisual}），前端贴图映射用
     * @param cropName   作物中文名（后端唯一权威，前端按 cropId 覆盖）
     * @param sowDate    本季播种日
     * @param harvestDate 收割日
     * @param days       本季天数
     * @param dvs        收割时的发育进程
     * @param yieldRaw   <b>引擎原始产量</b>（未封顶、未乘因子前的 eyRaw）
     * @param yieldFinal <b>最终实收产量</b>（kg/亩）—— 叠加了病虫害/灾害/土壤/
     *                  出苗/杂草/工序遗漏/农具之后的真实入库量。
     *                  <b>null = 历史数据（2026-10-27 之前）没有这个字段。</b>
     * @param yieldCap   产量上限（{@code CropConfig.yieldCap()}）
     * @param fertility  结实率（0..1）
     * @param grainWeight 单粒/千粒重（禾谷=千粒重，园艺=单果重）
     * @param dmgPanicle  穗数（禾谷）/ 株数（园艺）受损率
     * @param dmgFloret   花器受损率
     * @param dmgFertility 结实率受损率
     * @param dmgWeight   粒重受损率
     * @param germination 出苗率（0..1）
     * @param withered   是否苗期枯死
     * @param pestLoss   病虫害总损失（0..1）
     * @param disasterLoss 灾害总损失（0..1）
     * @param pests      发生过的病虫害
     * @param disasters  发生过的灾害
     * @param inputCost  农资投入（元）
     * @param revenue    预估产值（元）
     * @param net        净收益（元）
     * @param nApplied   本季施入折纯氮（kg/亩）
     * @param phiViolated 是否触发安全间隔期违规
     * @param phiViolations 违规次数
     * @param phiItem    最严重的一次违规（药剂名 + 超期天数）
     * @param overdose   超量施用次数
     * @param weatherSource 天气来源标签
     */
    public record Harvest(
            String cropId, String visual, String cropName,
            String sowDate, String harvestDate, int days, double dvs,
            double yieldRaw, double yieldCap, boolean yieldCapped,
            /** 最终实收产量（kg/亩）；null = 历史数据没有这个字段。 */
            Double yieldFinal,
            double fertility, double grainWeight,
            double dmgPanicle, double dmgFloret, double dmgFertility, double dmgWeight,
            /** 出苗率（0..1）；null = 本季没有出苗评估结果（历史数据）。 */
            Double germination, boolean withered,
            double pestLoss, double disasterLoss,
            List<PestHit> pests, List<DisasterHit> disasters,
            double inputCost, double revenue, double net, double nApplied,
            boolean phiViolated, int phiViolations, String phiItem, int overdose,
            String weatherSource,
            /** 天气来源的结构化描述（跨语言）；null = 历史数据没有这个字段。 */
            Map<String, Object> weatherSourceData) {
    }

    /** 绝收（未成熟 / 苗期枯死）的归因明细。 */
    public record Failed(
            String cropId, String visual, String cropName,
            String sowDate, String harvestDate, int days, double dvs,
            Double germination, boolean withered,
            String failureType, String failureNote,
            double inputCost, double net) {
    }

    // ==================================================================
    //  工厂
    // ==================================================================

    /** 收割归因明细 → {@code extra_json} 的 Map（顺序固定，便于 diff）。 */
    public static Map<String, Object> toMap(Harvest h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crop", h.cropName());
        m.put("cropId", h.cropId());
        m.put("visual", h.visual());
        m.put("sowDate", h.sowDate());
        m.put("harvestDate", h.harvestDate());
        m.put("days", h.days());
        m.put("dvs", r3(h.dvs()));
        // —— 产量 ——
        m.put("yieldRaw", r1(h.yieldRaw()));
        m.put("yieldCap", r1(h.yieldCap()));
        m.put("yieldCapped", h.yieldCapped());
        // 🔴 2026-10-27：补「最终实收产量」。
        //
        // 为什么必须补：yieldRaw 是**引擎原始潜力**（只含温度/积温损伤），
        // 而玩家真正拿到手的是**实收**（叠加病虫害/灾害/土壤/出苗/杂草/
        // 工序遗漏/农具之后的产量）。两者差距往往很大 ——
        // 一季管理到位的田，潜力 620 kg/亩，实收可能只有 380。
        //
        // 缺了实收字段，后果是**归因面板自相矛盾**：
        // 面板顶部写「引擎原始产量 620」，下面列病虫害损失 16.5%、
        // 灾害损失 24%，可玩家从头到尾**看不到自己实际收了多少**，
        // 也不知道这些损失到底吃掉了多少斤粮。
        //
        // ⚠️ 绝不能拿 yieldRaw 顶替实收 —— 那是「潜力」，不是「结果」。
        // 两个字段并存，各司其职。
        m.put("yieldFinal", h.yieldFinal() == null ? null : r1(h.yieldFinal()));
        m.put("fertility", r3(h.fertility()));
        m.put("thousandGrainWeight", r2(h.grainWeight()));
        m.put("dmgPanicle", r3(h.dmgPanicle()));
        m.put("dmgFloret", r3(h.dmgFloret()));
        m.put("dmgFertility", r3(h.dmgFertility()));
        m.put("dmgWeight", r3(h.dmgWeight()));
        // —— 出苗 ——
        m.put("germination", h.germination() == null ? null : r3(h.germination()));
        m.put("withered", h.withered());
        // —— 损失 ——
        m.put("pestLoss", r3(h.pestLoss()));
        m.put("disasterLoss", r3(h.disasterLoss()));
        m.put("pests", pestList(h.pests()));
        m.put("disasters", disasterList(h.disasters()));
        // —— 投入产出 ——
        m.put("inputCost", r2(h.inputCost()));
        m.put("revenue", r1(h.revenue()));
        m.put("net", r1(h.net()));
        m.put("nApplied", r2(h.nApplied()));
        // —— 合规 ——
        m.put("phiViolated", h.phiViolated());
        m.put("phiViolations", h.phiViolations());
        if (h.phiItem() != null) {
            m.put("phiItem", h.phiItem());
        }
        m.put("overdose", h.overdose());
        // —— 天气 ——
        m.put("weatherSource", h.weatherSource());
        if (h.weatherSourceData() != null) {
            m.put("weatherSourceData", h.weatherSourceData());
        }
        return m;
    }

    /** 绝收归因明细 → {@code extra_json} 的 Map。 */
    public static Map<String, Object> toMap(Failed f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crop", f.cropName());
        m.put("cropId", f.cropId());
        m.put("visual", f.visual());
        m.put("sowDate", f.sowDate());
        m.put("harvestDate", f.harvestDate());
        m.put("days", f.days());
        m.put("dvs", r3(f.dvs()));
        m.put("yield", 0);
        m.put("germination", f.germination() == null ? null : r3(f.germination()));
        m.put("withered", f.withered());
        if (f.failureType() != null) {
            m.put("failureType", f.failureType());
        }
        if (f.failureNote() != null) {
            m.put("failureNote", f.failureNote());
        }
        m.put("inputCost", r2(f.inputCost()));
        m.put("revenue", 0);
        m.put("net", r1(f.net()));
        return m;
    }

    // ==================================================================
    //  引擎结果 → 结构化归因
    // ==================================================================

    /**
     * 病虫害：只保留<b>已显症</b>（{@code detected}）的实例。
     *
     * <p>🔴 口径说明：{@code PestEngine} 里未显症的实例也在列表里（潜伏期未过），
     * 但那还没造成损失，归因表不该列 —— 列了会让玩家以为「已经发生了病虫害」。
     */
    public static List<PestHit> pestHits(PestEngine.Result pr) {
        List<PestHit> out = new ArrayList<>();
        if (pr == null) {
            return out;
        }
        for (PestEngine.Instance x : pr.instances()) {
            if (!x.detected()) {
                continue;
            }
            out.add(new PestHit(x.id(), x.name(), x.type(), x.severity()));
        }
        return out;
    }

    /** 灾害：{@code DisasterEngine} 的事件列表原样结构化。 */
    public static List<DisasterHit> disasterHits(DisasterEngine.Result dr) {
        List<DisasterHit> out = new ArrayList<>();
        if (dr == null) {
            return out;
        }
        for (DisasterEngine.Event e : dr.events()) {
            out.add(new DisasterHit(e.type(), e.name(), e.date(),
                    e.intensity(), e.loss(), e.note()));
        }
        return out;
    }

    /**
     * 作物 id → 形态标识（前端贴图映射用；查不到就空串，<b>不猜</b>）。
     *
     * <p>🔴 <b>不能用 {@code CropCatalog.get(id)}</b>：它对未知 id 会<b>兜底返回
     * 水稻</b>（{@code get()} 的实现是 {@code c != null ? c : CROPS.get("rice_mid")}）。
     * 归因明细是「如实记录这一季发生了什么」，兜底成水稻就是<b>造假</b> ——
     * 一块种了白菜的地会记成水稻的形态，前端贴图也跟着错。
     * 这里直接查表（{@code all().get(id)}），查不到就是查不到。
     *
     * <p>🔴 {@code visual} 挂在 {@link CropConfig} 上（不是 {@link CropCatalog.Crop}），
     * 且语义在 2026-10-24 变过：园艺作物现在是<b>作物 id 本身</b>
     * （如 {@code cabbage} / {@code grape}），只有 4 个粮食作物保留
     * {@code rice / wheat / corn / soybean}。前端 {@code config.js} 按这个语义加载贴图。
     */
    public static String visualOf(String cropId) {
        if (cropId == null || cropId.isBlank()) {
            return "";
        }
        CropCatalog.Crop c = CropCatalog.all().get(cropId);
        if (c == null) {
            return "";
        }
        String v = c.config().visual();
        return v == null ? "" : v;
    }

    // ==================================================================
    //  内部
    // ==================================================================

    /**
     * 归因明细的精度约定（<b>唯一一处</b>，不散落在各调用点）：
     * <ul>
     *   <li>率值（受损率 / 出苗率 / 损失率）→ 3 位</li>
     *   <li>重量与投入 → 2 位</li>
     *   <li>产量与产值 → 1 位</li>
     * </ul>
     * 之前这些精度是 {@code FarmPlayService} 里各 {@code round(x, n)} 分散指定的，
     * 抽出来之后**精度表集中在这里**，改一处即可。
     */
    private static double r1(double v) { return round(v, 1); }
    private static double r2(double v) { return round(v, 2); }
    private static double r3(double v) { return round(v, 3); }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static List<Map<String, Object>> pestList(List<PestHit> pests) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (pests == null) {
            return out;
        }
        for (PestHit p : pests) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("type", p.type());
            m.put("severity", r3(p.severity()));
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> disasterList(List<DisasterHit> ds) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ds == null) {
            return out;
        }
        for (DisasterHit d : ds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", d.type());
            m.put("name", d.name());
            m.put("date", d.date());
            m.put("intensity", r3(d.intensity()));
            m.put("loss", r3(d.loss()));
            if (d.note() != null) {
                m.put("note", d.note());
            }
            out.add(m);
        }
        return out;
    }
}
