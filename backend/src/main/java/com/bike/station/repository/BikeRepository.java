package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.Bike;

import jakarta.persistence.LockModeType;

public interface BikeRepository extends JpaRepository<Bike, Long> {

    Optional<Bike> findByCode(String code);

    boolean existsByCode(String code);

    List<Bike> findAllByOrderByIdAsc();

    List<Bike> findByStatusOrderByIdAsc(String status);

    List<Bike> findByStationIdOrderByPileIdAsc(Long stationId);

    /** 某辆车当前占用的桩位（一桩一车，所以最多一条） */
    Optional<Bike> findByPileId(Long pileId);

    /** 站点当前停放（含维修区）的车辆数，用于站点容量校验 */
    long countByStationId(Long stationId);

    long countByStatus(String status);

    List<Bike> findByCodeContainingOrderByIdAsc(String keyword);

    /**
     * 【悲观锁】按主键锁 bike 表的这一行（SELECT ... FOR UPDATE）。
     *
     * 锁的是「这辆车」这一行：借车、还车、改状态、报废都要先拿到它的行锁，
     * 保证同一辆车不会被两个并发请求同时改成两个状态。
     * 必须在 @Transactional 里调用，否则锁马上释放。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bike b where b.id = :id")
    Optional<Bike> findByIdForUpdate(@Param("id") Long id);

    /**
     * 【悲观锁】按桩位号锁住停在该桩位上的那辆车的行（SELECT ... FOR UPDATE）。
     *
     * 锁的是「该桩位上那辆车的 bike 行」：借车时用它把车锁住，
     * 同一时间只可能有一个借车请求把 status 从 PILE 改成 RIDING。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bike b where b.pileId = :pileId")
    Optional<Bike> findByPileIdForUpdate(@Param("pileId") Long pileId);
}
