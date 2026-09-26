package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 清站车辆明细：一辆待迁车一行，是「绝不半迁移」的落点账本。
 *
 * itemStatus（见 {@link ClearanceDict}）：
 *   PLANNED  已排好计划去向（planStationId 有值）；
 *   UNPLACED 暂时无处安置（接收能力不足），planStationId 为 null，允许保存待执行；
 *   MIGRATED 已成功迁出，actualStationId/actualPileId 记录真实落点，只成功一次；
 *   BLOCKED  执行受阻（车被借走 / 目标桩被占 / 接收站停用…），
 *            车与统计保持原样，留在单内等下次执行，不回滚其它已迁出的车。
 *
 * bikeKind：NORMAL 普通车 / FAULT 故障车（维修中，或挂着未完成工单）。
 */
@Entity
@Table(name = "clearance_item")
public class ClearanceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clearance_id", nullable = false)
    private Long clearanceId;

    @Column(name = "bike_id", nullable = false)
    private Long bikeId;

    @Column(name = "bike_kind", nullable = false, length = 16)
    private String bikeKind;

    @Column(name = "item_status", nullable = false, length = 16)
    private String itemStatus;

    @Column(name = "plan_station_id")
    private Long planStationId;

    @Column(name = "plan_pile_id")
    private Long planPileId;

    @Column(name = "actual_station_id")
    private Long actualStationId;

    @Column(name = "actual_pile_id")
    private Long actualPileId;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "migrated_at")
    private LocalDateTime migratedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getClearanceId() {
        return clearanceId;
    }

    public void setClearanceId(Long clearanceId) {
        this.clearanceId = clearanceId;
    }

    public Long getBikeId() {
        return bikeId;
    }

    public void setBikeId(Long bikeId) {
        this.bikeId = bikeId;
    }

    public String getBikeKind() {
        return bikeKind;
    }

    public void setBikeKind(String bikeKind) {
        this.bikeKind = bikeKind;
    }

    public String getItemStatus() {
        return itemStatus;
    }

    public void setItemStatus(String itemStatus) {
        this.itemStatus = itemStatus;
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

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getMigratedAt() {
        return migratedAt;
    }

    public void setMigratedAt(LocalDateTime migratedAt) {
        this.migratedAt = migratedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
