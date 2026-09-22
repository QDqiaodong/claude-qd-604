package com.bike.station.dto;

import java.time.LocalDateTime;

/**
 * 运维工单模块的入参。
 *
 * 同 RentalDtos：bikeId / workerId 这类外键不加 @NotNull，
 * 由 WorkOrderService 先判 null 再查库，给中文提示。
 */
public final class WorkOrderDtos {

    private WorkOrderDtos() {
    }

    /** 上报故障 */
    public record Report(
            Long bikeId,
            String faultType,
            String level) {
    }

    /** 派单：把工单派给 workerId 这个维修工，占用 [planStart, planEnd) 这段时间 */
    public record Assign(
            Long workerId,
            LocalDateTime planStart,
            LocalDateTime planEnd) {
    }

    /** 修复 */
    public record Fix(
            String fixNote) {
    }
}
