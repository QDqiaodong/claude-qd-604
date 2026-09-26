package com.bike.station.domain;

import java.util.List;
import java.util.Map;

/**
 * 应急清站模块的状态词典。
 *
 * 只放常量、状态名与状态机判定，不放任何实体默认值 ——
 * 默认值一律在 ClearanceService 里显式赋值（与全仓口径一致）。
 */
public final class ClearanceDict {

    private ClearanceDict() {
    }

    // ------------------------------------------------------------------
    // 清站单状态
    // ------------------------------------------------------------------
    public static final String ORDER_PENDING = "PENDING";
    public static final String ORDER_RUNNING = "RUNNING";
    public static final String ORDER_PARTIALLY_BLOCKED = "PARTIALLY_BLOCKED";
    public static final String ORDER_COMPLETED = "COMPLETED";
    public static final String ORDER_CANCELLED = "CANCELLED";

    private static final Map<String, String> ORDER_STATUS_NAME = Map.of(
            ORDER_PENDING, "待执行",
            ORDER_RUNNING, "执行中",
            ORDER_PARTIALLY_BLOCKED, "部分受阻",
            ORDER_COMPLETED, "已完成",
            ORDER_CANCELLED, "已撤销");

    /** 还能继续点「执行」推进的状态 */
    public static final List<String> ORDER_EXECUTABLE = List.of(
            ORDER_PENDING, ORDER_RUNNING, ORDER_PARTIALLY_BLOCKED);

    /** 还在占用源站（站点看板要显示「正在清站」）的状态 */
    public static final List<String> ORDER_ACTIVE = List.of(
            ORDER_PENDING, ORDER_RUNNING, ORDER_PARTIALLY_BLOCKED);

    // ------------------------------------------------------------------
    // 明细车辆状态
    // ------------------------------------------------------------------
    public static final String ITEM_PLANNED = "PLANNED";
    public static final String ITEM_UNPLACED = "UNPLACED";
    public static final String ITEM_MIGRATED = "MIGRATED";
    public static final String ITEM_BLOCKED = "BLOCKED";

    private static final Map<String, String> ITEM_STATUS_NAME = Map.of(
            ITEM_PLANNED, "待迁出",
            ITEM_UNPLACED, "无处安置",
            ITEM_MIGRATED, "已迁出",
            ITEM_BLOCKED, "受阻");

    // ------------------------------------------------------------------
    // 车辆类型
    // ------------------------------------------------------------------
    public static final String KIND_NORMAL = "NORMAL";
    public static final String KIND_FAULT = "FAULT";

    private static final Map<String, String> KIND_NAME = Map.of(
            KIND_NORMAL, "普通车",
            KIND_FAULT, "故障车");

    // ------------------------------------------------------------------
    // 取值方法
    // ------------------------------------------------------------------

    public static boolean isValidOrderStatus(String status) {
        return status != null && ORDER_STATUS_NAME.containsKey(status);
    }

    public static String orderStatusName(String status) {
        return status == null ? "" : ORDER_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean isActive(String status) {
        return status != null && ORDER_ACTIVE.contains(status);
    }

    public static boolean isExecutable(String status) {
        return status != null && ORDER_EXECUTABLE.contains(status);
    }

    public static boolean isTerminal(String status) {
        return ORDER_COMPLETED.equals(status) || ORDER_CANCELLED.equals(status);
    }

    public static String itemStatusName(String status) {
        return status == null ? "" : ITEM_STATUS_NAME.getOrDefault(status, status);
    }

    public static String kindName(String kind) {
        return kind == null ? "" : KIND_NAME.getOrDefault(kind, kind);
    }
}
