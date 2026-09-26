package com.bike.station.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * 应急清站模块的入参。
 */
public final class ClearanceDtos {

    private ClearanceDtos() {
    }

    /**
     * 建单 / 方案预览：选一个源站 + 一个或多个运营中的接收站。
     * 接收站列表去重后按传入顺序保留（seq）。
     */
    public record ClearanceCreate(
            @NotNull(message = "源站不能为空")
            Long sourceStationId,

            @NotNull(message = "至少选择一个接收站")
            List<Long> receiverStationIds) {
    }

    /**
     * 人工调整某辆车的计划去向：
     *  - targetStationId 有值：改派到该接收站（targetPileId 可空，空着表示由后端挑空桩；
     *    维修区车辆不允许指定桩位）；
     *  - unplace=true 且 targetStationId 为空：把车辆改回「暂时无处安置」；
     *  - targetStationId 为空且 unplace 不为 true：参数非法。
     */
    public record ItemAdjust(
            Long targetStationId,

            Long targetPileId,

            Boolean unplace) {
    }
}
