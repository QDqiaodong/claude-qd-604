package com.bike.station.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.bike.station.domain.Station;

/**
 * 站点间直线距离（公里），用等距圆柱近似（对同城驿站精度足够）。
 * 经纬度缺失时返回 null，页面显示「—」，分配时当作最远处理而不是报错。
 */
final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoUtils() {
    }

    static BigDecimal distanceKm(Station from, Station to) {
        if (from == null || to == null
                || from.getLongitude() == null || from.getLatitude() == null
                || to.getLongitude() == null || to.getLatitude() == null) {
            return null;
        }
        double lat1 = Math.toRadians(from.getLatitude().doubleValue());
        double lat2 = Math.toRadians(to.getLatitude().doubleValue());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.getLongitude().doubleValue()
                - from.getLongitude().doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return BigDecimal.valueOf(EARTH_RADIUS_KM * c).setScale(2, RoundingMode.HALF_UP);
    }
}
