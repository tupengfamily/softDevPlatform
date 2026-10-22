package com.learning.springboot.farm.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 连续逐日天气序列（跨年连续，供跨年回放使用）。 */
public final class WeatherSeries {

    private WeatherSeries() {
    }

    public static List<DayWeather> loadFromClasspath(String resource) throws IOException {
        ObjectMapper om = new ObjectMapper();
        try (InputStream in = WeatherSeries.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("找不到天气数据: " + resource);
            }
            JsonNode root = om.readTree(in);
            JsonNode days = root.get("days");
            List<DayWeather> out = new ArrayList<>(days.size());
            for (JsonNode d : days) {
                out.add(new DayWeather(
                        d.get("date").asText(),
                        d.get("tmax").asDouble(),
                        d.get("tmin").asDouble(),
                        d.path("precip").asDouble(0)));
            }
            return out;
        }
    }

    /** 返回 date 完全相等的下标；找不到返回 -1（不猜、不模糊匹配）。 */
    public static int indexOf(List<DayWeather> days, String date) {
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i).date().equals(date)) {
                return i;
            }
        }
        return -1;
    }
}
