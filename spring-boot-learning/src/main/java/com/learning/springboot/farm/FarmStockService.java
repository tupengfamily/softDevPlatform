package com.learning.springboot.farm;

import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.farm.engine.InputCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农资库存服务（S25）：购买入库、施用扣减、库存视图。
 *
 * <h2>为什么农资也要有库存</h2>
 * 作物入库是为了做「什么时候卖」的决策；农资入库是为了做「什么时候用、用多少」的决策。
 * <b>没有库存的农资，等于钱白花</b>——玩家点一次施药按钮就扣一次钱，
 * 但不知道家里还有几袋尿素，于是要么囤到过期（浪费），要么每次都用最贵的。
 *
 * <h2>与作物的区别</h2>
 * <ul>
 *   <li>农资<b>不会变质</b>：尿素放十年还是尿素，所以没有仓储期与品质分级；</li>
 *   <li>单位不统一：kg / g / ml 三种，所以数量列不与作物表合并；</li>
 *   <li>施用时<b>先扣库存</b>，库存不足直接拒绝（不能「用不存在的药」）。</li>
 * </ul>
 *
 * <h2>首次进入的兜底</h2>
 * 老玩家（本次需求之前就开始玩的）库存表是空的，如果不做处理，
 * 点施药会全部失败——比功能没做还糟。这里给一份<b>初始备货</b>，
 * 数量按「够一季用」的量给，不多给（多给就没有购买决策了）。
 */
@Service
public class FarmStockService {

    /**
     * 初始备货：够种一季的量（按推荐用量 × 3 轮的粗略估计）。
     *
     * <p>⚠️ 用 {@link LinkedHashMap} 而不是 {@code Map.of()}——
     * {@code Map.of()} 最多支持 10 对键值，而这里有 17 种农资。
     */
    private static final Map<String, Double> INITIAL_STOCK = initialStock();

    private static Map<String, Double> initialStock() {
        Map<String, Double> m = new LinkedHashMap<>();
        // 化肥：按每亩推荐量给 5 亩的量
        m.put("UREA", 110.0);
        m.put("COMPOUND", 150.0);
        m.put("KCL", 50.0);
        m.put("BORAX", 4.0);
        // 农药：g / ml 为单位，给 10 亩地的量
        m.put("TRICYCLAZOLE", 500.0);
        m.put("JINGGANGMEISU", 400.0);
        m.put("COPPER", 600.0);
        m.put("IMIDACLOPRID", 200.0);
        m.put("CHLORANBENILIPROLE", 300.0);
        m.put("CYPROCONAZOLE", 800.0);
        m.put("TRIADIMEFON", 600.0);
        m.put("EMAMECTIN", 150.0);
        m.put("RHIZOBIUM", 2500.0);
        m.put("MANCOZEB", 1800.0);
        m.put("ABAMECTIN", 400.0);
        m.put("BT", 600.0);
        // 除草剂
        m.put("QUIZALOFOP", 500.0);
        m.put("BENSULFURON", 900.0);
        return java.util.Collections.unmodifiableMap(m);
    }

    private final FarmStockRepository stockRepo;

    public FarmStockService(FarmStockRepository stockRepo) {
        this.stockRepo = stockRepo;
    }

    // ==================== 查询 ====================

    /**
     * 库存视图（仓库面板的「农资」区）。
     *
     * @param userId 用户
     * @param farmId 农场过滤（null = 全部）
     * @param kind   类别过滤（FERT / PEST / HERB，null = 全部）
     * @return 库存行 + 汇总
     */
    public Map<String, Object> panel(Long userId, Long farmId, String kind) {
        ensureInitialStock(userId);

        List<FarmStock> rows = farmId == null
                ? stockRepo.findByUserIdOrderByQuantityDescIdAsc(userId)
                : stockRepo.findByUserIdAndFarmIdOrderByQuantityDescIdAsc(userId, farmId);

        List<Map<String, Object>> items = new ArrayList<>();
        double totalValue = 0;
        int kinds = 0;
        for (FarmStock s : rows) {
            if (kind != null && !kind.isBlank() && !kind.equalsIgnoreCase(s.getItemKind())) {
                continue;
            }
            if (s.getQuantity() <= 0.0001) {
                continue; // 用尽的货不再列（保持清单清爽）
            }
            InputCatalog.Item def = InputCatalog.get(s.getItemId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("itemId", s.getItemId());
            m.put("itemName", s.getItemName());
            m.put("kind", s.getItemKind());
            m.put("category", def == null ? "" : def.category());
            m.put("unit", s.getUnit());
            m.put("quantity", round(s.getQuantity(), 1));
            m.put("totalBought", round(s.getTotalBought(), 1));
            m.put("pricePerUnit", s.getPricePerUnit());
            m.put("value", round(s.getQuantity() * (s.getPricePerUnit() == null ? 0 : s.getPricePerUnit()), 1));
            m.put("totalCost", round(s.getTotalCost(), 1));
            m.put("farmId", s.getFarmId());
            // 一件农资够施多少亩（推荐用量）—— 玩家最想知道的是「还能用几遍」
            m.put("dosesLeft", def == null || def.doseOpt() <= 0
                    ? 0
                    : Math.floor(s.getQuantity() / def.doseOpt() * 10) / 10.0);
            // 🔴 S25：**每施一遍要多少**（doseOpt）。
            //
            // 前端「补货」默认量要按「施 N 遍需要多少货」来算，
            // 而 N 是玩家自己定的（不是 dosesLeft —— 那是「现有库存还能施几遍」，
            // 两个量纲混用会算出 9999 这种荒谬的默认购买量）。
            m.put("doseOpt", def == null ? 0 : def.doseOpt());
            m.put("doseMin", def == null ? 0 : def.doseMin());
            m.put("doseMax", def == null ? 0 : def.doseMax());
            m.put("note", def == null ? "" : def.note());
            m.put("phiDays", def == null ? 0 : def.phiDays());
            m.put("modeGroup", def == null ? "" : def.modeGroup());
            items.add(m);
            totalValue += s.getQuantity() * (s.getPricePerUnit() == null ? 0 : s.getPricePerUnit());
            kinds++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("totalValue", round(totalValue, 1));
        out.put("kindCount", kinds);
        out.put("totalCost", round(rows.stream()
                .mapToDouble(s -> s.getTotalCost() == null ? 0 : s.getTotalCost()).sum(), 1));
        return out;
    }

    /**
     * 取某件农资的可用库存（跨农场汇总）。
     *
     * @return 剩余数量；没有记录返回 0
     */
    public double available(Long userId, String itemId) {
        double sum = 0;
        for (FarmStock s : stockRepo.findByUserIdAndItemIdOrderByIdAsc(userId, itemId)) {
            sum += s.getQuantity() == null ? 0 : s.getQuantity();
        }
        return round(sum, 1);
    }

    // ==================== 变更 ====================

    /**
     * 购买入库。
     *
     * @param userId 用户
     * @param itemId 农资 id
     * @param qty    数量（按该农资的单位）
     * @param farmId 农场（可空 = 全局）
     * @return 新的库存行
     */
    @Transactional
    public FarmStock buy(Long userId, String itemId, double qty, Long farmId) {
        InputCatalog.Item def = InputCatalog.get(itemId);
        if (def == null) {
            throw new BusinessException("未知农资：" + itemId);
        }
        if (qty <= 0) {
            throw new BusinessException("购买数量必须大于 0");
        }
        double price = def.pricePerUnit();
        double cost = round(qty * price, 2);

        // 同一用户 + 同一农资 + 同一农场 → 合并到一行（避免碎片行）
        FarmStock row = null;
        List<FarmStock> existing = stockRepo.findByUserIdAndItemIdOrderByIdAsc(userId, itemId);
        for (FarmStock s : existing) {
            boolean sameFarm = s.getFarmId() == null ? farmId == null : s.getFarmId().equals(farmId);
            if (sameFarm) {
                row = s;
                break;
            }
        }
        if (row == null) {
            row = new FarmStock();
            row.setUserId(userId);
            row.setItemId(def.id());
            row.setItemName(def.name());
            row.setItemKind(def.kind().name());
            row.setUnit(def.unit());
            row.setFarmId(farmId);
            row.setPricePerUnit(price);
            row.setTotalBought(0.0);
            row.setTotalCost(0.0);
        }
        row.setQuantity(round((row.getQuantity() == null ? 0 : row.getQuantity()) + qty, 1));
        row.setTotalBought(round((row.getTotalBought() == null ? 0 : row.getTotalBought()) + qty, 1));
        row.setTotalCost(round((row.getTotalCost() == null ? 0 : row.getTotalCost()) + cost, 2));
        row.setStatus(FarmStock.Status.IN_STOCK);
        return stockRepo.save(row);
    }

    /**
     * 施用扣减库存。
     *
     * <p>🔴 库存不足时<b>抛异常</b>（与变卖变质货不同）：
     * 这里是「前置条件不满足」，调用方 {@code applyInput} 本身就在事务里，
     * 抛出去正好让整个施用动作回滚，不存在「标记写了又作废」的问题。
     *
     * @param userId 用户
     * @param itemId 农资 id
     * @param dose   用量
     */
    @Transactional
    public void consume(Long userId, String itemId, double dose) {
        if (dose <= 0) {
            return;
        }
        List<FarmStock> rows = stockRepo.findByUserIdAndItemIdOrderByIdAsc(userId, itemId);
        double have = 0;
        for (FarmStock s : rows) {
            have += s.getQuantity() == null ? 0 : s.getQuantity();
        }
        if (have + 1e-6 < dose) {
            InputCatalog.Item def = InputCatalog.get(itemId);
            String name = def == null ? itemId : def.name();
            String unit = def == null ? "" : def.unit();
            throw new BusinessException(String.format(
                    "库存不足：%s 只剩 %s%s，够用 %s%s，但这次要 %s%s",
                    name, trim(have), unit, trim(have), unit, trim(dose), unit));
        }
        // 先进先出：从最早的库存行开始扣
        double left = dose;
        for (FarmStock s : rows) {
            if (left <= 1e-6) {
                break;
            }
            double q = s.getQuantity() == null ? 0 : s.getQuantity();
            if (q <= 0) {
                continue;
            }
            double take = Math.min(q, left);
            s.setQuantity(round(q - take, 1));
            left = round(left - take, 1);
            s.setStatus(s.getQuantity() <= 0.0001
                    ? FarmStock.Status.DEPLETED
                    : FarmStock.Status.IN_STOCK);
            stockRepo.save(s);
        }
    }

    // ==================== 内部 ====================

    /**
     * 首次进入给一份初始备货。
     *
     * <p><b>为什么必须有</b>：老玩家的库存表是空的。如果不兜底，
     * 他点任何一次施药都会「库存不足」——功能看起来像坏了。
     * 初始备货按「够种一季」给，不多给（多给就没有购买决策了）。
     */
    @Transactional
    public void ensureInitialStock(Long userId) {
        List<FarmStock> existing = stockRepo.findByUserIdOrderByQuantityDescIdAsc(userId);
        if (!existing.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Double> e : INITIAL_STOCK.entrySet()) {
            InputCatalog.Item def = InputCatalog.get(e.getKey());
            if (def == null) {
                continue;
            }
            FarmStock s = new FarmStock();
            s.setUserId(userId);
            s.setItemId(def.id());
            s.setItemName(def.name());
            s.setItemKind(def.kind().name());
            s.setUnit(def.unit());
            s.setFarmId(null);
            s.setQuantity(e.getValue());
            s.setTotalBought(e.getValue());
            s.setPricePerUnit(def.pricePerUnit());
            s.setTotalCost(round(e.getValue() * def.pricePerUnit(), 2));
            s.setStatus(FarmStock.Status.IN_STOCK);
            stockRepo.save(s);
        }
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    /** 去掉多余的 .0，显示更干净。 */
    private static String trim(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(round(v, 1));
    }
}
