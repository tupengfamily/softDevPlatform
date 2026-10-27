package com.learning.springboot.farm.achievement;

import com.learning.springboot.farm.FarmSeason;
import com.learning.springboot.farm.FarmSeasonRepository;
import com.learning.springboot.farm.engine.CropCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成就 / 目标服务。
 *
 * <p>职责边界：<b>只做「读档案 → 算成就 → 拼 DTO」</b>，
 * 计算逻辑全部在 {@link AchievementEngine}（纯函数，可单元测试）。
 *
 * <p>🔴 <b>为什么不需要新表存成就状态</b>：
 * 成就是玩家档案的<b>纯函数</b>（同一份 {@code t_farm_season} 永远算出同样的结果），
 * 所以「成就已解锁」这件事不需要持久化 —— 重算即可。
 * 反过来说，如果做成「解锁时写一行到成就表」，就会出现
 * 「档案被删/被改，成就状态对不上」的不一致；而且新增成就类型时
 * 老玩家的历史解锁记录还要做数据迁移。
 * <p>
 * 代价是每次打开面板要重算一遍 —— 但档案量级是「几十上百季」，
 * 纯内存遍历，微秒级，完全不值得为它引入一致性负担。
 *
 * <p>🔴 <b>顺带避开了一个地基性风险</b>：活动库当前是 <b>50 表 / 497 列</b>，
 * 而 SQLite 的复合 SELECT 硬上限是 500（见 {@code SqliteMetadataFix} 的注释）。
 * 新建一张成就表要多占几列，把余量压到危险线；本方案**零新增表**，
 * 彻底绕开了这个雷。
 */
@Service
public class FarmAchievementService {

    private final FarmSeasonRepository seasonRepo;

    @Autowired
    public FarmAchievementService(FarmSeasonRepository seasonRepo) {
        this.seasonRepo = seasonRepo;
    }

    /**
     * 某玩家的成就面板数据。
     *
     * @param userId 用户 id
     * @return {@code {summary, achievements, album}} —— 概览 + 成就列表 + 作物图鉴
     */
    public Map<String, Object> panel(Long userId) {
        List<FarmSeason> seasons = seasonRepo.findByUserIdOrderBySlotAscSeasonNoAsc(userId);

        // 作物 id → 参照产量（yieldRef）。成就的「比值口径」分母。
        Map<String, Double> refs = new LinkedHashMap<>();
        for (CropCatalog.Crop c : CropCatalog.list()) {
            refs.put(c.id(), c.config().yieldRef());
        }

        AchievementEngine.Stats stats = new AchievementEngine.Stats(
                seasons, refs, CropCatalog.list().size());
        List<AchievementEngine.Achievement> achievements = AchievementEngine.evaluate(stats);
        AchievementEngine.Summary summary = AchievementEngine.summarize(achievements, stats);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summaryToMap(summary));
        out.put("achievements", achievements.stream().map(this::achievementToMap).toList());
        out.put("album", album(seasons, stats));
        return out;
    }

    private Map<String, Object> summaryToMap(AchievementEngine.Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unlockedCount", s.unlockedCount());
        m.put("totalCount", s.totalCount());
        m.put("percent", s.percent());
        m.put("cropsCollected", s.cropsCollected());
        m.put("cropsTotal", s.cropsTotal());
        m.put("bestYieldRatio", round(s.bestYieldRatio(), 3));
        m.put("totalNet", round(s.totalNet(), 2));
        m.put("longestStreak", s.longestStreak());
        m.put("harvestedCount", s.harvestedCount());
        m.put("firstSowDate", s.firstSowDate() == null ? null : s.firstSowDate().toString());
        m.put("firstHarvestDate", s.firstHarvestDate() == null ? null : s.firstHarvestDate().toString());
        return m;
    }

    private Map<String, Object> achievementToMap(AchievementEngine.Achievement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", a.code());
        m.put("category", a.category().name());
        m.put("target", a.target());
        m.put("progress", round(a.progress(), 3));
        m.put("unit", a.unit());
        m.put("nameKey", a.nameKey());
        m.put("descKey", a.descKey());
        m.put("unlocked", a.unlocked());
        m.put("percent", a.percent());
        return m;
    }

    /**
     * 作物图鉴：全部 22 种作物 + 是否已收获 + 该作物历史最好一季。
     *
     * <p>🔴 <b>「已收集」的判定只认正常收获</b>（{@code HARVESTED}）——
     * 失败/放弃的一季不算「收集到」，否则玩家种一次枯死就把图鉴点亮了，
     * 图鉴就失去了「我真的种成过」的含义。
     *
     * <p>图鉴给出<b>该作物历史最好的实收比值</b>，让玩家知道「我离这作物的极限还差多少」。
     */
    private List<Map<String, Object>> album(List<FarmSeason> seasons,
                                            AchievementEngine.Stats stats) {
        // 作物 id → 历史最好一季
        Map<String, FarmSeason> best = new LinkedHashMap<>();
        for (FarmSeason s : seasons) {
            if (s.getStatus() != FarmSeason.Status.HARVESTED || s.getCropId() == null) {
                continue;
            }
            FarmSeason cur = best.get(s.getCropId());
            if (cur == null || betterThan(s, cur)) {
                best.put(s.getCropId(), s);
            }
        }

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (CropCatalog.Crop c : CropCatalog.list()) {
            FarmSeason b = best.get(c.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cropId", c.id());
            m.put("name", c.shortName());
            m.put("category", c.category());
            m.put("collected", b != null);
            double ref = c.config().yieldRef();
            m.put("yieldRef", ref);
            if (b != null) {
                double ratio = AchievementEngine.ratioOf(b.getYieldKgPerMu(), ref);
                m.put("bestYield", b.getYieldKgPerMu());
                m.put("bestRatio", round(ratio, 3));
                m.put("harvestDate", b.getHarvestDate() == null ? null : b.getHarvestDate().toString());
            } else {
                m.put("bestYield", null);
                m.put("bestRatio", null);
                m.put("harvestDate", null);
            }
            out.add(m);
        }
        return out;
    }

    /** 档案 a 是否比档案 b「更好」（同作物比实收产量）。 */
    private static boolean betterThan(FarmSeason a, FarmSeason b) {
        double ya = a.getYieldKgPerMu() == null ? 0 : a.getYieldKgPerMu();
        double yb = b.getYieldKgPerMu() == null ? 0 : b.getYieldKgPerMu();
        return ya > yb;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
