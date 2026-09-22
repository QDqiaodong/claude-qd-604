package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 运维工单（故障上报 → 派单 → 修复 → 关闭，单向流程）。
 *
 * status：REPORTED → ASSIGNED → FIXED → CLOSED，每一步都不能跳级、不能回退。
 * planStart / planEnd 是维修工的预定上门时段，「同一维修工时段不重叠」这条规则
 * 就是拿这两个字段跟该维修工名下未完工工单比区间。
 *
 * 约定：业务字段不写 Java 默认值，默认值在 WorkOrderService 里赋值。
 */
@Entity
@Table(name = "work_order")
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 32, unique = true)
    private String orderNo;

    @Column(name = "bike_id", nullable = false)
    private Long bikeId;

    @Column(name = "fault_type", nullable = false, length = 32)
    private String faultType;

    @Column(name = "level", nullable = false, length = 16)
    private String level;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "worker_id")
    private Long workerId;

    @Column(name = "plan_start")
    private LocalDateTime planStart;

    @Column(name = "plan_end")
    private LocalDateTime planEnd;

    @Column(name = "report_time", nullable = false)
    private LocalDateTime reportTime;

    @Column(name = "assign_time")
    private LocalDateTime assignTime;

    @Column(name = "fix_time")
    private LocalDateTime fixTime;

    @Column(name = "fix_note", length = 255)
    private String fixNote;

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

    public Long getBikeId() {
        return bikeId;
    }

    public void setBikeId(Long bikeId) {
        this.bikeId = bikeId;
    }

    public String getFaultType() {
        return faultType;
    }

    public void setFaultType(String faultType) {
        this.faultType = faultType;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getWorkerId() {
        return workerId;
    }

    public void setWorkerId(Long workerId) {
        this.workerId = workerId;
    }

    public LocalDateTime getPlanStart() {
        return planStart;
    }

    public void setPlanStart(LocalDateTime planStart) {
        this.planStart = planStart;
    }

    public LocalDateTime getPlanEnd() {
        return planEnd;
    }

    public void setPlanEnd(LocalDateTime planEnd) {
        this.planEnd = planEnd;
    }

    public LocalDateTime getReportTime() {
        return reportTime;
    }

    public void setReportTime(LocalDateTime reportTime) {
        this.reportTime = reportTime;
    }

    public LocalDateTime getAssignTime() {
        return assignTime;
    }

    public void setAssignTime(LocalDateTime assignTime) {
        this.assignTime = assignTime;
    }

    public LocalDateTime getFixTime() {
        return fixTime;
    }

    public void setFixTime(LocalDateTime fixTime) {
        this.fixTime = fixTime;
    }

    public String getFixNote() {
        return fixNote;
    }

    public void setFixNote(String fixNote) {
        this.fixNote = fixNote;
    }
}
