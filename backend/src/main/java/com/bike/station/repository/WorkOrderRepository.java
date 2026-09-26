package com.bike.station.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.WorkOrder;

import jakarta.persistence.LockModeType;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    List<WorkOrder> findAllByOrderByReportTimeDesc();

    List<WorkOrder> findByStatusOrderByReportTimeDesc(String status);

    Optional<WorkOrder> findByOrderNo(String orderNo);

    long countByBikeIdAndStatusIn(Long bikeId, Collection<String> statuses);

    long countByStatus(String status);

    /** 某维修工名下未完工工单数（应急清站：判定站点「有没有未完成工单冲突」） */
    long countByWorkerIdAndStatusIn(Long workerId, Collection<String> statuses);

    /**
     * 某一批车辆当前挂着未完工工单（REPORTED / ASSIGNED）的车辆 id。
     * 应急清站据此把车分成「故障车 / 普通车」。
     */
    @Query("select distinct o.bikeId from WorkOrder o where o.bikeId in :bikeIds and o.status in :statuses")
    List<Long> findFaultBikeIds(@Param("bikeIds") Collection<Long> bikeIds,
                                @Param("statuses") Collection<String> statuses);

    /**
     * 某一批车辆当前仍未关闭（未到 CLOSED）工单的车辆 id —— 清站完成闸门用。
     */
    @Query("select distinct o.bikeId from WorkOrder o where o.bikeId in :bikeIds and o.status <> 'CLOSED'")
    List<Long> findOpenBikeIds(@Param("bikeIds") Collection<Long> bikeIds);

    /**
     * 【悲观锁】按主键锁 work_order 表的这一行（SELECT ... FOR UPDATE）。
     *
     * 锁的是「这张工单」这一行：派单、修复、关闭都先锁它，
     * 保证一张工单不会被并发地派给两个维修工、或跳级改成两个状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from WorkOrder o where o.id = :id")
    Optional<WorkOrder> findByIdForUpdate(@Param("id") Long id);

    /**
     * 【悲观锁】锁住「该维修工名下、时段与 [start, end) 相交且未完工的工单」这些行。
     *
     * 锁的是 work_order 表里 worker_id = :workerId、status in :statuses、
     * 且 plan_start < :end 且 plan_end > :start 的那些行。
     * 一个维修工最多同时只被一张工单占住一段时间，这就是「时段不重叠」的判据。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from WorkOrder o where o.workerId = :workerId and o.status in :statuses "
            + "and o.planStart < :end and o.planEnd > :start")
    List<WorkOrder> findOverlappingForUpdate(@Param("workerId") Long workerId,
                                             @Param("statuses") Collection<String> statuses,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);
}
