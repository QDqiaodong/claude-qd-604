package com.bike.station.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 运维维修工。
 *
 * status：ON_DUTY 在岗 / OFF_DUTY 休息 —— 休息中的维修工不允许被派单。
 *
 * 约定：业务字段不写 Java 默认值，默认值由调用方显式赋值。
 */
@Entity
@Table(name = "worker")
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 32)
    private String name;

    @Column(name = "phone", nullable = false, length = 20, unique = true)
    private String phone;

    @Column(name = "skill", nullable = false, length = 32)
    private String skill;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /**
     * 驻站站点（可为 null = 无固定驻站）。
     * 应急清站据此判断哪些「运营中」站点具备接收故障车的能力：
     * 站点下至少有一名在岗（ON_DUTY）且名下没有未完成工单的维修工。
     */
    @Column(name = "station_id")
    private Long stationId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }
}
