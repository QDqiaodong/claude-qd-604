package com.bike.station.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 车辆台账模块的入参。
 *
 * 全部字段都允许为 null（PUT 走局部更新：null 表示「这一项不改」），
 * 所以校验只加在「新增时必填」的字段上，其余在 service 里按业务判断。
 */
public final class BikeDtos {

    private BikeDtos() {
    }

    /** 新增车辆：status / totalRides / createdAt 由 service 赋，不由前端传 */
    public record BikeCreate(
            @NotBlank(message = "车辆编号不能为空")
            @Size(max = 32, message = "车辆编号长度不能超过 32")
            String code,

            @NotBlank(message = "车型不能为空")
            @Size(max = 32, message = "车型长度不能超过 32")
            String model,

            Long stationId,

            Long pileId) {
    }

    /**
     * 局部更新车辆：只改传了值的字段。
     * status 只接受人工可做的迁移（在桩↔维修中），借车/还车/报废走各自的专门接口。
     */
    public record BikeUpdate(
            @Size(max = 32, message = "车型长度不能超过 32")
            String model,

            String status,

            Long stationId,

            Long pileId,

            @Size(max = 128, message = "报废原因长度不能超过 128")
            String scrapReason) {
    }

    /** 报废：reason 可空，service 会补一句默认说明 */
    public record BikeScrap(
            @Size(max = 128, message = "报废原因长度不能超过 128")
            String reason) {
    }
}
