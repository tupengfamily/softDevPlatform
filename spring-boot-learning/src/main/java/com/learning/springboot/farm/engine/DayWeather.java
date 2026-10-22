package com.learning.springboot.farm.engine;

/**
 * 一天的天气。tmean 按 (tmax + tmin) / 2 计算 —— 与 Open-Meteo daily 口径一致。
 *
 * <p>口径红线（基线 §5 / S7 §4.6）：这是「百叶箱」口径的原始观测，**不是冠层温度**。
 * 冠层换算只在需要的地方做（S7 §4.6.4(c) 的 Δ_frost），不得在这里预换算，
 * 否则后面所有用到 T_min 的地方都会悄悄差 2–4℃。
 */
public record DayWeather(String date, double tmax, double tmin, double precip) {

    public double tmean() {
        return (tmax + tmin) / 2.0;
    }
}
