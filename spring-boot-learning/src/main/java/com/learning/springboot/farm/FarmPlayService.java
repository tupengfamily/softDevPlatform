package com.learning.springboot.farm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.farm.engine.CropConfig;
import com.learning.springboot.farm.engine.DayWeather;
import com.learning.springboot.farm.engine.GerminationEngine;
import com.learning.springboot.farm.engine.RiceConfig;
import com.learning.springboot.farm.engine.DisasterEngine;
import com.learning.springboot.farm.engine.InputCatalog;
import com.learning.springboot.farm.engine.PestEngine;
import com.learning.springboot.farm.engine.SimEngine;
import com.learning.springboot.farm.engine.ClimateModel;
import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.SoilEngine;
import com.learning.springboot.farm.engine.SuitabilityEngine;
import com.learning.springboot.farm.engine.WeedEngine;
import com.learning.springboot.service.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「稼穑」玩法服务。
 *
 * <p>三条实现原则（改之前先读）：
 * <ol>
 *   <li><b>状态由日期推导，不落库</b>：只存 {@code sowDate}，DVS/积温/损伤每次重算。
 *       同一块地同一天永远算出同一个结果。</li>
 *   <li><b>不留双份口径</b>：产量、物候、热害档全部由引擎给，Service 只做搬运与格式化。</li>
 *   <li><b>演示模式显式化</b>：{@code farm.demo-mode=true} 时允许"手动推进天数"，
 *       并在返回体里带 {@code demoMode=true} 让 UI 如实标注。正式环境关掉即为 1:1。</li>
 * </ol>
 */
@Service
public class FarmPlayService {

    private static final Logger log = LoggerFactory.getLogger(FarmPlayService.class);

    /** 游戏编码（需与 GameService.SUPPORTED_GAMES 同步放行） */
    public static final String GAME_CODE = "farm";

    /** 单用户地块上限（每个地块独立作物 / 播期 / 农事）。 */
    public static final int MAX_PLOTS = 100;

    /**
     * 单个农场的地块上限（2026-10-22）。
     *
     * <p>需求：「每个农场最多建 12 块地」。注意这与 {@link #MAX_PLOTS}（用户级总量）
     * 是两个维度：用户可以有多个农场，但<b>每个农场</b>的地块数不超过 12。
     */
    public static final int MAX_PLOTS_PER_FARM = 12;

    /** 水稻收购价（元/kg，2025 年最低收购价中晚籼 1.29 元/斤）。 */
    private static final double RICE_PRICE = 2.58;

    /**
     * 各作物收购价（元/kg，2025 年最低收购价 / 产地批发价量级）。
     *
     * <p>园艺作物价格波动远大于粮食：这里取<b>产地中位价</b>，不含冷链与损耗。
     * 花卉按 kg 当量折算（切花约 40g/支、25g/支），与其它作物同一套结算口径。
     */
    private static double priceOf(String cropId) {
        return switch (cropId == null ? "rice_mid" : cropId) {
            // 粮食
            case "wheat_winter" -> 2.40;
            case "corn_spring" -> 2.30;
            case "soybean" -> 5.00;
            // 蔬菜
            case "cabbage" -> 1.20;
            case "tomato" -> 4.50;
            case "cucumber" -> 3.20;
            case "chili" -> 6.00;
            // 水果
            case "strawberry" -> 20.00;
            case "watermelon" -> 2.00;
            case "melon" -> 5.00;
            // 花卉
            case "chrysanthemum" -> 15.00;
            case "sunflower" -> 8.00;
            case "tulip" -> 25.00;
            default -> RICE_PRICE;
        };
    }

    /** 可收获的 DVS 门槛（黄熟期起点） */
    private static final double HARVEST_DVS = 0.92;

    /** 演示推进上限：约 10 年，够跑十季；1:1 正式模式不生效 */
    private static final int MAX_DEMO_OFFSET_DAYS = 3650;

    /** 江西中稻适宜播期窗口（4/20 – 6/20），窗口外重开会自动落到下一个 5/20 */
    private static final java.time.MonthDay SOW_START = java.time.MonthDay.of(4, 20);
    private static final java.time.MonthDay SOW_END = java.time.MonthDay.of(6, 20);
    private static final java.time.MonthDay SOW_PREFERRED = java.time.MonthDay.of(5, 20);

    /** 新手接手时希望"剩余 6–10 天成熟"——按 D11 的 7±3 天硬约束 */
    private static final double INHERIT_DVS_MIN = 0.90;
    private static final double INHERIT_DVS_MAX = 0.995;

    private final FarmPlotRepository plotRepo;
    private final FarmSteadRepository steadRepo;
    private final FarmWeatherMockRepository mockRepo;
    private final FarmActionRepository actionRepo;
    private final FarmSeasonRepository seasonRepo;
    private final FarmWeather weather;
    private final GameService gameService;
    /** 默认作物（老数据没有 crop_id 时按水稻处理） */
    private static final CropConfig DEFAULT_CROP = RiceConfig.indicaMidSeason();

    private static String cropIdOf(FarmPlot plot) {
        String id = plot == null ? null : plot.getCropId();
        return (id == null || id.isBlank()) ? "rice_mid" : id;
    }

    private static CropConfig cropOf(FarmPlot plot) {
        return CropCatalog.get(cropIdOf(plot)).config();
    }

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 回放结果记忆化。
     *
     * <p>为什么不是"快照表"：实测 /state 全链路 6–10ms（引擎本身 &lt;1ms，HTTP + JSON 占大头），
     * 一季 150 天全量回放远不是瓶颈；而快照表要额外处理"快照与动作表不一致"的失效逻辑，
     * 收益为负。这里用内存记忆化：同一地块 + 同一结算日 + 玩家没做新动作 → 直接复用，
     * 命中场景是"面板刷新 / 多个接口同帧请求"，同时为将来多地块 + 真实气象 API 留出余量。
     *
     * <p>失效方式：所有写路径调用 {@link #invalidateSim(Long)} 递增该用户 epoch，key 里带 epoch。
     * 进程重启后 epoch 与 cache 同时归零，天然一致。
     */
    private final java.util.Map<String, SimEngine.SimResult> simCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long, Long> simEpoch = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SIM_CACHE_MAX = 1024;

    /**
     * 回放窗口上限（天）。
     *
     * <p>窗口是「以今天结尾、往前最多 N 天」，不是「从播种日起 N 天」。
     * 后者会让播种已久的地块**丢失最近的所有农事记录**（操作写进库了，
     * 但引擎回放时走不到那一天，玩家看到的是「点了没反应」）。
     *
     * <p>430 天覆盖了一季中稻（130–150 天）与晚播越冬作物，够用。
     */
    private static final int MAX_REPLAY_DAYS = 430;

    @Value("${farm.demo-mode:true}")
    private boolean demoMode;

    public FarmPlayService(FarmPlotRepository plotRepo,
                           FarmSteadRepository steadRepo,
                           FarmWeatherMockRepository mockRepo,
                           FarmActionRepository actionRepo,
                           FarmSeasonRepository seasonRepo,
                           FarmWeather weather,
                           GameService gameService) {
        this.plotRepo = plotRepo;
        this.steadRepo = steadRepo;
        this.mockRepo = mockRepo;
        this.actionRepo = actionRepo;
        this.seasonRepo = seasonRepo;
        this.weather = weather;
        this.gameService = gameService;
        // 启动时按 userId 分组, 把所有用户的 mock 灌入内存。
        // 注意: ApplicationReadyEvent 之后所有 mock 写入都会走 applyMock/resetMock,
        // 内存层与 DB 始终一致, 不需要再监听。
        java.util.Map<Long, java.util.List<FarmWeatherMock>> byUser = new java.util.HashMap<>();
        for (FarmWeatherMock m : mockRepo.findAll()) {
            byUser.computeIfAbsent(m.getUserId(), k -> new java.util.ArrayList<>()).add(m);
        }
        byUser.forEach(weather::loadUserMocks);
    }

    // ==================================================================
    //  取地块 / 建地块
    // ==================================================================

    @Transactional
    public FarmPlot getOrCreate(Long userId) {
        return getOrCreate(userId, 0);
    }

    /**
     * 取（或创建）指定槽位的地块。
     *
     * <p>slot 0 走「接手一块已经在长的地」逻辑，其余槽位默认是空地，
     * 等玩家在选地流程里确认作物与播期（zoneChosen = false）。
     */
    @Transactional
    public FarmPlot getOrCreate(Long userId, int slot) {
        int s0 = Math.max(0, Math.min(MAX_PLOTS - 1, slot));
        return plotRepo.findByUserIdAndSlot(userId, s0).orElseGet(() -> createInherited(userId, s0));
    }

    /** 该用户的全部地块（按槽位升序）。 */
    @Transactional(readOnly = true)
    public List<FarmPlot> listPlots(Long userId) {
        List<FarmPlot> list = plotRepo.findByUserIdOrderBySlotAsc(userId);
        if (list.isEmpty()) {
            list = new ArrayList<>();
            list.add(getOrCreate(userId, 0));
        }
        return list;
    }

    /** 新建一块地：找下一个空槽位（不传 slot 时）。 */
    @Transactional
    public int nextFreeSlot(Long userId) {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (FarmPlot p : plotRepo.findByUserIdOrderBySlotAsc(userId)) {
            used.add(p.getSlot());
        }
        for (int i = 0; i < MAX_PLOTS; i++) {
            if (!used.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 首次进入：按 D11 接手一块「已经在长」的地，而不是空地。
     *
     * <p>做法是**反解播种日**：从"今天往前 120–138 天"里挑一个，使当前 DVS 落在
     * {@code [0.90, 0.995]}（即还剩 6–10 天可收）。挑不到就用最接近的。
     * 这样玩家第一天进来就能做几件事、一周内收到第一茬，不必等 140 天。
     */
    private FarmPlot createInherited(Long userId, int slot) {
        if (plotRepo.countByUserId(userId) >= MAX_PLOTS) {
            throw new BusinessException("地块数量已达上限（" + MAX_PLOTS + " 块）");
        }
        if (slot > 0) {
            // 新地块：先给一块「还没种」的空地，等玩家选作物与播期
            FarmPlot empty = new FarmPlot();
            empty.setUserId(userId);
            empty.setSlot(slot);
            empty.setSowDate(LocalDate.now());
            empty.setZoneChosen(false);
            empty.setSeasons(0);
            empty.setBestYield(0.0);
            empty.setDemoOffsetDays(0);
            return plotRepo.save(empty);
        }
        LocalDate today = LocalDate.now();
        LocalDate bestSow = null;
        double bestScore = Double.MAX_VALUE;

        for (int offset = 118; offset <= 140; offset++) {
            LocalDate sow = today.minusDays(offset);
            SimEngine.SimResult r = replay(userId, sow, today);
            if (r == null) {
                continue;
            }
            double dvs = r.dvs();
            if (dvs >= INHERIT_DVS_MIN && dvs <= INHERIT_DVS_MAX) {
                double dist = Math.abs(dvs - 0.96); // 偏向"还剩一周"
                if (dist < bestScore) {
                    bestScore = dist;
                    bestSow = sow;
                }
            }
        }
        if (bestSow == null) {
            // 兜底：按 140 天的 94% 估
            bestSow = today.minusDays(132);
        }

        FarmPlot plot = new FarmPlot();
        plot.setUserId(userId);
        plot.setSlot(0);
        plot.setSowDate(bestSow);
        plot.setCropId("rice_mid");
        plot.setSeasons(0);
        plot.setBestYield(0.0);
        plot.setDemoOffsetDays(0);
        FarmPlot saved = plotRepo.save(plot);
        log.info("[稼穑] 用户 {} 接手地块：播种日 {}（今日 {}）", userId, bestSow, today);
        return saved;
    }

    // ==================================================================
    //  出苗 / 季节档案（2026-10-22）
    // ==================================================================

    /**
     * 评估一季的出苗情况（发芽率 + 苗期枯死风险）。
     *
     * <p>出苗窗口取「播种日 → 今天」这段天气（最多取 {@link GerminationEngine#EMERGENCE_WINDOW}
     * 天用于出苗判定，再往后取苗期窗口判枯死）。若播种日还没到（未来播期），返回默认值。
     *
     * <p>这个结果是<b>可复现</b>的：同一块地同一天永远算出同一个出苗率。
     */
    private GerminationEngine.Result germination(Long userId, FarmPlot plot, CropConfig crop) {
        LocalDate sow = plot.getSowDate();
        if (sow == null) {
            return new GerminationEngine.Result(1.0, 0, 0, false, null, null, List.of());
        }
        LocalDate today = effectiveToday(plot);
        if (sow.isAfter(today)) {
            // 还没播：出苗率未知，按 100% 处理
            return new GerminationEngine.Result(1.0, 0, 0, false, null, null, List.of());
        }
        int span = (int) Math.min(GerminationEngine.EMERGENCE_WINDOW + GerminationEngine.SEEDLING_WINDOW,
                ChronoUnit.DAYS.between(sow, today));
        List<DayWeather> days = weather.range(userId, zoneOf(plot), sow, sow.plusDays(Math.max(0, span)));
        try {
            return GerminationEngine.evaluate(days, crop);
        } catch (Exception e) {
            log.warn("[稼穑] 出苗评估失败(slot={})：{}", plot.getSlot(), e.toString());
            return new GerminationEngine.Result(1.0, 0, 0, false, null, null, List.of());
        }
    }

    /**
     * 开一条新的季节记录（建田时调用）。
     *
     * <p>季次号取该地块历史最大值 + 1，保证「同一块地第几季」连续可读。
     */
    private FarmSeason openSeason(Long userId, FarmPlot plot, CropCatalog.Crop cc,
                                  LocalDate sow, GerminationEngine.Result ger) {
        Integer max = seasonRepo.maxSeasonNo(userId, plot.getId());
        int no = (max == null ? 0 : max) + 1;
        FarmSeason s = new FarmSeason();
        s.setUserId(userId);
        s.setPlotId(plot.getId());
        s.setFarmId(plot.getFarmId());
        s.setSlot(plot.getSlot());
        s.setSeasonNo(no);
        s.setCropId(cc.id());
        s.setCropName(cc.name());
        s.setVisual(cc.config().visual());
        s.setZoneId(zoneOf(plot));
        s.setFieldName(plot.getFieldName());
        s.setSowDate(sow);
        s.setStatus(FarmSeason.Status.GROWING);
        if (ger != null) {
            s.setGermination(round(ger.rate(), 3));
        }
        return seasonRepo.save(s);
    }

    /** 把季节记录补全为「已收获」。 */
    private void closeSeason(Long userId, FarmPlot plot, LocalDate harvestDate, double dvs,
                             double yieldKgPerMu, double yieldRaw, Double germination,
                             double inputCost, double revenue, String detailJson,
                             FarmSeason.Status status, String failureType, String failureNote) {
        FarmSeason s = seasonRepo.findFirstByUserIdAndPlotIdAndStatus(
                userId, plot.getId(), FarmSeason.Status.GROWING).orElse(null);
        if (s == null) {
            // 没有 GROWING 记录（例如历史数据 / 补录），补一条归档
            CropCatalog.Crop cc = CropCatalog.get(cropIdOf(plot));
            s = openSeason(userId, plot, cc, plot.getSowDate(), null);
        }
        s.setHarvestDate(harvestDate);
        s.setDvs(round(dvs, 3));
        s.setYieldKgPerMu(round(yieldKgPerMu, 1));
        s.setYieldRaw(round(yieldRaw, 1));
        if (germination != null) {
            s.setGermination(round(germination, 3));
        }
        s.setInputCost(round(inputCost, 2));
        s.setRevenue(round(revenue, 1));
        s.setDetail(detailJson);
        s.setStatus(status);
        s.setFailureType(failureType);
        s.setFailureNote(failureNote);
        seasonRepo.save(s);
    }

    /**
     * 某农场（或用户）的季节档案列表，供农场详情页展示「种植与收获记录」。
     *
     * @param farmId 为 null 时返回该用户全部农场的记录
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> seasonHistory(Long userId, Long farmId) {
        List<FarmSeason> list;
        if (farmId == null) {
            list = seasonRepo.findByUserIdOrderBySlotAscSeasonNoAsc(userId);
        } else {
            // 🔴 2026-10-22：档案归属按**地块当前所属农场**判定，不按档案里的 farm_id 快照。
            // 玩家把地块从 A 农场转到 B 农场后，旧档案仍挂 A 的 farm_id、
            // 新一季挂 B 的 farm_id → 同一块地的历史被劈成两半，
            // A 农场显示「上一季」、B 农场显示「这一季」，两边都不是完整的这块地。
            // 正确做法：先取该农场名下**当前**的地块 id，再按地块取全部历史。
            List<Long> plotIds = plotRepo.findByUserIdAndFarmIdOrderBySlotAsc(userId, farmId)
                    .stream().map(FarmPlot::getId).toList();
            list = plotIds.isEmpty()
                    ? List.of()
                    : seasonRepo.findByUserIdAndPlotIdInOrderBySlotAscSeasonNoAsc(userId, plotIds);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (FarmSeason s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("plotId", s.getPlotId());
            // 返回**当前**农场 id（查询口径），不是档案里的建田快照——
            // 前端据此判断「这条记录属于哪个农场」，用快照会把转过场的地算到旧农场名下。
            m.put("farmId", farmId != null ? farmId : s.getFarmId());
            m.put("slot", s.getSlot());
            m.put("seasonNo", s.getSeasonNo());
            m.put("cropId", s.getCropId());
            m.put("cropName", s.getCropName());
            m.put("visual", s.getVisual());
            m.put("zoneId", s.getZoneId());
            m.put("fieldName", s.getFieldName());
            m.put("sowDate", s.getSowDate() == null ? null : s.getSowDate().toString());
            m.put("harvestDate", s.getHarvestDate() == null ? null : s.getHarvestDate().toString());
            m.put("dvs", s.getDvs());
            m.put("yieldKgPerMu", s.getYieldKgPerMu());
            m.put("yieldRaw", s.getYieldRaw());
            m.put("germination", s.getGermination());
            m.put("status", s.getStatus() == null ? null : s.getStatus().name());
            m.put("failureType", s.getFailureType());
            m.put("failureNote", s.getFailureNote());
            m.put("inputCost", s.getInputCost());
            m.put("revenue", s.getRevenue());
            m.put("detail", s.getDetail());
            out.add(m);
        }
        return out;
    }

    // ==================================================================
    //  查询
    // ==================================================================

    /**
     * 地块总览（多田渲染与地块选择用）。
     *
     * <p>每块地只跑一次 SimEngine 拿 DVS，不跑病虫害/土壤/灾害——列表页不需要那三层，
     * 100 块地也就几十毫秒。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> plotSummaries(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FarmPlot p : listPlots(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", p.getSlot());
            m.put("plotId", p.getId());
            m.put("farmId", p.getFarmId());
            m.put("cropId", p.getCropId());
            m.put("fieldName", p.getFieldName());
            m.put("zoneId", p.getZoneId());

            boolean chosen = p.getZoneChosen() != null && p.getZoneChosen();
            m.put("chosen", chosen);
            m.put("sowDate", p.getSowDate() == null ? null : p.getSowDate().toString());
            m.put("seasons", p.getSeasons());
            m.put("bestYield", p.getBestYield());
            m.put("lastYield", p.getLastYield());

            String cropId = cropIdOf(p);
            CropCatalog.Crop cc = CropCatalog.get(cropId);
            CropConfig cfg = cc.config();
            m.put("cropName", chosen ? cc.name() : null);
            m.put("visual", chosen ? cfg.visual() : null);

            double dvs = 0.0;
            GerminationEngine.Result ger = null;
            WeedEngine.Result wr = null;
            SimEngine.SimResult sim = null;
            if (chosen && p.getSowDate() != null) {
                LocalDate today = effectiveToday(p);
                if (!p.getSowDate().isAfter(today)) {
                    sim = safeReplay(userId, p, p.getSowDate(), today);
                    if (sim != null) {
                        dvs = sim.dvs();
                    }
                }
                ger = germination(userId, p, cfg);
                if (sim != null) {
                    wr = weeds(userId, p, sim, today);
                }
            }
            m.put("dvs", round(dvs, 3));
            // 出苗率与枯死（2026-10-22）：让「不合规律」变成看得见的田间表现
            m.put("germination", ger == null ? null : round(ger.rate(), 3));
            m.put("withered", ger != null && ger.withered());
            m.put("failureType", ger == null ? null : ger.failureType());
            m.put("failureNote", ger == null ? null : ger.note());
            m.put("cautions", ger == null ? List.of() : ger.cautions());
            m.put("stage", chosen ? stageOf(chosen, dvs, cfg, ger) : emptyStage(p));
            // 待播种（2026-10-22）：收完一季等玩家选下一季，前端要弹选种面板
            m.put("awaitingSowing", !chosen && everPlanted(p));
            // 杂草压力（2026-10-22 需求 O）：悬浮面板要显示「要不要除草」
            m.put("weedPressure", wr == null ? 0 : round(wr.pressure(), 3));
            m.put("weedLevel", wr == null ? WeedEngine.Level.NONE.name() : wr.level().name());
            m.put("weedLevelName", wr == null ? WeedEngine.levelCn(WeedEngine.Level.NONE)
                    : WeedEngine.levelCn(wr.level()));
            m.put("weedLossPct", wr == null ? 0 : round(wr.waterLoss() * 100, 1));
            out.add(m);
        }
        return out;
    }

    /**
     * 这块地是否处于「待播种」——上一季已结束，等玩家选下一季种什么。
     *
     * <p>判据是显式标志位 {@link FarmPlot#getAwaitingSow()}，只在
     * 「收割 / 记为绝收 / 重开一季」三条路径置位，「建田（开新一季）」清零。
     *
     * <p><b>为什么不能靠 {@code seasons > 0} 推断</b>：那是**成功收获**的季数，
     * 苗期枯死（绝收）与中途重开都不增加它，用它判断会出现
     * 「枯死过的地又变回待开垦」的错判。
     * <b>也不能靠 {@code cropId != null}</b>：首次进入时 {@code createInherited}
     * 会先写一个默认作物占位（否则回放无从下手），那时的地其实是「待开垦」。
     */
    private static boolean everPlanted(FarmPlot p) {
        return p.getAwaitingSow()
                || p.getSeasons() > 0
                || p.getLastHarvestDate() != null;
    }

    /**
     * 物候阶段文案（2026-10-22）。
     *
     * <p>新增两个与实际表现相关的状态：
     * <ul>
     *   <li><b>苗期枯死</b>——「枯死」，盖过一切物候（苗都没了，谈不上抽穗）；</li>
     *   <li><b>出苗率低</b>——「出苗不整齐（缺苗 X%）」，提醒玩家这块田群体偏小。</li>
     * </ul>
     * 否则「未出苗」会一直挂着，玩家看不出到底发生了什么。
     */
    private String stageOf(boolean chosen, double dvs, CropConfig cfg, GerminationEngine.Result ger) {
        if (!chosen) {
            return "待播种";
        }
        if (ger != null && ger.withered()) {
            return "枯死";
        }
        if (dvs <= 0) {
            return "未出苗";
        }
        String stage = safePhenology(dvs, cfg);
        if (ger != null && ger.rate() < 0.6 && dvs < 0.4) {
            return "出苗不整齐（缺苗 " + Math.round((1 - ger.rate()) * 100) + "%）";
        }
        return stage;
    }

    /**
     * 未种作物的地块状态文案（2026-10-22）。
     *
     * <p>区分两种「空地」：
     * <ul>
     *   <li><b>待开垦</b>——从没种过（seasons=0 且没收获过）：玩家还没开这块地；</li>
     *   <li><b>待播种</b>——收完一季，等玩家决定下一季种什么（2026-10-22 新增的行为）。</li>
     * </ul>
     * 两者都要点进去选作物，但提示语不同——玩家该知道「这是刚收完等下一季」
     * 还是「这块地还空着」。
     */
    private String emptyStage(FarmPlot plot) {
        if (everPlanted(plot)) {
            return "待播种";
        }
        return "待开垦";
    }

    /**
     * 回放一块地，异常时降级为「这块地算不出来」而不是把整个列表炸掉。
     *
     * <p><b>为什么需要这层防御（2026-10-21）</b>：{@code replay} 依赖地块上的
     * {@code zoneId} / {@code cropId} / {@code sowDate}。这些值是历史数据，
     * 可能来自旧版本或外部导入，一旦有一块地算不通（例如 zoneId 不在气候区表里），
     * 异常会一路冒到 {@code /board}，**整个看板 500，用户所有农场都看不到**——
     * 一块坏地拖垮全部农场，这在生产上不可接受。
     *
     * <p>降级策略：这一块地返回 null（dvs 记 0、阶段记「未出苗」），
     * 其余地块照常返回。玩家看到的是「这块地状态异常」，而不是全站白屏。
     */
    private SimEngine.SimResult safeReplay(Long userId, FarmPlot p, LocalDate from, LocalDate to) {
        try {
            return replay(userId, p, from, to);
        } catch (Exception e) {
            log.warn("[稼穑] 地块 {} (slot={}) 回放失败，已跳过：{}", p.getId(), p.getSlot(), e.toString());
            return null;
        }
    }

    /** 同上：物候换算失败时退回「未出苗」，不让单块地把看板拖 500。 */
    private String safePhenology(double dvs, CropConfig cfg) {
        try {
            return SimEngine.phenologyAt(dvs, cfg);
        } catch (Exception e) {
            log.warn("[稼穑] 物候换算失败(dvs={})，退回未出苗：{}", dvs, e.toString());
            return "未出苗";
        }
    }

    @Transactional(readOnly = true)
    public FarmView view(Long userId, int slot) {
        FarmPlot plot = getOrCreate(userId, slot);
        CropConfig crop = cropOf(plot);
        LocalDate today = effectiveToday(plot);

        // 🔴 2026-10-22：待播种的地块（收完一季等玩家选下一季）没有"本季"可看，
        // 走专门的分支返回，避免把空地的零值当成"刚种下、什么都还没长"渲染给玩家。
        if (!plot.getZoneChosen()) {
            return idleView(userId, plot, crop, today);
        }

        SimEngine.SimResult r = replay(userId, plot, plot.getSowDate(), today);
        if (r == null) {
            throw new BusinessException("地块数据异常：无法回放天气");
        }

        PestEngine.Result pr = pests(userId, plot, r, today);
        DisasterEngine.Result dr = disasters(userId, plot, r);
        SoilEngine.Result sr = soil(userId, plot, r, today);
        List<FarmAction> acts = seasonActions(userId, plot, plot.getSowDate(), today);
        List<OpChain.Step> chain = OpChain.build(plot.getSowDate(), r.dvsByDay(), acts,
                r.dvs(), r.dvs() >= HARVEST_DVS, cropIdOf(plot));
        // 出苗评估（2026-10-22）：出苗率折进群体密度，枯死直接绝收
        GerminationEngine.Result ger = germination(userId, plot, crop);
        // 杂草压力（2026-10-22 需求 O）：争水争光争肥，折进产量
        WeedEngine.Result wr = weeds(userId, plot, r, today);
        Yields y = yields(r, pr, dr, sr, chain, crop, ger, wr);

        String zoneId = zoneOf(plot);
        DayWeather w = weather.forDate(userId, zoneId, today);
        double display = SimEngine.heatDisplay(w.tmax(), SimEngine.profileAt(r.dvs(), crop));
        int heatTier = display <= 0 ? 0 : (display < 0.34 ? 1 : (display < 0.67 ? 2 : 3));

        String stage = stageOf(plot.getZoneChosen(), r.dvs(), crop, ger);
        String nextMilestone = nextMilestone(r.dvs());
        // 已可收割时不再给还差几天——那会让人以为要等
        int daysToNext = r.dvs() >= HARVEST_DVS
                ? 0
                : estimateDaysTo(userId, zoneId, plot.getSowDate(), today, r.dvs(), nextBoundary(r.dvs()), crop);

        List<String> changes = new ArrayList<>();
        changes.add(String.format("DVS %.3f → %s", r.dvs(), stage));
        // 出苗情况与风险（2026-10-22）
        if (ger != null) {
            if (ger.withered()) {
                changes.add("苗期枯死：" + ger.note() + "。本季已无产量，可重开一季。");
            } else if (ger.rate() < 0.85) {
                changes.add(String.format("出苗率 %.0f%%（缺苗 %.0f%%），群体偏小，产量会相应打折",
                        ger.rate() * 100, (1 - ger.rate()) * 100));
            }
            for (String c : ger.cautions()) {
                changes.add(c);
            }
        }
        if (heatTier >= 2) {
            changes.add("今日最高温触及热害阈值（展示层 " + heatTier + " 档），穗部温度需关注");
        }
        // 杂草提示（2026-10-22 需求 O）
        if (wr != null && wr.level() != WeedEngine.Level.NONE) {
            changes.add(WeedEngine.levelCn(wr.level()) + "：" + WeedEngine.advice(wr.level())
                    + "（正在跟作物争水争肥，产量已因此少 " + String.format("%.1f", wr.waterLoss() * 100) + "%）");
        }
        if (r.dvs() >= HARVEST_DVS) {
            changes.add("已到黄熟期，可以收割");
        }
        if (heatTier == 0 && r.dvs() < HARVEST_DVS) {
            changes.add("今日天气平稳，作物按积温正常推进");
        }
        if ("DROUGHT".equals(sr.status())) {
            changes.add("田面已落干，土壤含水偏低，建议灌水");
        } else if ("DRY".equals(sr.status())) {
            changes.add("田面偏干，注意保持浅水层");
        }
        if (sr.waterMm() > 90) {
            changes.add("水层偏深，长期深水易伤根");
        }
        for (DisasterEngine.Event ev : dr.events()) {
            if (today.toString().equals(ev.date())) {
                changes.add(String.format("灾害：%s（%s）", ev.name(), ev.note()));
            }
        }
        for (PestEngine.Instance pi : pr.instances()) {
            if (!pi.detected()) {
                continue;
            }
            changes.add(String.format("病虫害：%s（%s），病情指数 %d，已减产约 %.0f%%",
                    pi.name(), statusCn(pi.severity()), Math.round(pi.severity() * pi.incidence() * 100),
                    (pi.severity() * pi.incidence() * 0.35) * 100));
        }

        List<Map<String, Object>> recent = new ArrayList<>();
        try {
            Map<String, Object> stats = gameService.stats(userId, GAME_CODE, 5);
            Object rounds = stats.get("recent");
            if (rounds instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("score", m.get("score"));
                        item.put("playSeconds", m.get("playSeconds"));
                        item.put("createTime", m.get("createTime"));
                        recent.add(item);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[稼穑] 取历史成绩失败（不影响主流程）: {}", e.getMessage());
        }

        SuitabilityEngine.Report suit = suitabilityOf(zoneId, plot.getSowDate(), cropIdOf(plot));

        return new FarmView(
                today.toString(),
                demoMode,
                weather.sourceLabel(userId, zoneId),
                new FarmView.PlotView(
                        plot.getId() == null ? null : String.valueOf(plot.getId()),
                        cropIdOf(plot), crop.name(), crop.visual(),
                        plot.getFieldName() == null ? defaultFieldName(zoneId) : plot.getFieldName(),
                        zoneId,
                        ClimateModel.zone(zoneId).name(),
                        ClimateModel.zone(zoneId).site(),
                        plot.getZoneChosen(),
                        plot.getSowDate().toString(),
                        suit.level(), suit.levelCode(), suit.levelName(), suit.pPass(),
                        suit.dataSource(),
                        demoMode),
                new FarmView.CropView(
                        crop.id(), crop.name(), round(r.dvs(), 4), stage,
                        nextMilestone, daysToNext,
                        round(r.aatCum(), 0), round(crop.aatReq(), 0),
                        r.headingDate(), r.matureDate(),
                        plot.getSowDate().toString(),
                        r.dvs() >= HARVEST_DVS,
                        r.frostStop(),
                        // 出苗率与枯死（2026-10-22）
                        ger == null ? 1.0 : round(ger.rate(), 3),
                        ger != null && ger.withered(),
                        ger == null ? null : ger.failureType(),
                        ger == null ? null : ger.note()),
                new FarmView.WeatherView(
                        today.toString(), w.tmax(), w.tmin(), w.precip(), heatTier),
                new FarmView.YieldView(
                        round(y.panicles(), 0), round(y.grains(), 1), round(y.fertility(), 3),
                        round(y.weight(), 2), round(y.ey(), 1),
                        round(r.dmg().panicle(), 3), round(r.dmg().floret(), 3),
                        round(r.dmg().fertility(), 3), round(r.dmg().weight(), 3)),
                pestViews(pr),
                round(pr.totalLoss() * 100, 1),
                eventViews(dr),
                round(dr.totalLoss() * 100, 1),
                new FarmView.SoilView(
                        sr.waterMm(), sr.rwc(), sr.status(), sr.dryDays(), sr.waterlogDays(),
                        sr.sfi(), sr.soilCoef(), sr.droughtLoss(), sr.waterlogLoss(),
                        sr.etToday(), sr.percToday(),
                        sr.nApplied(), sr.nNeed(), sr.nSatisfaction()),
                new FarmView.WeedView(
                        round(wr.pressure(), 3), wr.level().name(),
                        WeedEngine.levelCn(wr.level()),
                        round(wr.waterLoss() * 100, 1),
                        wr.onsetDate(), wr.manualWeeds(), wr.herbicideDays(),
                        WeedEngine.advice(wr.level())),
                opViews(chain),
                inputViews(userId, plot, today, r, sr),
                inputSummary(userId, plot, today, r, sr, y.ey()),
                plot.getSeasons(),
                round(plot.getBestYield(), 1),
                plot.getLastHarvestDate() == null ? null : plot.getLastHarvestDate().toString(),
                plot.getLastYield() == null ? null : round(plot.getLastYield(), 1),
                changes,
                recent);
    }

    /**
     * 待播种地块的视图（2026-10-22）。
     *
     * <p><b>为什么要单独一条分支</b>：收割后地块进入「待播种」——此时既没有本季作物，
     * 也没有可回放的物候轨迹。如果沿用 {@link #view} 的正常路径：
     * <ul>
     *   <li>{@code replay} 会拿「上一季的播种日」去回放，得到的 DVS 是上一季的尾巴；</li>
     *   <li>病虫害/灾害/土壤三层会拿着上一季的动作重放，玩家看到的是「已经收完的地还在发病」；</li>
     *   <li>更糟的是 {@code harvest} 之后 {@code zoneChosen=false}，
     *       但 {@code cropId} 还留着上一季的作物，前端会把「上一季的作物」当成「本季种了什么」。</li>
     * </ul>
     * 所以待播种时本季的所有「生长相关」字段一律清零，只保留**上一季的历史成绩**，
     * 并在 {@code todayChanges} 里明确告诉玩家：这块地在等他做决定。
     *
     * <p>这里<b>不返回</b>「选什么作物」的推荐——那是前端选种面板的事，
     * 后端只保证「任何区、任何时间、任何作物都能种」（S23 §2.1）。
     */
    private FarmView idleView(Long userId, FarmPlot plot, CropConfig crop, LocalDate today) {
        String zoneId = zoneOf(plot);
        // 🔴 2026-10-22 修复：这里原来用 `seasons > 0 || lastHarvestDate != null`
        // 判断「这块地是不是已经种过」。但绝收（苗期枯死）季既不增加 seasons、
        // 也不写 lastHarvestDate —— 玩家种过一季却枯死了，地块会被判成「待开垦」，
        // 与「从没开过这块地」混淆。统一走 everPlanted()（含显式的 awaiting_sow 标志）。
        boolean everPlanted = everPlanted(plot);
        String stage = everPlanted ? "待播种" : "待开垦";

        // 适区评级：按「上一季的作物 + 上次的播期」给一份参考。
        // 玩家下一步会重新选作物与播期，这里只是让他知道这块地大概是什么条件。
        SuitabilityEngine.Report suit = suitabilityOf(zoneId,
                plot.getSowDate() == null ? today : plot.getSowDate(), cropIdOf(plot));

        List<String> changes = new ArrayList<>();
        if (everPlanted) {
            changes.add("这块地上一季已结束"
                    + (plot.getSeasons() > 0 ? "（第 " + plot.getSeasons() + " 季）" : "")
                    + (plot.getLastHarvestDate() == null ? "" : "，收获日 " + plot.getLastHarvestDate())
                    + "。现在等待你选择下一季种什么、什么时候种。");
            if (plot.getLastYield() != null) {
                changes.add(String.format("上一季亩产 %.1f kg/亩（历史最好 %.1f kg/亩）",
                        plot.getLastYield(), plot.getBestYield()));
            }
        } else {
            changes.add("这块地还没开垦。先选一个农业区、一种作物和一个播期，系统会告诉你这样种大概会发生什么。");
        }
        changes.add("任何区域、任何时间都可以种任何作物——系统不拦你，只告诉你这一季大概率会发生什么。");

        List<Map<String, Object>> recent = new ArrayList<>();
        try {
            Map<String, Object> stats = gameService.stats(userId, GAME_CODE, 5);
            Object rounds = stats.get("recent");
            if (rounds instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("score", m.get("score"));
                        item.put("playSeconds", m.get("playSeconds"));
                        item.put("createTime", m.get("createTime"));
                        recent.add(item);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[稼穑] 取历史成绩失败（不影响主流程）: {}", e.getMessage());
        }

        // 土壤：本季没有回放，用中性值（不假装"旱了/涝了"）
        SoilEngine.Result sr = SoilEngine.Result.EMPTY;

        return new FarmView(
                today.toString(),
                demoMode,
                weather.sourceLabel(userId, zoneId),
                new FarmView.PlotView(
                        plot.getId() == null ? null : String.valueOf(plot.getId()),
                        cropIdOf(plot), crop.name(), crop.visual(),
                        plot.getFieldName() == null ? defaultFieldName(zoneId) : plot.getFieldName(),
                        zoneId,
                        ClimateModel.zone(zoneId).name(),
                        ClimateModel.zone(zoneId).site(),
                        false,
                        plot.getSowDate() == null ? today.toString() : plot.getSowDate().toString(),
                        suit.level(), suit.levelCode(), suit.levelName(), suit.pPass(),
                        suit.dataSource(),
                        demoMode),
                new FarmView.CropView(
                        cropIdOf(plot), crop.name(), 0.0, stage,
                        "选择下一季作物", 0,
                        0, round(crop.aatReq(), 0),
                        null, null,
                        plot.getSowDate() == null ? today.toString() : plot.getSowDate().toString(),
                        false, false,
                        // 待播种没有出苗率概念（还没播），统一按 100% 处理
                        1.0, false, null, null),
                new FarmView.WeatherView(today.toString(), 0, 0, 0, 0),
                new FarmView.YieldView(0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                0,
                List.of(),
                0,
                new FarmView.SoilView(
                        sr.waterMm(), sr.rwc(), sr.status(), sr.dryDays(), sr.waterlogDays(),
                        sr.sfi(), sr.soilCoef(), sr.droughtLoss(), sr.waterlogLoss(),
                        sr.etToday(), sr.percToday(),
                        sr.nApplied(), sr.nNeed(), sr.nSatisfaction()),
                // 待播种没有本季作物，就没有本季杂草（上一季的杂草随收割一起结束）
                new FarmView.WeedView(0, WeedEngine.Level.NONE.name(),
                        WeedEngine.levelCn(WeedEngine.Level.NONE), 0, null, 0, 0,
                        WeedEngine.advice(WeedEngine.Level.NONE)),
                List.of(),
                // 货架仍要展示（玩家可以先买好下一季的农资）
                inputViews(userId, plot, today, emptySim(plot), sr),
                inputSummary(userId, plot, today, emptySim(plot), sr, 0),
                plot.getSeasons(),
                round(plot.getBestYield(), 1),
                plot.getLastHarvestDate() == null ? null : plot.getLastHarvestDate().toString(),
                plot.getLastYield() == null ? null : round(plot.getLastYield(), 1),
                changes,
                recent);
    }

    /**
     * 一个「什么都没长」的模拟结果。
     *
     * <p>只给 {@code inputViews} / {@code inputSummary} 用——它们需要 DVS 来判断
     * 农资是否在适用窗口内。待播种时 DVS = 0，所有农资都不在窗口里，
     * 展示层据此显示"尚未进入适用期"，语义正确。
     */
    private SimEngine.SimResult emptySim(FarmPlot plot) {
        return new SimEngine.SimResult(
                0, 0, 0.0,
                plot.getSowDate() == null ? null : plot.getSowDate().toString(),
                null, null,
                0, 0, 0,
                new SimEngine.Damage(0, 0, 0, 0),
                0, 0, 0, 0, 0, 0, 0,
                List.of(), false, false, 0);
    }

    // ==================================================================
    //  操作
    // ==================================================================

    /**
     * 演示模式：手动推进天数。
     *
     * <p>🔴 <b>正式版必须关闭</b>（{@code farm.demo-mode=false}）。
     * 1:1 实时下"推进时间"违反基线 D2「禁止任何形式的时间加速」。
     * 这里存在的唯一理由是：平台是通用学习系统，玩家不会为了看一季稻子等 140 天。
     */
    @Transactional
    public Map<String, Object> advance(Long userId, int slot, int days) {
        invalidateSim(userId);
        if (!demoMode) {
            throw new BusinessException("演示模式未开启：正式环境不允许推进时间");
        }
        if (days < 1 || days > 60) {
            throw new BusinessException("单次推进 1–60 天");
        }
        FarmPlot plot = getOrCreate(userId, slot);
        int next = plot.getDemoOffsetDays() + days;
        if (next > MAX_DEMO_OFFSET_DAYS) {
            throw new BusinessException("演示推进已达上限（约 10 年），请重开一季");
        }
        plot.setDemoOffsetDays(next);
        plotRepo.save(plot);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("worldDate", effectiveToday(plot).toString());
        out.put("note", "演示用推进，非游戏功能");
        return out;
    }

    /**
     * 记录本季失败（绝收 / 苗期枯死）。
     *
     * <p><b>为什么需要这个独立入口（2026-10-22）</b>：苗期枯死的地块 DVS 永远停在低位，
     * {@link #harvest} 会因为「没到黄熟期」直接拒绝——玩家既收不了，也没法把这季结掉，
     * 只能一直挂着。而这恰恰是最需要被记进档案的一季（枯死原因、出苗率、投入沉没）。
     *
     * <p>语义：
     * <ul>
     *   <li>本季产量记 0，季节档案状态 {@code FAILED}，写明枯死原因；</li>
     *   <li><b>不记游戏成绩</b>（0 分污染排行榜，失败不该占榜）；</li>
     *   <li>投入照记（沉没成本要如实体现：net = 0 − 投入）；</li>
     *   <li>地块进入「待播种」，与正常收割后一致。</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> closeFailedSeason(Long userId, int slot) {
        invalidateSim(userId);
        FarmPlot plot = getOrCreate(userId, slot);
        if (!plot.getZoneChosen()) {
            throw new BusinessException("这块地当前没有在长的作物，无需结季");
        }
        CropConfig crop = cropOf(plot);
        LocalDate today = effectiveToday(plot);
        SimEngine.SimResult r = replay(userId, plot, plot.getSowDate(), today);
        if (r == null) {
            throw new BusinessException("地块数据异常：无法回放天气");
        }
        GerminationEngine.Result ger = germination(userId, plot, crop);
        boolean withered = ger != null && ger.withered();

        // 已经到黄熟期却来「记失败」→ 说明玩家点错了，应该去收割
        if (r.dvs() >= HARVEST_DVS && !withered) {
            throw new BusinessException(String.format(
                    "本季已到黄熟期（DVS %.2f），应该去收割而不是记失败", r.dvs()));
        }

        // 沉没成本：本季已经花出去的农资照记
        double inputCost = 0;
        for (FarmAction a : seasonActions(userId, plot, plot.getSowDate(), today)) {
            if (a.getCost() != null) {
                inputCost += a.getCost();
            }
        }
        inputCost = round(inputCost, 2);

        String failureType = ger == null ? null : ger.failureType();
        String failureNote = ger == null
                ? "本季未成熟（停在 DVS " + round(r.dvs(), 2) + "），按绝收记录"
                : ger.note();
        if (failureNote == null) {
            failureNote = "本季未成熟（停在 DVS " + round(r.dvs(), 2) + "），按绝收记录";
        }

        // 归因明细
        String extra;
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("crop", crop.name());
            detail.put("sowDate", plot.getSowDate().toString());
            detail.put("harvestDate", today.toString());
            detail.put("dvs", round(r.dvs(), 3));
            detail.put("yield", 0);
            detail.put("germination", ger == null ? null : round(ger.rate(), 3));
            detail.put("withered", withered);
            detail.put("failureType", failureType);
            detail.put("failureNote", failureNote);
            detail.put("inputCost", inputCost);
            detail.put("revenue", 0);
            detail.put("net", round(-inputCost, 1));
            extra = json.writeValueAsString(detail);
        } catch (Exception e) {
            extra = null;
        }

        // 地块进入待播种
        plot.setZoneChosen(false);
        plot.setAwaitingSow(true);
        plot.setDemoOffsetDays(0);
        plot.setSowDate(today);
        plot.setSeasonStartedAt(java.time.LocalDateTime.now());
        plotRepo.save(plot);

        closeSeason(userId, plot, today, r.dvs(), 0, 0,
                ger == null ? null : ger.rate(),
                inputCost, 0, extra,
                FarmSeason.Status.FAILED, failureType, failureNote);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("failed", true);
        out.put("yieldKgPerMu", 0);
        out.put("dvs", round(r.dvs(), 3));
        out.put("days", (int) ChronoUnit.DAYS.between(plot.getSowDate(), today) + 1);
        out.put("germination", ger == null ? null : round(ger.rate(), 3));
        out.put("withered", withered);
        out.put("failureType", failureType);
        out.put("failureNote", failureNote);
        out.put("inputCost", inputCost);
        out.put("revenue", 0);
        out.put("net", round(-inputCost, 1));
        out.put("awaitingSowing", true);
        out.put("nextCropPrompt", "本季已结（绝收）。这块地进入待播种状态，重新选择作物与播期。");
        return out;
    }

    /**
     * 收割。产量记入游戏中心成绩（{@code gameCode=farm}，score=亩产 kg/亩），
     * 然后地块进入下一季（播种日重置为今天）。
     */
    @Transactional
    public Map<String, Object> harvest(Long userId, int slot) {
        invalidateSim(userId);
        FarmPlot plot = getOrCreate(userId, slot);
        CropConfig crop = cropOf(plot);
        LocalDate today = effectiveToday(plot);
        SimEngine.SimResult r = replay(userId, plot, plot.getSowDate(), today);
        if (r == null) {
            throw new BusinessException("地块数据异常：无法回放天气");
        }
        if (r.frostStop() && r.dvs() < HARVEST_DVS) {
            throw new BusinessException(String.format(
                    "本季已被初霜终止（停在 DVS %.2f，没熟），无法收割。换早熟品种或提前播种，也可以重开一季。",
                    r.dvs()));
        }
        if (r.dvs() < HARVEST_DVS) {
            throw new BusinessException(String.format(
                    "还没到黄熟期（DVS %.3f < %.2f），再等等", r.dvs(), HARVEST_DVS));
        }

        PestEngine.Result pr = pests(userId, plot, r, today);
        DisasterEngine.Result dr = disasters(userId, plot, r);
        SoilEngine.Result sr = soil(userId, plot, r, today);
        List<FarmAction> acts = seasonActions(userId, plot, plot.getSowDate(), today);
        List<OpChain.Step> chain = OpChain.build(plot.getSowDate(), r.dvsByDay(), acts,
                r.dvs(), true, cropIdOf(plot));
        // 出苗评估（2026-10-22）：出苗率折进群体密度，枯死直接绝收
        GerminationEngine.Result gerEarly = germination(userId, plot, crop);
        // 杂草压力（2026-10-22 需求 O）：收割时的最终产量也要扣杂草的争水争肥
        WeedEngine.Result wrEarly = weeds(userId, plot, r, today);
        Yields y = yields(r, pr, dr, sr, chain, crop, gerEarly, wrEarly);
        double yield = round(y.ey(), 1);
        int days = (int) ChronoUnit.DAYS.between(plot.getSowDate(), today) + 1;

        // 农资核算（S12）：投入、产值、以及"最后一口药打得太晚"的农残惩罚
        double inputCost = 0;
        boolean phiViolated = false;
        String phiItem = null;
        int phiDaysShort = 0;
        int phiViolations = 0;
        for (FarmAction a : acts) {
            if (a.getCost() != null) {
                inputCost += a.getCost();
            }
            InputCatalog.Item it = InputCatalog.get(a.getItemId());
            if (it != null && it.kind() == InputCatalog.Kind.PEST) {
                long gap = ChronoUnit.DAYS.between(a.getDate(), today);
                if (gap < it.phiDays()) {
                    phiViolated = true;
                    phiViolations++;
                    // 🔴 S12 修复：原来每次违规都覆盖上一条，最后只留"最后一次施药"的信息。
                    // 但真正该提示的是**最严重**的那次（安全间隔期差得最多的）——
                    // 玩家打 3 次药，第一次差 20 天、最后一次只差 2 天，
                    // 提示却只说"差 2 天"，玩家会误判风险。改成保留最严重的一条。
                    int shortDays = (int) (it.phiDays() - gap);
                    if (shortDays > phiDaysShort) {
                        phiDaysShort = shortDays;
                        phiItem = it.name();
                    }
                }
            }
        }
        inputCost = round(inputCost, 2);
        // 农残超标：收购价打折（记为 0.85），并写进成绩归因
        double priceFactor = phiViolated ? 0.85 : 1.0;
        double cropPrice = priceOf(cropIdOf(plot));
        double revenue = round(yield * cropPrice * priceFactor, 1);

        // 归因明细塞进 extra，游戏历史里能看到"这次为什么减产"
        String extra;
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("crop", crop.name());
            detail.put("sowDate", plot.getSowDate().toString());
            detail.put("harvestDate", today.toString());
            detail.put("fertility", round(r.fertility(), 3));
            detail.put("thousandGrainWeight", round(r.weight(), 2));
            detail.put("dmgFloret", round(r.dmg().floret(), 3));
            detail.put("dmgFertility", round(r.dmg().fertility(), 3));
            detail.put("dmgWeight", round(r.dmg().weight(), 3));
            detail.put("pestLoss", round(pr.totalLoss(), 3));
            detail.put("disasterLoss", round(dr.totalLoss(), 3));
            detail.put("disasters", dr.events().stream().map(x -> x.name() + ":" + x.loss()).toList());
            detail.put("pests", pr.instances().stream()
                    .filter(PestEngine.Instance::detected)
                    .map(x -> x.name() + ":" + x.severity())
                    .toList());
            detail.put("inputCost", inputCost);
            detail.put("revenue", revenue);
            detail.put("net", round(revenue - inputCost, 1));
            detail.put("nApplied", round(sr.nApplied(), 2));
            detail.put("phiViolated", phiViolated);
            detail.put("phiViolations", phiViolations);
            // 🔴 S12 修复：产量被 yieldCap 封顶时必须留痕。
            // 原来只是 Math.min(..., yieldCap()) 静默截断，玩家看到"产量怎么到顶了"，
            // 归因表里却查不到任何一条 —— 看起来像"到顶了所以只能这样"，
            // 实际上是"你多施了肥 / 遇了 favourable 天气，但模型不认"。
            detail.put("yieldRaw", round(r.eyRaw(), 1));
            detail.put("yieldCap", round(crop.yieldCap(), 1));
            detail.put("yieldCapped", r.eyRaw() > crop.yieldCap() + 0.5);
            if (phiItem != null) {
                detail.put("phiItem", phiItem + "（超 " + phiDaysShort + " 天）");
            }
            detail.put("overdose", acts.stream().filter(a -> {
                InputCatalog.Item it = InputCatalog.get(a.getItemId());
                return it != null && a.getDose() != null && InputCatalog.overdose(it, a.getDose());
            }).count());
            detail.put("weatherSource", weather.sourceLabel(userId, plot.getZoneId()));
            extra = json.writeValueAsString(detail);
        } catch (Exception e) {
            extra = null;
        }

        // 成绩口径：农残超标的粮按 0.85 折算（不是"不能卖"，是"卖不上价"）
        int score = (int) Math.round(yield * priceFactor);
        Map<String, Object> scoreResult = gameService.saveScore(
                userId, GAME_CODE, score, 0, days, 0, 1, extra);

        // 季节档案：把这一季的种植与收获记录存档（2026-10-22）
        GerminationEngine.Result ger = germination(userId, plot, crop);
        double germRate = ger == null ? 1.0 : ger.rate();
        boolean withered = ger != null && ger.withered();
        String failureType = ger == null ? null : ger.failureType();
        String failureNote = ger == null ? null : ger.note();
        // 归因明细里补上出苗信息，让"为什么减产"能查到缺苗这一条
        if (extra != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> detail = json.readValue(extra, Map.class);
                detail.put("germination", round(germRate, 3));
                detail.put("withered", withered);
                if (failureNote != null) {
                    detail.put("failureNote", failureNote);
                }
                extra = json.writeValueAsString(detail);
            } catch (Exception ignore) {
                // extra 解析失败不影响主流程
            }
        }

        plot.setSeasons(plot.getSeasons() + 1);
        plot.setLastHarvestDate(today);
        plot.setLastYield(yield);
        if (yield > plot.getBestYield()) {
            plot.setBestYield(yield);
        }

        // 🔴 2026-10-22 行为变更：收割后**不再自动续种同一作物**。
        //
        // 原逻辑：收完直接把 sowDate 设成 nextSow（适宜播期），下一季自动还是同一种作物，
        // 玩家没有任何决策空间——「种什么」这个核心玩法被架空了。
        //
        // 新逻辑：收完地块进入「待播种」（zoneChosen=false），由玩家自己选下一季种什么、
        // 什么时候种。系统只负责告诉他这个组合大概率会发生什么（S23 §2.1：
        // 任何区任何时间都能种，只是不合适会长成那样）。
        //
        // demoOffsetDays 的自洽性：这里把偏移清零、sowDate 设为今天——
        // 待播种期间没有"本季"可回放，等玩家选完作物后 createField 会重设两者。
        plot.setZoneChosen(false);
        plot.setAwaitingSow(true);
        plot.setDemoOffsetDays(0);
        plot.setSowDate(today);
        plot.setSeasonStartedAt(java.time.LocalDateTime.now());
        plotRepo.save(plot);

        // 季节档案补全：这一季已结束
        closeSeason(userId, plot, today, r.dvs(), yield, r.eyRaw(), germRate,
                inputCost, revenue, extra,
                withered ? FarmSeason.Status.FAILED : FarmSeason.Status.HARVESTED,
                failureType, failureNote);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("yieldKgPerMu", yield);
        out.put("fertility", round(r.fertility(), 3));
        out.put("thousandGrainWeight", round(r.weight(), 2));
        out.put("days", days);
        out.put("isNewBest", scoreResult.get("isNewBest"));
        out.put("seasons", plot.getSeasons());
        out.put("nextSowDate", plot.getSowDate().toString());
        out.put("inputCost", inputCost);
        out.put("revenue", revenue);
        out.put("net", round(revenue - inputCost, 1));
        out.put("phiViolated", phiViolated);
        out.put("phiItem", phiItem);
        out.put("phiViolations", phiViolations);
        out.put("nApplied", round(sr.nApplied(), 2));
        // 出苗率与枯死（2026-10-22）
        out.put("germination", round(germRate, 3));
        out.put("withered", withered);
        out.put("failureType", failureType);
        out.put("failureNote", failureNote);
        // 待播种：告诉前端要提示玩家选下一季
        out.put("awaitingSowing", true);
        out.put("nextCropPrompt", "已收割。这块地进入待播种状态，选择下一季种什么、什么时候种。");
        // 🔴 S12 修复：产量封顶要明示，别让玩家对着"到顶了"猜原因。
        out.put("yieldRaw", round(r.eyRaw(), 1));
        out.put("yieldCap", round(crop.yieldCap(), 1));
        out.put("yieldCapped", r.eyRaw() > crop.yieldCap() + 0.5);
        if (r.eyRaw() > crop.yieldCap() + 0.5) {
            out.put("yieldCapNote", String.format(
                    "本季实际测算 %.1f kg/亩，已达该品种的产量上限 %.0f kg/亩，按上限计。",
                    r.eyRaw(), crop.yieldCap()));
        }
        return out;
    }

    /**
     * 重开一季（提前放弃当前作物）：不记成绩。
     *
     * <p>演示模式下播种日会落到**最近的水稻适宜播期**（4/20–6/20，取 5/20），
     * 而不是"今天"——否则在 10 月重开，整季都撞在低温里，积温几乎不涨，
     * 玩家会看到"过了 60 天还是秧苗"，与真实的 130–140 天生育期完全对不上。
     */
    @Transactional
    public Map<String, Object> restart(Long userId, int slot) {
        invalidateSim(userId);
        FarmPlot plot = getOrCreate(userId, slot);
        LocalDate real = LocalDate.now();

        // 旧的一季标记为「放弃」（2026-10-22）：重开是玩家的主动决定，
        // 历史档案里要如实体现，不能让它一直挂在 GROWING。
        FarmSeason prev = seasonRepo.findFirstByUserIdAndPlotIdAndStatus(
                userId, plot.getId(), FarmSeason.Status.GROWING).orElse(null);
        if (prev != null) {
            prev.setStatus(FarmSeason.Status.ABANDONED);
            prev.setHarvestDate(real);
            prev.setFailureNote("玩家提前放弃本季");
            seasonRepo.save(prev);
        }

        // 🔴 2026-10-22 行为变更：重开不再自动定作物与播期，而是回到「待播种」。
        // 理由同 harvest：种什么、什么时候种都应该是玩家的决策。
        // 原来这里把播种日钉到"最近的水稻适宜播期"，等于替玩家把两件事都定了。
        plot.setZoneChosen(false);
        plot.setAwaitingSow(true);
        plot.setDemoOffsetDays(0);
        plot.setSowDate(real);
        plot.setSeasonStartedAt(java.time.LocalDateTime.now());
        plotRepo.save(plot);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sowDate", real.toString());
        out.put("worldDate", real.toString());
        out.put("awaitingSowing", true);
        out.put("nextCropPrompt", "已放弃本季。重新选择要种的作物与播期。");
        return out;
    }

    /**
     * 下一个适宜播期：窗口内 → 原样返回；窗口前 → 今年 5/20；窗口后 → 明年 5/20。
     * 让"重开一季"永远从一个农艺上说得过去的日期开始。
     */
    static LocalDate nextSowDate(LocalDate from) {
        java.time.MonthDay md = java.time.MonthDay.from(from);
        if (!md.isBefore(SOW_START) && !md.isAfter(SOW_END)) {
            return from;
        }
        LocalDate preferred = SOW_PREFERRED.atYear(from.getYear());
        return from.isBefore(preferred) ? preferred : SOW_PREFERRED.atYear(from.getYear() + 1);
    }

    // ==================================================================
    //  测试用 mock 天气
    // ==================================================================

    /**
     * 注入一段模拟天气。🔴 演示/测试模式专用。正式部署应让 {@code farm.demo-mode=false}，
     * Controller 闸门会拦截该请求（{@code FarmController#applyMock}）。
     *
     * <p>语义：把 [fromDate, toDate] 闭区间内每一天强制使用同一组 (tmax/tmin/precip)；
     * 区间外回落到演示回放。这覆盖了"测一次连续 3 天 36℃ 高温逼熟""测一次 100mm 暴雨"
     * 两种最常见场景。多次调用会追加（不同区间可叠加）。
     */
    @Transactional
    public Map<String, Object> applyMock(Long userId, LocalDate fromDate, LocalDate toDate,
                                         double tmax, double tmin, double precip, String label) {
        invalidateSim(userId);
        if (!demoMode) {
            throw new BusinessException("演示模式未开启：正式环境不允许注入模拟天气");
        }
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            throw new BusinessException("日期范围无效：from 必须 ≤ to");
        }
        long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (days > 90) {
            throw new BusinessException("单次注入区间最多 90 天（避免大面积污染回放）");
        }
        if (tmax < tmin) {
            throw new BusinessException("tmax 必须 ≥ tmin");
        }
        if (precip < 0 || precip > 500) {
            throw new BusinessException("precip 必须在 [0, 500] mm/天");
        }

        FarmWeatherMock m = new FarmWeatherMock();
        m.setUserId(userId);
        m.setFromDate(fromDate);
        m.setToDate(toDate);
        m.setTmax(tmax);
        m.setTmin(tmin);
        m.setPrecip(precip);
        m.setLabel(label);
        mockRepo.save(m);

        // 内存层追加（与 DB 同步）
        List<FarmWeatherMock> list = new java.util.ArrayList<>(weather.mockedSegments(userId));
        list.add(m);
        weather.loadUserMocks(userId, list);

        log.info("[稼穑] 用户 {} 注入模拟天气: {} → {}  tmax={} tmin={} precip={}mm  ({})",
                userId, fromDate, toDate, tmax, tmin, precip, label);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", m.getId());
        out.put("fromDate", fromDate.toString());
        out.put("toDate", toDate.toString());
        out.put("tmax", tmax);
        out.put("tmin", tmin);
        out.put("precip", precip);
        out.put("label", label);
        out.put("totalSegments", list.size());
        return out;
    }

    /** 列出该用户当前生效的全部 mock 段。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMocks(Long userId) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (FarmWeatherMock m : weather.mockedSegments(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("fromDate", m.getFromDate().toString());
            row.put("toDate", m.getToDate().toString());
            row.put("tmax", m.getTmax());
            row.put("tmin", m.getTmin());
            row.put("precip", m.getPrecip());
            row.put("label", m.getLabel());
            row.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
            out.add(row);
        }
        return out;
    }

    /** 清空该用户的所有 mock 段（不影响其他用户）。 */
    @Transactional
    public Map<String, Object> resetMocks(Long userId) {
        invalidateSim(userId);
        if (!demoMode) {
            throw new BusinessException("演示模式未开启");
        }
        int n = mockRepo.deleteByUserId(userId);
        weather.clearUserMocks(userId);
        log.info("[稼穑] 用户 {} 清空全部模拟天气 ({} 段)", userId, n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("removed", n);
        return out;
    }

    // ==================================================================
    //  农事操作（核心玩法）
    // ==================================================================

    /**
     * 玩家在 {@code today} 做一个农事动作。
     *
     * <p>按 D10 / S10 的物候窗口校验，不在窗口内直接拒绝（不是"扣点成功了但没效果"，
     * 是直接 400 告诉玩家"现在做这事没意义"）。这避免了"乱按按钮蒙混过关"的体验——
     * 真正的农事是"在对的时令做对的事"。
     */
    @Transactional
    public Map<String, Object> performAction(Long userId, int slot, FarmAction.Type type, String note) {
        invalidateSim(userId);
        FarmPlot plot = getOrCreate(userId, slot);
        CropConfig crop = cropOf(plot);
        LocalDate today = effectiveToday(plot);

        // 施肥/打药必须走 S12（带具体农资），否则就绕过了"对的物"这道闸门
        if (type == FarmAction.Type.PESTICIDE || type == FarmAction.Type.FERTILIZE
                || type == FarmAction.Type.BASE_FERT || type == FarmAction.Type.TILLER_FERT
                || type == FarmAction.Type.PANICLE_FERT) {
            throw new BusinessException("请通过「农资」选择具体药剂/肥料与用量（POST /input），不能只做一个笼统的施肥/打药动作");
        }
        // 拿到当前 DVS, 用来做窗口校验
        SimEngine.SimResult r = replay(userId, plot, plot.getSowDate(), today);
        if (r == null) {
            throw new BusinessException("地块数据异常：无法回放");
        }
        double dvs = r.dvs();
        validateActionWindow(type, dvs, crop);

        int duration = switch (type) {
            case FERTILIZE -> 7;
            case BASE_FERT, TILLER_FERT, PANICLE_FERT -> 3;
            default -> 1;
        };

        // 揭帘: 仅 DVS < 0.36 (未进入孕穗) 才有意义, 否则拒绝
        if (type == FarmAction.Type.UNCOVER && dvs >= 0.36) {
            throw new BusinessException("揭帘仅在秧苗/分蘖期有意义（DVS < 0.36）");
        }

        // 人工除草（2026-10-22 需求 O）：只在有作物的时候有意义。
        // 待播种 / 待开垦的地块没有杂草可除——放行只会让玩家对着空地除草。
        if (type == FarmAction.Type.WEED && !plot.getZoneChosen()) {
            throw new BusinessException("这块地还没种东西，没有杂草可除");
        }

        FarmAction a = new FarmAction();
        a.setUserId(userId);
        a.setPlotId(plot.getId());
        a.setDate(today);
        a.setType(type);
        a.setDurationDays(duration);
        a.setNote(note);
        actionRepo.save(a);

        log.info("[稼穑] 用户 {} 在 {} 做 {} (duration={}d)", userId, today, type, duration);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", a.getId());
        out.put("type", type.name());
        out.put("date", today.toString());
        out.put("durationDays", duration);
        out.put("dvsAtAction", round(dvs, 3));
        out.put("stage", SimEngine.phenologyAt(dvs, crop));
        return out;
    }

    /**
     * 列出玩家做过的农事（最近 50 条，按时间倒序）。
     * UI 在「操作历史」面板展示。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listActions(Long userId, int slot) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        // findByUserIdOrderByDateDescIdDesc 会拉所有历史, 50 条足够
        FarmPlot plot = getOrCreate(userId, slot);
        List<FarmAction> all = (plot != null && plot.getId() != null)
                ? actionRepo.findByUserIdAndPlotIdOrderByDateDescIdDesc(userId, plot.getId())
                : actionRepo.findByUserIdOrderByDateDescIdDesc(userId);
        int cap = Math.min(all.size(), 50);
        for (int i = 0; i < cap; i++) {
            FarmAction a = all.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("date", a.getDate().toString());
            row.put("type", a.getType().name());
            row.put("durationDays", a.getDurationDays());
            row.put("note", a.getNote());
            row.put("createdAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
            out.add(row);
        }
        return out;
    }

    /**
     * 物候窗口校验。
     * <ul>
     *   <li>IRRIGATE: 全期可做（水分敏感贯穿全生育期）</li>
     *   <li>DRAIN / DRY_FIELD: 仅够苗晒田期（0.36 ≤ DVS &lt; 0.44）；其他时段拒绝</li>
     *   <li>UNCOVER: 仅秧苗/分蘖期（DVS &lt; 0.36）</li>
     *   <li>施肥与打药: 走 S12「农资」通道（{@code applyInput}），
     *       这里不再重复校验 —— 超窗不拒绝，而是效力 ×0.5。</li>
     * </ul>
     *
     * <p>🔴 S12 修复：原来这里的 {@code FERTILIZE} / {@code PESTICIDE} 分支是<b>死代码</b>：
     * {@code performAction} 入口已经把这两种类型连同 BASE/TILLER/PANICLE_FERT 一起拦掉
     * （"请通过「农资」选择具体药剂/肥料"）。留着两套互相矛盾的窗口配置，
     * 以后有人照着这段注释改窗口就会踩空 —— 比如把 FERTILIZE 的窗口改宽，
     * 实际上一行都走不到。
     * 现在把这两个类型从 switch 里移除，让"死配置"不再存在。
     */
    private void validateActionWindow(FarmAction.Type type, double dvs, CropConfig crop) {
        boolean ok = switch (type) {
            case IRRIGATE -> true;
            case DRAIN, DRY_FIELD    -> dvs >= 0.36 && dvs < 0.44;
            case UNCOVER             -> dvs < 0.36;
            case PUDDLE, TRANSPLANT  -> dvs < 0.06;
            case DRAIN_FINAL         -> dvs >= 0.90;
            case DRY_GRAIN           -> dvs >= 0.92;
            // 人工除草（需求 O）：全期可做。
            // 封行后杂草被郁闭自然抑制，除草收益低，但不该硬拦——
            // 玩家的直觉是"看到草就该拔"，拦住反而像 bug。
            case WEED                -> true;
            // 施肥/打药不在这里出现（见上方注释）
            case FERTILIZE, PESTICIDE, BASE_FERT, TILLER_FERT, PANICLE_FERT -> true;
        };
        if (!ok) {
            String window = switch (type) {
                case DRAIN, DRY_FIELD -> "够苗晒田期（DVS 0.36–0.44）";
                case FERTILIZE        -> "分蘖肥期（DVS 0.16–0.32）或穗肥期（DVS 0.55–0.68）";
                case UNCOVER          -> "秧苗/分蘖期（DVS < 0.36）";
                case PUDDLE, TRANSPLANT -> "移栽前（DVS < 0.06）";
                case BASE_FERT        -> "整地后、移栽前（DVS < 0.10）";
                case TILLER_FERT      -> "分蘖期（DVS 0.16–0.34）";
                case PANICLE_FERT     -> "孕穗期（DVS 0.41–0.55）";
                case DRAIN_FINAL      -> "黄熟前（DVS ≥ 0.90）";
                case DRY_GRAIN        -> "收割后（DVS ≥ 0.92）";
                default               -> "当前物候期";
            };
            String stage = SimEngine.phenologyAt(dvs, crop);
            throw new BusinessException(String.format(
                    "当前 %s（DVS %.3f）不能做「%s」；请在 %s 期间操作",
                    stage, dvs, type, window));
        }
    }

    // ==================================================================
    //  内部
    // ==================================================================

    /** 调整后的产量四要素（把病虫害损失落到 S7 的要素上，而不是只扣一个总数）。 */
    private record Yields(double panicles, double grains, double fertility, double weight, double ey) {
    }

    private Yields yields(SimEngine.SimResult r, PestEngine.Result pr,
                          DisasterEngine.Result dr, SoilEngine.Result sr,
                          List<OpChain.Step> chain, CropConfig crop) {
        return yields(r, pr, dr, sr, chain, crop, null, null);
    }

    /**
     * 产量折算（含出苗率、枯死与杂草压力）。
     *
     * @param ger 出苗评估结果（可为 null → 不打折）。<b>出苗率作用于「群体密度」</b>：
     *             缺苗 30% → 每穴少 30% 的穗 → 产量同比减少。
     *             这与现实的「缺苗 → 群体变小 → 产量降」是同一机制，
     *             而不是简单地在最后乘一个系数。
     * @param withered 苗期枯死：整季绝收，产量为 0。
     * @param wr 杂草压力（可为 null）。<b>杂草主要走「争水争光争肥」通道</b>：
     *             它不直接砍产量四要素，而是乘一个总体损失系数
     *             （旱地争水更凶，所以旱地系数更大）。
     */
    private Yields yields(SimEngine.SimResult r, PestEngine.Result pr,
                          DisasterEngine.Result dr, SoilEngine.Result sr,
                          List<OpChain.Step> chain, CropConfig crop,
                          GerminationEngine.Result ger, WeedEngine.Result wr) {
        double stem = clamp01(pr.stemLoss(), 0.60);
        double leaf = clamp01(pr.leafLoss(), 0.60);
        double grainL = clamp01(pr.grainLoss(), 0.60);
        double flower = clamp01(pr.flowerLoss(), 0.60);

        double dis = clamp01(dr.totalLoss(), 0.60);
        double drought = clamp01(sr.droughtLoss(), 0.30);
        double waterlog = clamp01(sr.waterlogLoss(), 0.20);
        double soil = clamp01(sr.soilCoef(), 1.0);

        // 出苗率 → 群体密度（2026-10-22）
        double germ = 1.0;
        if (ger != null) {
            germ = clamp01(ger.rate(), 1.0);
        }
        boolean withered = ger != null && ger.withered();

        double panicles = r.panicles() * (1 - stem) * (1 - waterlog * 0.6);
        double grains = r.grains() * (1 - flower) * (1 - grainL * 0.8 - leaf * 0.2)
                * (1 - dis * 0.6) * (1 - drought * 0.8);
        double fertility = r.fertility() * (1 - flower);
        double weight = r.weight() * (1 - leaf * 0.5 - grainL * 0.3) * (1 - dis * 0.4);
        // 工序遗漏惩罚：该做的没做，按 S10 §4.1 的敏感度折算
        double tillerMiss = missed(chain, "TILLER_FERT") ? 0.94 : 1.0;
        double panicleMiss = missed(chain, "PANICLE_FERT") ? 0.94 : 1.0;
        double dryFieldMiss = missed(chain, "DRY_FIELD") ? 0.97 : 1.0;
        double drainMiss = missed(chain, "DRAIN_FINAL") ? 0.98 : 1.0;
        double qualityFactor = qualityFactor(chain);
        // 杂草压力 → 争水争光争肥（2026-10-22 需求 O）
        // 杂草不是"病"，它不直接砍某个产量要素，而是整体压低作物的资源获取，
        // 所以乘一个总体损失系数最贴近真实机制。
        double weedLoss = 0;
        if (wr != null) {
            weedLoss = clamp01(wr.waterLoss(), 0.35);
        }
        // 与 SimEngine 同一口径：除以 (1000 × weightPer)，禾谷 = 千粒重，园艺 = 单果重
        double ey = Math.min(
                panicles * germ * tillerMiss * grains * panicleMiss * weight * dryFieldMiss * drainMiss
                        / (1e3 * Math.max(1, crop.weightPer())) * soil * qualityFactor * (1 - weedLoss),
                crop.yieldCap());
        if (withered) {
            // 枯死 = 整季绝收：一苗不活，穗也不会有
            ey = 0;
            panicles = 0;
            grains = 0;
        }
        return new Yields(panicles, Math.max(0, grains), Math.max(0, fertility),
                Math.max(0, weight), Math.max(0, ey));
    }

    // ==================================================================
    //  农资（S12）：三道闸门
    // ==================================================================

    /**
     * 综合效力 = 对症（由 targets 决定）× 剂量 × 时机 × 抗性。
     *
     * <p>这里只算"这一喷有多强"，具体打谁由 {@link PestEngine} 按 targets 匹配。
     */
    private static double sprayPower(InputCatalog.Item it, double dose, double dvs, double resistance) {
        double doseF = Math.max(0, InputCatalog.doseFactor(it, dose));
        double timing = InputCatalog.inWindow(it.id(), dvs) ? 1.0 : 0.5;
        return clamp01(it.efficacy() * doseF * timing * resistance, 1.0);
    }

    /** 一次农资施用的结果，直接给前端做反馈。 */
    private record ApplyResult(InputCatalog.Item item, double dose, double power,
                               double cost, boolean inWindow, boolean overdose,
                               double nAdded, List<String> warnings) {
    }

    /**
     * 施用农资。与普通农事的区别：超窗不拒绝，而是效力 ×0.5（S10 §4.6.2）。
     *
     * <p>🔴 这里不做"能不能种好"的许可判断——只做三道闸门的折算与提示。
     */
    @Transactional
    public Map<String, Object> applyInput(Long userId, int slot, String itemId, Double doseArg) {
        invalidateSim(userId);
        InputCatalog.Item it = InputCatalog.get(itemId);
        if (it == null) {
            throw new BusinessException("未知农资：" + itemId);
        }
        FarmPlot plot = getOrCreate(userId, slot);
        CropConfig crop = cropOf(plot);
        LocalDate today = effectiveToday(plot);
        SimEngine.SimResult r = replay(userId, plot, plot.getSowDate(), today);
        if (r == null) {
            throw new BusinessException("地块数据异常：无法回放天气");
        }
        double dose = doseArg == null ? it.doseOpt() : Math.max(0, doseArg);
        if (dose <= 0) {
            throw new BusinessException("用量必须大于 0");
        }
        // 🔴 S12 修复：原来上限是 doseMax × 2，而「过量/药害」阈值是 doseOpt × 1.5。
        // 两套阈值脱节 → 玩家可以合法施用 1.5~2 倍推荐量的**药害剂量**：
        // doseFactor 在 >1.5 倍后返回负数，power 被 clamp 到 0（药效全无），
        // 但钱照花、药害照算，纯亏。
        // 现在把硬上限对齐到推荐量的 1.5 倍：超过就是药害剂量，直接拒绝，
        // 玩家想"多打点"也不该能越过药害线。
        double maxDose = Math.min(it.doseMax() * 2, it.doseOpt() * 1.5);
        if (dose > maxDose) {
            throw new BusinessException(String.format(
                    "用量超出上限：%s 推荐 %s%s/亩，最多 %s%s/亩（超过 1.5 倍会药害/徒长）",
                    it.name(), trim(it.doseOpt()), it.unit(), trim(maxDose), it.unit()));
        }
        if (r.frostStop() && r.dvs() < 0.92) {
            throw new BusinessException("本季已被初霜终止，施药施肥没有意义了");
        }

        boolean inWindow = InputCatalog.inWindow(it.id(), r.dvs());
        boolean overdose = InputCatalog.overdose(it, dose);
        double resistance = resistanceFactor(userId, plot, it, today);
        double power = sprayPower(it, dose, r.dvs(), resistance);
        double cost = round(dose * it.pricePerUnit(), 2);

        List<String> warnings = new ArrayList<>();
        if (!inWindow) {
            warnings.add("错窗：" + it.name() + " 的适宜窗口是 DVS "
                    + String.format("%.2f–%.2f", InputCatalog.windowOf(it.id())[0],
                    InputCatalog.windowOf(it.id())[1]) + "，现在 DVS " + String.format("%.2f", r.dvs())
                    + "，效力打五折");
        }
        if (overdose) {
            warnings.add("超量：" + String.format("%.1f", dose) + it.unit() + "/亩 超过推荐量 1.5 倍，会徒长/药害");
        }
        if (it.kind() == InputCatalog.Kind.PEST) {
            List<String> active = new ArrayList<>();
            PestEngine.Result pr = pests(userId, plot, r, today);
            for (PestEngine.Instance p : pr.instances()) {
                if (p.detected() && it.targets().contains(p.id())) {
                    active.add(p.name());
                }
            }
            if (active.isEmpty()) {
                warnings.add("当前田里没有 " + String.join("/", targetNames(it)) + " 的显症记录，这一喷是预防");
            }
            int left = phiLeftToday(userId, plot, today);
            if (left >= 0 && left < it.phiDays()) {
                warnings.add("安全间隔期：" + it.name() + " 需 " + it.phiDays()
                        + " 天，距采收只剩 " + left + " 天，会检出农残");
            }
        }
        if (it.kind() == InputCatalog.Kind.HERB) {
            // 除草剂的专属提示（2026-10-22 需求 O）：
            // 除草剂没有农残问题（草不进人），但有两个别的坑：
            // ① 苗期打会伤苗 ② 封行后打杂草已经抢完水肥了。
            WeedEngine.Result wr = weeds(userId, plot, r, today);
            if (wr.level() == WeedEngine.Level.NONE || wr.level() == WeedEngine.Level.LIGHT) {
                warnings.add("当前田间杂草很少（" + WeedEngine.levelCn(wr.level())
                        + "），这一喷基本是白花钱");
            }
            if (r.dvs() < 0.08) {
                warnings.add("苗还没出齐，除草剂会直接接触芽鞘，容易伤苗——建议等出苗后再打");
            }
            if (r.dvs() > 0.45) {
                warnings.add("已经封行，杂草被郁闭压住了，现在打药来不及——水肥早被抢走了");
            }
        }

        // 🔴 S12 修复：施肥工序类型按"当前作物的工序链窗口"判定，
        // 而不是硬编码 0.10 / 0.34 / 0.55（那是水稻的窗口）。
        // 小麦返青肥窗口是 0.42–0.52、玉米苗期肥 0.15–0.30、瓜类整枝 0.18–0.45，
        // 用水稻阈值会把这些作物判到错误的工序上 → 工序链匹配不上 → 漏工序惩罚误触发。
        FarmAction.Type type;
        if (it.kind() == InputCatalog.Kind.FERT) {
            String step = OpChain.fertStepFor(r.dvs(), cropIdOf(plot));
            type = switch (step == null ? "FERTILIZE" : step) {
                case "BASE_FERT" -> FarmAction.Type.BASE_FERT;
                case "TILLER_FERT" -> FarmAction.Type.TILLER_FERT;
                case "PANICLE_FERT" -> FarmAction.Type.PANICLE_FERT;
                default -> FarmAction.Type.FERTILIZE;
            };
        } else if (it.kind() == InputCatalog.Kind.HERB) {
            // 除草剂走 WEED 通道（不是 PESTICIDE）——它作用在杂草上，不是病虫上
            type = FarmAction.Type.WEED;
        } else {
            type = FarmAction.Type.PESTICIDE;
        }

        FarmAction a = new FarmAction();
        a.setUserId(userId);
        a.setPlotId(plot.getId());
        a.setDate(today);
        a.setType(type);
        a.setDurationDays(1);
        a.setItemId(it.id());
        a.setDose(round(dose, 2));
        a.setCost(cost);
        a.setNote((inWindow ? "" : "错窗 ") + (overdose ? "超量 " : "") + it.name());
        actionRepo.save(a);

        double nAdded = it.kind() == InputCatalog.Kind.FERT
                ? round(InputCatalog.nutrient(it, dose)
                * (inWindow ? 1.0 : 0.5) * Math.max(0, InputCatalog.doseFactor(it, dose)), 2)
                : 0;
        if ("RHIZOBIUM".equals(it.id())) {
            if ("soybean".equals(crop.visual())) {
                // 根瘤菌剂：接种成功相当于多固氮 3 kg/亩（数据卡 +15~25%）
                nAdded = round(nAdded + 3.0 * (inWindow ? 1.0 : 0.5), 2);
            } else {
                warnings.add("根瘤菌剂只对大豆（豆科）有效，这一袋白买了");
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("itemId", it.id());
        out.put("itemName", it.name());
        out.put("dose", round(dose, 2));
        out.put("unit", it.unit());
        out.put("cost", cost);
        out.put("inWindow", inWindow);
        out.put("overdose", overdose);
        out.put("power", round(power, 2));
        out.put("resistance", round(resistance, 2));
        out.put("nAdded", nAdded);
        out.put("warnings", warnings);
        return out;
    }

    /** 货架视图：每件农资的当前适用性（窗口 / 抗性 / PHI / 预计效力）。 */
    private List<FarmView.InputView> inputViews(Long userId, FarmPlot plot, LocalDate today,
                                                SimEngine.SimResult r, SoilEngine.Result sr) {
        Map<String, Integer> usedById = new HashMap<>();
        Map<String, Integer> groupUse = new HashMap<>();
        for (FarmAction a : seasonActions(userId, plot, plot.getSowDate(), today)) {
            if (a.getItemId() != null) {
                usedById.merge(a.getItemId(), 1, Integer::sum);
            }
            InputCatalog.Item prev = InputCatalog.get(a.getItemId());
            if (prev != null && prev.kind() == InputCatalog.Kind.PEST) {
                groupUse.merge(prev.modeGroup(), 1, Integer::sum);
            }
        }
        int phiLeft = phiLeftToday(userId, plot, today);
        double dvs = r.dvs();
        List<FarmView.InputView> out = new ArrayList<>();
        for (InputCatalog.Item it : InputCatalog.all().values()) {
            int used = usedById.getOrDefault(it.id(), 0);
            double risk;
            if (it.kind() == InputCatalog.Kind.PEST && !it.category().endsWith("BIO")) {
                risk = Math.max(0.60, Math.pow(0.85, groupUse.getOrDefault(it.modeGroup(), 0)));
            } else {
                risk = 1.0;
            }
            boolean inWindow = InputCatalog.inWindow(it.id(), dvs);
            double power = it.kind() == InputCatalog.Kind.PEST
                    ? sprayPower(it, it.doseOpt(), dvs, risk)
                    : Math.max(0, InputCatalog.doseFactor(it, it.doseOpt()))
                    * (inWindow ? 1.0 : 0.5);
            boolean phiRisk = it.kind() == InputCatalog.Kind.PEST
                    && phiLeft >= 0 && phiLeft < it.phiDays();
            double[] win = InputCatalog.windowOf(it.id());
            out.add(new FarmView.InputView(
                    it.id(), it.name(), it.kind().name(), it.category(), it.unit(),
                    it.doseMin(), it.doseOpt(), it.doseMax(), it.pricePerUnit(), it.nContent(),
                    it.element(), win[0], win[1],
                    it.targets(), targetNames(it), it.modeGroup(), it.phiDays(), it.protectDays(),
                    it.note(), used, round(risk, 2), inWindow, round(power, 2), phiRisk,
                    round(it.doseOpt() * it.pricePerUnit(), 2)));
        }
        return out;
    }

    /**
     * 农资归类：这一条动作算「肥」还是算「药」，还是都不算。
     *
     * <p>🔴 2026-10-22 需求 O：除草剂（{@link InputCatalog.Kind#HERB}）<b>两者都不是</b>。
     * 原来这里用「不是 FERT 就是药」的二分法，除草剂被当成农药：
     * ① 农药次数 +1（面板上显示「农药 1 次」，玩家根本没打药）；
     * ② {@code lastSpray} 被撑到除草剂那天，PHI 倒计时按除草剂算 ——
     * 草又不进人，误报一次农残事故；③ 除草剂还吃了农药的抗性累积。
     *
     * <p>抽成包级静态方法是为了能被单测直接覆盖 —— 这类"归类错了但不出错、
     * 只是数字不对"的 bug，光靠端到端点一遍是发现不了的。
     *
     * @return FERT → 1；PEST → 2；HERB → 0
     */
    static int inputClassOf(InputCatalog.Kind kind) {
        return switch (kind) {
            case FERT -> 1;
            case PEST -> 2;
            // 除草剂只计成本，不进农药统计、不参与 PHI、不吃抗性累积
            case HERB -> 0;
        };
    }

    /** 本季投入汇总：钱、氮、药、以及最近的 PHI 倒计时。 */
    private FarmView.InputSummary inputSummary(Long userId, FarmPlot plot, LocalDate today,
                                               SimEngine.SimResult r, SoilEngine.Result sr,
                                               double estimatedYield) {
        double cost = 0;
        int ferts = 0;
        int pests = 0;
        LocalDate lastSpray = null;
        double worstRisk = 1.0;
        Map<String, Integer> groupUse = new HashMap<>();
        for (FarmAction a : seasonActions(userId, plot, plot.getSowDate(), today)) {
            if (a.getCost() != null) {
                cost += a.getCost();
            }
            InputCatalog.Item it = InputCatalog.get(a.getItemId());
            if (it == null) {
                continue;
            }
            int cls = inputClassOf(it.kind());
            if (cls == 1) {
                ferts++;
            } else if (cls == 2) {
                pests++;
                if (lastSpray == null || a.getDate().isAfter(lastSpray)) {
                    lastSpray = a.getDate();
                }
                if (!it.category().endsWith("BIO")) {
                    int n = groupUse.merge(it.modeGroup(), 1, Integer::sum);
                    worstRisk = Math.min(worstRisk, Math.max(0.60, Math.pow(0.85, n)));
                }
            }
            // cls == 0（HERB 除草剂）：只计成本，不进农药统计、不参与 PHI、不吃抗性累积
        }
        double revenue = round(estimatedYield * priceOf(cropIdOf(plot)), 1);
        return new FarmView.InputSummary(
                round(cost, 1), ferts, pests, round(sr.nApplied(), 2), sr.nNeed(),
                phiLeftToday(userId, plot, today),
                lastSpray == null ? null : lastSpray.toString(),
                round(worstRisk, 2), false, revenue, round(revenue - cost, 1));
    }

    /** 同一作用机理连续使用 → 抗性累积（S9 §4.4.3 的货架层近似）。 */
    private double resistanceFactor(Long userId, FarmPlot plot, InputCatalog.Item it, LocalDate today) {
        if (it.kind() != InputCatalog.Kind.PEST || it.category().endsWith("BIO")) {
            return 1.0;
        }        int used = 0;
        for (FarmAction a : seasonActions(userId, plot, plot.getSowDate(), today)) {
            InputCatalog.Item prev = InputCatalog.get(a.getItemId());
            if (prev != null && it.modeGroup().equals(prev.modeGroup())) {
                used++;
            }
        }
        return Math.max(0.60, Math.pow(0.85, used));
    }

    private static List<String> targetNames(InputCatalog.Item it) {
        // 病虫害中文名与 PestEngine 的对齐；这里只用于提示文案
        List<String> out = new ArrayList<>();
        for (String id : it.targets()) {
            out.add(switch (id) {
                case "blast" -> "稻瘟病";
                case "sheath_blight" -> "纹枯病";
                case "bacterial_blight" -> "白叶枯病";
                case "stem_borer" -> "二化螟";
                case "planthopper" -> "稻飞虱";
                case "leaf_roller" -> "稻纵卷叶螟";
                // 除草剂的作用对象不是病虫而是杂草（需求 O）
                case "weed" -> "杂草";
                default -> id;
            });
        }
        return out;
    }

    /**
     * 距离采收还剩几天（用于 PHI 倒计时）。已过黄熟期返回 0；
     * 若本季不会成熟（霜冻终止）返回 -1，表示"没有采收，谈不残"。
     */
    public int phiLeftToday(Long userId, FarmPlot plot, LocalDate today) {
        SimEngine.SimResult r = replay(userId, plot, plot.getSowDate(), today);
        if (r == null) {
            return 0;
        }
        if (r.matureDate() != null) {
            return (int) Math.max(0, ChronoUnit.DAYS.between(today, LocalDate.parse(r.matureDate())));
        }
        if (r.frostStop()) {
            return -1;   // 不会成熟 → 没有采收，谈不残
        }
        // 还没熟：用最近 7 天积温外推剩余天数，否则刚抽穗就会误报"距采收 0 天"
        return estimateDaysTo(userId, zoneOf(plot), plot.getSowDate(), today, r.dvs(), 1.0, cropOf(plot));
    }

    /** 跑一遍病虫害模拟：与生长共用一份天气与 DVS 轨迹，保证同一天结果可复现。 */
    private PestEngine.Result pests(Long userId, FarmPlot plot, SimEngine.SimResult r, LocalDate today) {
        CropConfig crop = cropOf(plot);
        LocalDate sow = plot.getSowDate();
        int n = r.dvsByDay() == null ? 0 : r.dvsByDay().size();
        if (n <= 0) {
            return PestEngine.Result.EMPTY;
        }
        List<DayWeather> days = weather.range(userId, zoneOf(plot), sow, sow.plusDays(n - 1));
        List<PestEngine.Spray> sprays = new ArrayList<>();
        Map<String, Integer> groupUse = new HashMap<>();
        // 🔴 S12 修复：是否含"旧数据"（无 itemId 的打药记录）。
        // 原来这里恒传 false，导致历史打药记录（targets 为空集）被判成"打错药"，
        // 防效只剩 5% —— 玩家在旧存档里补打一次药，药效几乎为零。
        // 现在按"本季是否出现旧记录"动态决定：旧记录按全靶标、满效力兼容。
        boolean anyLegacy = false;
        for (FarmAction a : seasonActions(userId, plot, sow, today)) {
            if (a.getType() != FarmAction.Type.PESTICIDE) {
                continue;
            }
            InputCatalog.Item it = InputCatalog.get(a.getItemId());
            if (it == null) {
                // 旧数据：没有药剂信息，按"全靶标、满效力"兼容
                sprays.add(new PestEngine.Spray(a.getDate(), null, java.util.Set.of(), 1.0, 10));
                anyLegacy = true;
                continue;
            }
            int idx = Math.max(0, Math.min(n - 1, (int) ChronoUnit.DAYS.between(sow, a.getDate())));
            double dvs = r.dvsByDay().get(idx);
            String group = it.modeGroup();
            int used = groupUse.merge(group, 1, Integer::sum) - 1;
            double resistance = it.category().endsWith("BIO") ? 1.0 : Math.max(0.6, Math.pow(0.85, used));
            double power = sprayPower(it, a.getDose() == null ? it.doseOpt() : a.getDose(), dvs, resistance);
            sprays.add(new PestEngine.Spray(a.getDate(), it.id(),
                    java.util.Set.copyOf(it.targets()), power, it.protectDays()));
        }
        long seed = userId * 1_000_003L + sow.toEpochDay();
        return PestEngine.simulate(days, 0, n, r.dvsByDay(), seed, sprays, anyLegacy, crop.visual());
    }

    /**
     * 跑一遍杂草模拟（2026-10-22 需求 O）。
     *
     * <p>与 {@link #pests} 同构：共用同一份天气与 DVS 轨迹，所以同一天结果可复现。
     *
     * <p><b>怎么区分人工除草和化学除草</b>：两者都记在 {@code t_farm_action} 里，
     * 用「有没有 itemId」区分——
     * <ul>
     *   <li>有 itemId 且是 HERB 类 → 化学除草（有 2–3 天见效延迟 + 保护期）；</li>
     *   <li>没 itemId → 人工除草（即时见效）。</li>
     * </ul>
     * 这样不需要给农事表加新列，老数据也天然兼容（老数据里根本没有除草记录）。
     */
    private WeedEngine.Result weeds(Long userId, FarmPlot plot, SimEngine.SimResult r, LocalDate today) {
        CropConfig crop = cropOf(plot);
        LocalDate sow = plot.getSowDate();
        int n = r.dvsByDay() == null ? 0 : r.dvsByDay().size();
        if (n <= 0) {
            return WeedEngine.Result.EMPTY;
        }
        // 🔴 窗口口径必须与 doReplay 一致：**以今天结尾**往前推。
        // 原来这里按 sow 起算 n 天，而 doReplay 的 days 是 [sow, sow+span]，
        // 两者在「播种超过 430 天」时会错开 —— 杂草引擎读到的天气序列
        // 与作物回放的不是同一段，导致 DVS 与天气对不上号。
        LocalDate winStart = today.minusDays(Math.max(0, n - 1L));
        if (winStart.isBefore(sow)) {
            winStart = sow;
        }
        List<DayWeather> days = weather.range(userId, zoneOf(plot), winStart, today);
        List<WeedEngine.WeedControl> controls = new ArrayList<>();
        for (FarmAction a : seasonActions(userId, plot, winStart, today)) {
            if (a.getType() != FarmAction.Type.WEED) {
                continue;
            }
            InputCatalog.Item it = InputCatalog.get(a.getItemId());
            boolean chemical = it != null && it.kind() == InputCatalog.Kind.HERB;
            controls.add(new WeedEngine.WeedControl(a.getDate(), !chemical));
        }
        long seed = userId * 2_000_003L + sow.toEpochDay();
        return WeedEngine.simulate(days, 0, n, r.dvsByDay(), controls,
                "rice".equals(crop.visual()), seed);
    }

    /** 该工序是否已错过窗口且没做。 */
    private static boolean missed(List<OpChain.Step> chain, String id) {
        for (OpChain.Step s : chain) {
            if (s.id().equals(id)) {
                return s.status() == OpChain.Status.MISSED;
            }
        }
        return false;
    }

    /** 作业质量系数：已做工序的平均时机质量（0.5–1.0）映射到 0.96–1.0。 */
    private static double qualityFactor(List<OpChain.Step> chain) {
        double sum = 0;
        int n = 0;
        for (OpChain.Step s : chain) {
            if (s.status() == OpChain.Status.DONE && !s.repeatable()) {
                sum += s.quality();
                n++;
            }
        }
        if (n == 0) {
            return 1.0;
        }
        double avg = sum / n;
        return 0.96 + 0.04 * avg;
    }

    private List<FarmView.OpView> opViews(List<OpChain.Step> chain) {
        List<FarmView.OpView> out = new ArrayList<>();
        for (OpChain.Step s : chain) {
            out.add(new FarmView.OpView(s.id(), s.name(), s.group(), s.from(), s.to(),
                    s.repeatable(), s.status().name(), s.date(), s.quality(), s.count(), s.hint()));
        }
        return out;
    }

    // ==================================================================
    //  建田与适区（S2 / S23）
    // ==================================================================

    /** 地块区域 id；未建田时按江南算，保证老数据仍能打开。 */
    private static String zoneOf(FarmPlot plot) {
        String z = plot == null ? null : plot.getZoneId();
        return (z == null || z.isBlank()) ? "jiangnan" : z;
    }

    private static String defaultFieldName(String zoneId) {
        return ClimateModel.zone(zoneId).name() + "一号田";
    }

    /** 评级结果按「区 + 播期」缓存：10 年回放单次约 40ms，同一组合不重复算。 */
    private final Map<String, SuitabilityEngine.Report> suitCache = new java.util.concurrent.ConcurrentHashMap<>();

    public SuitabilityEngine.Report suitabilityOf(String zoneId, LocalDate sow) {
        return suitabilityOf(zoneId, sow, "rice_mid");
    }

    public SuitabilityEngine.Report suitabilityOf(String zoneId, LocalDate sow, String cropId) {
        String z = (zoneId == null || zoneId.isBlank()) ? "jiangnan" : zoneId;
        String c = cropId == null || cropId.isBlank() ? "rice_mid" : cropId;
        return suitCache.computeIfAbsent(z + ":" + c + ":" + sow,
                k -> SuitabilityEngine.evaluate(z, sow, CropCatalog.get(c).config()));
    }

    /** 可选区域列表 + 各区在「推荐播期」下的水稻评级摘要，用于建田选地页。 */
    public List<Map<String, Object>> zones() {
        return zones("rice_mid");
    }

    public List<Map<String, Object>> zones(String cropId) {
        CropCatalog.Crop cc = CropCatalog.get(cropId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClimateModel.Zone z : ClimateModel.zones().values()) {
            ClimateModel.Stats st = ClimateModel.stats(z.id());
            SuitabilityEngine.Report rep = suitabilityOf(z.id(),
                    CropCatalog.defaultSow(cc.id(), z.id(), LocalDate.now()), cc.id());
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
            out.add(m);
        }
        return out;
    }

    /** 指定区 + 播期的完整评级报告（建田页随播期拖动实时刷新）。 */
    public SuitabilityEngine.Report suitability(String zoneId, LocalDate sow) {
        return suitability(zoneId, sow, "rice_mid");
    }

    public SuitabilityEngine.Report suitability(String zoneId, LocalDate sow, String cropId) {
        if (!ClimateModel.zones().containsKey(zoneId)) {
            throw new BusinessException("未知农业区：" + zoneId);
        }
        return suitabilityOf(zoneId, sow, cropId);
    }

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
            out.add(m);
        }
        return out;
    }

    /** 建田 / 换地：确定区域与播期，重置本季。 */
    @Transactional
    public Map<String, Object> createField(Long userId, String zoneId, LocalDate sow, String fieldName) {
        return createField(userId, 0, zoneId, sow, fieldName, "rice_mid");
    }

    @Transactional
    public Map<String, Object> createField(Long userId, int slot, String zoneId, LocalDate sow,
                                           String fieldName, String cropId) {
        return createField(userId, slot, null, zoneId, sow, fieldName, cropId);
    }

    /**
     * 建地块（可挂到农场）。
     *
     * <p>{@code farmId} 非空时，农业区<b>以农场所在区县为准</b>，忽略传入的 zoneId——
     * 玩家不该能在一个县的地块上手动改气候，否则评级与实况会不一致。
     */
    @Transactional
    public Map<String, Object> createField(Long userId, int slot, Long farmId, String zoneId,
                                           LocalDate sow, String fieldName, String cropId) {
        invalidateSim(userId);
        FarmStead stead = null;
        if (farmId != null) {
            stead = steadRepo.findByIdAndUserId(farmId, userId)
                    .orElseThrow(() -> new BusinessException("农场不存在或不属于当前用户"));
            zoneId = stead.getZoneId();
        }
        ClimateModel.Zone z = ClimateModel.zones().get(zoneId);
        if (z == null) {
            throw new BusinessException("未知农业区：" + zoneId);
        }
        if (!z.open()) {
            throw new BusinessException("该区域 v1.0 未开放（数据已算好，等版本解锁）");
        }
        CropCatalog.Crop cc = CropCatalog.get(cropId);
        if (sow == null) {
            sow = CropCatalog.defaultSow(cc.id(), zoneId, LocalDate.now());
        }
        if (sow.getYear() < 2000 || sow.getYear() > 2100) {
            throw new BusinessException("播期不合法：" + sow);
        }

        FarmPlot plot = getOrCreate(userId, slot);

        // 每农场 12 块地上限（2026-10-22）
        // 注意：只有「新增地块」才受限；在已有地块上换作物/换播期不算新建。
        boolean isNewPlot = !plot.getZoneChosen() && plot.getSeasons() == 0 && plot.getLastHarvestDate() == null
                && plot.getCropId() == null;
        if (farmId != null && isNewPlot) {
            long owned = plotRepo.countByUserIdAndFarmId(userId, farmId);
            if (owned >= MAX_PLOTS_PER_FARM) {
                throw new BusinessException(String.format(
                        "这个农场最多 %d 块地（已建 %d 块）。要多种地，先在别的农场开垦。",
                        MAX_PLOTS_PER_FARM, owned));
            }
        }

        plot.setZoneId(zoneId);
        plot.setFarmId(farmId);
        plot.setCropId(cc.id());
        if (fieldName == null || fieldName.isBlank()) {
            String base = stead != null && stead.getDistrictName() != null
                    ? stead.getDistrictName()
                    : ClimateModel.zone(zoneId).name();
            fieldName = base + cc.shortName() + "田";
        }
        plot.setFieldName(fieldName.trim());
        plot.setZoneChosen(true);
        // 开新一季 → 不再是「待播种」了
        plot.setAwaitingSow(false);
        plot.setSowDate(sow);
        // 🔴 2026-10-22 修复：这里原来无条件把 seasons / bestYield / lastYield / lastHarvestDate
        // 全部清零。后果是——玩家收割完一季、选了下一季作物再提交，
        // 上一季的「第 1 季」记录、最好亩产、上一季收获日全被抹掉，
        // 而这四项正是需求要的「每块地的种植和收获记录产量」。
        //
        // 正确语义：**地块的历史不清零，只有「本季」重置**。
        // seasons 是累计季数（第几季），bestYield / lastYield / lastHarvestDate 是历史成绩，
        // 它们跨季保留；SoilEngine 的连作地力衰减也正是靠 seasons 累计生效的。
        // 只有「从没开垦过的新地块」本来就是 0，无需特殊处理。
        plot.setSeasonStartedAt(java.time.LocalDateTime.now());
        if (demoMode) {
            long offset = Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), sow));
            int capped = (int) Math.min(offset, MAX_DEMO_OFFSET_DAYS);
            // 🔴 S12 修复：偏移触顶时播种日跟着回落，保持 sowDate 与 effectiveToday 自洽。
            if (capped < offset) {
                sow = LocalDate.now().plusDays(capped);
                plot.setSowDate(sow);
            }
            plot.setDemoOffsetDays(capped);
        } else {
            plot.setDemoOffsetDays(0);
        }
        plotRepo.save(plot);

        // 季节档案：开一条新的「种植记录」（2026-10-22）
        // 若上一季还挂在 GROWING（换了作物但没收割），先把它标成 ABANDONED——
        // 否则同一块地会同时存在两条 GROWING 记录，详情页会显示重影。
        FarmSeason prev = seasonRepo.findFirstByUserIdAndPlotIdAndStatus(
                userId, plot.getId(), FarmSeason.Status.GROWING).orElse(null);
        if (prev != null) {
            prev.setStatus(FarmSeason.Status.ABANDONED);
            prev.setFailureNote("改种其它作物，本季提前结束");
            seasonRepo.save(prev);
        }
        GerminationEngine.Result ger = germination(userId, plot, cc.config());
        openSeason(userId, plot, cc, sow, ger);

        SuitabilityEngine.Report rep = suitabilityOf(zoneId, sow, cc.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("zoneId", zoneId);
        out.put("cropId", cc.id());
        out.put("cropName", cc.name());
        out.put("fieldName", plot.getFieldName());
        out.put("sowDate", sow.toString());
        out.put("level", rep.level());
        out.put("levelName", rep.levelName());
        out.put("pPass", rep.pPass());
        out.put("noticeLevel", rep.noticeLevel());
        out.put("summary", rep.summary());
        // 出苗风险预告（2026-10-22）：不阻断，只提示「这样种大概会怎样」
        if (ger != null) {
            out.put("germination", round(ger.rate(), 3));
            out.put("cautions", ger.cautions());
        }
        return out;
    }

    /**
     * 该区的推荐播期：江南 / 华南 / 黄淮海落在本地适宜窗口，东北偏晚（避低温）。
     * 只用于「选地页的默认值」，玩家仍可任意改播期（S23 §2.1：适区不是许可）。
     */
    private static LocalDate defaultSow(String zoneId, LocalDate today) {
        LocalDate base = nextSowDate(today);
        return switch (zoneId) {
            case "huanan" -> LocalDate.of(base.getYear(), 3, 10).isBefore(today)
                    ? LocalDate.of(base.getYear() + 1, 3, 10) : LocalDate.of(base.getYear(), 3, 10);
            case "dongbei" -> LocalDate.of(base.getYear(), 5, 5).isBefore(today)
                    ? LocalDate.of(base.getYear() + 1, 5, 5) : LocalDate.of(base.getYear(), 5, 5);
            case "huanghuaihai" -> base;
            default -> base;
        };
    }

    /** 土壤水分与地力：与生长共用同一份天气和 DVS 轨迹。 */
    private SoilEngine.Result soil(Long userId, FarmPlot plot, SimEngine.SimResult r, LocalDate today) {
        CropConfig crop = cropOf(plot);
        LocalDate sow = plot.getSowDate();
        int n = r.dvsByDay() == null ? 0 : r.dvsByDay().size();
        if (n <= 0) {
            return SoilEngine.Result.EMPTY;
        }
        List<DayWeather> days = weather.range(userId, zoneOf(plot), sow, sow.plusDays(n - 1));
        java.util.Set<LocalDate> drains = new java.util.HashSet<>();
        java.util.Set<LocalDate> irrigates = new java.util.HashSet<>();
        Map<LocalDate, Double> nByDate = new HashMap<>();
        for (FarmAction a : seasonActions(userId, plot, sow, sow.plusDays(n - 1))) {
            switch (a.getType()) {
                case DRAIN, DRY_FIELD, DRAIN_FINAL -> drains.add(a.getDate());
                case IRRIGATE, PUDDLE -> irrigates.add(a.getDate());
                case FERTILIZE, BASE_FERT, TILLER_FERT, PANICLE_FERT -> {
                    InputCatalog.Item it = InputCatalog.get(a.getItemId());
                    int idx = Math.max(0, Math.min(n - 1, (int) ChronoUnit.DAYS.between(sow, a.getDate())));
                    double dvs = r.dvsByDay().get(idx);
                    double timing = InputCatalog.inWindow(a.getItemId(), dvs) ? 1.0 : 0.5;
                    double nAmt;
                    if (it != null) {
                        double dose = a.getDose() == null ? it.doseOpt() : a.getDose();
                        nAmt = InputCatalog.nutrient(it, dose) * timing
                                * Math.max(0, InputCatalog.doseFactor(it, dose));
                    } else {
                        // 旧数据：一次通用施肥按 3 kg N 计
                        nAmt = 3.0 * timing;
                    }
                    nByDate.merge(a.getDate(), nAmt, Double::sum);
                }
                default -> { }
            }
        }
        return SoilEngine.simulate(days, 0, n, r.dvsByDay(), drains, irrigates, nByDate,
                plot.getSeasons() == null ? 0 : plot.getSeasons(),
                crop.nNeed(), "soybean".equals(crop.visual()),
                "rice".equals(crop.visual()));
    }

    /** 灾害回放：同样只依赖天气与 DVS 轨迹。 */
    private DisasterEngine.Result disasters(Long userId, FarmPlot plot, SimEngine.SimResult r) {
        LocalDate sow = plot.getSowDate();
        int n = r.dvsByDay() == null ? 0 : r.dvsByDay().size();
        if (n <= 0) {
            return DisasterEngine.Result.EMPTY;
        }
        List<DayWeather> days = weather.range(userId, zoneOf(plot), sow, sow.plusDays(n - 1));
        java.util.Set<LocalDate> drains = new java.util.HashSet<>();
        java.util.Set<LocalDate> irrigates = new java.util.HashSet<>();
        for (FarmAction a : seasonActions(userId, plot, sow, sow.plusDays(n - 1))) {
            if (a.getType() == FarmAction.Type.DRAIN) drains.add(a.getDate());
            if (a.getType() == FarmAction.Type.IRRIGATE) irrigates.add(a.getDate());
        }
        CropConfig crop = cropOf(plot);
        return DisasterEngine.simulate(days, 0, n, r.dvsByDay(), drains, irrigates,
                crop.winterHardy(), crop.winterKillTmin());
    }

    private List<FarmView.EventView> eventViews(DisasterEngine.Result dr) {
        List<FarmView.EventView> out = new ArrayList<>();
        List<DisasterEngine.Event> list = dr.events();
        for (int i = list.size() - 1, c = 0; i >= 0 && c < 6; i--, c++) {
            DisasterEngine.Event e = list.get(i);
            out.add(new FarmView.EventView(e.type(), e.name(), e.date(),
                    round(e.intensity(), 2), round(e.loss(), 3), e.note()));
        }
        return out;
    }

    private List<FarmView.PestView> pestViews(PestEngine.Result pr) {
        List<FarmView.PestView> out = new ArrayList<>();
        for (PestEngine.Instance p : pr.instances()) {
            String status = p.latencyLeft() > 0 ? "LATENT" : statusCode(p.severity());
            out.add(new FarmView.PestView(
                    p.id(), p.name(), p.type(),
                    round(p.severity(), 3), round(p.incidence(), 3),
                    p.detected(), p.onsetDate(), status, adviceCode(p), p.target()));
        }
        return out;
    }

    private static String statusCode(double severity) {
        if (severity < 0.10) return "LIGHT";
        if (severity < 0.25) return "MODERATE";
        if (severity < 0.45) return "HEAVY";
        return "SEVERE";
    }

    private static String statusCn(double severity) {
        return switch (statusCode(severity)) {
            case "LIGHT" -> "初发";
            case "MODERATE" -> "中等";
            case "HEAVY" -> "偏重";
            default -> "严重";
        };
    }

    /** 防治建议：早期打药收益最高；显症中后期只能压制。 */
    private static String adviceCode(PestEngine.Instance p) {
        if (p.latencyLeft() > 0) return "PREVENT";
        if (p.severity() < 0.15) return "EARLY";
        if (p.severity() < 0.40) return "MODERATE";
        return "LATE";
    }

    private static double clamp01(double v, double max) {
        return Math.max(0, Math.min(max, v));
    }

    /** 去掉小数尾巴上的零（22.0 → 22，0.75 → 0.75），让提示文案更好读。 */
    private static String trim(double v) {
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    /** 演示偏移只对演示模式生效；正式模式一律用真实今天。 */
    LocalDate effectiveToday(FarmPlot plot) {
        LocalDate today = LocalDate.now();
        if (demoMode && plot.getDemoOffsetDays() != null && plot.getDemoOffsetDays() > 0) {
            return today.plusDays(plot.getDemoOffsetDays());
        }
        return today;
    }

    /**
     * 下一季的适宜播期（演示模式换季用）。
     *
     * <p>{@code CropCatalog.defaultSow} 在"今年播期已过"时会顺延到明年，
     * 这里传入的是"今天 + 1 天"，保证收完当季后拿到的是下一个完整生长季的起点。
     */
    private LocalDate nextSowOfCrop(FarmPlot plot, LocalDate today) {
        return CropCatalog.defaultSow(cropIdOf(plot), zoneOf(plot), today.plusDays(1));
    }

    /**
     * 取「本季」的农事：先按日期区间查，再按 seasonStartedAt 过滤掉上一季的旧记录。
     * 演示模式重开会回到同一个播种日，不加这层过滤会把旧季的打药/晒田重新算进来。
     */
    private List<FarmAction> seasonActions(Long userId, FarmPlot plot, LocalDate from, LocalDate to) {
        // 多地块：只取本块地的操作，否则 A 田打的药会算到 B 田头上
        List<FarmAction> all = (plot != null && plot.getId() != null)
                ? actionRepo.findByUserIdAndPlotIdAndDateBetweenOrderByDate(userId, plot.getId(), from, to)
                : actionRepo.findByUserIdAndDateBetweenOrderByDate(userId, from, to);
        LocalDateTime start = plot.getSeasonStartedAt();
        if (start == null) {
            return all;
        }
        List<FarmAction> out = new ArrayList<>();
        for (FarmAction a : all) {
            if (a.getCreatedAt() == null || !a.getCreatedAt().isBefore(start)) {
                out.add(a);
            }
        }
        return out;
    }

    /** 从播种日逐日回放到 today。上限 430 天：够一季中稻（130–150 天）与晚播越冬，再长视为脏数据。 */
    private SimEngine.SimResult replay(Long userId, LocalDate sow, LocalDate today) {
        return replay(userId, null, sow, today);
    }

    /** 写操作后让该用户的回放结果失效（内存级，进程重启自然归零）。 */
    private void invalidateSim(Long userId) {
        if (userId == null) {
            return;
        }
        simEpoch.merge(userId, 1L, Long::sum);
        String prefix = userId + "|";
        simCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 带记忆化的回放入口。 */
    private SimEngine.SimResult replay(Long userId, FarmPlot plot, LocalDate sow, LocalDate today) {
        if (plot == null || plot.getId() == null) {
            return doReplay(userId, plot, sow, today);
        }
        long ep = simEpoch.getOrDefault(userId, 0L);
        String key = userId + "|" + plot.getId() + "|" + sow + "|" + today + "|" + ep;
        SimEngine.SimResult hit = simCache.get(key);
        if (hit != null) {
            return hit;
        }
        SimEngine.SimResult r = doReplay(userId, plot, sow, today);
        if (r != null) {
            if (simCache.size() >= SIM_CACHE_MAX) {
                simCache.clear();
            }
            simCache.put(key, r);
        }
        return r;
    }

    private SimEngine.SimResult doReplay(Long userId, FarmPlot plot, LocalDate sow, LocalDate today) {
        CropConfig crop = cropOf(plot);
        if (sow.isAfter(today)) {
            return null;
        }
        // 🔴 2026-10-22 修复（需求 O 验收时发现）：
        // 原来窗口是 [sow, sow+430]，超过 430 天的播种日会把**最近 430 天之后**
        // 的农事动作（浇水 / 除草 / 打药）全部落在回放窗口之外 ——
        // 引擎按 i >= days.size() 提前 break，玩家刚做的操作当场"没反应"，
        // 但数据库里明明有记录。这是「操作无反馈」的根因之一。
        //
        // 正确口径：回放窗口应当**以今天结尾**，往前取最多 430 天。
        // 这样无论这季种了多久，玩家最近的每一次操作都在窗口内，
        // 而远古的播种期积温不再被错误地重复计入。
        long span = ChronoUnit.DAYS.between(sow, today);
        LocalDate winStart = sow;
        if (span > MAX_REPLAY_DAYS) {
            span = MAX_REPLAY_DAYS;
            winStart = today.minusDays(MAX_REPLAY_DAYS);
        }
        List<DayWeather> days = weather.range(userId, zoneOf(plot), winStart, today);

        // 加载该用户在回放窗口内做过的农事, 按日期分组, 引擎按日应用。
        // 注: 只看 [winStart, today] 之间, 防止 demo 推进后查到"窗口之前"的动作污染回放。
        java.util.Map<String, java.util.List<SimEngine.ActionEffect>> actionsByDate = new java.util.HashMap<>();
        List<FarmAction> actions = (plot == null)
                ? actionRepo.findByUserIdAndDateBetweenOrderByDate(userId, winStart, today)
                : seasonActions(userId, plot, winStart, today);
        for (FarmAction a : actions) {
            String dateKey = a.getDate().toString();
            SimEngine.ActionEffect base = SimEngine.ActionEffect.of(a.getType().name());
            // 施肥持续 durationDays 天: 当日 + 后续 N-1 天各享一次 effect
            for (int d = 0; d < a.getDurationDays(); d++) {
                String key = a.getDate().plusDays(d).toString();
                if (key.compareTo(winStart.toString()) < 0) continue;
                if (key.compareTo(today.toString()) > 0) break;
                actionsByDate.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(base);
            }
        }

        return SimEngine.simulate(days, 0, crop, null, 0.0, actionsByDate, (int) span + 1);
    }

    private String nextMilestone(double dvs) {
        if (dvs < 0.16) return "返青分蘖始";
        if (dvs < 0.36) return "够苗晒田期";
        if (dvs < 0.41) return "拔节孕穗期";
        if (dvs < 0.63) return "抽穗开花期";
        if (dvs < 0.72) return "灌浆期";
        if (dvs < 0.92) return "黄熟期";
        return "可收割";
    }

    private double nextBoundary(double dvs) {
        if (dvs < 0.16) return 0.16;
        if (dvs < 0.36) return 0.36;
        if (dvs < 0.41) return 0.41;
        if (dvs < 0.63) return 0.63;
        if (dvs < 0.72) return 0.72;
        if (dvs < 0.92) return 0.92;
        return 1.0;
    }

    /** 用最近 7 天日均 AAT 外推"距下一节点还有几天"——给估算，不给承诺。 */
    private int estimateDaysTo(Long userId, String zoneId, LocalDate sow, LocalDate today,
                               double dvs, double boundary, CropConfig crop) {
        if (boundary <= dvs) {
            return 0;
        }
        double need = (boundary - dvs) * crop.aatReq();
        LocalDate from = today.minusDays(6).isBefore(sow) ? sow : today.minusDays(6);
        double sum = 0;
        int cnt = 0;
        for (DayWeather d : weather.range(userId, zoneId, from, today)) {
            double tm = d.tmean();
            sum += (tm >= crop.aatRef() ? Math.min(tm, crop.tOptUpper()) : 0);
            cnt++;
        }
        double avg = cnt > 0 ? sum / cnt : 27.5;
        if (avg <= 0.1) {
            return 99;
        }
        return (int) Math.ceil(need / avg);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
