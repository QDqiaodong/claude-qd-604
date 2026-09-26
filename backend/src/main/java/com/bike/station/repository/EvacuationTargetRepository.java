package com.bike.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bike.station.domain.EvacuationTarget;

public interface EvacuationTargetRepository extends JpaRepository<EvacuationTarget, Long> {

    List<EvacuationTarget> findByEvacuationIdOrderByIdAsc(Long evacuationId);

    boolean existsByEvacuationIdAndStationId(Long evacuationId, Long stationId);
}
