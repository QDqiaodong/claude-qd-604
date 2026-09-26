package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.Pile;

import jakarta.persistence.LockModeType;

public interface PileRepository extends JpaRepository<Pile, Long> {

    List<Pile> findByStationIdOrderByPileNoAsc(Long stationId);

    Optional<Pile> findByStationIdAndPileNo(Long stationId, String pileNo);

    long countByStationId(Long stationId);

    long countByStatus(String status);

    /**
     * 【悲观锁】按主键锁 pile 表的这一行（SELECT ... FOR UPDATE）。
     *
     * 锁的是「这个桩位」这一行：借车、还车、调拨占桩前都要先锁目标桩位，
     * 把「查这个桩位有没有车」和「把车放上去」变成一个串行区间，
     * 否则两个并发请求会同时查到"空桩"然后把两辆车塞进同一个桩位。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pile p where p.id = :id")
    Optional<Pile> findByIdForUpdate(@Param("id") Long id);

    /**
     * 【悲观锁】按桩位号顺序锁住某站点下的全部桩位行。
     *
     * 清站执行分配落点时使用：站点行锁保证「容量」，桩位行锁保证「挑空桩」
     * 与「占位」之间不会被并发还车/调拨插队（顺序加锁也避免互锁）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pile p where p.stationId = :stationId order by p.id asc")
    List<Pile> findByStationIdForUpdate(@Param("stationId") Long stationId);
}
