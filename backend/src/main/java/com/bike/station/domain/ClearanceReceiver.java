package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 清站单接收站：一张清站单选了一个或多个「运营中」接收站。
 * seq 记录运营人员的选择顺序，方案生成只在这些站里做分配。
 */
@Entity
@Table(name = "clearance_receiver")
public class ClearanceReceiver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clearance_id", nullable = false)
    private Long clearanceId;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
