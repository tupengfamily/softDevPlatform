package com.learning.springboot.farm;

import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.InputCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商店服务（2026-10-23 需求 R）：买种子、农具、农资、水资源。
 *
 * <h2>需求原文</h2>
 * <blockquote>「仓库旁边新增商店功能 用于购买 种子 农具 农资 水资源等农业生产用具
 * 有这些东西后才能种植 或者提高农业生产效率」</blockquote>
 *
 * <h2>四类商品的语义差异（这是设计的关键）</h2>
 * <table border="1">
 *   <caption>商品分类语义</caption>
 *   <tr><th>品类</th><th>库存形态</th><th>消耗时机</th><th>作用</th></tr>
 *   <tr><td>种子</td><td>按包计</td><td>播种时扣 1 包</td><td><b>硬门槛</b>：没种子开不了田</td></tr>
 *   <tr><td>农资</td><td>按 kg/g/ml</td><td>施用时扣</td><td>已有（S12/S25）</td></tr>
 *   <tr><td>农具</td><td>拥有/未拥有</td><td><b>不消耗</b></td><td>提高效率、放宽作业窗口</td></tr>
 *   <tr><td>水资源</td><td>按份（份内含次数）</td><td>每次灌溉扣 1 次</td><td><b>硬门槛</b>：没额度灌不了</td></tr>
 * </table>
 *
 * <h2>为什么农具是「耐用品」而种子/水是「消耗品」</h2>
 * 农具买一次长期持有（库存数量 0 或 1），重复购买纯浪费——所以购买时要拦。
 * 种子和水是消耗品，买多份有意义（多地轮作 / 缺水年多备）。
 *
 * <h2>金额口径</h2>
 * 余额挂在 {@link FarmWallet}（用户级）。买货扣钱，卖货进账——
 * 这条闭环是「商店」这个需求真正的价值所在：原来农资是白给的，
 * 现在每一笔投入都有成本，仓库里的每一批货都能换成下一季的本钱。
 */
@Service
public class FarmShopService {

    private final FarmStockRepository stockRepo;
    private final FarmWalletRepository walletRepo;
    private final FarmStockService stockService;
    /** 地块仓库（算用户级世界日期用） */
    private final FarmPlotRepository plotRepo;
    /**
     * 用户级世界时钟（2026-10-26）：商店世界日期的唯一来源。
     */
    private final FarmUserClockService clock;
    private final boolean demoMode;

    public FarmShopService(FarmStockRepository stockRepo,
                           FarmWalletRepository walletRepo,
                           FarmStockService stockService,
                           FarmPlotRepository plotRepo,
                           FarmUserClockService clock,
                           @org.springframework.beans.factory.annotation.Value("${farm.demo-mode:true}")
                           boolean demoMode) {
        this.stockRepo = stockRepo;
        this.walletRepo = walletRepo;
        this.stockService = stockService;
        this.plotRepo = plotRepo;
        this.clock = clock;
        this.demoMode = demoMode;
    }

    // ==================== 查询 ====================

    /**
     * 商店面板：按四个 Tab 列出商品 + 当前持有量 + 单价 + 能否买得起。
     *
     * @param userId 用户
     * @param kind   类别过滤（SEED / TOOL / FERT / PEST / HERB / WATER，null = 全部）
     * @return 面板数据
     */
    @Transactional
    public Map<String, Object> panel(Long userId, String kind) {
        FarmWallet wallet = ensureWallet(userId);
        // 首次进入给初始备货（否则农资 Tab 空着像坏了）
        stockService.ensureInitialStock(userId);

        Map<String, Long> owned = ownedMap(userId);

        List<Map<String, Object>> items = new ArrayList<>();
        double totalAssets = 0;
        for (InputCatalog.Item it : InputCatalog.all().values()) {
            if (kind != null && !kind.isBlank() && !kind.equalsIgnoreCase(it.kind().name())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.id());
            m.put("name", it.name());
            m.put("kind", it.kind().name());
            m.put("category", it.category());
            m.put("unit", it.unit());
            // 🔴 2026-10-28：种子库存单位是「亩」不是「包」，前端「已有 N」要跟着改。
            m.put("stockUnit", it.stockUnit());
            m.put("price", it.pricePerUnit());
            m.put("note", it.note());

            long have = owned.getOrDefault(it.id(), 0L);
            m.put("owned", have);
            m.put("durable", it.durable());

            // 语义化补充字段：前端不需要自己换算
            if (it.kind() == InputCatalog.Kind.SEED) {
                String cropId = it.cropId();
                m.put("cropId", cropId);
                CropCatalog.Crop c = cropId == null ? null : CropCatalog.get(cropId);
                m.put("cropName", c == null ? "" : c.name());
                m.put("cropCategory", c == null ? "" : c.category());
                // 一包能播多少亩。
                // 🔴 2026-10-23 修复：这里原来写成 `protectDays() / 1000.0`，
                // 但 InputCatalog 里种子的 protectDays 传的就是**亩数本身**（5 / 6 / 4…），
                // 不是「亩数×1000」。除以 1000 后前端显示「可播 0.005 亩」——
                // 少了三个数量级，玩家完全看不懂这个数字。
                // 注释里写的「×1000」是当初设计时的意图，但落地数据没跟着改，
                // 结果两处口径对不上。统一按「数据即亩数」处理，注释同步更正。
                m.put("coversMu", it.protectDays());
                // 🔴 2026-10-28：库存单位改成「亩」后，doses = 已有的亩数。
                // 之前 doses 写的是 protectDays（= 每包可播亩数），语义是「一包能播多少」，
                // 但前端 FarmShop.vue 显示的却是 `it.owned`（已有多少）——
                // 两个数字含义不同却挨着显示，玩家会以为「已有 5 亩」其实是「一包 5 亩」。
                // 现在统一：doses = 已有的亩数，coversMu = 每包可播亩数。
                m.put("doses", have);
            } else if (it.kind() == InputCatalog.Kind.WATER) {
                // 🔴 库存存的是「次」（不是份），所以 doses = 已有的灌溉次数。
                // packSize = 一份含多少次（protectDays），前端据此显示「1 份 = 20 次」。
                m.put("doses", have);
                m.put("packSize", it.protectDays());
                m.put("zones", it.targets());
            } else if (it.kind() == InputCatalog.Kind.TOOL) {
                // 效率加成系数
                m.put("efficiency", it.efficacy());
                m.put("applies", it.targets());
            } else {
                // 农资：还能施几遍
                double per = it.doseOpt();
                m.put("doses", per <= 0 ? 0 : Math.floor(have * it.doseOpt() / per) / 10.0);
                m.put("doseOpt", it.doseOpt());
                m.put("doseMin", it.doseMin());
                m.put("doseMax", it.doseMax());
            }

            // 能否买得起 + 已购买（耐用品）
            m.put("affordable", wallet.getBalance() >= it.pricePerUnit());
            m.put("alreadyOwned", it.durable() && have > 0);

            double value = have * it.pricePerUnit();
            totalAssets += value;
            items.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("balance", round(wallet.getBalance(), 2));
        out.put("totalIncome", round(wallet.getTotalIncome(), 2));
        out.put("totalExpense", round(wallet.getTotalExpense(), 2));
        out.put("assetValue", round(totalAssets, 2));
        // 世界日期（前端头部显示）——与仓库面板同源
        // 🔴 2026-10-26：改走用户级时钟，不再从地块反推
        out.put("worldDate", clock.today(userId).toString());
        // 分类计数（前端 Tab 上的角标）
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (InputCatalog.Kind k : InputCatalog.Kind.values()) {
            counts.put(k.name(), 0);
        }
        for (Map<String, Object> it : items) {
            String k = String.valueOf(it.get("kind"));
            counts.merge(k, 1, Integer::sum);
        }
        out.put("counts", counts);
        return out;
    }

    // ==================== 购买 ====================

    /**
         * 买货：扣钱 + 入库。
     *
     * <p>🔴 2026-10-28：这是<b>购买农资的唯一入口</b>。
     *
     * <p>原先仓库面板的补货按钮走的是另一个接口
     * （{@code POST /warehouse/stocks/buy}），它直接调
     * {@code FarmStockService.buy} —— <b>完全不扣钱包</b>，
     * 玩家可以无限量白拿化肥/农药/种子。这直接违反
     * 「买入扣钱、施用只扣库存」的资金流口径。
     *
     * <p>现在两个入口都收敛到这里：扣钱、校验「已拥有」、换算单位
     * 全部只有一份实现，堵住后门的同时也消灭了「同一件商品两个单位语义」的分叉。
     *
     * @param userId 用户
     * @param itemId 商品 id
     * @param qty   数量（<b>售卖单位</b>：水=份、种子=包、化肥=kg、农药=g/ml、农具=台）
     * @param farmId 归属农场（可空 = 全局库存）。仓库补货按农场入库时传。
     * @return 购买结果
     */
    @Transactional
    public Map<String, Object> buy(Long userId, String itemId, double qty) {
        return buy(userId, itemId, qty, null);
    }

    @Transactional
    public Map<String, Object> buy(Long userId, String itemId, double qty, Long farmId) {
        InputCatalog.Item it = InputCatalog.get(itemId);
        if (it == null) {
            throw new BusinessException("商店没有这件商品：" + itemId);
        }
        if (qty <= 0) {
            throw new BusinessException("购买数量必须大于 0");
        }
        if (it.durable() && qty > 1) {
            throw new BusinessException(it.name() + " 是耐用品，一次只能买 1 台");
        }

        FarmWallet wallet = ensureWallet(userId);
        double cost = round(qty * it.pricePerUnit(), 2);

        // 🔴 耐用品：已经买过就不让重复买（钱不是大风刮来的）。
        //
        // ⚠️ **这个检查必须在余额检查之前**（2026-10-23 实测踩到）：
        // 顺序反了的话，玩家买第二台时看到的提示是「余额不足」，
        // 而真正的原因是「你已经有一台了」——提示会把他引向「去赚钱」，
        // 但他真正需要知道的是「这东西不用再买了」。两句话指向完全不同的下一步。
        if (it.durable()) {
            long have = ownedMap(userId).getOrDefault(it.id(), 0L);
            if (have > 0) {
                throw new BusinessException(it.name() + " 你已经有一台了，不需要重复买");
            }
        }

        if (wallet.getBalance() + 1e-6 < cost) {
            throw new BusinessException(String.format(
                    "余额不足：%s 需要 ¥%.2f，当前只有 ¥%.2f（差 ¥%.2f）",
                    it.name(), cost, wallet.getBalance(), cost - wallet.getBalance()));
        }

        // 扣钱
        wallet.setBalance(round(wallet.getBalance() - cost, 2));
        wallet.setTotalExpense(round(wallet.getTotalExpense() + cost, 2));
        walletRepo.save(wallet);

        // 入库。
        // 🔴 WATER 的单位换算：**买 1 份 = protectDays 次灌溉**，
        // 库存统一存「次」，灌一次扣 1（详见 InputCatalog 里 WATER 的注释）。
        // 🔴 2026-10-28 SEED 同样要换算：**买 1 包 = protectDays 亩**，
        // 库存统一存「亩」，开一块地扣 1 亩。
        // 其它商品：农具固定 1，其余按 qty。
        double addQty;
        if (it.durable()) {
            addQty = 1;
        } else if (it.kind() == InputCatalog.Kind.WATER || it.kind() == InputCatalog.Kind.SEED) {
            addQty = qty * it.protectDays();
        } else {
            addQty = qty;
        }
        FarmStock row = stockService.buy(userId, it.id(), addQty, farmId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("itemId", it.id());
        out.put("name", it.name());
        out.put("qty", addQty);
        out.put("packs", qty);
        out.put("unit", it.unit());
        out.put("cost", cost);
        out.put("balance", round(wallet.getBalance(), 2));
        out.put("stockId", row == null ? null : row.getId());
        out.put("note", it.durable()
                ? it.name() + " 已入手，长期有效"
                : (it.kind() == InputCatalog.Kind.WATER
                    ? "已购入 " + it.name() + " × " + trim(qty) + " 份（" + trim(addQty) + " 次灌溉额度）"
                    : "已购入 " + it.name() + " × " + trim(addQty) + it.unit()));
        return out;
    }

    // ==================== 资金 ====================

    /**
     * 取钱包（没有就建一个，带初始启动金）。
     */
    @Transactional
    public FarmWallet ensureWallet(Long userId) {
        return walletRepo.findByUserId(userId).orElseGet(() -> {
            FarmWallet w = new FarmWallet();
            w.setUserId(userId);
            w.setBalance(FarmWallet.INITIAL_BALANCE);
            return walletRepo.save(w);
        });
    }

    /**
     * 卖货进账（仓库变卖时调用）。
     *
     * @param userId 用户
     * @param amount 收入金额
     */
    @Transactional
    public void earn(Long userId, double amount) {
        if (amount <= 0) {
            return;
        }
        FarmWallet wallet = ensureWallet(userId);
        wallet.setBalance(round(wallet.getBalance() + amount, 2));
        wallet.setTotalIncome(round(wallet.getTotalIncome() + amount, 2));
        walletRepo.save(wallet);
    }

    /**
     * 🔴 2026-10-27：{@code spend()} 已删除。
     *
     * <p><b>为什么删</b>：它原本的注释写着「花钱（施用农资、灌溉等消耗）」，
     * 但<b>全仓零调用点</b> —— 真正的扣款发生在 {@link #buy(Long, String, double)}
     * 里（种子/农具/农资/水，一次性扣清）。也就是说：
     * <ul>
     *   <li>它的注释描述的是一个<b>不存在的资金流</b>（最典型的「注释与实现脱节」）；</li>
     *   <li>2026-10-26 有人把它接上后，<b>同一批货被扣了两遍钱</b>
     *       （商店买时扣一次 + 施用时再扣一次）；</li>
     *   <li>现在口径统一为「<b>买入扣钱，施用只扣库存</b>」，
     *       这个方法彻底没有存在意义，留着只会诱导下一个人再接一次。</li>
     * </ul>
     *
     * <p>资金流唯一出口：{@link #buy()} 扣钱，{@link #earn()} 进账。
     * 施用/灌溉<b>不碰钱包</b>，只扣库存。
     */
    /** 当前余额（无钱包时返回初始值，不建库）。 */
    @Transactional(readOnly = true)
    public double balanceOf(Long userId) {
        return walletRepo.findByUserId(userId)
                .map(FarmWallet::getBalance)
                .orElse(FarmWallet.INITIAL_BALANCE);
    }

    // ==================== 内部 ====================

    /** 用户已持有的各商品数量（tool 数量为 0/1）。 */
    private Map<String, Long> ownedMap(Long userId) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (FarmStock s : stockRepo.findByUserIdOrderByQuantityDescIdAsc(userId)) {
            long q = s.getQuantity() == null ? 0L : Math.round(s.getQuantity());
            if (q > 0) {
                m.merge(s.getItemId(), q, Long::sum);
            }
        }
        return m;
    }

    /** 用户已拥有的农具 id 集合（用于引擎加成查询）。 */
    @Transactional(readOnly = true)
    public Set<String> ownedToolIds(Long userId) {
        Set<String> ids = new LinkedHashSet<>();
        for (FarmStock s : stockRepo.findByUserIdOrderByQuantityDescIdAsc(userId)) {
            if (s.getQuantity() == null || s.getQuantity() <= 0.0001) {
                continue;
            }
            InputCatalog.Item it = InputCatalog.get(s.getItemId());
            if (it != null && it.kind() == InputCatalog.Kind.TOOL) {
                ids.add(it.id());
            }
        }
        return ids;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static String trim(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(round(v, 1));
    }
}
