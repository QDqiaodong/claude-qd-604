package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.Worker;

import jakarta.persistence.LockModeType;

public interface WorkerRepository extends JpaRepository<Worker, Long> {

    List<Worker> findAllByOrderByIdAsc();

    List<Worker> findByStatusOrderByIdAsc(String status);

    /** 某站点的全部驻站维修工 */
    List<Worker> findByStationIdOrderByIdAsc(Long stationId);

    /** 某站点在岗的驻站维修工 */
    List<Worker> findByStationIdAndStatusOrderByIdAsc(Long stationId, String status);

    /**
     * 【悲观锁】按主键锁 worker 表的这一行（SELECT ... FOR UPDATE）。
     *
     * 锁的是「这个维修工」这一行：派单前先锁他，
     * 把「查他名下未完工工单的时段」和「把这单派给他」变成串行区间，
     * 否则两个并发派单会同时查到"没有冲突"然后把两个重叠时段派给同一个人。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Worker w where w.id = :id")
    Optional<Worker> findByIdForUpdate(@Param("id") Long id);
}
