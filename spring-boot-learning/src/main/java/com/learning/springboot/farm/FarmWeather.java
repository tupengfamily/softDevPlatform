package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.ClimateModel;
import com.learning.springboot.farm.engine.DayWeather;
import com.learning.springboot.farm.engine.WeatherSeries;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 天气数据源。
 *
 * <p>两层结构：底层是「南昌 2019 年实测，按日历日循环重放」的演示回放；上层是
 * 测试/演示用的 <b>mock 覆盖层</b>（按 userId 隔离），玩家在演示模式可注入特定
 * 日期段的高温/暴雨/寒露风等场景，用于观察作物响应。
 *
 * <p><b>🔴 演示/测试模式提示</b>：基线合规红线第 8 条要求天气表述必须与数据来源一致。
 * mock 注入后，{@link #sourceLabel(String)} 会切换为"演示天气 + 已注入 N 段模拟"，前端必须如实展示。
 *
 * <p>正式部署必须关掉注入入口（{@code farm.demo-mode=false}），并让
 * {@link #mockedSegments(Long)} 永远返回空——见 {@code FarmController#applyMock} 的闸门。
 */
@Component
public class FarmWeather {

    /** 重放基准年：选 2019（南昌九年里生育期最接近中位的年份，见 prototype 验证报告） */
    private static final int REPLAY_YEAR = 2019;

    private final Map<MonthDay, DayWeather> byMonthDay = new HashMap<>();

    /**
     * 测试用 mock 覆盖层。结构：userId -> (fromDate, toDate] -> 该段恒定的 mock 值。
     * 注意 key 是区间，不是单日——一次注入覆盖一段日期。
     */
    private final Map<Long, List<FarmWeatherMock>> mocksByUser = new ConcurrentHashMap<>();

    public FarmWeather() {
        List<DayWeather> all;
        try {
            all = WeatherSeries.loadFromClasspath("farm/weather-nanchang.json");
        } catch (Exception e) {
            throw new IllegalStateException("演示天气数据加载失败（resources/farm/weather-nanchang.json）", e);
        }
        for (DayWeather d : all) {
            LocalDate date = LocalDate.parse(d.date());
            if (date.getYear() == REPLAY_YEAR) {
                byMonthDay.put(MonthDay.from(date), d);
            }
        }
        if (byMonthDay.isEmpty()) {
            throw new IllegalStateException("演示天气数据里没有 " + REPLAY_YEAR + " 年的记录");
        }
    }

    // ==================================================================
    //  Mock 覆盖层（按 userId 隔离）
    // ==================================================================

    /** 把用户的所有 mock 段灌入内存（与 DB 同步）。 */
    public void loadUserMocks(Long userId, List<FarmWeatherMock> mocks) {
        if (mocks == null || mocks.isEmpty()) {
            mocksByUser.remove(userId);
        } else {
            mocksByUser.put(userId, new ArrayList<>(mocks));
        }
    }

    /** 清除该用户的全部 mock 段。 */
    public void clearUserMocks(Long userId) {
        mocksByUser.remove(userId);
    }

    /** 该用户当前生效的 mock 段（用于 UI 展示）。 */
    public List<FarmWeatherMock> mockedSegments(Long userId) {
        return mocksByUser.getOrDefault(userId, List.of());
    }

    /** 该用户是否有任何 mock 段生效。 */
    public boolean hasMock(Long userId) {
        List<FarmWeatherMock> list = mocksByUser.get(userId);
        return list != null && !list.isEmpty();
    }

    // ==================================================================
    //  取值
    // ==================================================================

    /**
     * 某用户某日历日的天气。优先级：mock 段覆盖 > 演示回放。
     * 2 月 29 日回落到 2 月 28 日（2019 是平年，没有 0229）。
     */
    public DayWeather forDate(Long userId, LocalDate date) {
        return forDate(userId, null, date);
    }

    /**
     * 分区域取天气。
     *
     * <p>江南沿用手上的真实观测重放（2019 南昌），其余区域走 {@link ClimateModel}
     * 的逐年推算——两者都必须在 UI 上如实标注来源，不能混为一谈。
     */
    public DayWeather forDate(Long userId, String zoneId, LocalDate date) {
        DayWeather mocked = findMock(userId, date);
        if (mocked != null) {
            return mocked;
        }
        if (zoneId == null || zoneId.isBlank() || "jiangnan".equals(zoneId)) {
            return replayForDate(date);
        }
        return ClimateModel.day(zoneId, date);
    }

    /** [from, to] 闭区间的连续逐日天气。 */
    public List<DayWeather> range(Long userId, LocalDate from, LocalDate to) {
        return range(userId, null, from, to);
    }

    public List<DayWeather> range(Long userId, String zoneId, LocalDate from, LocalDate to) {
        List<DayWeather> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(forDate(userId, zoneId, d));
        }
        return out;
    }

    /** 给前端展示的源标签。mock 注入后会标注"已注入 N 段模拟"。 */
    public String sourceLabel(Long userId) {
        return sourceLabel(userId, null);
    }

    public String sourceLabel(Long userId, String zoneId) {
        int n = mockedSegments(userId).size();
        boolean jiangnan = zoneId == null || zoneId.isBlank() || "jiangnan".equals(zoneId);
        String base = jiangnan
                ? "演示天气：" + REPLAY_YEAR + " 年南昌实测，按日历日重放（非实时，非预报）"
                : "气候推算：" + ClimateModel.zone(zoneId).name() + "逐日生成（非实测、非预报）";
        if (n == 0) {
            return base;
        }
        return base + "，已注入 " + n + " 段模拟覆盖（测试模式）";
    }

    // ==================================================================
    //  内部
    // ==================================================================

    private DayWeather findMock(Long userId, LocalDate date) {
        List<FarmWeatherMock> list = mocksByUser.get(userId);
        if (list == null) {
            return null;
        }
        for (FarmWeatherMock m : list) {
            if (!date.isBefore(m.getFromDate()) && !date.isAfter(m.getToDate())) {
                return new DayWeather(date.toString(), m.getTmax(), m.getTmin(), m.getPrecip());
            }
        }
        return null;
    }

    private DayWeather replayForDate(LocalDate date) {
        DayWeather w = byMonthDay.get(MonthDay.from(date));
        if (w == null && date.getMonthValue() == 2 && date.getDayOfMonth() == 29) {
            w = byMonthDay.get(MonthDay.of(2, 28));
        }
        if (w == null) {
            throw new IllegalStateException("演示天气缺少 " + date + " 对应的记录");
        }
        // 日期字段换成调用方要的那一天，其余（温度/降水）用重放值
        return new DayWeather(date.toString(), w.tmax(), w.tmin(), w.precip());
    }
}
