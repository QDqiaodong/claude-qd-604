package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.ClearanceOrder;

import jakarta.persistence.LockModeType;

public interface ClearanceOrderRepository extends JpaRepository<ClearanceOrder, Long> {

    Optional<ClearanceOrder> findByOrderNo(String orderNo);

    List<ClearanceOrder> findAllByOrderByIdDesc();

    List<ClearanceOrder> findByStatusOrderByIdDesc(String status);

    /** 某源站当前仍在进行中（待执行/执行中/部分受阻）的清站单 */
    List<ClearanceOrder> findBySourceStationIdAndStatusInOrderByIdDesc(Long sourceStationId, List<String> statuses);

    /** 某源站最近一张已完成的清站单（站点看板显示完成结果） */
    Optional<ClearanceOrder> findFirstBySourceStationIdAndStatusOrderByIdDesc(Long sourceStationId, String status);

    long countByStatusIn(List<String> statuses);

    /**
     * 【悲观锁】锁 clearance_order 表的这一行。
     *
     * 清站执行的串行锚点：重复点击、刷新重提、两名运营人员同时点执行，
     * 都先抢这把行锁；拿到锁后再用 executingToken 判定「是不是该我跑」，
     * 保证每辆车最多被迁出一次、已经迁出的结果原样保留。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ClearanceOrder c where c.id = :id")
    Optional<ClearanceOrder> findByIdForUpdate(@Param("id") Long id);
}
