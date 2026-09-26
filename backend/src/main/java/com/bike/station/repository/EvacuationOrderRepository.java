package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.EvacuationOrder;

import jakarta.persistence.LockModeType;

public interface EvacuationOrderRepository extends JpaRepository<EvacuationOrder, Long> {

    Optional<EvacuationOrder> findByOrderNo(String orderNo);

    List<EvacuationOrder> findAllByOrderByCreatedAtDesc();

    List<EvacuationOrder> findByStatusOrderByCreatedAtDesc(String status);

    List<EvacuationOrder> findBySourceStationIdOrderByCreatedAtDesc(Long sourceStationId);

    /**
     * 某源站当前是否已有未完结（PENDING/RUNNING/PARTIAL）的清站单。
     * 真正的硬约束在表上的 uk_evac_active_source 唯一索引（active_source_id 生成列），
     * 这里只是提前给出中文业务提示。
     */
    @Query("select e from EvacuationOrder e where e.sourceStationId = :stationId "
            + "and e.status in ('PENDING', 'RUNNING', 'PARTIAL')")
    Optional<EvacuationOrder> findActiveBySourceStationId(@Param("stationId") Long stationId);

    /**
     * 【悲观锁】锁清站单这一行：执行 / 撤销 / 调整方案都先锁它。
     * 重复点击执行、刷新重提、两人同时执行时，只有一个请求能进临界区，
     * 其他请求拿到锁后读到的就是最新进度，直接按最新状态返回，不会重复迁车。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EvacuationOrder e where e.id = :id")
    Optional<EvacuationOrder> findByIdForUpdate(@Param("id") Long id);
}
