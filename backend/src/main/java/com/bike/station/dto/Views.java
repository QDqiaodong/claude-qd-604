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
            /** 正在清站（待执行/执行中/部分受阻）时非空，站点看板显示「正在清站 · 受阻 N」 */
            ClearanceBadge activeClearance,
            /** 最近一次清站完成结果（已停用站点展示「清站完成」） */
            ClearanceBadge lastClearance) {
    }

    /** 站点卡片上的清站角标 */
    public record ClearanceBadge(
            Long clearanceId,
            String orderNo,
            String status,
            String statusName,
            Integer migratedCount,
            Integer totalCount,
            Integer blockedCount,
            LocalDateTime completedTime) {
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
            long orderAssigned,
            /** 正在清站（待执行/执行中/部分受阻）的清站单数 */
            long clearanceActive,
            /** 其中「部分受阻」的清站单数 */
            long clearanceBlocked) {
    }

    // ------------------------------------------------------------------
    // 模块五：应急清站
    // ------------------------------------------------------------------

    /** 清站方案里的接收站实时信息（容量/空桩/维修工接收能力） */
    public record ClearanceStationInfo(
            Long stationId,
            String stationCode,
            String stationName,
            String stationStatus,
            Integer capacity,
            long bikeCount,
            long freePileCount,
            /** 是否具备故障车接收能力：有在岗维修工、且这些维修工名下都没有未完成工单 */
            boolean faultCapable,
            /** 具备接收能力的在岗维修工姓名（故障车优先分配时展示依据） */
            List<String> freeWorkerNames,
            /** 不具备能力的原因（在岗维修工都有未完成工单 / 没有在岗维修工） */
            String faultBlockReason,
            /** 距源站直线距离（公里），无法定位时为 null */
            Double distanceKm,
            Integer seq) {
    }

    /** 清站方案里的一辆待迁车（含计划去向与无处安置原因） */
    public record ClearanceBikeView(
            Long itemId,
            Long bikeId,
            String bikeCode,
            String bikeModel,
            String bikeStatus,
            String bikeStatusName,
            String bikeKind,
            String bikeKindName,
            String itemStatus,
            String itemStatusName,
            Long planStationId,
            String planStationName,
            Long planPileId,
            String planPileNo,
            Long actualStationId,
            String actualStationName,
            Long actualPileId,
            String actualPileNo,
            String failReason,
            LocalDateTime migratedAt) {
    }

    /** 清站单列表项 */
    public record ClearanceListItem(
            Long id,
            String orderNo,
            Long sourceStationId,
            String sourceStationCode,
            String sourceStationName,
            String sourceStationStatus,
            String status,
            String statusName,
            int totalCount,
            int plannedCount,
            int migratedCount,
            int blockedCount,
            String planRemark,
            String lastExecuteResult,
            LocalDateTime lastExecuteTime,
            LocalDateTime completedTime,
            LocalDateTime cancelledTime,
            LocalDateTime createdAt,
            List<String> receiverStationNames) {
    }

    /** 清站单详情（含全部车辆明细与接收站实时容量） */
    public record ClearanceOrderView(
            Long id,
            String orderNo,
            Long sourceStationId,
            String sourceStationCode,
            String sourceStationName,
            String sourceStationStatus,
            String status,
            String statusName,
            int totalCount,
            int plannedCount,
            int migratedCount,
            int blockedCount,
            String planRemark,
            String lastExecuteResult,
            LocalDateTime executingAt,
            LocalDateTime lastExecuteTime,
            LocalDateTime completedTime,
            LocalDateTime cancelledTime,
            LocalDateTime createdAt,
            List<ClearanceStationInfo> receivers,
            List<ClearanceBikeView> items,
            /** 完成闸门的实时尾巴：在站车辆数 / 在途租借数 / 未关闭工单数 / 占用桩位数 */
            ClearanceGate gate) {
    }

    /** 建单前的方案预览（不落库） */
    public record ClearancePreviewView(
            Long sourceStationId,
            String sourceStationCode,
            String sourceStationName,
            int waitingCount,
            int plannedCount,
            int unplacedCount,
            String planRemark,
            List<ClearanceStationInfo> receivers,
            List<ClearanceBikeView> items) {
    }

    /** 清站完成闸门的四类尾巴，任一不为 0 都不能完成、不能停用源站 */
    public record ClearanceGate(
            long bikesAtStation,
            long ridingRentals,
            long openWorkOrders,
            long occupiedPiles) {
    }
}
