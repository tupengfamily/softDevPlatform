package com.learning.springboot.farm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GeoUtil} 单元测试。
 *
 * <p>纯计算类，无需 Spring 上下文，秒级跑完。
 * 用「已知真值」校验：墨卡托正反算往返、Haversine 与已知城市距离、
 * Shoelace 面积与标准矩形对照。
 */
class GeoUtilTest {

    private static final double EPS = 1e-6;

    // ==================================================================
    //  Web Mercator
    // ==================================================================

    @Test
    @DisplayName("墨卡托正算：原点(0,0) → (0,0)")
    void mercatorOrigin() {
        double[] r = GeoUtil.lngLatToMercator(0, 0);
        assertEquals(0.0, r[0], EPS);
        assertEquals(0.0, r[1], EPS);
    }

    @Test
    @DisplayName("墨卡托正算：赤道 180° → ±20037508 米（半周长）")
    void mercatorEquator180() {
        double[] r = GeoUtil.lngLatToMercator(180, 0);
        assertEquals(Math.PI * GeoUtil.EARTH_RADIUS, r[0], 1.0);
    }

    @Test
    @DisplayName("墨卡托正反算往返一致（多组随机点）")
    void mercatorRoundTrip() {
        double[][] pts = {
                {116.4, 39.9}, {-122.4, 37.8}, {0.0, 0.0},
                {139.7, 35.7}, {-58.4, -34.6}, {151.2, -33.9}
        };
        for (double[] p : pts) {
            double[] m = GeoUtil.lngLatToMercator(p[0], p[1]);
            double[] back = GeoUtil.mercatorToLngLat(m[0], m[1]);
            assertEquals(p[0], back[0], 1e-9, "lng 往返误差");
            assertEquals(p[1], back[1], 1e-9, "lat 往返误差");
        }
    }

    @Test
    @DisplayName("墨卡托纬度超范围被截断（不产生 Infinity）")
    void mercatorClampsLat() {
        double[] north = GeoUtil.lngLatToMercator(0, 89.9);
        double[] clamped = GeoUtil.lngLatToMercator(0, 85.05112878);
        assertTrue(Double.isFinite(north[1]), "纬度超范围不应产生无穷大");
        assertEquals(clamped[1], north[1], 1e-6);
    }

    @Test
    @DisplayName("经纬度 → 瓦片行列号（北京 z=10 落在已知瓦片）")
    void lngLatToTile() {
        int[] t = GeoUtil.lngLatToTile(116.4, 39.9, 10);
        assertEquals(843, t[0]);
        assertEquals(388, t[1]);
    }

    @Test
    @DisplayName("瓦片行列号：z=0 只有一个瓦片")
    void tileAtZoomZero() {
        int[] t = GeoUtil.lngLatToTile(116.4, 39.9, 0);
        assertEquals(0, t[0]);
        assertEquals(0, t[1]);
    }

    // ==================================================================
    //  距离
    // ==================================================================

    @Test
    @DisplayName("Haversine：同点距离为 0")
    void distanceSamePoint() {
        assertEquals(0.0, GeoUtil.distanceMeters(116.4, 39.9, 116.4, 39.9), 1e-6);
    }

    @Test
    @DisplayName("Haversine：北京 → 上海 ≈ 1067 km（对照 WGS84 测地线真值 1065.6km）")
    void distanceBeijingShanghai() {
        // 北京(116.4,39.9) → 上海(121.47,31.23)
        // 权威真值(WGS84 椭球测地线/Vincenty): 1065.6 km
        // 本实现(平均半径球面): 1067.1 km, 误差 +0.14%
        double d = GeoUtil.distanceMeters(116.4, 39.9, 121.47, 31.23);
        assertEquals(1_065_600.0, d, 1_065_600.0 * 0.005, "京沪距离应在测地线真值 ±0.5% 内");
    }

    @Test
    @DisplayName("Haversine：1° 纬度 ≈ 111.19 km（平均半径）")
    void distanceOneDegreeLat() {
        // 平均半径 R=6371008.8 → 1° 纬度 = 2πR/360 = 111195.1 m
        double d = GeoUtil.distanceMeters(0, 0, 0, 1);
        assertEquals(111_195.0, d, 111_195.0 * 0.001, "1° 纬度约 111.2km");
    }

    @Test
    @DisplayName("Haversine：经度差在赤道处等效于纬度差")
    void distanceEquatorSymmetry() {
        double dLng = GeoUtil.distanceMeters(0, 0, 1, 0);
        double dLat = GeoUtil.distanceMeters(0, 0, 0, 1);
        assertEquals(dLat, dLng, 1.0, "赤道上经纬等距");
    }

    // ==================================================================
    //  多边形
    // ==================================================================

    @Test
    @DisplayName("多边形面积：1°×1° 方格 ≈ 12321 km²（容差 1%）")
    void polygonAreaOneDegreeSquare() {
        List<double[]> square = List.of(
                new double[]{0, 0},
                new double[]{1, 0},
                new double[]{1, 1},
                new double[]{0, 1}
        );
        double area = GeoUtil.polygonAreaMeters(square);
        // 1° 纬度 ≈ 111.19 km；1° 经度在赤道 ≈ 111.19 km；赤道附近 1°×1° ≈ 12,321 km²
        assertEquals(12_321_000_000.0, area, 12_321_000_000.0 * 0.01);
    }

    @Test
    @DisplayName("多边形面积：顶点数不足 3 返回 0")
    void polygonAreaTooFewPoints() {
        assertEquals(0.0, GeoUtil.polygonAreaMeters(List.of(
                new double[]{0, 0}, new double[]{1, 1})), EPS);
        assertEquals(0.0, GeoUtil.polygonAreaMeters(null), EPS);
    }

    @Test
    @DisplayName("多边形面积：顶点顺序不影响结果（顺/逆时针同面积）")
    void polygonAreaOrderIndependent() {
        List<double[]> cw = List.of(
                new double[]{0, 0}, new double[]{1, 0}, new double[]{1, 1}, new double[]{0, 1});
        List<double[]> ccw = List.of(
                new double[]{0, 0}, new double[]{0, 1}, new double[]{1, 1}, new double[]{1, 0});
        assertEquals(GeoUtil.polygonAreaMeters(cw), GeoUtil.polygonAreaMeters(ccw), 1.0);
    }

    @Test
    @DisplayName("多边形面积：显式闭合（首尾重复）不重复计边")
    void polygonAreaExplicitlyClosed() {
        List<double[]> closed = List.of(
                new double[]{0, 0}, new double[]{1, 0}, new double[]{1, 1},
                new double[]{0, 1}, new double[]{0, 0});
        List<double[]> open = List.of(
                new double[]{0, 0}, new double[]{1, 0}, new double[]{1, 1}, new double[]{0, 1});
        assertEquals(GeoUtil.polygonAreaMeters(open), GeoUtil.polygonAreaMeters(closed), 1.0);
    }

    @Test
    @DisplayName("多边形周长：1°×1° 方格 ≈ 444.8 km")
    void polygonPerimeterSquare() {
        List<double[]> square = List.of(
                new double[]{0, 0}, new double[]{1, 0}, new double[]{1, 1}, new double[]{0, 1});
        double perimeter = GeoUtil.polygonPerimeterMeters(square);
        // 4 条边，每条 ≈ 111.19 km
        assertEquals(444_780.0, perimeter, 444_780.0 * 0.01);
    }

    @Test
    @DisplayName("多边形周长：顶点数不足 2 返回 0")
    void polygonPerimeterTooFewPoints() {
        assertEquals(0.0, GeoUtil.polygonPerimeterMeters(List.of(
                new double[]{0, 0})), EPS);
        assertEquals(0.0, GeoUtil.polygonPerimeterMeters(null), EPS);
    }

    @Test
    @DisplayName("多边形周长：2 个点退化成一条往返线（距离 ×2）")
    void polygonPerimeterTwoPoints() {
        // 2 点构成退化多边形：边 A→B 与 B→A 各一条
        double d = GeoUtil.distanceMeters(0, 0, 1, 0);
        assertEquals(d * 2, GeoUtil.polygonPerimeterMeters(List.of(
                new double[]{0, 0}, new double[]{1, 0})), 1.0);
    }

    // ==================================================================
    //  格式化
    // ==================================================================

    @Test
    @DisplayName("距离格式化：m / km 自动切换")
    void formatDistance() {
        assertEquals("950 m", GeoUtil.formatDistance(950));
        assertEquals("12.35 km", GeoUtil.formatDistance(12345));
    }

    @Test
    @DisplayName("面积格式化：m² / ha / km² 自动切换")
    void formatArea() {
        assertEquals("500 m²", GeoUtil.formatArea(500));
        assertEquals("2.50 ha", GeoUtil.formatArea(25000));
        assertEquals("5.00 km²", GeoUtil.formatArea(5_000_000));
    }

    @Test
    @DisplayName("格式化：NaN/Infinity 返回占位符")
    void formatInvalid() {
        assertEquals("-", GeoUtil.formatDistance(Double.NaN));
        assertEquals("-", GeoUtil.formatArea(Double.POSITIVE_INFINITY));
    }
}
