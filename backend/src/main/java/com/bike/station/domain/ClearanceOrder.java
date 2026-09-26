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
 * 状态机（status）取值见 {@link ClearanceDict}：
 *   PENDING 待执行 → RUNNING 执行中 → COMPLETED 已完成
 *                                    ↘ PARTIALLY_BLOCKED 部分受阻（可反复执行推进）
 *   PENDING → CANCELLED 已撤销（仅在尚未迁出任何车辆时允许）。
 *
 * 口径：
 *  - totalCount   ：建单瞬间源站待迁车辆数（含暂时无处可去的车），之后不再变；
 *  - migratedCount：已成功迁出数；只有它等于 totalCount 且源站四类尾巴
 *                   （在站车辆 / 在途租借 / 未关闭工单 / 占用桩位）全清零，
 *                   才能 COMPLETED 并真正停用源站；
 *  - executingToken/executingAt 是幂等串行锚点：同一时间只有一个执行请求在跑，
 *    重复点击 / 刷新重提 / 双人同点，其他人拿到的都是最新进度。
 */
@Entity
@Table(name = "clearance_order")
public class ClearanceOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 32, unique = true)
    private String orderNo;

    @Column(name = "source_station_id", nullable = false)
    private Long sourceStationId;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    @Column(name = "planned_count", nullable = false)
    private Integer plannedCount;

    @Column(name = "migrated_count", nullable = false)
    private Integer migratedCount;

    @Column(name = "blocked_count", nullable = false)
    private Integer blockedCount;

    @Column(name = "plan_remark", length = 255)
    private String planRemark;

    @Column(name = "executing_token", length = 64)
    private String executingToken;

    @Column(name = "executing_at")
    private LocalDateTime executingAt;

    @Column(name = "last_execute_time")
    private LocalDateTime lastExecuteTime;

    @Column(name = "last_execute_result", length = 255)
    private String lastExecuteResult;

    @Column(name = "cancelled_time")
    private LocalDateTime cancelledTime;

    @Column(name = "completed_time")
    private LocalDateTime completedTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getPlannedCount() {
        return plannedCount;
    }

    public void setPlannedCount(Integer plannedCount) {
        this.plannedCount = plannedCount;
    }

    public Integer getMigratedCount() {
        return migratedCount;
    }

    public void setMigratedCount(Integer migratedCount) {
        this.migratedCount = migratedCount;
    }

    public Integer getBlockedCount() {
        return blockedCount;
    }

    public void setBlockedCount(Integer blockedCount) {
        this.blockedCount = blockedCount;
    }

    public String getPlanRemark() {
        return planRemark;
    }

    public void setPlanRemark(String planRemark) {
        this.planRemark = planRemark;
    }

    public String getExecutingToken() {
        return executingToken;
    }

    public void setExecutingToken(String executingToken) {
        this.executingToken = executingToken;
    }

    public LocalDateTime getExecutingAt() {
        return executingAt;
    }

    public void setExecutingAt(LocalDateTime executingAt) {
        this.executingAt = executingAt;
    }

    public LocalDateTime getLastExecuteTime() {
        return lastExecuteTime;
    }

    public void setLastExecuteTime(LocalDateTime lastExecuteTime) {
        this.lastExecuteTime = lastExecuteTime;
    }

    public String getLastExecuteResult() {
        return lastExecuteResult;
    }

    public void setLastExecuteResult(String lastExecuteResult) {
        this.lastExecuteResult = lastExecuteResult;
    }

    public LocalDateTime getCancelledTime() {
        return cancelledTime;
    }

    public void setCancelledTime(LocalDateTime cancelledTime) {
        this.cancelledTime = cancelledTime;
    }

    public LocalDateTime getCompletedTime() {
        return completedTime;
    }

    public void setCompletedTime(LocalDateTime completedTime) {
        this.completedTime = completedTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
