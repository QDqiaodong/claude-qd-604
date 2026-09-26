package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 应急清站单。
 *
 * 状态机（status）取值见 {@link BikeDict}：
 *   PENDING 待执行 —— 刚建好，一辆车都还没迁出，此时可以撤销 / 调整方案 / 加接收站；
 *   RUNNING 执行中 —— 已经有车辆成功迁出，不能再撤销，可重复执行直到清空；
 *   PARTIAL 部分受阻 —— 最近一次执行后仍有车辆留单（被借走、桩位被占、容量不足等），
 *                       已成功迁出的结果原样保留，可加接收站 / 调方案后继续执行；
 *   DONE 已完成 —— 所有车辆迁出，且源站没有在途租借、未关闭工单或仍占用的桩位，
 *                  源站已在完成事务里被真正停用（终态）；
 *   CANCELLED 已撤销 —— 只允许发生在尚未迁出任何车辆时（终态）。
 *
 * 表上 active_source_id 生成列（PENDING/RUNNING/PARTIAL 时等于源站 id）配唯一索引，
 * 在数据库层面硬保证「同一站点同时只能有一张未完结清站单」，实体不映射该列。
 *
 * 约定：业务字段不写 Java 默认值，默认值在 EvacuationService 里显式赋值。
 */
@Entity
@Table(name = "evacuation_order")
public class EvacuationOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 32, unique = true)
    private String orderNo;

    @Column(name = "source_station_id", nullable = false)
    private Long sourceStationId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "finish_blocker", length = 255)
    private String finishBlocker;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getSourceStationId() {
        return sourceStationId;
    }

    public void setSourceStationId(Long sourceStationId) {
        this.sourceStationId = sourceStationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getFinishBlocker() {
        return finishBlocker;
    }

    public void setFinishBlocker(String finishBlocker) {
        this.finishBlocker = finishBlocker;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
}
