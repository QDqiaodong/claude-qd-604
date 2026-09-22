package com.bike.station.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 站点与桩位模块的入参。
 */
public final class StationDtos {

    private StationDtos() {
    }

    /** 新增站点：status 由 service 置为「运营中」，不由前端传 */
    public record StationCreate(
            @NotBlank(message = "站点编号不能为空")
            @Size(max = 32, message = "站点编号长度不能超过 32")
            String code,

            @NotBlank(message = "站点名称不能为空")
            @Size(max = 64, message = "站点名称长度不能超过 64")
            String name,

            @NotBlank(message = "所在行政区不能为空")
            @Size(max = 32, message = "所在行政区长度不能超过 32")
            String district,

            @NotNull(message = "站点容量不能为空")
            @Min(value = 1, message = "站点容量至少为 1")
            Integer capacity,

            BigDecimal longitude,

            BigDecimal latitude) {
    }

    /** 站点容量调整 */
    public record StationCapacity(
            @NotNull(message = "站点容量不能为空")
            @Min(value = 1, message = "站点容量至少为 1")
            Integer capacity) {
    }

    /** 站点停用 / 启用 */
    public record StationStatus(
            @NotBlank(message = "站点状态不能为空")
            String status) {
    }

    /** 给站点加一个桩位 */
    public record PileCreate(
            @NotBlank(message = "桩位号不能为空")
            @Size(max = 16, message = "桩位号长度不能超过 16")
            String pileNo) {
    }

    /**
     * 拖拽调拨：把 bikeId 这辆车调到 toPileId 这个桩位上。
     *
     * 只给 toStationId（把车丢在站点卡片上、没指定具体桩位）时，service 会自动
     * 挑该站点里第一个空闲桩位；两个都不给就明确报「目标桩位不能为空」，
     * 而不是把 NPE 抛到页面上。
     */
    public record Transfer(
            Long bikeId,

            Long toPileId,

            Long toStationId) {
    }
}
