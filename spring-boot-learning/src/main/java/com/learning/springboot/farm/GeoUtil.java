package com.learning.springboot.farm;

import java.util.List;

/**
 * 地理计算工具（纯 JDK 实现，无第三方依赖）。
 *
 * <p>覆盖三类运算：
 * <ol>
 *   <li><b>投影正算/反算</b>：Web Mercator（EPSG:3857）—— 瓦片地图的坐标系，
 *       与天地图 WMTS 的 {@code TILEMATRIXSET=w} 一致</li>
 *   <li><b>球面距离</b>：Haversine 公式 —— 两点间大圆距离（米）</li>
 *   <li><b>多边形量算</b>：Shoelace（鞋带）公式算面积、逐边累加算周长 —— 用于地块面积/围栏长度</li>
 * </ol>
 *
 * <p><b>精度说明</b>：
 * <ul>
 *   <li>Haversine 用球面模型，<b>半径取地球平均半径 6371008.8m</b>（而非赤道半径）——
 *       平均半径是球面近似下误差最小的选择。以京沪线为例：
 *       平均半径 1067.1km vs 赤道半径 1068.3km vs WGS84 椭球测地线真值 1065.6km，
 *       误差从 0.25% 降到 0.14%。若需法律/测绘级精度请改用 {@code GeographicLib}。</li>
 *   <li>墨卡托投影用<b>赤道半径 6378137m</b>（EPSG:3857 规定值，不可改），
 *       否则瓦片层级与天地图的 {@code TILEMATRIXSET=w} 对不齐。</li>
 *   <li>Shoelace 面积在墨卡托平面上计算会随纬度放大，故本实现
 *       <b>先按纬度做等距圆柱近似修正</b>（乘以 cos(中心纬度)），
 *       中心纬度取多边形各顶点纬度的算术平均。</li>
 * </ul>
 *
 * <p>本类是纯函数集合，无状态，线程安全。
 */
public final class GeoUtil {

    /** EPSG:3857 基准椭球的赤道半径（米）—— 墨卡托投影专用，不可改 */
    public static final double EARTH_RADIUS = 6378137.0;

    /** 地球平均半径（米）—— Haversine 球面距离专用，误差最小 */
    public static final double MEAN_EARTH_RADIUS = 6371008.8;

    /** 赤道周长（米） */
    public static final double EARTH_CIRCUMFERENCE = 2 * Math.PI * EARTH_RADIUS;

    private GeoUtil() {}

    // ==================================================================
    //  Web Mercator (EPSG:3857) 投影
    // ==================================================================

    /**
     * 经纬度 → Web Mercator 平面坐标（米）。
     *
     * <p>公式（球面墨卡托）：
     * <pre>
     * x = R * lon(rad)
     * y = R * ln(tan(π/4 + lat(rad)/2))
     * </pre>
     *
     * @param lng 经度（度，[-180, 180]）
     * @param lat 纬度（度，[-85.05112878, 85.05112878]）
     * @return {@code double[2]} = {x, y}，单位米
     */
    public static double[] lngLatToMercator(double lng, double lat) {
        double clampedLat = clampMercatorLat(lat);
        double x = EARTH_RADIUS * Math.toRadians(lng);
        double y = EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(clampedLat) / 2.0));
        return new double[]{x, y};
    }

    /**
     * Web Mercator 平面坐标（米）→ 经纬度。
     *
     * @param x 墨卡托 x（米）
     * @param y 墨卡托 y（米）
     * @return {@code double[2]} = {lng, lat}，单位度
     */
    public static double[] mercatorToLngLat(double x, double y) {
        double lng = Math.toDegrees(x / EARTH_RADIUS);
        double lat = Math.toDegrees(2.0 * Math.atan(Math.exp(y / EARTH_RADIUS)) - Math.PI / 2.0);
        return new double[]{lng, lat};
    }

    /**
     * 墨卡托纬度截断上下限（±85.05112878°）。
     * <p>超出该范围时正算结果会趋于无穷（极点），必须截断。
     */
    public static double clampMercatorLat(double lat) {
        return Math.max(-85.05112878, Math.min(85.05112878, lat));
    }

    /**
     * 经纬度 → 瓦片行列号（Web Mercator 切片方案，天地图 {@code TILEMATRIXSET=w}）。
     *
     * @param lng 经度（度）
     * @param lat 纬度（度）
     * @param z  缩放层级
     * @return {@code int[2]} = {tileX, tileY}
     */
    public static int[] lngLatToTile(double lng, double lat, int z) {
        int n = 1 << z;
        double latRad = Math.toRadians(clampMercatorLat(lat));
        int tileX = (int) Math.floor((lng + 180.0) / 360.0 * n);
        int tileY = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
        return new int[]{tileX, tileY};
    }

    // ==================================================================
    //  距离
    // ==================================================================

    /**
     * 两点球面距离（Haversine 公式，单位：米）。
     *
     * <p>比平面欧氏距离准确得多：地球是球体，直接用经纬度当直角算，
     * 在跨省尺度上误差可达 20%+。
     *
     * @param lng1 点1经度（度）
     * @param lat1 点1纬度（度）
     * @param lng2 点2经度（度）
     * @param lat2 点2纬度（度）
     * @return 两点大圆距离（米）
     */
    public static double distanceMeters(double lng1, double lat1, double lng2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        // 数值保护：浮点误差可能让 a 略大于 1，导致 asin 域错
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1 - a)));
        return MEAN_EARTH_RADIUS * c;
    }

    // ==================================================================
    //  多边形量算
    // ==================================================================

    /**
     * 多边形面积（Shoelace 公式，单位：平方米）。
     *
     * <p>步骤：经纬度转墨卡托 → 鞋带公式算平面面积 → 按中心纬度做 cos 修正。
     * 直接在经纬度上套鞋带公式会得到「度²」，必须先投影。
     *
     * <p><b>首尾重复顶点会被去重</b>：如果传入的环已经显式闭合
     * （最后一个点 == 第一个点），直接参与计算会让中心纬度多算一个点，
     * 导致 cos 修正系数偏小、面积偏大。GeoJSON 的 Polygon 外环
     * 约定就是首尾重复，所以必须处理。
     *
     * @param ring 多边形外环顶点序列，每个元素为 {@code double[]{lng, lat}}；
     *             顶点顺序应为顺时针或逆时针，首尾可重复（会自动去重闭合）
     * @return 面积（平方米）；去重后点数不足 3 时返回 0
     */
    public static double polygonAreaMeters(List<double[]> ring) {
        if (ring == null) return 0.0;
        List<double[]> pts = dedupeClosingVertex(ring);
        if (pts.size() < 3) return 0.0;

        // 投影到墨卡托平面：mx[i] = x, my[i] = y
        int n = pts.size();
        double[] mx = new double[n];
        double[] my = new double[n];
        for (int i = 0; i < n; i++) {
            double[] merc = lngLatToMercator(pts.get(i)[0], pts.get(i)[1]);
            mx[i] = merc[0];
            my[i] = merc[1];
        }

        // 鞋带公式：Σ(x_i * y_{i+1} - x_{i+1} * y_i)，取绝对值
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += mx[i] * my[j] - mx[j] * my[i];
        }
        double area = Math.abs(sum) / 2.0;

        // 墨卡托在 y 方向按 1/cos(lat) 放大，用中心纬度反算回真实面积
        double centerLat = centerLatitude(pts);
        double cos = Math.cos(Math.toRadians(centerLat));
        if (cos > 1e-9) {
            area = area * cos;
        }
        return area;
    }

    /**
     * 多边形周长（单位：米）。
     *
     * <p>逐边用 Haversine 累加（球面真实距离），首尾自动闭合。
     *
     * @param ring 多边形外环顶点序列，每个元素为 {@code double[]{lng, lat}}
     * @return 周长（米）；去重后点数不足 2 时返回 0
     */
    public static double polygonPerimeterMeters(List<double[]> ring) {
        if (ring == null) return 0.0;
        List<double[]> pts = dedupeClosingVertex(ring);
        if (pts.size() < 2) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < pts.size(); i++) {
            double[] cur = pts.get(i);
            double[] next = pts.get((i + 1) % pts.size());
            sum += distanceMeters(cur[0], cur[1], next[0], next[1]);
        }
        return sum;
    }

    /**
     * 去掉首尾重复的闭合顶点。
     * <p>GeoJSON 规定 Polygon 外环首尾必须重复（闭合），但直接参与
     * 面积/周长计算会引入多余的零长度边和偏心的中心纬度。
     */
    private static List<double[]> dedupeClosingVertex(List<double[]> ring) {
        if (ring.size() < 2) return ring;
        double[] first = ring.get(0);
        double[] last = ring.get(ring.size() - 1);
        if (first == null || last == null) return ring;
        if (first.length < 2 || last.length < 2) return ring;
        if (Math.abs(first[0] - last[0]) < 1e-12 && Math.abs(first[1] - last[1]) < 1e-12) {
            return ring.subList(0, ring.size() - 1);
        }
        return ring;
    }

    /**
     * 多边形中心纬度（各顶点纬度的算术平均）。
     * <p>用作墨卡托面积修正的参考纬度。
     */
    public static double centerLatitude(List<double[]> ring) {
        if (ring == null || ring.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double[] p : ring) {
            sum += p[1];
        }
        return sum / ring.size();
    }

    // ==================================================================
    //  格式化
    // ==================================================================

    /**
     * 距离格式化：自动切换 m / km。
     *
     * <p>例：{@code formatDistance(950)} → {@code "950 m"}；
     * {@code formatDistance(12345)} → {@code "12.35 km"}。
     *
     * @param meters 距离（米）
     */
    public static String formatDistance(double meters) {
        if (Double.isNaN(meters) || Double.isInfinite(meters)) return "-";
        if (meters < 1000.0) {
            return String.format("%.0f m", meters);
        }
        return String.format("%.2f km", meters / 1000.0);
    }

    /**
     * 面积格式化：自动切换 m² / km² / 公顷(ha)。
     *
     * <p>例：{@code formatArea(500)} → {@code "500 m²"}；
     * {@code formatArea(25000)} → {@code "2.50 ha"}；
     * {@code formatArea(5000000)} → {@code "5.00 km²"}。
     *
     * @param squareMeters 面积（平方米）
     */
    public static String formatArea(double squareMeters) {
        if (Double.isNaN(squareMeters) || Double.isInfinite(squareMeters)) return "-";
        if (squareMeters < 10_000.0) {
            return String.format("%.0f m²", squareMeters);
        }
        if (squareMeters < 1_000_000.0) {
            return String.format("%.2f ha", squareMeters / 10_000.0);
        }
        return String.format("%.2f km²", squareMeters / 1_000_000.0);
    }

    /**
     * 经纬度格式化（六位小数，约 0.1 m 精度）。
     */
    public static String formatLngLat(double lng, double lat) {
        return String.format("%.6f, %.6f", lng, lat);
    }
}
