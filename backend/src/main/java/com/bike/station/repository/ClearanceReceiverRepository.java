package com.bike.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bike.station.domain.ClearanceReceiver;

public interface ClearanceReceiverRepository extends JpaRepository<ClearanceReceiver, Long> {

    List<ClearanceReceiver> findByClearanceIdOrderBySeqAsc(Long clearanceId);
}
