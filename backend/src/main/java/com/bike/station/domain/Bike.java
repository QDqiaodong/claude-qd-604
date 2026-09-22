package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 车辆台账。
 *
 * 状态机（status）取值见 {@link BikeDict}：
 * PILE 在桩 → RIDING 骑行中 / REPAIR 维修中 / SCRAPPED 报废（终态）。
 *
 * 位置口径：stationId / pileId 只反映「这辆车现在停在哪儿」。
 *   - PILE    ：stationId、pileId 都有值
 *   - REPAIR  ：stationId 有值（放在站点维修区），pileId 为 null（不占桩）
 *   - RIDING  ：两者都为 null
 *   - SCRAPPED：两者都为 null
 * pileId 上带唯一约束，所以「一桩一车」在数据库层面也是硬保证。
 *
 * 约定：本类所有业务字段【都不写 Java 默认值】，默认值一律在 BikeService 里
 * 创建时显式赋值 —— 否则 PUT 局部更新时，未被传入的字段会被刷回默认值。
 */
@Entity
@Table(name = "bike")
public class Bike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 32, unique = true)
    private String code;

    @Column(name = "model", nullable = false, length = 32)
    private String model;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "station_id")
    private Long stationId;

    @Column(name = "pile_id")
    private Long pileId;

    @Column(name = "total_rides", nullable = false)
    private Integer totalRides;

    @Column(name = "scrap_reason", length = 128)
    private String scrapReason;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
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

    public Long getPileId() {
        return pileId;
    }

    public void setPileId(Long pileId) {
        this.pileId = pileId;
    }

    public Integer getTotalRides() {
        return totalRides;
    }

    public void setTotalRides(Integer totalRides) {
        this.totalRides = totalRides;
    }

    public String getScrapReason() {
        return scrapReason;
    }

    public void setScrapReason(String scrapReason) {
        this.scrapReason = scrapReason;
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
