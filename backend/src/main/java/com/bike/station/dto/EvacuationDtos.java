package com.bike.station.dto;

import java.util.List;

/**
 * 应急清站模块的入参。
 *
 * 外键 id 刻意不加 @NotNull，由 service 先判 null 再查库，
 * 与 RentalDtos / WorkOrderDtos 的口径一致，返回中文业务提示而不是 JPA 报错。
 */
public final class EvacuationDtos {

    private EvacuationDtos() {
    }

    /**
     * 创建清站单：
     *   sourceStationId  待停用源站；
     *   targetStationIds 一个或多个【仍在运营】的接收站；
     *   remark           备注；
     *   faultBikeIds     人工指定的故障车（为空则后端按「挂着未关闭工单」自动判定）；
     *   overrides        在【预览方案】上人工调整后的逐车去向（bikeId → 接收站，null=暂无处安置）。
     *
     * 即使所有车都能排进接收站之外，接收能力不足时也允许保存 ——
     * 排不下的车 planStationId 为 null（暂无处安置），但单据不会被伪造成完整方案。
     */
    public record Create(
            Long sourceStationId,
            List<Long> targetStationIds,
            String remark,
            List<Long> faultBikeIds,
            List<ItemPlanOverride> overrides) {
    }

    /** 创建向导第三步：人工对自动方案里某辆车的去向做覆盖（stationId=null 表示强制留空）。 */
    public record ItemPlanOverride(
            Long bikeId,
            Long planStationId,
            Long planPileId) {
    }

    /** 只预览、不落库：入参与创建一致。 */
    public record Preview(
            Long sourceStationId,
            List<Long> targetStationIds,
            List<Long> faultBikeIds) {
    }

    /**
     * 人工调整某辆车的计划去向（方案生成结果可改）。
     * planStationId 传 null 表示把这辆车改回「暂无处安置」；
     * planPileId 传 null 表示不指定具体桩位，执行时到站自动选空桩。
     */
    public record ItemAdjust(
            Long itemId,
            Long planStationId,
            Long planPileId) {
    }

    /** 部分受阻后补选接收站（执行时这些站也按实时状态重新校验） */
    public record AddTargets(
            List<Long> targetStationIds) {
    }
}
