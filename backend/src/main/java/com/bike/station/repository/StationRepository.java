package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.Station;

import jakarta.persistence.LockModeType;

public interface StationRepository extends JpaRepository<Station, Long> {

    Optional<Station> findByCode(String code);

    boolean existsByCode(String code);

    List<Station> findAllByOrderByCodeAsc();

    List<Station> findByCodeContainingOrNameContainingOrderByCodeAsc(String code, String name);

    long countByStatus(String status);

    /**
     * 【悲观锁】按主键锁 station 表的这一行（SELECT ... FOR UPDATE）。
     *
     * 锁的是「这个站点」这一行：还车、调拨入站、站点停用/启用、改容量都要先锁它，
     * 保证「站点已停用」「站点已满」的判断与随后的写库不会被并发插队，
     * 也让容量（capacity）这条线在并发下仍然成立。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Station s where s.id = :id")
    Optional<Station> findByIdForUpdate(@Param("id") Long id);
}
