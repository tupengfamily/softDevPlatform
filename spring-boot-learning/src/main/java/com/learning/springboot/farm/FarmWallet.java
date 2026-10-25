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
 * 启动金按「够买 3 亩地的全套投入 + 一点余量」估算。
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
     * <p>按「3 亩地一季的最低投入」估算：
     * <pre>
     *   种子    3 亩 × 约 30 元/亩     ≈ 90
     *   复合肥  3 亩 × 30kg × 3.2 元  ≈ 288
     *   灌溉水  3 亩 × 20 次 × 6 元   ≈ 360
     *   ------------------------------------------
     *   合计                              ≈ 738 → 取 800 元留一点余量
     * </pre>
     * 数字与 {@code InputCatalog} 里的价格联动，改价时要一起调。
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
