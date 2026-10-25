package com.learning.springboot.farm;

import com.learning.springboot.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link MockWeatherService} 单元测试（S2）。
 *
 * <p><b>为什么要有这个</b>：S2 把 mock 天气从 {@code FarmPlayService}（3000+ 行）
 * 抽成了独立服务。抽取本身是「移动代码」，最容易出错的地方是
 * <b>漏掉那些藏在构造器里的隐式行为</b> —— 尤其是：
 * <ol>
 *   <li>启动时从 DB 灌内存层（漏了 → 重启后 mock 全丢）；</li>
 *   <li>写完后广播事件让读侧失效缓存（漏了 → 「点了没反应」）；</li>
 *   <li>演示模式闸门（漏了 → 正式部署能注入假天气）。</li>
 * </ol>
 * 这三条各有独立测试守着。
 *
 * <p>📌 这里用<b>真实的 {@link FarmWeather} 实例</b>而不是 mock：
 * <ul>
 *   <li>mock 层是纯内存 Map（{@code loadUserMocks} / {@code mockedSegments} / {@code clearUserMocks}），
 *       用真对象能真正验证「DB ↔ 内存层同步」这个语义，而不是只验证「调用了这个方法」；</li>
 *   <li>当前 JDK 是 27，而 Mockito 5.11 + ByteBuddy 1.14 的 inline mock 尚不支持它
 *       （只影响具体类，interface 不受影响），所以 mock 具体类会直接抛异常。</li>
 * </ul>
 */
class MockWeatherServiceTest {

    private final FarmWeatherMockRepository mockRepo = mock(FarmWeatherMockRepository.class);
    /** 真实实例：mock 覆盖层就是内存 Map，真对象能真正验证同步语义。 */
    private final FarmWeather weather = new FarmWeather();
    private final org.springframework.context.ApplicationEventPublisher publisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);

    private MockWeatherService newService(boolean demoMode) {
        MockWeatherService svc = new MockWeatherService(mockRepo, weather, publisher);
        // @Value 字段注入的等价物
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "demoMode", demoMode);
        return svc;
    }

    // ==================================================================
    //  参数校验
    // ==================================================================

    @Test
    @DisplayName("日期区间非法时拒绝注入")
    void rejectsInvalidDateRange() {
        MockWeatherService svc = newService(true);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.applyMock(
                1L, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 1),
                30.0, 20.0, 0.0, "倒序"));
        assertTrue(e.getMessage().contains("日期范围无效"));
        verify(mockRepo, never()).save(any());
    }

    @Test
    @DisplayName("单次注入超过 90 天时拒绝")
    void rejectsTooLongRange() {
        MockWeatherService svc = newService(true);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.applyMock(
                1L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                30.0, 20.0, 0.0, "太久"));
        assertTrue(e.getMessage().contains("90 天"));
        verify(mockRepo, never()).save(any());
    }

    @Test
    @DisplayName("tmax < tmin 时拒绝")
    void rejectsInvertedTemperature() {
        MockWeatherService svc = newService(true);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.applyMock(
                1L, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3),
                20.0, 30.0, 0.0, "倒挂"));
        assertTrue(e.getMessage().contains("tmax 必须 ≥ tmin"));
    }

    @Test
    @DisplayName("precip 越界时拒绝（负数 / 超 500）")
    void rejectsOutOfRangePrecip() {
        MockWeatherService svc = newService(true);
        assertTrue(assertThrows(BusinessException.class, () -> svc.applyMock(
                1L, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1),
                30.0, 20.0, -1.0, "负雨")).getMessage().contains("precip"));
        assertTrue(assertThrows(BusinessException.class, () -> svc.applyMock(
                1L, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1),
                30.0, 20.0, 501.0, "暴雨超标")).getMessage().contains("precip"));
        verify(mockRepo, never()).save(any());
    }

    // ==================================================================
    //  演示模式闸门（正式部署的最后一道锁）
    // ==================================================================

    @Test
    @DisplayName("demo-mode=false 时拒绝注入与清空")
    void rejectsWhenDemoModeOff() {
        MockWeatherService svc = newService(false);
        assertTrue(assertThrows(BusinessException.class, () -> svc.applyMock(
                1L, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1),
                36.0, 26.0, 0.0, "正式环境")).getMessage().contains("演示模式未开启"));
        assertTrue(assertThrows(BusinessException.class, () -> svc.resetMocks(1L))
                .getMessage().contains("演示模式未开启"));
        verify(mockRepo, never()).save(any());
        verify(mockRepo, never()).deleteByUserId(anyLong());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    // ==================================================================
    //  写路径：落库 + 内存同步 + 广播事件
    // ==================================================================

    @Test
    @DisplayName("注入成功后：落库 → 同步内存 → 广播事件（顺序即语义）")
    void applyMockPersistsSyncsAndPublishes() {
        MockWeatherService svc = newService(true);
        whenSaveAssignsId(77L);

        Map<String, Object> out = svc.applyMock(9L,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3),
                36.0, 26.0, 5.5, "高温逼熟");

        // 返回体
        assertEquals(true, out.get("ok"));
        assertEquals(77L, out.get("id"));
        assertEquals("2026-05-01", out.get("fromDate"));
        assertEquals("2026-05-03", out.get("toDate"));
        assertEquals(36.0, out.get("tmax"));
        assertEquals(26.0, out.get("tmin"));
        assertEquals(5.5, out.get("precip"));
        assertEquals("高温逼熟", out.get("label"));

        // 落库
        ArgumentCaptor<FarmWeatherMock> captor = ArgumentCaptor.forClass(FarmWeatherMock.class);
        verify(mockRepo).save(captor.capture());
        FarmWeatherMock saved = captor.getValue();
        assertEquals(9L, saved.getUserId());
        assertEquals(LocalDate.of(2026, 5, 1), saved.getFromDate());
        assertEquals(LocalDate.of(2026, 5, 3), saved.getToDate());
        assertEquals(36.0, saved.getTmax());

        // 内存层同步：真实对象里能读回刚注入的那一段
        List<FarmWeatherMock> inMemory = weather.mockedSegments(9L);
        assertEquals(1, inMemory.size());
        assertEquals(36.0, inMemory.get(0).getTmax());
        assertEquals(LocalDate.of(2026, 5, 1), inMemory.get(0).getFromDate());

        // 广播事件（读侧靠它失效回放缓存）
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertTrue(eventCaptor.getValue() instanceof FarmMockWeatherChangedEvent);
        assertEquals(9L, ((FarmMockWeatherChangedEvent) eventCaptor.getValue()).userId());
    }

    @Test
    @DisplayName("清空时：删库 → 清内存 → 广播事件")
    void resetMocksClearsAndPublishes() {
        MockWeatherService svc = newService(true);
        org.mockito.Mockito.when(mockRepo.deleteByUserId(9L)).thenReturn(3);

        Map<String, Object> out = svc.resetMocks(9L);

        assertEquals(true, out.get("ok"));
        assertEquals(3, out.get("removed"));
        verify(mockRepo).deleteByUserId(9L);
        // 内存层真的清空了（不是只调了方法）
        assertTrue(weather.mockedSegments(9L).isEmpty());
        verify(publisher).publishEvent(any(FarmMockWeatherChangedEvent.class));
    }

    // ==================================================================
    //  启动装载（漏了 → 重启后 mock 全丢）
    // ==================================================================

    @Test
    @DisplayName("启动时把 DB 里的 mock 按用户分组灌进内存层")
    void hydrateFromDatabaseGroupsByUser() {
        FarmWeatherMock a = new FarmWeatherMock();
        a.setUserId(1L);
        a.setFromDate(LocalDate.of(2026, 5, 1));
        a.setToDate(LocalDate.of(2026, 5, 2));
        a.setTmax(30.0);
        a.setTmin(20.0);
        a.setPrecip(0.0);

        FarmWeatherMock b = new FarmWeatherMock();
        b.setUserId(1L);
        b.setFromDate(LocalDate.of(2026, 6, 1));
        b.setToDate(LocalDate.of(2026, 6, 2));
        b.setTmax(31.0);
        b.setTmin(21.0);
        b.setPrecip(0.0);

        FarmWeatherMock c = new FarmWeatherMock();
        c.setUserId(2L);
        c.setFromDate(LocalDate.of(2026, 7, 1));
        c.setToDate(LocalDate.of(2026, 7, 2));
        c.setTmax(32.0);
        c.setTmin(22.0);
        c.setPrecip(0.0);

        org.mockito.Mockito.when(mockRepo.findAll()).thenReturn(List.of(a, b, c));

        newService(true).hydrateFromDatabase();

        // 用户 1 两段、用户 2 一段 —— 分组不能串
        assertEquals(2, weather.mockedSegments(1L).size());
        assertEquals(1, weather.mockedSegments(2L).size());
        assertTrue(weather.mockedSegments(99L).isEmpty());
    }

    // ==================================================================
    //  读路径
    // ==================================================================

    @Test
    @DisplayName("listMocks 列出内存层的全部段")
    void listMocksReturnsAllSegments() {
        MockWeatherService svc = newService(true);
        FarmWeatherMock m = new FarmWeatherMock();
        setId(m, 5L);
        m.setFromDate(LocalDate.of(2026, 5, 1));
        m.setToDate(LocalDate.of(2026, 5, 3));
        m.setTmax(36.0);
        m.setTmin(26.0);
        m.setPrecip(1.0);
        m.setLabel("标签");
        weather.loadUserMocks(9L, List.of(m));

        List<Map<String, Object>> out = svc.listMocks(9L);
        assertEquals(1, out.size());
        assertEquals(5L, out.get(0).get("id"));
        assertEquals("2026-05-01", out.get(0).get("fromDate"));
        assertEquals(36.0, out.get(0).get("tmax"));
        assertEquals("标签", out.get(0).get("label"));
    }

    // ==================================================================
    //  辅助
    // ==================================================================

    /** 让 {@code mockRepo.save(m)} 回填 id，模拟 JPA 的自增主键。 */
    private void whenSaveAssignsId(long id) {
        doAnswer(inv -> {
            FarmWeatherMock m = inv.getArgument(0);
            setId(m, id);
            return m;
        }).when(mockRepo).save(any(FarmWeatherMock.class));
    }

    /** 反射设置主键（实体没有公开 setter，id 由 JPA 生成）。 */
    private void setId(FarmWeatherMock m, long id) {
        try {
            var field = FarmWeatherMock.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(m, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
