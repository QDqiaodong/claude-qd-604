package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.Rental;

import jakarta.persistence.LockModeType;

public interface RentalRepository extends JpaRepository<Rental, Long> {

    List<Rental> findAllByOrderByStartTimeDesc();

    List<Rental> findByStatusOrderByStartTimeDesc(String status);

    List<Rental> findByUserIdOrderByStartTimeDesc(Long userId);

    long countByStatus(String status);

    long countByBikeIdAndStatus(Long bikeId, String status);

    /** 车辆编号直查用：按订单号精确命中 */
    Optional<Rental> findByOrderNo(String orderNo);

    /** 该车的在途单（非加锁版本，只用于页面展示） */
    Optional<Rental> findFirstByBikeIdAndStatus(Long bikeId, String status);

    /** 从某站点借出、至今未还（在途）的租借单数 —— 清站完成闸门用 */
    long countByStartStationIdAndStatus(Long startStationId, String status);

    /**
     * 【悲观锁】按主键锁 rental 表的这一行（SELECT ... FOR UPDATE）。
     *
     * 锁的是「这张订单」这一行：还车、结算都先锁订单，
     * 保证同一张在途单不会被两个并发请求同时结算两次（重复扣款）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rental r where r.id = :id")
    Optional<Rental> findByIdForUpdate(@Param("id") Long id);

    /**
     * 【悲观锁】锁住「这辆车的在途单」这一行（SELECT ... FOR UPDATE）。
     *
     * 锁的是 rental 表里 bike_id = :bikeId 且 status = 'RIDING' 的那一行：
     * 借车时先锁它再确认结果为空，配合 bike 行锁一起，
     * 让「同一辆车同时只能有一张在途单」在并发下也成立
     * （表上 uk_rental_active_bike 唯一索引是最后一道兜底）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rental r where r.bikeId = :bikeId and r.status = :status")
    List<Rental> findByBikeIdAndStatusForUpdate(@Param("bikeId") Long bikeId,
                                                @Param("status") String status);
}
