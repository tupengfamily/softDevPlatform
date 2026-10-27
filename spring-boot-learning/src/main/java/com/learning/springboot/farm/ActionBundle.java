package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.InputCatalog;
import com.learning.springboot.farm.engine.SimEngine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一季农事的<b>一次性分类结果</b>（2026-10-26 P2）。
 *
 * <p>🔴 <b>要解决的问题</b>：{@code FarmPlayService} 里原本有<b>五条</b>平行通路
 * 各自遍历同一份 {@code ctx.actions()}：
 * <pre>
 *   ① SimEngine 的 effects     —— 按 type 分派成 ActionEffect，按日期分组
 *   ② PestEngine 的 sprays     —— 只挑 PESTICIDE，还要处理 legacy 兼容 + 抗性衰减
 *   ③ WeedEngine 的 controls   —— 只挑 WEED，还要区分人工/化学
 *   ④ SoilEngine 的 drains/irrigates/nByDate —— 再挑一遍 DRAIN/IRRIGATE/FERT
 *   ⑤ DisasterEngine 的 drains/irrigates —— 又挑一遍 DRAIN/IRRIGATE
 * </pre>
 * 每条通路都<b>各自重新遍历一遍</b>、<b>各自重新判断一次类型</b>。
 * 后果不是「慢」，而是<b>口径会漂移</b>：
 * <ul>
 *   <li>2026-10-25：窗口口径复制在 4 个地方，只修了 {@code weeds} 一处，
 *       另外三处仍按 {@code sow} 起算 → 天气与 DVS 轨迹整体错位。</li>
 *   <li>2026-10-26：{@code soil()} 把 {@code DRY_FIELD}/{@code DRAIN_FINAL}
 *       算作排水，而 {@code disasters()} 只认 {@code DRAIN} ——
 *       两边对「排水」的定义不同，却没有一处注释说明为什么。</li>
 * </ul>
 *
 * <p><b>解法</b>：把「分类」这件事<b>收成一处</b>。本类在一轮里把农事分成
 * 若干个<b>有名字的投影</b>，每个引擎拿自己那份。
 * 加新类型 / 改口径时只改这一个类，五个引擎不会再各说各话。
 *
 * <p><b>设计取舍</b>：
 * <ul>
 *   <li><b>不缓存、不持久化</b>：它是一次请求内的派生值，跟着 {@link SeasonContext} 走。</li>
 *   <li><b>口径差异要显式声明</b>：见 {@link #drainDates()} 与 {@link #disasterDrainDates()}。
 *       「土壤排水」与「灾害排水」口径不同是有理由的（见两处注释），
 *       但以前它们藏在两个方法体里，没人看得到；现在摆在同一个类里，一眼可比。</li>
 *   <li><b>纯计算</b>：不依赖 Spring，可直接单元测试。</li>
 * </ul>
 */
public final class ActionBundle {

    // ==================================================================
    //  分类结果
    // ==================================================================

    /**
     * 一次施用农药的「原始事实」。
     *
     * @param date    施用日期
     * @param item    对应农资；<b>null = legacy 记录</b>（老数据没有 itemId）
     * @param itemId  农资 id（legacy 时为 null）
     * @param dose    实际用量（legacy 时为 null）
     * @param legacy  是否是「无 itemId 的旧记录」
     */
    public record SprayAction(LocalDate date, InputCatalog.Item item, String itemId,
                              Double dose, boolean legacy) {
    }

    /**
     * 一次除草动作的「原始事实」。
     *
     * @param date     动作日期
     * @param chemical 是否化学除草（有 HERB 类 itemId）
     */
    public record WeedAction(LocalDate date, boolean chemical) {
    }

    /**
     * 一次施肥的「原始事实」。
     *
     * @param date    施肥日期
     * @param item    对应农资；null = legacy 通用施肥
     * @param itemId  农资 id
     * @param dose    实际用量
     * @param legacy  是否是「无 itemId 的旧记录」
     */
    public record FertilizeAction(LocalDate date, InputCatalog.Item item, String itemId,
                                   Double dose, boolean legacy) {
    }

    // ==================================================================
    //  分类入口
    // ==================================================================

    /**
     * 把窗口内农事一次性分好类。
     *
     * <p>⚠️ <b>窗口裁剪不在这里做</b>：调用方传入的 {@code actions}
     * 应当已经是窗口内的（{@code ctx.actions()}）。工序效果的「按持续天数摊到多天」
     * 会越过窗口边界，所以这里保留全部日期，由 {@link SeasonContext} 的窗口在调用侧裁剪 ——
     * 窗口口径的唯一来源是 {@link SeasonContext#windowStart}，不在这里另写一遍。
     *
     * @param actions 窗口内农事（{@code ctx.actions()}）
     * @return 分类结果
     */
    public static ActionBundle of(List<FarmAction> actions) {
        List<FarmAction> src = actions == null ? List.of() : actions;

        List<SprayAction> pesticides = new ArrayList<>();
        List<WeedAction> weeds = new ArrayList<>();
        List<FertilizeAction> fertilizes = new ArrayList<>();

        // 土壤口径：泡田/晒田/收前排水都会真的改变水层
        Set<LocalDate> soilDrains = new LinkedHashSet<>();
        Set<LocalDate> soilIrrigates = new LinkedHashSet<>();
        // 灾害口径：只有「清沟排水」才缓解渍涝
        Set<LocalDate> disasterDrains = new LinkedHashSet<>();
        // 灾害口径：只有真正的 IRRIGATE 才算「灌水调温」，PUDDLE 只建浅水层
        Set<LocalDate> disasterIrrigates = new LinkedHashSet<>();

        Map<LocalDate, List<SimEngine.ActionEffect>> effectsByDate = new LinkedHashMap<>();

        for (FarmAction a : src) {
            if (a == null || a.getType() == null || a.getDate() == null) {
                continue;
            }
            LocalDate date = a.getDate();
            InputCatalog.Item it = InputCatalog.get(a.getItemId());
            boolean legacy = it == null;

            switch (a.getType()) {
                case PESTICIDE ->
                        pesticides.add(new SprayAction(date, it, a.getItemId(), a.getDose(), legacy));
                case WEED ->
                        weeds.add(new WeedAction(date, it != null && it.kind() == InputCatalog.Kind.HERB));
                case FERTILIZE, BASE_FERT, TILLER_FERT, PANICLE_FERT ->
                        fertilizes.add(new FertilizeAction(date, it, a.getItemId(), a.getDose(), legacy));
                case DRAIN -> {
                    soilDrains.add(date);
                    disasterDrains.add(date);
                }
                case DRY_FIELD, DRAIN_FINAL -> soilDrains.add(date);
                case IRRIGATE -> {
                    soilIrrigates.add(date);
                    disasterIrrigates.add(date);
                }
                case PUDDLE -> soilIrrigates.add(date);
                default -> { }
            }

            // 生长引擎的工序效果：按「持续天数」摊到多天
            SimEngine.ActionEffect base = SimEngine.ActionEffect.of(a.getType().name());
            int dur = a.getDurationDays() == null ? 1 : a.getDurationDays();
            for (int d = 0; d < dur; d++) {
                effectsByDate.computeIfAbsent(date.plusDays(d), k -> new ArrayList<>()).add(base);
            }
        }

        return new ActionBundle(pesticides, weeds, fertilizes,
                soilDrains, soilIrrigates, disasterDrains, disasterIrrigates, effectsByDate);
    }

    private final List<SprayAction> pesticides;
    private final List<WeedAction> weeds;
    private final List<FertilizeAction> fertilizes;
    private final Set<LocalDate> soilDrains;
    private final Set<LocalDate> soilIrrigates;
    private final Set<LocalDate> disasterDrains;
    private final Set<LocalDate> disasterIrrigates;
    private final Map<LocalDate, List<SimEngine.ActionEffect>> effectsByDate;

    private ActionBundle(List<SprayAction> pesticides,
                         List<WeedAction> weeds,
                         List<FertilizeAction> fertilizes,
                         Set<LocalDate> soilDrains,
                         Set<LocalDate> soilIrrigates,
                         Set<LocalDate> disasterDrains,
                         Set<LocalDate> disasterIrrigates,
                         Map<LocalDate, List<SimEngine.ActionEffect>> effectsByDate) {
        this.pesticides = List.copyOf(pesticides);
        this.weeds = List.copyOf(weeds);
        this.fertilizes = List.copyOf(fertilizes);
        this.soilDrains = Set.copyOf(soilDrains);
        this.soilIrrigates = Set.copyOf(soilIrrigates);
        this.disasterDrains = Set.copyOf(disasterDrains);
        this.disasterIrrigates = Set.copyOf(disasterIrrigates);
        Map<LocalDate, List<SimEngine.ActionEffect>> copy = new LinkedHashMap<>();
        effectsByDate.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.effectsByDate = Collections.unmodifiableMap(copy);
    }

    // ==================================================================
    //  各引擎的投影
    // ==================================================================

    /**
     * 生长引擎用的工序效果（按日期分组）。
     *
     * <p>⚠️ 调用方需要自己按回放窗口裁剪日期 —— 本类<b>不</b>做窗口判断，
     * 因为「窗口」是请求级上下文的事（{@link SeasonContext}），不是分类的事。
     */
    public Map<LocalDate, List<SimEngine.ActionEffect>> effectsByDate() {
        return effectsByDate;
    }

    /** 施用农药的原始事实。 */
    public List<SprayAction> pesticides() {
        return pesticides;
    }

    /** 除草动作的原始事实。 */
    public List<WeedAction> weeds() {
        return weeds;
    }

    /** 施肥动作的原始事实。 */
    public List<FertilizeAction> fertilizes() {
        return fertilizes;
    }

    /**
     * 排水日期（<b>土壤引擎</b>口径）。
     *
     * <p>含 {@code DRAIN} / {@code DRY_FIELD} / {@code DRAIN_FINAL}：
     * 晒田与收前排水都会真的把水层排空，土壤引擎必须看到。
     */
    public Set<LocalDate> drainDates() {
        return soilDrains;
    }

    /**
     * 灌水日期（<b>土壤引擎</b>口径）。
     *
     * <p>含 {@code IRRIGATE} / {@code PUDDLE}：泡田建立浅水层也是加水，
     * 土壤引擎按「补水」处理。
     */
    public Set<LocalDate> irrigateDates() {
        return soilIrrigates;
    }

    /**
     * 排水日期（<b>灾害引擎</b>口径）。
     *
     * <p>🔴 <b>与 {@link #drainDates()} 的差异是有意的</b>：
     * 灾害引擎的「排水缓解」指的是<b>清沟排水</b>（暴雨后把沟排开，降低渍涝），
     * 而 {@code DRY_FIELD}（够苗晒田）与 {@code DRAIN_FINAL}（收前排水）
     * 是<b>主动的水层管理</b>，跟「防涝」无关 —— 把它们算进去会让玩家
     * 「收前排水」意外消掉渍涝灾害的损失，这是错的。
     *
     * <p>🔴 以前这两个口径分别藏在 {@code soil()} 与 {@code disasters()} 的方法体里，
     * 没有任何注释说明差异存在；现在放在同一个类里，一眼能看出「哪里不同、为什么」。
     */
    public Set<LocalDate> disasterDrainDates() {
        return disasterDrains;
    }

    /**
     * 灌水日期（<b>灾害引擎</b>口径）。
     *
     * <p>🔴 <b>与 {@link #irrigateDates()} 的差异是有意的</b>：
     * 灾害的「灌水调温」只认真正的 {@code IRRIGATE}；
     * {@code PUDDLE}（泡田）只建一层浅水，<b>不调温</b>，
     * 所以灾害口径<b>不含</b>它 —— 含了会让玩家「泡一次田」就白拿高温抗性。
     */
    public Set<LocalDate> disasterIrrigateDates() {
        return disasterIrrigates;
    }
}
