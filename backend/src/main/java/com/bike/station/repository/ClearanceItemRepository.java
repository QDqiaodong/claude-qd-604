package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.ClearanceItem;

import jakarta.persistence.LockModeType;

public interface ClearanceItemRepository extends JpaRepository<ClearanceItem, Long> {

    List<ClearanceItem> findByClearanceIdOrderByIdAsc(Long clearanceId);

    Optional<ClearanceItem> findByClearanceIdAndBikeId(Long clearanceId, Long bikeId);

    long countByClearanceIdAndItemStatus(Long clearanceId, String itemStatus);

    /**
     * 【悲观锁】锁住该清站单下指定状态的明细行（执行时逐辆加锁，配合 bike 行锁）。
     * 排序保证 bike 加锁顺序稳定，避免两个执行事务交叉等锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ClearanceItem i where i.clearanceId = :clearanceId and i.itemStatus in :statuses order by i.bikeId asc")
    List<ClearanceItem> findByClearanceIdAndItemStatusInForUpdate(@Param("clearanceId") Long clearanceId,
                                                                  @Param("statuses") List<String> statuses);

    /** 【悲观锁】锁单条明细（人工调整计划去向时用） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ClearanceItem i where i.id = :id")
    Optional<ClearanceItem> findByIdForUpdate(@Param("id") Long id);
}
