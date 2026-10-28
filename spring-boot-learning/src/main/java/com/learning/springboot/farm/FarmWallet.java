package com.learning.springboot.farm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 玩家的<b>农场资金账户</b>（2026-10-23 需求 R · 商店）。
 *
 * <p><b>为什么要有钱</b>：需求说「商店用于购买种子、农具、农资、水资源」，
 * 而这些商品本来是<b>免费无限</b>的（老逻辑里施药直接扣钱但不校验，
 * 种子更是压根不存在——种什么都行）。加商店就要有一条完整的经济闭环：
 *
 * <pre>
 *   收割卖粮 → 钱包进账 → 商店买种子/农资/农具/水 → 播种/施用/灌溉消耗
 * </pre>
 *
 * <p><b>为什么挂在用户级而不是农场级</b>：仓库是用户级的（跨农场共享存货），
 * 商店同理——玩家在 A 农场赚的钱，应该能拿去 B 农场买种子。
 * 如果按农场分账，会出现「A 农场有钱但 B 农场缺种子」这种反直觉的卡死。
 *
 * <p><b>初始资金</b>：给一笔启动金（{@link #INITIAL_BALANCE}）。
 * 没有启动金的话，新玩家种第一季就得先有种子 → 但第一季收成前没钱 → 死锁。
 * 启动金按「够买 3 亩地的全套投入 + 一点余量」估算
 * （<b>具体推导已按真实引擎复算，见 {@link #INITIAL_BALANCE} 的注释</b>）。
 *
 * <p>⚠️ 金额一律用 {@code double} 存储，与项目其它金额字段（仓库估值等）保持一致。
 * 这里是单机游戏教学项目，不涉及真实支付，不需要 BigDecimal。
 */
@Entity
@Table(name = "t_farm_wallet",
        indexes = @Index(name = "idx_farm_wallet_user", columnList = "user_id"))
public class FarmWallet {

    /**
     * 初始启动金（元）。
     *
     * <p>🔴 2026-10-28 <b>推导依据已按真实引擎复算重写</b>。原注释的口径
     * 有两处硬伤，都会把数字算大：
     * <ul>
     *   <li><b>水按「次」计价</b> —— 实际是<b>按份卖</b>：1 份 = 20 次
     *       （{@code WATER_TAP.protectDays = 20}），6 元/份。
     *       原式 {@code 3 亩 × 20 次 × 6 元 = 360} 把它当成「6 元/次」，
     *       <b>虚高了 20 倍</b>。</li>
     *   <li><b>种子按「亩」计价</b> —— 实际也是<b>按包卖</b>：1 包 = 5 亩
     *       （{@code SEED_rice_mid.protectDays = 5}），28 元/包。
     *       原式 {@code 3 亩 × 30 元/亩 = 90} 把它当成「30 元/亩」，
     *       实际 3 亩只要 1 包 = 28 元。</li>
     * </ul>
     *
     * <p><b>真实引擎复算的口径</b>（江南 · 水稻中稻 · 裸播 2019-05-05）：
     * <pre>
     *   全季成熟需要 142 天（AAT 累积到 1.0）
     *   逐日模拟干旱点 = 16 次  → 16 次灌溉 = 1 份水 = ¥6
     *
     *   种子    1 包（可播 5 亩）              = ¥28
     *   水      1 份（可灌 20 次，需 16 次）    = ¥6
     *   ----------------------------------------------
     *   1 亩一季的**现金刚需**                = ¥34
     *   3 亩（首包种子可覆盖）                 = ¥34 + ¥6 = ¥40
     * </pre>
     *
     * <p><b>为什么 800 而不是 40</b>：{@code ¥34/亩} 只是<b>现金刚需</b>，
     * 不含农药/除草剂（初始备货里已免费给了 5 亩一季的化肥 + 10 亩一季的农药）。
     * 800 元给的是<b>试错空间</b>：玩家要试不同播期、不同作物、可能绝收重来。
     * 按真实刚需算，800 元够开 <b>23.5 亩</b>（首包种子摊薄后更多），
     * 远超农场上限（12 块地/农场）——
     * <b>所以 800 元的真实作用是「保证玩家永远不会因为没钱而卡死」，
     * 而不是「刚好够种满农场」</b>。
     *
     * <p>⚠️ 数字与 {@code InputCatalog} 里的价格联动，改价时要一起调。
     * <p>⚠️ <b>备货里没有种子、没有水、没有农具</b> —— 这三项必须用现金买，
     * 这是启动金必须够的真实原因。
     */
    public static final double INITIAL_BALANCE = 800.0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 余额（元） */
    @Column(nullable = false)
    private Double balance = INITIAL_BALANCE;

    /** 累计收入（卖货进账） */
    @Column(name = "total_income", nullable = false)
    private Double totalIncome = 0.0;

    /** 累计支出（购物花钱） */
    @Column(name = "total_expense", nullable = false)
    private Double totalExpense = 0.0;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
        if (balance == null) {
            this.balance = INITIAL_BALANCE;
        }
        if (totalIncome == null) {
            this.totalIncome = 0.0;
        }
        if (totalExpense == null) {
            this.totalExpense = 0.0;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }

    public Double getBalance() { return balance; }
    public void setBalance(Double v) { this.balance = v; }

    public Double getTotalIncome() { return totalIncome; }
    public void setTotalIncome(Double v) { this.totalIncome = v; }

    public Double getTotalExpense() { return totalExpense; }
    public void setTotalExpense(Double v) { this.totalExpense = v; }

    public LocalDateTime getCreateTime() { return createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
}
