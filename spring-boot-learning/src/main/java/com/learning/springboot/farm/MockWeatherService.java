package com.learning.springboot.farm;

import com.learning.springboot.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用模拟天气服务（S2，从 {@code FarmPlayService} 抽出）。
 *
 * <p><b>为什么单独抽</b>：原���这三段逻辑塞在 {@code FarmPlayService}（3000+ 行）里，
 * 但它的依赖只有「mock 仓储 + 天气数据源 + 演示开关」三项，与玩法主流程毫无关系。
 * 独立出来有三个好处：
 * <ol>
 *   <li>{@code FarmPlayService} 的构造器依赖从 10 个降到 9 个；</li>
 *   <li>演示/测试工具与正式玩法**物理隔离** —— 将来关掉演示模式时，删掉这个类即可，
 *       不用在 3000 行里翻找哪几行是「测试专用」的；</li>
 *   <li>这段逻辑有独立的安全边界（日期区间上限、参数范围校验），
 *       独立成类后可以被单独审查。</li>
 * </ol>
 *
 * <p>🔴 <b>演示模式闸门</b>：{@code farm.demo-mode=false}（正式部署必须）时，
 * {@link #applyMock} 与 {@link #resetMocks} 直接拒绝执行。
 * 这是「正式部署不得注入假天气」的最后一道锁。
 *
 * <p><b>为什么不用 Spring 事件解耦</b>：这段逻辑需要与 {@code FarmPlayService}
 * 的回放缓存失效（{@code invalidateSim}）联动。用事件的话要额外定义事件类 + 监听器，
 * 但当前只有一个消费方，事件带来的间接性大于收益 —— <b>直接依赖更清晰</b>。
 * 将来若出现第二个消费方，再改成事件不迟。
 *
 * @see FarmPlayService
 */
@Service
public class MockWeatherService {

    private static final Logger log = LoggerFactory.getLogger(MockWeatherService.class);

    /** 单次注入的最大天数（防止大面积污染回放）。 */
    private static final int MAX_INJECT_DAYS = 90;

    /** 单日降水上限（mm/天）。 */
    private static final double MAX_PRECIP = 500;

    private final FarmWeatherMockRepository mockRepo;
    private final FarmWeather weather;
    /**
     * 事件发布器：mock 变更后广播，让读侧（{@code FarmPlayService}）丢弃回放缓存。
     *
     * <p>🔴 <b>顺序很重要</b>：必须<b>先改数据、后发事件</b>。
     * 事件是同步派发的，监听方收到事件后会立刻重新回放；
     * 如果先发事件再改数据，监听方读到的还是旧天气。
     */
    private final ApplicationEventPublisher eventPublisher;

    @Value("${farm.demo-mode:true}")
    private boolean demoMode;

    public MockWeatherService(FarmWeatherMockRepository mockRepo,
                               FarmWeather weather,
                               ApplicationEventPublisher eventPublisher) {
        this.mockRepo = mockRepo;
        this.weather = weather;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 启动时把 DB 里的 mock 灌进内存层（S2 从 {@code FarmPlayService} 迁过来的行为）。
     *
     * <p>🔴 <b>不能漏</b>：{@code FarmWeather} 的 mock 覆盖层是纯内存的（进程内 Map），
     * 真正的数据在 {@code t_farm_weather_mock} 表。启动时不灌一次，
     * 重启后玩家注入的模拟天气就全丢了，回放会退回真实天气 —— 表现为「昨天注入的高温今天没了」。
     *
     * <p>注：{@code ApplicationReadyEvent} 之后的所有 mock 写入都会走
     * {@link #applyMock} / {@link #resetMocks}，内存层与 DB 始终一致，不需要再监听。
     */
    @PostConstruct
    void hydrateFromDatabase() {
        Map<Long, List<FarmWeatherMock>> byUser = new java.util.HashMap<>();
        for (FarmWeatherMock m : mockRepo.findAll()) {
            byUser.computeIfAbsent(m.getUserId(), k -> new ArrayList<>()).add(m);
        }
        byUser.forEach(weather::loadUserMocks);
        if (!byUser.isEmpty()) {
            log.info("[稼穑] 启动时载入模拟天气: {} 个用户 / {} 段", byUser.size(),
                    byUser.values().stream().mapToInt(List::size).sum());
        }
    }

    /**
     * 注入一段模拟天气。🔴 演示/测试模式专用。正式部署应让 {@code farm.demo-mode=false}。
     *
     * <p>语义：把 [fromDate, toDate] 闭区间内每一天强制使用同一组 (tmax/tmin/precip)；
     * 区间外回落到演示回放。这覆盖了"测一次连续 3 天 36℃ 高温逼熟""测一次 100mm 暴雨"
     * 两种最常见场景。多次调用会追加（不同区间可叠加）。
     */
    @Transactional
    public Map<String, Object> applyMock(Long userId, LocalDate fromDate, LocalDate toDate,
                                         double tmax, double tmin, double precip, String label) {
        requireDemoMode("演示模式未开启：正式环境不允许注入模拟天气");
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            throw new BusinessException("日期范围无效：from 必须 ≤ to");
        }
        long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (days > MAX_INJECT_DAYS) {
            throw new BusinessException("单次注入区间最多 " + MAX_INJECT_DAYS + " 天（避免大面积污染回放）");
        }
        if (tmax < tmin) {
            throw new BusinessException("tmax 必须 ≥ tmin");
        }
        if (precip < 0 || precip > MAX_PRECIP) {
            throw new BusinessException("precip 必须在 [0, " + (int) MAX_PRECIP + "] mm/天");
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
        List<FarmWeatherMock> list = new ArrayList<>(weather.mockedSegments(userId));
        list.add(m);
        weather.loadUserMocks(userId, list);

        log.info("[稼穑] 用户 {} 注入模拟天气: {} → {}  tmax={} tmin={} precip={}mm  ({})",
                userId, fromDate, toDate, tmax, tmin, precip, label);

        // 数据已落库、内存层已同步 → 广播，读侧丢弃回放缓存
        eventPublisher.publishEvent(new FarmMockWeatherChangedEvent(userId));

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
        List<Map<String, Object>> out = new ArrayList<>();
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
        requireDemoMode("演示模式未开启");
        int n = mockRepo.deleteByUserId(userId);
        weather.clearUserMocks(userId);
        log.info("[稼穑] 用户 {} 清空全部模拟天气 ({} 段)", userId, n);

        // 数据已清、内存层已同步 → 广播，读侧丢弃回放缓存
        eventPublisher.publishEvent(new FarmMockWeatherChangedEvent(userId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("removed", n);
        return out;
    }

    /** 演示模式闸门：正式部署（demo-mode=false）时拒绝一切 mock 写入。 */
    private void requireDemoMode(String message) {
        if (!demoMode) {
            throw new BusinessException(message);
        }
    }
}
