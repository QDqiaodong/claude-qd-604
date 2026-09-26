package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 清站单车辆明细：源站每一辆【在桩】车在创建时固化成一行。
 *
 * itemStatus（见 {@link BikeDict}）：
 *   PLANNED 已计划 —— 等待执行；planStationId 为 null 表示「暂时无处安置」，
 *                     此时单据仍可保存为待执行，但绝不允许把源站标成已清空；
 *   MOVED   已迁出 —— actualStationId / actualPileId / movedAt 在迁出成功的事务里
 *                     一次性写定，任何重复执行、并发执行都只能读到它、不能改它；
 *   BLOCKED 受阻   —— 方案生成后车辆被借走、目标桩位被占、接收站停用、容量满等，
 *                     失败原因写在 failReason，车留在清站单里等下一次执行。
 *
 * faultFlag 为 true 表示按故障车分配（优先送有在岗维修工且无未完工工单冲突的站点）；
 * 创建时由「该车有没有未关闭工单」自动判定，允许人工调整。
 */
@Entity
@Table(name = "evacuation_item")
public class EvacuationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evacuation_id", nullable = false)
    private Long evacuationId;

    @Column(name = "bike_id", nullable = false)
    private Long bikeId;

    @Column(name = "bike_code", nullable = false, length = 32)
    private String bikeCode;

    @Column(name = "fault_flag", nullable = false)
    private Boolean faultFlag;

    @Column(name = "plan_station_id")
    private Long planStationId;

    @Column(name = "plan_pile_id")
    private Long planPileId;

    @Column(name = "actual_station_id")
    private Long actualStationId;

    @Column(name = "actual_pile_id")
    private Long actualPileId;

    @Column(name = "item_status", nullable = false, length = 16)
    private String itemStatus;

    @Column(name = "manual_flag", nullable = false)
    private Boolean manualFlag;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "moved_at")
    private LocalDateTime movedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEvacuationId() {
        return evacuationId;
    }

    public void setEvacuationId(Long evacuationId) {
        this.evacuationId = evacuationId;
    }

    public Long getBikeId() {
        return bikeId;
    }

    public void setBikeId(Long bikeId) {
        this.bikeId = bikeId;
    }

    public String getBikeCode() {
        return bikeCode;
    }

    public void setBikeCode(String bikeCode) {
        this.bikeCode = bikeCode;
    }

    public Boolean getFaultFlag() {
        return faultFlag;
    }

    public void setFaultFlag(Boolean faultFlag) {
        this.faultFlag = faultFlag;
    }

    public Long getPlanStationId() {
        return planStationId;
    }

    public void setPlanStationId(Long planStationId) {
        this.planStationId = planStationId;
    }

    public Long getPlanPileId() {
        return planPileId;
    }

    public void setPlanPileId(Long planPileId) {
        this.planPileId = planPileId;
    }

    public Long getActualStationId() {
        return actualStationId;
    }

    public void setActualStationId(Long actualStationId) {
        this.actualStationId = actualStationId;
    }

    public Long getActualPileId() {
        return actualPileId;
    }

    public void setActualPileId(Long actualPileId) {
        this.actualPileId = actualPileId;
    }

    public String getItemStatus() {
        return itemStatus;
    }

    public void setItemStatus(String itemStatus) {
        this.itemStatus = itemStatus;
    }

    public Boolean getManualFlag() {
        return manualFlag;
    }

    public void setManualFlag(Boolean manualFlag) {
        this.manualFlag = manualFlag;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getMovedAt() {
        return movedAt;
    }

    public void setMovedAt(LocalDateTime movedAt) {
        this.movedAt = movedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
