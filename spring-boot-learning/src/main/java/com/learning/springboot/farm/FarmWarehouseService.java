package com.learning.springboot.farm;

import com.learning.springboot.entity.Notification;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.farm.engine.WarehouseEngine;
import com.learning.springboot.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仓库服务（S24）：收获物入库、估值、变卖、变质提醒。
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li><b>收割即入库</b>：{@code harvest()} 调 {@link #store} 写一条批次，
 *       钱要等玩家在仓库里点「变卖」才结算——这样「什么时候卖」成为真正的决策；</li>
 *   <li><b>先过期先卖</b>：变卖时优先扣临期批次，避免玩家因为排序问题白扔；</li>
 *   <li><b>供给压力</b>：用户自己囤的货会压低该作物的价格（局部均衡），见
 *       {@link WarehouseEngine#supplyFactor}；</li>
 *   <li><b>临期提醒</b>：距变质 ≤ 提前量时生成站内通知（每批次只提醒一次）。</li>
 * </ul>
 *
 * <h2>⚠️ 世界日期口径</h2>
 * 仓库是<b>用户级</b>的，所以「今天」取<b>用户级世界日期</b>
 * （{@link FarmUserClockService}），而不是 {@code LocalDate.now()}，
 * 也不是「地块里最靠后的那个」（那会被无关地块的偏移污染）。
 */
@Service
public class FarmWarehouseService {

    /** 变卖通知类型（站内消息）。 */
    public static final String NOTICE_TYPE = "SYSTEM";

    /** 关联类型：仓库（临期提醒）。 */
    public static final String REL_WAREHOUSE = "WAREHOUSE";

    /** 关联类型：仓库（已变质提醒）——与临期分开，才能各自判重。 */
    public static final String REL_WAREHOUSE_ROT = "WAREHOUSE_ROT";

    /**
     * 入库品质折价系数上限（2026-10-26）。
     *
     * <p>正常是 1.0，烘干机之类的设备加成最多到 1.3 左右；超过就是脏数据。
     * 夹紧它，避免一条坏记录把整批货的单价算成天文数字。
     */
    public static final double MAX_QUALITY_FACTOR = 1.5;

    private final FarmWarehouseRepository warehouseRepo;
    private final NotificationRepository notificationRepo;
    private final FarmPlotRepository plotRepo;
    private final FarmSteadRepository steadRepo;
    /** 农资库存服务（S25）：仓库的「农资」区 */
    private final FarmStockService stockService;
    /**
     * 商店服务（2026-10-23 需求 R）：卖货进账。
     *
     * <p>⚠️ 依赖方向必须是 {@code Warehouse → Shop → Stock}，不能反过来：
     * 如果 {@code FarmShopService} 反过来依赖 {@code FarmWarehouseService}，
     * 就形成构造器注入环（application.yml 未开 {@code allow-circular}）。
     */
    private final FarmShopService shopService;
    /**
     * 用户级世界时钟（2026-10-26）：仓库老化基准的唯一来源。
     */
    private final FarmUserClockService clock;
    private final boolean demoMode;

    public FarmWarehouseService(FarmWarehouseRepository warehouseRepo,
                                NotificationRepository notificationRepo,
                                FarmPlotRepository plotRepo,
                                FarmSteadRepository steadRepo,
                                FarmStockService stockService,
                                FarmShopService shopService,
                                FarmUserClockService clock,
                                @org.springframework.beans.factory.annotation.Value("${farm.demo-mode:true}")
                                boolean demoMode) {
        this.warehouseRepo = warehouseRepo;
        this.notificationRepo = notificationRepo;
        this.plotRepo = plotRepo;
        this.steadRepo = steadRepo;
        this.stockService = stockService;
        this.shopService = shopService;
        this.clock = clock;
        this.demoMode = demoMode;
    }

    // ==================== 入库 ====================

    /**
     * 收获物入库（收割时调用）。
     *
     * @param userId   用户
     * @param cropId   作物 id
     * @param cropName 作物名
     * @param zoneId   农业区（决定地区价格系数）
     * @param plotId   来源地块
     * @param farmId   来源农场
     * @param kg       重量（kg）
     * @param worldDate 入库的世界日期
     */
    @Transactional
    public FarmWarehouse store(Long userId, String cropId, String cropName, String zoneId,
                                Long plotId, Long farmId, double kg, LocalDate worldDate) {
        return store(userId, cropId, cropName, zoneId, plotId, farmId, kg, worldDate, 1.0);
    }

    /**
     * 收获物入库（收割时调用）。
     *
     * <p>🔴 2026-10-26 修复轻级问题：这个方法以前是<b>死代码</b>——
     * {@code FarmPlayService.harvest} 绕开它手工构造 {@code FarmWarehouse} 实体，
     * 于是这里的 {@code kg <= 0} 拦截与 {@code qualityFactor} 上下界校验<b>全部失效</b>。
     * 现在收割改为真正调用本方法，校验重新生效。
     *
     * @param userId   用户
     * @param cropId   作物 id
     * @param cropName 作物名
     * @param zoneId   农业区（决定地区价格系数）
     * @param plotId   来源地块
     * @param farmId   来源农场
     * @param kg       重量（kg）——必须 &gt; 0，否则不入库
     * @param worldDate 入库的世界日期（必须与 {@link #userWorldDate} 同口径）
     * @param qualityFactor 入库时固化的品质折价（农残超标 0.85，正常 1.0，烘干机可 &gt; 1）
     */
    @Transactional
    public FarmWarehouse store(Long userId, String cropId, String cropName, String zoneId,
                                Long plotId, Long farmId, double kg, LocalDate worldDate,
                                double qualityFactor) {
        if (kg <= 0) {
            return null;
        }
        // 品质折价必须落在合理区间：0 = 货一文不值，1 = 正常，>1 = 烘干机等设备加成。
        // 越界值一律夹紧，避免脏数据把单价算成天文数字或负数。
        double qf = Math.min(MAX_QUALITY_FACTOR, Math.max(0.0, qualityFactor));
        FarmWarehouse w = new FarmWarehouse();
        w.setUserId(userId);
        w.setCropId(cropId);
        w.setCropName(cropName);
        w.setZoneId(zoneId);
        w.setPlotId(plotId);
        w.setFarmId(farmId);
        w.setQuantity(round(kg, 1));
        w.setStoredDate(worldDate);
        w.setStatus(FarmWarehouse.Status.STORED);
        w.setQualityFactor(qf);
        return warehouseRepo.save(w);
    }

    // ==================== 查询 ====================

    /**
     * 仓库面板数据。
     *
     * @return 见 {@link #view} 的字段说明
     */
    @Transactional(readOnly = true)
    public Map<String, Object> panel(Long userId) {
        LocalDate today = userWorldDate(userId);
        List<FarmWarehouse> stored = warehouseRepo.findByUserIdAndStatusOrderByStoredDateAscIdAsc(
                userId, FarmWarehouse.Status.STORED);

        // 各作物总库存（供给压力的输入）——只统计**还能卖**的
        //
        // ⚠️ 已变质的货不能算进供给压力：收购商根本不收烂货，
        // 它压不了价却会把玩家可卖批次的单价算低。已变质的批次要单独统计
        // （rottenKgByCrop），UI 上提示玩家赶紧清理。
        Map<String, Double> kgByCrop = new LinkedHashMap<>();
        Map<String, Double> rottenKgByCrop = new LinkedHashMap<>();
        for (FarmWarehouse w : stored) {
            double kg = w.getQuantity() == null ? 0 : w.getQuantity();
            if (WarehouseEngine.grade(w.getCropId(), w.getStoredDate(), today) == WarehouseEngine.Grade.ROTTEN) {
                rottenKgByCrop.merge(w.getCropId(), kg, Double::sum);
            } else {
                kgByCrop.merge(w.getCropId(), kg, Double::sum);
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        double totalValue = 0;
        double totalKg = 0;
        double rottenKg = 0;
        int expiringCount = 0;
        int rottenCount = 0;

        // 🔴 S25：农场 id → 名称（批次要显示「来自哪块地」）
        Map<Long, String> farmNames = farmNameMap(userId);

        for (FarmWarehouse w : stored) {
            double kg = w.getQuantity() == null ? 0 : w.getQuantity();
            WarehouseEngine.Storage s = WarehouseEngine.storage(w.getCropId());
            WarehouseEngine.Grade g = WarehouseEngine.grade(w.getCropId(), w.getStoredDate(), today);
            double cropKg = kgByCrop.getOrDefault(w.getCropId(), 0.0);
            double qf = w.getQualityFactor() == null || w.getQualityFactor() <= 0 ? 1.0 : w.getQualityFactor();
            WarehouseEngine.Valuation v = WarehouseEngine.value(w.getCropId(), w.getZoneId(), kg,
                    w.getStoredDate(), today, cropKg, qf);
            int left = v.daysLeft();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", w.getId());
            item.put("cropId", w.getCropId());
            item.put("cropName", w.getCropName() != null ? w.getCropName() : s.name());
            item.put("zoneId", w.getZoneId());
            item.put("zoneName", zoneName(w.getZoneId()));
            item.put("quantity", v.quantity());
            // 🔴 2026-10-26：storedDate 判空。原代码直接 .toString()，
            // 遇到入库日期为空的脏批次 → NullPointerException → 整个仓库面板 500。
            // 引擎侧（WarehouseEngine.agedDays）本来就判空返回 0 天，
            // 这里保持一致：null 就当「刚入库」（仓储 0 天）。
            item.put("storedDate", w.getStoredDate() == null ? null : w.getStoredDate().toString());
            item.put("agedDays", v.agedDays());
            item.put("daysLeft", left);
            item.put("storageDays", s.storageDays());
            item.put("warnDays", s.warnDays());
            item.put("grade", g.name());
            item.put("gradeName", g.label());
            item.put("sellable", g.sellable());
            item.put("expiring", v.expiring());
            item.put("unitPrice", v.unitPrice());
            item.put("amount", v.amount());
            item.put("supplyNote", v.supplyNote());
            item.put("basePrice", s.basePrice());
            item.put("zoneFactor", round(WarehouseEngine.zonePriceFactor(w.getZoneId(), w.getCropId()), 3));
            item.put("supplyFactor", round(WarehouseEngine.supplyFactor(cropKg), 3));
            item.put("cropTotalKg", round(cropKg, 1));
            // 🔴 S25：农场归属 —— 前端「按农场筛选」和「农场」列都靠这个字段。
            // 老批次（建仓前没有 farm_id）会是 null，前端归入「未归属农场」。
            item.put("farmId", w.getFarmId());
            item.put("farmName", farmNames.get(w.getFarmId()));
            // 批次固化的品质折价（农残超标 0.85）——UI 要如实标出来
            item.put("qualityFactor", round(qf, 3));
            item.put("residuePenalty", qf < 0.999);
            items.add(item);

            totalKg += kg;
            if (g == WarehouseEngine.Grade.ROTTEN) {
                rottenCount++;
                rottenKg += kg;
            } else {
                totalValue += v.amount();
            }
            if (v.expiring()) {
                expiringCount++;
            }
        }

        // 临期在前、其次按剩余天数升序（最紧急的排最前）
        items.sort(Comparator
                .comparing((Map<String, Object> m) -> !Boolean.TRUE.equals(m.get("expiring")))
                .thenComparing(m -> (Integer) m.get("daysLeft"))
                .thenComparing(m -> (String) m.get("cropId")));

        // 已变卖的历史（最近 20 条）
        List<Map<String, Object>> sold = new ArrayList<>();
        double totalIncome = 0;
        for (FarmWarehouse w : warehouseRepo.findByUserIdOrderByStoredDateAscIdAsc(userId)) {
            // 部分卖出的批次也要进历史（soldKg > 0），但还没卖完的不算「已变卖」
            double soldKg = w.getSoldKg() == null ? 0 : w.getSoldKg();
            if (w.getStatus() != FarmWarehouse.Status.SOLD && soldKg <= 0) {
                continue;
            }
            double amt = w.getSoldAmount() == null ? 0 : w.getSoldAmount();
            totalIncome += amt;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("cropId", w.getCropId());
            m.put("cropName", w.getCropName());
            // 展示**累计卖出**的重量，不是剩余库存
            m.put("quantity", soldKg > 0 ? soldKg : w.getQuantity());
            m.put("soldPrice", w.getSoldPrice());
            m.put("soldAmount", amt);
            m.put("soldDate", w.getSoldDate() == null ? null : w.getSoldDate().toString());
            sold.add(m);
        }
        sold.sort(Comparator.comparing(m -> (String) m.get("soldDate"),
                Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worldDate", today.toString());
        out.put("items", items);
        out.put("totalValue", round(totalValue, 1));
        out.put("totalKg", round(totalKg, 1));
        out.put("expiringCount", expiringCount);
        out.put("rottenCount", rottenCount);
        out.put("rottenKg", round(rottenKg, 1));
        out.put("totalIncome", round(totalIncome, 1));
        out.put("soldHistory", sold.size() > 20 ? sold.subList(0, 20) : sold);
        out.put("storageRules", storageRules());
        // S25：筛选维度元数据（农场 / 作物 / 地域）+ 农资库存区
        out.put("facets", facets(userId));
        out.put("inputs", stockService.panel(userId, null, null));
        return out;
    }

    /**
     * 农场 id → 农场名（S25）。
     *
     * <p>{@code panel()} 和 {@code facets()} 都要用这张表，抽出来避免两处口径不一致。
     */
    private Map<Long, String> farmNameMap(Long userId) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (FarmStead st : steadRepo.findByUserIdOrderByIdAsc(userId)) {
            names.put(st.getId(), st.getName());
        }
        return names;
    }

    /**
     * 仓库筛选维度的可选值（S25）。
     *
     * <p>三个维度各自独立：<b>农场</b>（货从哪块地来的）、<b>作物</b>（是什么货）、
     * <b>地域</b>（哪个农业区 → 决定地区价格系数）。
     *
     * <p>「未归属农场」的老田（{@code farm_id = null}）必须单列，
     * 否则统计会凭空少一大半——这是地图农场上线前开的老地。
     */
    private Map<String, Object> facets(Long userId) {
        List<FarmWarehouse> stored = warehouseRepo.findByUserIdAndStatusOrderByStoredDateAscIdAsc(
                userId, FarmWarehouse.Status.STORED);

        // 农场维度：id → 名称（去重）
        Map<Long, String> farmNames = farmNameMap(userId);
        // 该用户所有地块归属的 farm_id（用于「有货但地块已删」的兜底）
        Set<Long> farmIds = new LinkedHashSet<>();
        for (FarmPlot p : plotRepo.findByUserIdOrderBySlotAsc(userId)) {
            if (p.getFarmId() != null) {
                farmIds.add(p.getFarmId());
            }
        }
        for (FarmWarehouse w : stored) {
            if (w.getFarmId() != null) {
                farmIds.add(w.getFarmId());
            }
        }

        List<Map<String, Object>> farmOptions = new ArrayList<>();
        boolean hasUnassigned = false;
        for (FarmWarehouse w : stored) {
            if (w.getFarmId() == null) {
                hasUnassigned = true;
            }
        }
        if (hasUnassigned) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", null);
            m.put("name", "未归属农场");
            m.put("count", countBy(stored, w -> w.getFarmId() == null));
            farmOptions.add(m);
        }
        for (Long fid : farmIds) {
            String nm = farmNames.get(fid);
            if (nm == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", fid);
            m.put("name", nm);
            m.put("count", countBy(stored, w -> fid.equals(w.getFarmId())));
            farmOptions.add(m);
        }

        // 作物维度：cropId → 名称 + 数量
        Map<String, String> cropNames = new LinkedHashMap<>();
        Map<String, Double> cropKg = new LinkedHashMap<>();
        for (FarmWarehouse w : stored) {
            cropNames.putIfAbsent(w.getCropId(),
                    w.getCropName() != null ? w.getCropName() : WarehouseEngine.storage(w.getCropId()).name());
            cropKg.merge(w.getCropId(), w.getQuantity() == null ? 0 : w.getQuantity(), Double::sum);
        }
        List<Map<String, Object>> cropOptions = new ArrayList<>();
        for (Map.Entry<String, Double> e : cropKg.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("name", cropNames.get(e.getKey()));
            m.put("quantity", round(e.getValue(), 1));
            cropOptions.add(m);
        }
        cropOptions.sort(Comparator.comparing(m -> (String) m.get("name")));

        // 地域维度：zoneId → 名称 + 数量
        Map<String, String> zoneNames = new LinkedHashMap<>();
        Map<String, Double> zoneKg = new LinkedHashMap<>();
        for (FarmWarehouse w : stored) {
            if (w.getZoneId() == null) {
                continue;
            }
            zoneNames.putIfAbsent(w.getZoneId(), zoneName(w.getZoneId()));
            zoneKg.merge(w.getZoneId(), w.getQuantity() == null ? 0 : w.getQuantity(), Double::sum);
        }
        List<Map<String, Object>> zoneOptions = new ArrayList<>();
        for (Map.Entry<String, Double> e : zoneKg.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("name", zoneNames.get(e.getKey()));
            m.put("quantity", round(e.getValue(), 1));
            zoneOptions.add(m);
        }
        zoneOptions.sort(Comparator.comparing(m -> (String) m.get("name")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("farms", farmOptions);
        out.put("crops", cropOptions);
        out.put("zones", zoneOptions);
        return out;
    }

    /** 满足条件的批次数。 */
    private int countBy(List<FarmWarehouse> list, java.util.function.Predicate<FarmWarehouse> p) {
        int n = 0;
        for (FarmWarehouse w : list) {
            if (p.test(w)) {
                n++;
            }
        }
        return n;
    }

    /** 各作物的仓储期规则（UI 展示「每种作物能存多久」）。 */
    private List<Map<String, Object>> storageRules() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (WarehouseEngine.Storage s : WarehouseEngine.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cropId", s.id());
            m.put("cropName", s.name());
            m.put("storageDays", s.storageDays());
            m.put("warnDays", s.warnDays());
            m.put("basePrice", s.basePrice());
            m.put("perishable", s.perishable());
            rules.add(m);
        }
        return rules;
    }

    // ==================== 变卖 ====================

    /**
     * 变卖：把仓库里的存货卖掉。
     *
     * <p><b>批次选择策略</b>：优先卖<b>临期</b>的（剩余天数少的先走），
     * 同等紧迫度按入库时间早的先走。玩家可以选择卖全部或只卖某个作物。
     *
     * @param userId  用户
     * @param cropId  只卖这个作物；null = 全部卖掉
     * @param kg      卖多少 kg；≤0 或 null = 该作物全部卖掉
     * @return 成交明细
     */
    @Transactional
    public Map<String, Object> sell(Long userId, String cropId, Double kg) {
        LocalDate today = userWorldDate(userId);
        List<FarmWarehouse> stored = warehouseRepo.findByUserIdAndStatusOrderByStoredDateAscIdAsc(
                userId, FarmWarehouse.Status.STORED);

        // 过滤目标作物
        List<FarmWarehouse> targets = new ArrayList<>();
        for (FarmWarehouse w : stored) {
            if (cropId != null && !cropId.isBlank() && !cropId.equals(w.getCropId())) {
                continue;
            }
            targets.add(w);
        }
        if (targets.isEmpty()) {
            throw new BusinessException("仓库里没有可卖的货");
        }

        // 排序：先卖临期的（剩余天数少的先走），再按入库时间
        //
        // ⚠️ 供给压力基数只算**还能卖**的货：已变质的货收购商不收，
        // 它压不了价，却会把玩家可卖批次的单价算低（口径与 panel() 一致）。
        Map<String, Double> kgByCrop = new LinkedHashMap<>();
        for (FarmWarehouse w : stored) {
            if (WarehouseEngine.grade(w.getCropId(), w.getStoredDate(), today) == WarehouseEngine.Grade.ROTTEN) {
                continue;
            }
            kgByCrop.merge(w.getCropId(), w.getQuantity() == null ? 0 : w.getQuantity(), Double::sum);
        }
        LocalDate todayFinal = today;
        targets.sort(Comparator
                .comparingInt((FarmWarehouse w) -> {
                    WarehouseEngine.Storage s = WarehouseEngine.storage(w.getCropId());
                    return WarehouseEngine.daysToSpoil(s, w.getStoredDate(), todayFinal);
                })
                .thenComparing(FarmWarehouse::getStoredDate)
                .thenComparing(FarmWarehouse::getId));

        double wantKg = (kg == null || kg <= 0) ? Double.MAX_VALUE : kg;
        double soldKg = 0;
        double income = 0;
        List<Map<String, Object>> soldItems = new ArrayList<>();
        int expiredSkipped = 0;

        for (FarmWarehouse w : targets) {
            if (soldKg >= wantKg) {
                break;
            }
            double batchKg = w.getQuantity() == null ? 0 : w.getQuantity();
            if (batchKg <= 0) {
                continue;
            }
            WarehouseEngine.Grade g = WarehouseEngine.grade(w.getCropId(), w.getStoredDate(), today);
            if (!g.sellable()) {
                // 已变质：不能卖，直接标丢弃（白扔）
                w.setStatus(FarmWarehouse.Status.DISCARDED);
                warehouseRepo.save(w);
                expiredSkipped++;
                continue;
            }
            double cropKg = kgByCrop.getOrDefault(w.getCropId(), 0.0);
            double qf = w.getQualityFactor() == null || w.getQualityFactor() <= 0 ? 1.0 : w.getQualityFactor();
            // ⚠️ 供给压力按「卖之前的库存」算：卖得越多，压价越狠的是卖之前的状态
            double unit = round(WarehouseEngine.storage(w.getCropId()).basePrice()
                    * WarehouseEngine.zonePriceFactor(w.getZoneId(), w.getCropId())
                    * g.factor()
                    * qf
                    * WarehouseEngine.supplyFactor(cropKg), 3);

            double take = Math.min(batchKg, wantKg - soldKg);
            double amount = round(unit * take, 1);

            // ⚠️ 部分卖出：金额/重量要**累计**，不能覆盖。
            // 原来直接 setSoldAmount(amount) —— 第二次卖出会把第一次的金额抹掉，
            // 「变卖记录」显示的收入比实际少一半。
            w.setQuantity(round(batchKg - take, 1));
            double prevKg = w.getSoldKg() == null ? 0 : w.getSoldKg();
            double prevAmt = w.getSoldAmount() == null ? 0 : w.getSoldAmount();
            w.setSoldKg(round(prevKg + take, 1));
            w.setSoldAmount(round(prevAmt + amount, 1));
            w.setSoldPrice(unit);
            w.setSoldDate(today);
            if (w.getQuantity() <= 0.01) {
                w.setStatus(FarmWarehouse.Status.SOLD);
            }
            warehouseRepo.save(w);

            soldKg += take;
            income += amount;
            // 供给压力随卖出即时下降
            kgByCrop.merge(w.getCropId(), -take, Double::sum);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cropId", w.getCropId());
            m.put("cropName", w.getCropName());
            m.put("kg", round(take, 1));
            m.put("unitPrice", unit);
            m.put("amount", amount);
            m.put("grade", g.name());
            soldItems.add(m);
        }

        // ⚠️ 这里**不能抛异常**。
        // 抛出去会让 Spring 回滚整个事务 —— 上面刚标的 DISCARDED 全部作废，
        // 玩家下次点「卖出」还是「这批货已经变质」，仓库里那批烂货永远清不掉
        // （实测踩到过）。改成正常返回，让「已清掉 N 批变质货」这个事实能落库，
        // 前端据 expiredDiscarded 提示玩家。
        //
        // 🔴 2026-10-23 需求 R：卖货收入进钱包。
        // 这是「商店」能玩起来的前提——原来卖货的钱凭空消失，
        // 玩家既没钱买种子也没钱买农具，整个经济闭环是断的。
        // ⚠️ earn() 内部也不抛异常（余额不足才抛），这里收入一定 > 0，安全。
        if (income > 0) {
            shopService.earn(userId, income);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("soldKg", round(soldKg, 1));
        out.put("income", round(income, 1));
        out.put("items", soldItems);
        out.put("expiredDiscarded", expiredSkipped);
        out.put("worldDate", today.toString());
        out.put("balance", round(shopService.balanceOf(userId), 2));
        if (soldKg <= 0) {
            out.put("message", expiredSkipped > 0
                    ? "这批货已经变质，卖不出去了（已帮你清理掉 " + expiredSkipped + " 批）"
                    : "没有可卖的货");
        }
        return out;
    }

    // ==================== 临期提醒 ====================

    /**
     * 检查临期批次并生成站内通知（每批次每种提醒只发一次）。
     *
     * <p>去重方式：按 {@code relatedType + relatedId} 判重。
     * <ul>
     *   <li>{@link #REL_WAREHOUSE} —— 临期提醒（距变质 ≤ 提前量）；</li>
     *   <li>{@link #REL_WAREHOUSE_ROT} —— 已变质提醒（货彻底废了，该提示玩家去清理）。</li>
     * </ul>
     * 两种提醒分开去重，因为同一批货会先「临期」再「变质」，
     * 用同一个 relatedType 判重会让第二条永远发不出去。
     *
     * @return 本次新生成的通知条数
     */
    @Transactional
    public int checkExpiring(Long userId) {
        LocalDate today = userWorldDate(userId);
        List<FarmWarehouse> stored = warehouseRepo.findByUserIdAndStatusOrderByStoredDateAscIdAsc(
                userId, FarmWarehouse.Status.STORED);

        // 已有提醒的批次 id（按提醒类型分开）
        java.util.Set<Long> notifiedExpiring = notifiedIds(userId, REL_WAREHOUSE);
        java.util.Set<Long> notifiedRotten = notifiedIds(userId, REL_WAREHOUSE_ROT);

        int created = 0;
        for (FarmWarehouse w : stored) {
            WarehouseEngine.Storage s = WarehouseEngine.storage(w.getCropId());
            String cropName = w.getCropName() != null ? w.getCropName() : s.name();
            double kg = w.getQuantity() == null ? 0 : w.getQuantity();

            // ① 已变质：货废了，明确告诉玩家「这批只能扔了」
            if (WarehouseEngine.grade(w.getCropId(), w.getStoredDate(), today) == WarehouseEngine.Grade.ROTTEN) {
                if (notifiedRotten.contains(w.getId())) {
                    continue;
                }
                notifiedRotten.add(w.getId());
                notificationRepo.save(new Notification(userId, NOTICE_TYPE,
                        "仓库里的" + cropName + "已经变质了",
                        "你入仓的 " + round(kg, 1) + " kg " + cropName
                                + "已超过 " + s.storageDays() + " 天的仓储期，收购商不收这类货了。"
                                + "在仓库里点「卖出」会把这批清掉——记得及时清理，别占着地方。",
                        REL_WAREHOUSE_ROT, w.getId()));
                created++;
                continue;
            }

            // ② 临期：还能卖，赶紧卖
            if (!WarehouseEngine.isExpiring(s, w.getStoredDate(), today)) {
                continue;
            }
            if (notifiedExpiring.contains(w.getId())) {
                continue;
            }
            notifiedExpiring.add(w.getId());
            int left = WarehouseEngine.daysToSpoil(s, w.getStoredDate(), today);
            notificationRepo.save(new Notification(userId, NOTICE_TYPE,
                    "仓库里的" + cropName + "快变质了",
                    "你入仓的 " + round(kg, 1) + " kg " + cropName
                            + "还剩 " + left + " 天就要变质，变质后卖不出去了。"
                            + "现在就卖掉还能按 " + gradePriceHint(w, today) + " 出手。",
                    REL_WAREHOUSE, w.getId()));
            created++;
        }
        return created;
    }

    /** 某类仓库提醒下已通知过的批次 id 集合。 */
    private java.util.Set<Long> notifiedIds(Long userId, String relatedType) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Notification n : notificationRepo.findByUserIdAndRelatedType(userId, relatedType)) {
            if (n.getRelatedId() != null) {
                ids.add(n.getRelatedId());
            }
        }
        return ids;
    }

    /** 提醒文案里的价格提示（粗算当前单价，不含供给压力，避免文案过长）。 */
    private String gradePriceHint(FarmWarehouse w, LocalDate today) {
        WarehouseEngine.Grade g = WarehouseEngine.grade(w.getCropId(), w.getStoredDate(), today);
        double qf = w.getQualityFactor() == null || w.getQualityFactor() <= 0 ? 1.0 : w.getQualityFactor();
        double unit = WarehouseEngine.storage(w.getCropId()).basePrice()
                * WarehouseEngine.zonePriceFactor(w.getZoneId(), w.getCropId())
                * g.factor()
                * qf;
        return round(unit, 2) + " 元/kg";
    }

    // ==================== 工具 ====================

    /**
     * 该用户的「世界日期」。
     *
     * <p>🔴 2026-10-26：改读 {@link FarmUserClockService}（用户级时钟），
     * 不再从地块反推「最靠后的那个」。
     *
     * <p>旧口径的致命问题：任何一块无关地块的偏移都会污染仓库老化基准。
     * 2026-10-25 实测：{@code plot27} 残留偏移 660 天 → 整个用户被推到 2028-08-15
     * → 仓库 6 件商品全部「仓储 800+ 天 → 已变质 → ¥0」。
     *
     * <p>仓库是<b>用户级</b>的，所以「今天」必须是<b>用户级</b>的，
     * 且与收割入库日期（也走 {@code userToday}）同源 ——
     * 这样「入库 → 老化」永远在同一条时间轴上。
     */
    public LocalDate userWorldDate(Long userId) {
        return clock.today(userId);
    }

    private String zoneName(String zoneId) {
        if (zoneId == null) {
            return null;
        }
        try {
            return com.learning.springboot.farm.engine.ClimateModel.zone(zoneId).name();
        } catch (Exception ex) {
            return zoneId;
        }
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
