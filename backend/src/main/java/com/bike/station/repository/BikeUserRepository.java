package com.bike.station.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.BikeUser;

import jakarta.persistence.LockModeType;

public interface BikeUserRepository extends JpaRepository<BikeUser, Long> {

    Optional<BikeUser> findByPhone(String phone);

    /**
     * 【悲观锁】按主键锁 bike_user 表的这一行（SELECT ... FOR UPDATE）。
     *
     * 锁的是「这个骑行人」这一行：借车时校验余额、还车时扣款都要先锁它，
     * 保证同一账户的「余额够不够」判断和「扣款」之间不会被另一个请求插队
     * （否则余额 10 元能并发借出两单各扣 8 元，把余额刷成负数）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from BikeUser u where u.id = :id")
    Optional<BikeUser> findByIdForUpdate(@Param("id") Long id);
}
