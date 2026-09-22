package com.bike.station.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bike.station.common.BizException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.BikeUser;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Rental;
import com.bike.station.domain.Station;
import com.bike.station.dto.RentalDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.BikeUserRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.RentalRepository;

/**
 * 租还与计费。
 *
 * 规则：
 *  1. 借车 → 还车是单向流程：订单从「骑行中」走到「已结算」，不能回退、不能重复结算；
 *  2. 同一辆车同时只能有一张在途订单（服务层用悲观锁判、数据库层用唯一索引兜底）；
 *  3. 超时长加收：起步价 1.50 元含 60 分钟，超出后每 30 分钟加收 1.00 元（不足 30 分钟按 30 分钟计），单次封顶 20.00 元；
 *  4. 余额不足拦：借车时余额低于起步价直接拒；还车时余额不够付本次费用也拒；
 *  5. 借还都要过「站点停用 / 桩位停用 / 一桩一车 / 站点容量」四道校验。
 */
@Service
public class RentalService {

    private static final DateTimeFormatter ORDER_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final RentalRepository rentalRepository;
    private final BikeRepository bikeRepository;
    private final PileRepository pileRepository;
    private final BikeUserRepository userRepository;
    private final SlotService slotService;
    private final StationService stationService;

    public RentalService(RentalRepository rentalRepository,
                         BikeRepository bikeRepository,
                         PileRepository pileRepository,
                         BikeUserRepository userRepository,
                         SlotService slotService,
                         StationService stationService) {
        this.rentalRepository = rentalRepository;
        this.bikeRepository = bikeRepository;
        this.pileRepository = pileRepository;
        this.userRepository = userRepository;
        this.slotService = slotService;
        this.stationService = stationService;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<Views.RentalView> list(String status) {
        List<Rental> rentals;
        if (status == null || status.isBlank()) {
            rentals = rentalRepository.findAllByOrderByStartTimeDesc();
        } else {
            if (!BikeDict.isValidRentalStatus(status)) {
                throw new BizException("订单状态取值非法：" + status);
            }
            rentals = rentalRepository.findByStatusOrderByStartTimeDesc(status);
        }
        return toViews(rentals);
    }

    public List<Views.RentalView> active() {
        return toViews(rentalRepository.findByStatusOrderByStartTimeDesc(BikeDict.RENTAL_RIDING));
    }

    public Views.RentalView get(Long id) {
        Rental rental = rentalRepository.findById(id)
                .orElseThrow(() -> new BizException("订单不存在（id=" + id + "）"));
        return toView(rental, stationNameMap(), bikeCodeMap(), pileNoMap(), userNicknameMap());
    }

    public List<BikeUser> users() {
        return userRepository.findAll();
    }

    // ------------------------------------------------------------------
    // 借车
    // ------------------------------------------------------------------

    /**
     * 借车。整个方法在一个事务里，并用了四处悲观锁：
     *   站点行（保证站点还开着）→ 桩位行（保证桩位不被并发抢）→
     *   车辆行（同一辆车只能被借走一次）→ 骑行人行（余额判断与不并发）
     * 再锁住该车的在途订单行，确认确实没有在途单。
     */
    @Transactional
    public Views.BorrowResult borrow(RentalDtos.Borrow req) {
        if (req.userId() == null) {
            throw new BizException("借车失败：借车人不能为空");
        }
        if (req.pileId() == null) {
            throw new BizException("借车失败：借车桩位不能为空");
        }

        // 无锁预读：只为拿到站点 id，好按「站点 → 桩位 → 车辆 → 骑行人」的固定顺序上锁
        userRepository.findById(req.userId())
                .orElseThrow(() -> new BizException("借车失败：借车人不存在（id=" + req.userId() + "）"));
        Pile pileProbe = pileRepository.findById(req.pileId())
                .orElseThrow(() -> new BizException("借车失败：桩位不存在（id=" + req.pileId() + "）"));

        Station station = slotService.lockStation(pileProbe.getStationId(), "借车");
        Pile pile = slotService.lockPile(req.pileId(), "借车");
        Bike bike = bikeRepository.findByPileIdForUpdate(pile.getId())
                .orElseThrow(() -> new BizException("借车失败：桩位「" + pile.getPileNo()
                        + "」上当前没有车辆，不能借车"));
        BikeUser user = userRepository.findByIdForUpdate(req.userId())
                .orElseThrow(() -> new BizException("借车失败：借车人不存在（id=" + req.userId() + "）"));
        List<Rental> riding = rentalRepository.findByBikeIdAndStatusForUpdate(
                bike.getId(), BikeDict.RENTAL_RIDING);

        slotService.assertStationOpen(station, "借车");
        slotService.assertPileUsable(pile, "借车");

        if (!BikeDict.BIKE_PILE.equals(bike.getStatus())) {
            throw new BizException("借车失败：车辆 " + bike.getCode() + " 当前状态为「"
                    + BikeDict.bikeStatusName(bike.getStatus()) + "」，只有「在桩」的车辆才能借");
        }
        if (!riding.isEmpty()) {
            throw new BizException("借车失败：车辆 " + bike.getCode() + " 已有一张在途订单 "
                    + riding.get(0).getOrderNo() + "，同一辆车同时只能有一张在途订单");
        }
        if (!BikeDict.USER_NORMAL.equals(user.getStatus())) {
            throw new BizException("借车失败：账号「" + user.getNickname() + "」当前为「"
                    + BikeDict.userStatusName(user.getStatus()) + "」状态，不能借车");
        }
        if (user.getBalance().compareTo(BikeDict.START_PRICE) < 0) {
            throw new BizException("借车失败：余额不足，起步价 " + BikeDict.START_PRICE
                    + " 元，当前余额 " + user.getBalance() + " 元，请先充值");
        }

        LocalDateTime now = LocalDateTime.now();

        // 车离桩：释放桩位、清掉站点与桩位、状态转「骑行中」、累计骑行次数 +1
        slotService.releasePile(bike);
        bike.setStationId(null);
        bike.setPileId(null);
        bike.setStatus(BikeDict.BIKE_RIDING);
        bike.setTotalRides(bike.getTotalRides() == null ? 1 : bike.getTotalRides() + 1);
        bike.setUpdatedAt(now);

        Rental rental = new Rental();
        rental.setOrderNo("R" + now.format(ORDER_NO_FORMAT));
        rental.setUserId(user.getId());
        rental.setBikeId(bike.getId());
        rental.setStartStationId(station.getId());
        rental.setStartPileId(pile.getId());
        rental.setStartTime(now);
        // 默认值统一在 service 赋：新单就是「骑行中」，金额留空等还车时算
        rental.setStatus(BikeDict.RENTAL_RIDING);
        rental.setDurationMinutes(null);
        rental.setAmount(null);
        rental.setCreatedAt(now);
        rentalRepository.save(rental);

        Views.RentalView view = toView(rental, stationNameMap(), bikeCodeMap(), pileNoMap(), userNicknameMap());
        return new Views.BorrowResult(
                "借车成功：" + bike.getCode() + " 已从「" + station.getName() + " / " + pile.getPileNo() + "」骑出",
                view,
                bike.getCode(),
                station.getName(),
                pile.getPileNo(),
                user.getBalance());
    }

    // ------------------------------------------------------------------
    // 还车
    // ------------------------------------------------------------------

    /**
     * 还车。同样在锁里做完整校验：桩位空着、站点开着、车确实在骑行中、余额够付。
     */
    @Transactional
    public Views.ReturnResult returnBike(Long rentalId, RentalDtos.Return req) {
        if (rentalId == null) {
            throw new BizException("还车失败：订单不能为空");
        }
        if (req == null || req.pileId() == null) {
            throw new BizException("还车失败：还车桩位不能为空");
        }

        Rental probe = rentalRepository.findById(rentalId)
                .orElseThrow(() -> new BizException("还车失败：订单不存在（id=" + rentalId + "）"));
        Pile pileProbe = pileRepository.findById(req.pileId())
                .orElseThrow(() -> new BizException("还车失败：还车桩位不存在（id=" + req.pileId() + "）"));

        Station station = slotService.lockStation(pileProbe.getStationId(), "还车");
        Pile pile = slotService.lockPile(req.pileId(), "还车");
        Bike bike = bikeRepository.findByIdForUpdate(probe.getBikeId())
                .orElseThrow(() -> new BizException("还车失败：车辆不存在（id=" + probe.getBikeId() + "）"));
        BikeUser user = userRepository.findByIdForUpdate(probe.getUserId())
                .orElseThrow(() -> new BizException("还车失败：借车人不存在（id=" + probe.getUserId() + "）"));
        Rental rental = rentalRepository.findByIdForUpdate(rentalId)
                .orElseThrow(() -> new BizException("还车失败：订单不存在（id=" + rentalId + "）"));

        if (BikeDict.RENTAL_SETTLED.equals(rental.getStatus())) {
            throw new BizException("还车失败：订单 " + rental.getOrderNo()
                    + " 已经结算过了，还车是单向流程，不能重复还车");
        }
        slotService.assertStationOpen(station, "还车");
        slotService.assertPileUsable(pile, "还车");
        slotService.assertPileFree(pile, "还车");
        slotService.assertStationHasRoom(station, null, "还车");
        if (!BikeDict.BIKE_RIDING.equals(bike.getStatus())) {
            throw new BizException("还车失败：车辆 " + bike.getCode() + " 当前状态为「"
                    + BikeDict.bikeStatusName(bike.getStatus()) + "」，不在骑行中，不能还车");
        }

        LocalDateTime now = LocalDateTime.now();
        long minutes = Duration.between(rental.getStartTime(), now).toMinutes();
        if (minutes < 0) {
            minutes = 0;
        }
        BigDecimal amount = BikeDict.calcAmount(minutes);

        if (user.getBalance().compareTo(amount) < 0) {
            throw new BizException("还车失败：余额不足，本次骑行 " + minutes + " 分钟应付 " + amount
                    + " 元，当前余额 " + user.getBalance() + " 元，请先充值再还车");
        }

        rental.setEndStationId(station.getId());
        rental.setEndPileId(pile.getId());
        rental.setEndTime(now);
        rental.setDurationMinutes((int) minutes);
        rental.setAmount(amount);
        rental.setStatus(BikeDict.RENTAL_SETTLED);

        bike.setStatus(BikeDict.BIKE_PILE);
        slotService.place(bike, station, pile);

        BigDecimal balanceAfter = user.getBalance().subtract(amount);
        user.setBalance(balanceAfter);

        Views.RentalView view = toView(rental, stationNameMap(), bikeCodeMap(), pileNoMap(), userNicknameMap());
        return new Views.ReturnResult(
                "还车成功：" + bike.getCode() + " 已还到「" + station.getName() + " / " + pile.getPileNo()
                        + "」，骑行 " + minutes + " 分钟，结算 " + amount + " 元",
                view,
                bike.getCode(),
                station.getName(),
                pile.getPileNo(),
                (int) minutes,
                amount,
                balanceAfter);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private List<Views.RentalView> toViews(List<Rental> rentals) {
        Map<Long, String> stations = stationNameMap();
        Map<Long, String> bikes = bikeCodeMap();
        Map<Long, String> piles = pileNoMap();
        Map<Long, String> users = userNicknameMap();
        List<Views.RentalView> list = new ArrayList<>(rentals.size());
        for (Rental r : rentals) {
            list.add(toView(r, stations, bikes, piles, users));
        }
        return list;
    }

    private Views.RentalView toView(Rental r,
                                    Map<Long, String> stations,
                                    Map<Long, String> bikes,
                                    Map<Long, String> piles,
                                    Map<Long, String> users) {
        return new Views.RentalView(
                r.getId(),
                r.getOrderNo(),
                r.getUserId(),
                users.get(r.getUserId()),
                r.getBikeId(),
                bikes.get(r.getBikeId()),
                r.getStartStationId(),
                stations.get(r.getStartStationId()),
                r.getStartPileId(),
                piles.get(r.getStartPileId()),
                r.getEndStationId(),
                r.getEndStationId() == null ? null : stations.get(r.getEndStationId()),
                r.getEndPileId(),
                r.getEndPileId() == null ? null : piles.get(r.getEndPileId()),
                r.getStartTime(),
                r.getEndTime(),
                r.getDurationMinutes(),
                r.getAmount(),
                r.getStatus(),
                BikeDict.rentalStatusName(r.getStatus()));
    }

    private Map<Long, String> stationNameMap() {
        return stationService.stationNameMap();
    }

    private Map<Long, String> bikeCodeMap() {
        Map<Long, String> map = new LinkedHashMap<>();
        for (Bike b : bikeRepository.findAll()) {
            map.put(b.getId(), b.getCode());
        }
        return map;
    }

    private Map<Long, String> pileNoMap() {
        Map<Long, String> map = new LinkedHashMap<>();
        for (Pile p : pileRepository.findAll()) {
            map.put(p.getId(), p.getPileNo());
        }
        return map;
    }

    private Map<Long, String> userNicknameMap() {
        Map<Long, String> map = new HashMap<>();
        for (BikeUser u : userRepository.findAll()) {
            map.put(u.getId(), u.getNickname());
        }
        return map;
    }
}
