package com.bike.station.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 单车驿站业务词典与规则参数表。
 *
 * 状态码、状态机、计费参数、容量与时长口径集中在这里，
 * 以后要调计费规则、加一个状态或改一条阈值，只需要动这个类，不用翻实体。
 *
 * 注意：这里只放【常量与规则参数】，不放任何实体的默认值 ——
 * 实体的默认值一律在 service 的 create 方法里显式赋值。
 */
public final class BikeDict {

    private BikeDict() {
    }

    // ------------------------------------------------------------------
    // 车辆状态机
    // ------------------------------------------------------------------
    public static final String BIKE_PILE = "PILE";
    public static final String BIKE_RIDING = "RIDING";
    public static final String BIKE_REPAIR = "REPAIR";
    public static final String BIKE_SCRAPPED = "SCRAPPED";

    private static final Map<String, String> BIKE_STATUS_NAME = Map.of(
            "PILE", "在桩",
            "RIDING", "骑行中",
            "REPAIR", "维修中",
            "SCRAPPED", "报废");

    /**
     * 允许人工调整的状态迁移（借车/还车引起的迁移由 RentalService 单独控制）。
     * 没列出来的迁移一律拒绝；SCRAPPED 是终态，出度为 0。
     */
    private static final Map<String, List<String>> BIKE_MANUAL_TRANSITION = Map.of(
            "PILE", List.of("REPAIR"),
            "RIDING", List.of("REPAIR"),
            "REPAIR", List.of("PILE"),
            "SCRAPPED", List.of());

    // ------------------------------------------------------------------
    // 租借订单状态
    // ------------------------------------------------------------------
    public static final String RENTAL_RIDING = "RIDING";
    public static final String RENTAL_SETTLED = "SETTLED";

    private static final Map<String, String> RENTAL_STATUS_NAME = Map.of(
            "RIDING", "骑行中",
            "SETTLED", "已结算");

    // ------------------------------------------------------------------
    // 工单状态机
    // ------------------------------------------------------------------
    public static final String ORDER_REPORTED = "REPORTED";
    public static final String ORDER_ASSIGNED = "ASSIGNED";
    public static final String ORDER_FIXED = "FIXED";
    public static final String ORDER_CLOSED = "CLOSED";

    private static final Map<String, String> ORDER_STATUS_NAME = Map.of(
            "REPORTED", "已上报",
            "ASSIGNED", "已派单",
            "FIXED", "已修复",
            "CLOSED", "已关闭");

    /** 仍然占用维修工时段的工单状态（只有这两种会参与「时段不重叠」判定） */
    public static final List<String> ORDER_OPEN_STATUS = List.of(
            ORDER_REPORTED, ORDER_ASSIGNED);

    private static final Map<String, String> FAULT_LEVEL_NAME = Map.of(
            "LOW", "低",
            "MID", "中",
            "HIGH", "高");

    // ------------------------------------------------------------------
    // 站点 / 桩位 / 人员状态
    // ------------------------------------------------------------------
    public static final String STATION_OPEN = "OPEN";
    public static final String STATION_CLOSED = "CLOSED";

    private static final Map<String, String> STATION_STATUS_NAME = Map.of(
            "OPEN", "运营中",
            "CLOSED", "已停用");

    public static final String PILE_IDLE = "IDLE";
    public static final String PILE_OCCUPIED = "OCCUPIED";
    public static final String PILE_DISABLED = "DISABLED";

    private static final Map<String, String> PILE_STATUS_NAME = Map.of(
            "IDLE", "空闲",
            "OCCUPIED", "占用",
            "DISABLED", "停用");

    public static final String WORKER_ON_DUTY = "ON_DUTY";
    public static final String WORKER_OFF_DUTY = "OFF_DUTY";

    private static final Map<String, String> WORKER_STATUS_NAME = Map.of(
            "ON_DUTY", "在岗",
            "OFF_DUTY", "休息");

    // ------------------------------------------------------------------
    // 骑行人账号状态
    // ------------------------------------------------------------------
    public static final String USER_NORMAL = "NORMAL";
    public static final String USER_FROZEN = "FROZEN";

    private static final Map<String, String> USER_STATUS_NAME = Map.of(
            "NORMAL", "正常",
            "FROZEN", "冻结");

    public static boolean isValidUserStatus(String status) {
        return status != null && USER_STATUS_NAME.containsKey(status);
    }

    public static String userStatusName(String status) {
        return status == null ? "" : USER_STATUS_NAME.getOrDefault(status, status);
    }

    // ------------------------------------------------------------------
    // 计费规则：起步价含 60 分钟，超出后每 30 分钟 1.00 元（不足 30 分钟按 30 分钟计），单次封顶 20.00 元
    // ------------------------------------------------------------------
    public static final BigDecimal START_PRICE = BigDecimal.valueOf(1.50);
    public static final int FREE_MINUTES = 60;
    public static final BigDecimal STEP_PRICE = BigDecimal.valueOf(1.00);
    public static final int STEP_MINUTES = 30;
    public static final BigDecimal MAX_AMOUNT = BigDecimal.valueOf(20.00);

    /** 车辆编号前缀与长度口径：BK- 加 4 位数字 */
    public static final String BIKE_CODE_PREFIX = "BK-";

    // ------------------------------------------------------------------
    // 取值方法
    // ------------------------------------------------------------------

    public static boolean isValidBikeStatus(String status) {
        return status != null && BIKE_STATUS_NAME.containsKey(status);
    }

    public static String bikeStatusName(String status) {
        return status == null ? "" : BIKE_STATUS_NAME.getOrDefault(status, status);
    }

    /**
     * 人工改状态是否合法（终态 SCRAPPED 永远返回 false）
     */
    public static boolean canManuallyMove(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return BIKE_MANUAL_TRANSITION.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isScrapped(String status) {
        return BIKE_SCRAPPED.equals(status);
    }

    public static boolean isValidRentalStatus(String status) {
        return status != null && RENTAL_STATUS_NAME.containsKey(status);
    }

    public static String rentalStatusName(String status) {
        return status == null ? "" : RENTAL_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean isValidOrderStatus(String status) {
        return status != null && ORDER_STATUS_NAME.containsKey(status);
    }

    public static String orderStatusName(String status) {
        return status == null ? "" : ORDER_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean isValidFaultLevel(String level) {
        return level != null && FAULT_LEVEL_NAME.containsKey(level);
    }

    public static String faultLevelName(String level) {
        return level == null ? "" : FAULT_LEVEL_NAME.getOrDefault(level, level);
    }

    public static boolean isValidStationStatus(String status) {
        return status != null && STATION_STATUS_NAME.containsKey(status);
    }

    public static String stationStatusName(String status) {
        return status == null ? "" : STATION_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean isValidPileStatus(String status) {
        return status != null && PILE_STATUS_NAME.containsKey(status);
    }

    public static String pileStatusName(String status) {
        return status == null ? "" : PILE_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean isValidWorkerStatus(String status) {
        return status != null && WORKER_STATUS_NAME.containsKey(status);
    }

    public static String workerStatusName(String status) {
        return status == null ? "" : WORKER_STATUS_NAME.getOrDefault(status, status);
    }

    /**
     * 计费：起步价 1.50 元含 60 分钟；超出部分每 30 分钟 1.00 元，
     * 不足 30 分钟按 30 分钟计；单次封顶 20.00 元。
     *
     * 例：25 分钟 → 1.50；95 分钟 → 3.50；200 分钟 → 6.50；700 分钟 → 20.00（封顶）
     */
    public static BigDecimal calcAmount(long minutes) {
        long usage = Math.max(minutes, 0L);
        BigDecimal amount = START_PRICE;
        if (usage > FREE_MINUTES) {
            long over = usage - FREE_MINUTES;
            long segments = (over + STEP_MINUTES - 1) / STEP_MINUTES;
            amount = amount.add(STEP_PRICE.multiply(BigDecimal.valueOf(segments)));
        }
        return amount.min(MAX_AMOUNT);
    }
}
