package com.bike.station.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。统一把「外键 id」翻译成人能看懂的编号与名称，
 * 前端拿到的就是可以直接铺在卡片上的数据，不用再自己拼。
 */
public final class Views {

    private Views() {
    }

    /** 车辆台账（带站点/桩位编号，便于卡片直接显示） */
    public record BikeView(
            Long id,
            String code,
            String model,
            String status,
            String statusName,
            Long stationId,
            String stationCode,
            String stationName,
            Long pileId,
            String pileNo,
            Integer totalRides,
            String scrapReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 桩位格子里的一格：空着就只有状态，有车就带上车的编号与车型 */
    public record PileView(
            Long id,
            String pileNo,
            String status,
            String statusName,
            Long bikeId,
            String bikeCode,
            String bikeModel) {
    }

    /** 站点卡片：给「站点卡片 + 拖拽调拨」用 */
    public record StationCard(
            Long id,
            String code,
            String name,
            String district,
            Integer capacity,
            String status,
            String statusName,
            long bikeCount,
            long freePileCount,
            List<PileView> piles,
            String evacStatus,
            String evacStatusName,
            String evacOrderNo,
            long evacTotal,
            long evacMoved,
            long evacBlocked,
            String lastEvacOrderNo,
            boolean clearedByEvac) {
    }

    /** 租借订单 */
    public record RentalView(
            Long id,
            String orderNo,
            Long userId,
            String userNickname,
            Long bikeId,
            String bikeCode,
            Long startStationId,
            String startStationName,
            Long startPileId,
            String startPileNo,
            Long endStationId,
            String endStationName,
            Long endPileId,
            String endPileNo,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Integer durationMinutes,
            BigDecimal amount,
            String status,
            String statusName) {
    }

    /** 借车结果 */
    public record BorrowResult(
            String message,
            RentalView rental,
            String bikeCode,
            String stationName,
            String pileNo,
            BigDecimal balance) {
    }

    /** 还车结果（含计费明细，前端用它弹结算单） */
    public record ReturnResult(
            String message,
            RentalView rental,
            String bikeCode,
            String stationName,
            String pileNo,
            Integer durationMinutes,
            BigDecimal amount,
            BigDecimal balanceAfter) {
    }

    /** 运维工单 */
    public record WorkOrderView(
            Long id,
            String orderNo,
            Long bikeId,
            String bikeCode,
            String faultType,
            String level,
            String levelName,
            String status,
            String statusName,
            Long workerId,
            String workerName,
            LocalDateTime planStart,
            LocalDateTime planEnd,
            LocalDateTime reportTime,
            LocalDateTime assignTime,
            LocalDateTime fixTime,
            String fixNote) {
    }

    /** 调度结果（拖拽调拨返回这个，前端用来弹提示） */
    public record TransferResult(
            String message,
            Long bikeId,
            String bikeCode,
            String fromStationName,
            String fromPileNo,
            String toStationName,
            String toPileNo) {
    }

    /** 顶部概览数字 */
    public record Overview(
            long bikeTotal,
            long bikePile,
            long bikeRiding,
            long bikeRepair,
            long bikeScrapped,
            long stationTotal,
            long stationOpen,
            long pileTotal,
            long pileIdle,
            long pileOccupied,
            long rentalRiding,
            long rentalSettled,
            long orderReported,
            long orderAssigned) {
    }

    /**
     * 创建清站单时的接收站实时能力：站点看板/创建向导用它显示
     * 「实时剩余容量、可用桩位、是否有在岗且无工单冲突的维修工、距源站多远」。
     */
    public record ReceiverOption(
            Long stationId,
            String stationCode,
            String stationName,
            String district,
            Integer capacity,
            long bikeCount,
            long remainCapacity,
            long freePileCount,
            boolean faultCapable,
            String workerSummary,
            java.math.BigDecimal distanceKm) {
    }

    /** 清站单列表行 */
    public record EvacuationListItem(
            Long id,
            String orderNo,
            Long sourceStationId,
            String sourceStationCode,
            String sourceStationName,
            String sourceStationStatus,
            String status,
            String statusName,
            int totalCount,
            int movedCount,
            int blockedCount,
            int unplacedCount,
            String finishBlocker,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime executedAt,
            java.time.LocalDateTime finishedAt) {
    }

    /** 清站单里的一辆待迁车：计划去向、实际去向、失败原因都在这里 */
    public record EvacuationItemView(
            Long itemId,
            Long bikeId,
            String bikeCode,
            String bikeModel,
            String bikeStatus,
            String bikeStatusName,
            boolean faultFlag,
            Long planStationId,
            String planStationName,
            Long planPileId,
            String planPileNo,
            Long actualStationId,
            String actualStationName,
            Long actualPileId,
            String actualPileNo,
            String itemStatus,
            String itemStatusName,
            String failReason,
            java.time.LocalDateTime movedAt) {
    }

    /** 清站单里的一个接收站（创建时快照 + 当前实时能力） */
    public record EvacuationTargetView(
            Long stationId,
            String stationCode,
            String stationName,
            java.math.BigDecimal distanceKm,
            boolean faultCapableSnapshot,
            boolean currentlyOpen,
            boolean currentlyFaultCapable,
            long remainCapacity,
            long freePileCount,
            long plannedIn,
            long movedIn) {
    }

    /** 清站单详情 */
    public record EvacuationDetail(
            Long id,
            String orderNo,
            Long sourceStationId,
            String sourceStationCode,
            String sourceStationName,
            String sourceStationStatus,
            String status,
            String statusName,
            String remark,
            String finishBlocker,
            int totalCount,
            int movedCount,
            int blockedCount,
            int plannedCount,
            int unplacedCount,
            long sourceBikeCount,
            long sourceActiveRentals,
            long sourceUnclosedOrders,
            long sourceOccupiedPiles,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime startedAt,
            java.time.LocalDateTime executedAt,
            java.time.LocalDateTime finishedAt,
            java.time.LocalDateTime cancelledAt,
            List<EvacuationTargetView> targets,
            List<EvacuationItemView> items,
            List<ReceiverOption> availableReceivers) {
    }

    /**
     * 创建向导的方案预览（不落库）：源站待迁车辆 + 自动生成的计划去向 +
     * 所选接收站的实时能力，运营人员在第三步据此人工调整后再真正创建。
     */
    public record EvacuationPlanPreview(
            Long sourceStationId,
            String sourceStationCode,
            String sourceStationName,
            int totalCount,
            int plannedCount,
            int unplacedCount,
            int faultCount,
            List<EvacuationItemView> items,
            List<EvacuationTargetView> targets) {
    }

    /** 执行结果：本轮每辆车的处置 + 单的最新状态（重复执行拿到的就是最新进度） */
    public record EvacuationExecuteResult(            String message,
            String orderNo,
            String status,
            String statusName,
            int movedThisRound,
            int blockedThisRound,
            int movedCount,
            int blockedCount,
            int plannedCount,
            boolean sourceClosed,
            String finishBlocker,
            List<EvacuationItemView> items) {
    }
}
