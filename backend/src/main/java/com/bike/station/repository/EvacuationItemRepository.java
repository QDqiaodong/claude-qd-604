package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.EvacuationItem;

import jakarta.persistence.LockModeType;

public interface EvacuationItemRepository extends JpaRepository<EvacuationItem, Long> {

    List<EvacuationItem> findByEvacuationIdOrderByIdAsc(Long evacuationId);

    long countByEvacuationIdAndItemStatus(Long evacuationId, String itemStatus);

    Optional<EvacuationItem> findByIdAndEvacuationId(Long id, Long evacuationId);

    /**
     * 【悲观锁】某张清站单里「还没成功迁出」的明细行（已计划 + 受阻）。
     * 排序就是执行顺序：故障车在前（优先安顿），其余按创建先后。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from EvacuationItem i where i.evacuationId = :evacuationId "
            + "and i.itemStatus in ('PLANNED', 'BLOCKED') "
            + "order by case when i.faultFlag = true then 0 else 1 end, i.id asc")
    List<EvacuationItem> findPendingForUpdate(@Param("evacuationId") Long evacuationId);

    /** 同车是否已挂在某张【未完结】清站单里（被借走又还回来的车也不能被重复收进新单） */
    @Query("select count(i) from EvacuationItem i join EvacuationOrder e on i.evacuationId = e.id "
            + "where i.bikeId = :bikeId and e.status in ('PENDING', 'RUNNING', 'PARTIAL')")
    long countActiveByBikeId(@Param("bikeId") Long bikeId);
}
