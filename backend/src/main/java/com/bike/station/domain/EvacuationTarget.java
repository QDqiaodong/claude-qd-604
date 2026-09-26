package com.bike.station.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 清站单接收站。一张清站单可以选多个仍在运营的接收站。
 *
 * distanceKm / faultCapable 都是【创建那一刻的快照】，只用于回显当初的分配依据；
 * 真正执行时，接收站是否仍运营、是否还有在岗且无工单冲突的维修工，
 * 一律以实时数据重新判定。
 */
@Entity
@Table(name = "evacuation_target")
public class EvacuationTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evacuation_id", nullable = false)
    private Long evacuationId;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "distance_km", precision = 8, scale = 2)
    private BigDecimal distanceKm;

    @Column(name = "fault_capable", nullable = false)
    private Boolean faultCapable;

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

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(BigDecimal distanceKm) {
        this.distanceKm = distanceKm;
    }

    public Boolean getFaultCapable() {
        return faultCapable;
    }

    public void setFaultCapable(Boolean faultCapable) {
        this.faultCapable = faultCapable;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
