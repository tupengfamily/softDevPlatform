package com.learning.springboot.farm;

/**
 * 模拟天气变更事件（S2）。
 *
 * <p><b>为什么用事件而不是直接调用</b>：
 * {@code MockWeatherService}（写侧）改动天气后，{@code FarmPlayService}（读侧）
 * 必须丢弃内存里的回放缓存 —— 否则玩家注入一段高温后，
 * 面板上看到的还是注入前的旧 DVS，出现「操作没反应」的错觉。
 *
 * <p>直接调用 {@code playService.invalidateSim(userId)} 会让两个模块产生<b>编译期硬依赖</b>：
 * 写侧要知道读侧的方法名，读侧也要知道谁会触发它。这是典型的双向耦合。
 *
 * <p>改成事件后：
 * <ul>
 *   <li>写侧只管「发个广播」，不知道谁在听；</li>
 *   <li>读侧只管「我关心这个事件」，不知道谁在发；</li>
 *   <li>将来若第二个模块也需要失效缓存，加个监听器即可，不用改写侧。</li>
 * </ul>
 *
 * <p>这正是「模块化单体」里模块间协作的推荐姿势：<b>同步事件，不直接调用</b>。
 * 事件是<b>进程内</b>的（Spring 的 {@code ApplicationEventPublisher}），
 * 将来真拆成微服务时，同一个事件类可以直接改成 MQ 消息，调用方无需改动。
 *
 * @param userId 受影响的用户（mock 天气按用户隔离）
 */
public record FarmMockWeatherChangedEvent(Long userId) {
}
