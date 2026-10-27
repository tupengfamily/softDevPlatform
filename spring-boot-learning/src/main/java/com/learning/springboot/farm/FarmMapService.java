package com.learning.springboot.farm;

import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.farm.engine.ClimateModel;
import com.learning.springboot.farm.engine.DayWeather;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农场（地图选点）与三级看板的业务层。
 *
 * <p><b>层级</b>：区县 → 农场 → 地块 → 作物。
 * 区县是地图上的最小可选单位，决定农业气候区；农场是玩家在区县里的一个落脚点；
 * 地块是真正种东西的单位，同一农场内每块地只能种一种作物。
 *
 * <p><b>数据来源分工</b>：本类只管「谁在哪种了什么」；
 * 「这个县适合种什么」由 {@link DistrictCropService} 从气候模型推导；
 * 「现在长到哪一步」复用 {@link FarmPlayService#plotSummaries}，不另立口径。
 */
@Service
public class FarmMapService {

    /** 单个用户的农场数量上限（区县数上限远大于此，仅防刷）。 */
    public static final int MAX_FARMS = 20;

    private final FarmSteadRepository steadRepo;
    private final FarmPlotRepository plotRepo;
    private final DistrictIndex districts;
    private final DistrictCropService districtCrops;
    private final FarmPlayService play;
    private final FarmWeather weather;
    /**
     * 用户级世界时钟（2026-10-26）：地图「今天」的唯一来源。
     */
    private final FarmUserClockService clock;
    /** 演示模式开关（决定「今天」是世界日期还是现实日期） */
    private final boolean demoMode;

    public FarmMapService(FarmSteadRepository steadRepo,
                          FarmPlotRepository plotRepo,
                          DistrictIndex districts,
                          DistrictCropService districtCrops,
                          FarmPlayService play,
                          FarmWeather weather,
                          FarmUserClockService clock,
                          @org.springframework.beans.factory.annotation.Value("${farm.demo-mode:true}")
                          boolean demoMode) {
        this.steadRepo = steadRepo;
        this.plotRepo = plotRepo;
        this.districts = districts;
        this.districtCrops = districtCrops;
        this.play = play;
        this.weather = weather;
        this.clock = clock;
        this.demoMode = demoMode;
    }

    // ==================================================================
    //  地图
    // ==================================================================

    /** 全国省级聚合：每省我的农场数，用于地图着色与图例。 */
    @Transactional(readOnly = true)
    public Map<String, Object> provinceMap(Long userId) {
        Map<Integer, Integer> countByProv = new LinkedHashMap<>();
        for (FarmStead s : steadRepo.findByUserIdOrderByIdAsc(userId)) {
            int p = s.getAdcode() / 10000 * 10000;
            countByProv.merge(p, 1, Integer::sum);
        }
        List<Map<String, Object>> provinces = new ArrayList<>();
        for (Map.Entry<Integer, List<DistrictIndex.District>> e : districts.all().entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("adcode", e.getKey());
            m.put("name", e.getValue().isEmpty() ? "" : e.getValue().get(0).province());
            m.put("districtCount", e.getValue().size());
            m.put("farmCount", countByProv.getOrDefault(e.getKey(), 0));
            provinces.add(m);
        }
        provinces.sort(Comparator.comparingInt(a -> (int) a.get("adcode")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provinces", provinces);
        out.put("districtTotal", districts.size());
        out.put("farmTotal", countByProv.values().stream().mapToInt(Integer::intValue).sum());
        out.put("notice", "区县边界来自阿里 DataV GeoAtlas（简化后离线加载）；"
                + "主栽作物为气候模型推算，非统计年鉴");
        return out;
    }

    /**
     * 某省（或某市）下辖的全部区县：主栽作物 + 我的农场标记。
     *
     * <p>{@code adcode} 同时接受省级（{@code 370000}）和地级市（{@code 370100}）编码，
     * 按位数自动判断——前端在「省 → 市 → 区县」下钻时两种都要查。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> districtMap(Long userId, int adcode) {
        Map<Integer, FarmStead> mine = new LinkedHashMap<>();
        for (FarmStead s : steadRepo.findByUserIdOrderByIdAsc(userId)) {
            mine.put(s.getAdcode(), s);
        }
        // 🔴 2026-10-23：改用**世界日期**。原来用 LocalDate.now()（现实日期），
        // 演示模式下世界日期已推进（实测 2027-11-21），会导致地图上区县作物
        // 的「当前物候」按 2026-10-23 算，与玩家自己的地块差了整整一年。
        LocalDate today = userWorldDate(userId);
        List<DistrictIndex.District> list = isCityAdcode(adcode)
                ? districts.ofCity(adcode)
                : districts.ofProvince(adcode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DistrictIndex.District d : list) {
            Map<String, Object> m = districtCrops.brief(d, today);
            FarmStead s = mine.get(d.adcode());
            m.put("farmId", s == null ? null : s.getId());
            m.put("farmName", s == null ? null : s.getName());
            out.add(m);
        }
        return out;
    }

    /**
     * 判断一个 adcode 是地级市还是省级单位。
     *
     * <p>规则：末两位为 00 的是省级（110000/370000），否则 6 位码就是地级市
     * （370100）。直辖市没有地级市层，按省级处理。
     */
    private boolean isCityAdcode(int adcode) {
        if (adcode <= 0 || adcode % 100 != 0) {
            return false;
        }
        // 省级：末四位为 00（110000 / 370000 / 810000）
        return adcode % 10000 != 0;
    }

    /** 单区县详情：气候、四作物评级与当前物候、我的农场。 */
    @Transactional(readOnly = true)
    public Map<String, Object> districtDetail(Long userId, int adcode) {
        // 🔴 2026-10-23：同 districtMap —— 用世界日期，不用现实日期。
        Map<String, Object> m = districtCrops.detail(adcode, userWorldDate(userId));
        if (m == null) {
            throw new BusinessException("未找到该区县：" + adcode);
        }
        FarmStead s = steadRepo.findByUserIdAndAdcode(userId, adcode).orElse(null);
        if (s != null) {
            m.put("farmId", s.getId());
            m.put("farmName", s.getName());
            m.put("myPlots", plotsOfFarm(userId, s.getId()));
        }
        return m;
    }

    /** 区县搜索（名称/全名模糊）。 */
    public List<Map<String, Object>> searchDistricts(String keyword) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DistrictIndex.District d : districts.search(keyword, 20)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("adcode", d.adcode());
            m.put("name", d.name());
            m.put("fullName", d.fullName());
            m.put("province", d.province());
            m.put("city", d.city());
            m.put("lng", d.lng());
            m.put("lat", d.lat());
            out.add(m);
        }
        return out;
    }

    // ==================================================================
    //  农场
    // ==================================================================

    @Transactional(readOnly = true)
    public List<FarmStead> list(Long userId) {
        return steadRepo.findByUserIdOrderByIdAsc(userId);
    }

    /**
     * 在地图上某区县建农场。
     *
     * <p>幂等：同一区县重复点击返回已有农场，而不是报错——玩家双击地图很常见，
     * 弹「已存在」既没用又打断操作。
     */
    @Transactional
    public Map<String, Object> create(Long userId, int adcode, String name) {
        return create(userId, adcode, name, null);
    }

    /**
     * 在地图上选点建农场（可指定开垦日期）。
     *
     * <p><b>开垦日期不能早于今天</b>（2026-10-22 需求）：农场是在「某个时候」
     * 开垦的，往前填日期会让农时起点凭空提前，还可能与已有地块的季次冲突。
     *
     * <p>基准是<b>用户级世界日期</b>（{@link FarmUserClockService}），不是 {@code LocalDate.now()}——
     * 演示模式下世界日期被推进过，用现实日期会把玩家已推进的那一季判成非法。
     *
     * <p>🔴 2026-10-26：原来这里写的是「用户级最靠后的地块日期」——
     * 那正是「plot27 偏移污染全用户」的根因，已改为只读用户级时钟。
     */
    @Transactional
    public Map<String, Object> create(Long userId, int adcode, String name, LocalDate openedOn) {
        DistrictIndex.District d = districts.get(adcode)
                .orElseThrow(() -> new BusinessException("未知区县代码：" + adcode));
        FarmStead exist = steadRepo.findByUserIdAndAdcode(userId, adcode).orElse(null);
        if (exist != null) {
            Map<String, Object> m = farmView(userId, exist, false);
            m.put("created", false);
            return m;
        }
        if (steadRepo.countByUserId(userId) >= MAX_FARMS) {
            throw new BusinessException("农场数量已达上限（" + MAX_FARMS + " 个）");
        }
        // 开垦日期校验：不得早于世界日期
        // 🔴 2026-10-26：改走用户级时钟，不再从地块反推
        LocalDate minDate = clock.today(userId);
        LocalDate opened = openedOn == null ? minDate : openedOn;
        if (opened.isBefore(minDate)) {
            throw new BusinessException("开垦日期不能早于今天（" + minDate + "）");
        }
        String zoneId = districtCrops.zoneOf(d);
        FarmStead s = new FarmStead();
        s.setUserId(userId);
        s.setAdcode(adcode);
        s.setName(name == null || name.isBlank() ? d.name() + "农场" : name.trim());
        s.setDistrictName(d.name());
        s.setCityName(d.city());
        s.setProvinceName(d.province());
        s.setLng(d.lng());
        s.setLat(d.lat());
        s.setZoneId(zoneId);
        s.setOpenedOn(opened);
        steadRepo.save(s);

        Map<String, Object> m = farmView(userId, s, true);
        m.put("created", true);
        return m;
    }

    /** 改名 / 改备注。 */
    @Transactional
    public Map<String, Object> rename(Long userId, Long farmId, String name) {
        FarmStead s = steadRepo.findByIdAndUserId(farmId, userId)
                .orElseThrow(() -> new BusinessException("农场不存在"));
        if (name != null && !name.isBlank()) {
            s.setName(name.trim());
            steadRepo.save(s);
        }
        return farmView(userId, s, false);
    }

    /** 删除农场：地里还有作物时拒绝，避免误删几十季的记录。 */
    @Transactional
    public Map<String, Object> delete(Long userId, Long farmId) {
        FarmStead s = steadRepo.findByIdAndUserId(farmId, userId)
                .orElseThrow(() -> new BusinessException("农场不存在"));
        long n = plotRepo.countByUserIdAndFarmId(userId, farmId);
        if (n > 0) {
            throw new BusinessException("农场下还有 " + n + " 块地，请先收割并清空后再删除");
        }
        steadRepo.delete(s);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("farmId", farmId);
        return m;
    }

    // ==================================================================
    //  看板：农场 → 地块 → 作物
    // ==================================================================

    /**
     * 统一看板。
     *
     * <p>只调用一次 {@code plotSummaries}（每块地跑一次 SimEngine 拿 DVS），
     * 再按 farmId 分组，避免逐农场重复回放——100 块地也就几十毫秒。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> board(Long userId) {
        List<Map<String, Object>> plots = play.plotSummaries(userId);
        Map<Long, List<Map<String, Object>>> byFarm = new LinkedHashMap<>();
        List<Map<String, Object>> unassigned = new ArrayList<>();
        int growing = 0;
        int ready = 0;
        for (Map<String, Object> p : plots) {
            Long fid = (Long) p.get("farmId");
            if (fid == null) {
                unassigned.add(p);
            } else {
                byFarm.computeIfAbsent(fid, k -> new ArrayList<>()).add(p);
            }
            if (Boolean.TRUE.equals(p.get("chosen"))) {
                double dvs = ((Number) p.get("dvs")).doubleValue();
                // 🔴 2026-10-27：这里原来写死 `dvs >= 0.92` —— 那是水稻口径，
                // 于是番茄（阈值 0.82）收不了、玉米（阈值 0.95）提前算「可收」。
                // 「能不能收」的唯一真源是 OpChain.harvestDvsOf()，
                // 由 plotSummaries() 直接下发结论，这里只消费，不再自己判。
                if (Boolean.TRUE.equals(p.get("harvestable"))) {
                    ready++;
                } else if (dvs > 0) {
                    growing++;
                }
            }
        }

        List<Map<String, Object>> farms = new ArrayList<>();
        for (FarmStead s : steadRepo.findByUserIdOrderByIdAsc(userId)) {
            Map<String, Object> m = farmView(userId, s, false);
            List<Map<String, Object>> ps = byFarm.getOrDefault(s.getId(), new ArrayList<>());
            m.put("plots", ps);
            m.put("plotCount", ps.size());
            m.put("chosenCount", ps.stream().filter(x -> Boolean.TRUE.equals(x.get("chosen"))).count());
            // 🔴 2026-10-27：这里原来也写死 `dvs >= 0.92`（与上面 board() 里的
            // ready/growing 统计是同一语义的两份实现）。同样改为消费后端结论。
            m.put("readyCount", ps.stream()
                    .filter(x -> Boolean.TRUE.equals(x.get("chosen"))
                            && Boolean.TRUE.equals(x.get("harvestable")))
                    .count());
            m.put("crops", distinctCrops(ps));
            farms.add(m);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("farmCount", farms.size());
        summary.put("plotCount", plots.size());
        summary.put("growingCount", growing);
        summary.put("readyCount", ready);
        summary.put("unassignedCount", unassigned.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("farms", farms);
        out.put("unassigned", unassigned);
        out.put("summary", summary);
        // 🔴 S25：这里必须给**世界日期**而不是 LocalDate.now()——
        // 演示模式下玩家已推进过时间轴，真实「今天」可能已经是 2027 年。
        // 前端建农场/种庄稼的日期选择器要用它来禁用「今天之前」的日期。
        // 🔴 2026-10-26：改走用户级时钟（唯一来源），不再从地块反推。
        out.put("today", clock.today(userId).toString());
        return out;
    }

    /** 单个农场的详情（地图点击、看板展开都用它）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long userId, Long farmId) {
        FarmStead s = steadRepo.findByIdAndUserId(farmId, userId)
                .orElseThrow(() -> new BusinessException("农场不存在"));
        Map<String, Object> m = farmView(userId, s, true);
        // 🔴 2026-10-23：同 districtMap —— 用世界日期，不用现实日期。
        Map<String, Object> d = districtCrops.detail(s.getAdcode(), userWorldDate(userId));
        if (d != null) {
            m.put("district", d);
        }
        m.put("plots", plotsOfFarm(userId, farmId));
        return m;
    }

    // ==================================================================
    //  内部
    // ==================================================================

    private List<Map<String, Object>> plotsOfFarm(Long userId, Long farmId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : play.plotSummaries(userId)) {
            if (farmId.equals(p.get("farmId"))) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<String> distinctCrops(List<Map<String, Object>> ps) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> p : ps) {
            Object n = p.get("cropName");
            if (n != null && !names.contains(String.valueOf(n))) {
                names.add(String.valueOf(n));
            }
        }
        return names;
    }

    private Map<String, Object> farmView(Long userId, FarmStead s, boolean withPlots) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("farmId", s.getId());
        m.put("name", s.getName());
        m.put("adcode", s.getAdcode());
        m.put("districtName", s.getDistrictName());
        m.put("cityName", s.getCityName());
        m.put("provinceName", s.getProvinceName());
        m.put("location", s.getLocationText());
        m.put("lng", s.getLng());
        m.put("lat", s.getLat());
        m.put("zoneId", s.getZoneId());
        m.put("zoneName", ClimateModel.zone(s.getZoneId()).name());
        m.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
        // 开垦日期（S25）：老农场没有这个值 → 退化为世界日期（前端显示「今天」）
        m.put("openedOn", s.getOpenedOn() == null
                ? farmWorldDate(userId, s.getId()).toString()
                : s.getOpenedOn().toString());
        m.put("plotCount", plotRepo.countByUserIdAndFarmId(userId, s.getId()));
        // 当前天气（2026-10-22 需求 O）：地图悬浮面板要显示「这个农场今天什么天气」。
        // 天气按农场所在农业区取，与地块用的是同一份数据源（FarmWeather）。
        // 日期用农场世界日期（演示模式下可能已推进），与地块状态保持同一天。
        m.put("weather", weatherOf(userId, s.getZoneId(), farmWorldDate(userId, s.getId())));
        if (withPlots) {
            m.put("plots", plotsOfFarm(userId, s.getId()));
        }
        return m;
    }

    /**
     * 农场所在区域的当前天气（需求 O）。
     *
     * <p><b>口径纪律</b>：这里只取<b>农场世界当天</b>的天气值，不做任何预测、不做外推。
     * 天气来源与地块完全一致（同一个 {@link FarmWeather}），只是不逐地块各跑一遍——
     * 同一农业区的农场共用一份天气，避免看板 N 个农场就回放 N 次。
     *
     * <p>🔴 <b>2026-10-22 修正</b>：这里原来直接用 {@code LocalDate.now()}，
     * 但演示模式下每块地可以有自己的演示偏移（{@code demo_offset_days}），
     * 地块的世界日期可能已经是 12 月，悬浮面板却还在显示 10 月 22 日的天气——
     * 玩家看到「27℃/17℃ 无降水」旁边的地块却显示「返青抽叶、DVS 0.42」，
     * 两套日期对不上，整个面板就不可信了。
     * 现在按农场下<b>最靠后的有效世界日期</b>取天气，保证面板里的天气与地块同一天。
     *
     * <p>异常不让整个看板 500：取不到就返回 {@code null}，前端不显示天气区。
     */
    private Map<String, Object> weatherOf(Long userId, String zoneId, LocalDate worldDate) {
        try {
            LocalDate date = worldDate == null ? LocalDate.now() : worldDate;
            DayWeather w = weather.forDate(userId, zoneId, date);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", date.toString());
            m.put("tmax", w.tmax());
            m.put("tmin", w.tmin());
            m.put("precip", w.precip());
            m.put("source", weather.sourceLabel(userId, zoneId));
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 农场的世界日期 = <b>用户级</b>世界日期。
     *
     * <p>🔴 2026-10-26：原先是「该农场下所有地块里最靠后的那个有效日期」。
     * 那个口径有两个问题：
     * <ol>
     *   <li><b>口径分裂</b>：同一个农场，地图面板显示的日期可能和仓库老化基准
     *       （用户级）不是同一天 → 玩家看到「仓库里刚收的菜已经开始变质」；
     *   <li><b>污染扩散</b>：任何一块无关地块的残留偏移会把整个农场面板的日期带偏。</li>
     * </ol>
     *
     * <p>现在两级时钟已分离（用户级世界日期 vs 地块级生长时钟），
     * 农场面板显示的「今天」是<b>用户级</b>语义 → 直接取用户级时钟。
     * 地块各自的生长进度仍在 {@code FarmPlayService.growthToday} 里按地块算。
     */
    private LocalDate farmWorldDate(Long userId, Long farmId) {
        return clock.today(userId);
    }

    /**
     * <b>用户级</b>世界日期。
     *
     * <p>🔴 <b>2026-10-23 新增</b>，<b>2026-10-26 改口径</b>。
     *
     * <p>用途：区县地图的作物概览（{@code districtMap} / {@code districtDetail}）
     * 与农场详情里的区县信息，都是<b>账号级</b>的视图，不属于某个具体农场，
     * 所以要用用户级日期，否则地图上的物候会和玩家自己的地块对不上。
     *
     * <p>🔴 原实现是「该用户所有地块里最靠后的那个日期」——
     * 正是 2026-10-25「plot27 残留偏移污染全用户」的根因。
     * 现在改读 {@link FarmUserClockService}，与 {@code FarmPlayService#userToday(Long)}
     * 完全同源，避免两处算出两个「今天」。
     */
    private LocalDate userWorldDate(Long userId) {
        return clock.today(userId);
    }
}
