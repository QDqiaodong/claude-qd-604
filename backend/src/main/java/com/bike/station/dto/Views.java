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
            List<PileView> piles) {
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
}
