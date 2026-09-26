package com.bike.station.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bike.station.common.BizException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.ClearanceDict;
import com.bike.station.domain.ClearanceOrder;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Station;
import com.bike.station.dto.StationDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.ClearanceOrderRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.StationRepository;

/**
 * 站点与桩位。
 *
 * 规则：
 *  1. 站点容量：站内车辆数（含维修区）不得超过 capacity，桩位数也不得超过 capacity；
 *  2. 一桩一车：桩位上已经有车就不能再放第二辆；
 *  3. 站点停用：CLOSED 站点不能借车、不能还车、不能把车调拨进来；
 *  4. 桩位停用：DISABLED 桩位不能被借还或调拨占用；
 *  5. 调拨 = 把车从它现在的桩位摘下来，放到目标桩位上（同一个事务，失败整体回滚）。
 */
@Service
public class StationService {

    private final StationRepository stationRepository;
    private final PileRepository pileRepository;
    private final BikeRepository bikeRepository;
    private final ClearanceOrderRepository clearanceOrderRepository;
    private final SlotService slotService;

    public StationService(StationRepository stationRepository,
                          PileRepository pileRepository,
                          BikeRepository bikeRepository,
                          ClearanceOrderRepository clearanceOrderRepository,
                          SlotService slotService) {
        this.stationRepository = stationRepository;
        this.pileRepository = pileRepository;
        this.bikeRepository = bikeRepository;
        this.clearanceOrderRepository = clearanceOrderRepository;
        this.slotService = slotService;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 站点卡片列表：站点卡里带桩位格，桩位格里带车。
     * 前端「站点卡片 + 拖拽调拨」用的就是这个结构。
     */
    public List<Views.StationCard> cards(String keyword) {
        String kw = keyword == null ? null : keyword.trim().toUpperCase();
        List<Station> stations = stationRepository.findAllByOrderByCodeAsc();
        if (kw != null && !kw.isEmpty()) {
            stations = stations.stream()
                    .filter(s -> s.getCode().toUpperCase().contains(kw)
                            || s.getName().toUpperCase().contains(kw)
                            || s.getDistrict().toUpperCase().contains(kw))
                    .collect(Collectors.toList());
        }

        Map<Long, Bike> bikeByPile = new HashMap<>();
        Map<Long, Long> bikeCountByStation = new HashMap<>();
        for (Bike b : bikeRepository.findAll()) {
            if (b.getPileId() != null) {
                bikeByPile.put(b.getPileId(), b);
            }
            if (b.getStationId() != null) {
                bikeCountByStation.merge(b.getStationId(), 1L, Long::sum);
            }
        }

        Map<Long, List<Pile>> pilesByStation = pileRepository.findAll().stream()
                .collect(Collectors.groupingBy(Pile::getStationId));

        // 清站角标：进行中的单显示「正在清站 · 受阻 N」；最近完成的单显示完成结果
        Map<Long, Views.ClearanceBadge> activeBadge = new HashMap<>();
        Map<Long, Views.ClearanceBadge> lastBadge = new HashMap<>();
        for (ClearanceOrder co : clearanceOrderRepository.findAllByOrderByIdDesc()) {
            if (ClearanceDict.isActive(co.getStatus())) {
                activeBadge.putIfAbsent(co.getSourceStationId(), badgeOf(co));
            } else if (ClearanceDict.ORDER_COMPLETED.equals(co.getStatus())) {
                lastBadge.putIfAbsent(co.getSourceStationId(), badgeOf(co));
            }
        }

        List<Views.StationCard> cards = new ArrayList<>(stations.size());
        for (Station s : stations) {
            List<Pile> piles = pilesByStation.getOrDefault(s.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Pile::getPileNo))
                    .collect(Collectors.toList());
            List<Views.PileView> pileViews = new ArrayList<>(piles.size());
            long free = 0;
            for (Pile p : piles) {
                Bike b = bikeByPile.get(p.getId());
                if (b == null && BikeDict.PILE_IDLE.equals(p.getStatus())) {
                    free++;
                }
                pileViews.add(new Views.PileView(
                        p.getId(),
                        p.getPileNo(),
                        p.getStatus(),
                        BikeDict.pileStatusName(p.getStatus()),
                        b == null ? null : b.getId(),
                        b == null ? null : b.getCode(),
                        b == null ? null : b.getModel()));
            }
            cards.add(new Views.StationCard(
                    s.getId(),
                    s.getCode(),
                    s.getName(),
                    s.getDistrict(),
                    s.getCapacity(),
                    s.getStatus(),
                    BikeDict.stationStatusName(s.getStatus()),
                    bikeCountByStation.getOrDefault(s.getId(), 0L),
                    free,
                    pileViews,
                    activeBadge.get(s.getId()),
                    lastBadge.get(s.getId())));
        }
        return cards;
    }

    private Views.ClearanceBadge badgeOf(ClearanceOrder co) {
        return new Views.ClearanceBadge(
                co.getId(),
                co.getOrderNo(),
                co.getStatus(),
                ClearanceDict.orderStatusName(co.getStatus()),
                co.getMigratedCount(),
                co.getTotalCount(),
                co.getBlockedCount(),
                co.getCompletedTime());
    }

    public List<Views.PileView> piles(Long stationId) {
        return cards(null).stream()
                .filter(c -> c.id().equals(stationId))
                .findFirst()
                .map(Views.StationCard::piles)
                .orElseThrow(() -> new BizException("站点不存在（id=" + stationId + "）"));
    }

    // ------------------------------------------------------------------
    // 站点增改
    // ------------------------------------------------------------------

    @Transactional
    public Views.StationCard createStation(StationDtos.StationCreate req) {
        String code = req.code() == null ? "" : req.code().trim().toUpperCase();
        String name = req.name() == null ? "" : req.name().trim();
        String district = req.district() == null ? "" : req.district().trim();
        if (code.isEmpty()) {
            throw new BizException("新增站点失败：站点编号不能为空");
        }
        if (!code.matches("^S\\d{3}$")) {
            throw new BizException("新增站点失败：站点编号必须形如 S001（S 加 3 位数字），当前为 " + code);
        }
        if (name.isEmpty()) {
            throw new BizException("新增站点失败：站点名称不能为空");
        }
        if (district.isEmpty()) {
            throw new BizException("新增站点失败：所在行政区不能为空");
        }
        if (req.capacity() == null || req.capacity() < 1) {
            throw new BizException("新增站点失败：站点容量至少为 1");
        }
        if (stationRepository.existsByCode(code)) {
            throw new BizException("新增站点失败：站点编号 " + code + " 已存在，站点编号必须唯一");
        }

        Station station = new Station();
        station.setCode(code);
        station.setName(name);
        station.setDistrict(district);
        station.setCapacity(req.capacity());
        // 默认值放在 service：新建站点一律是「运营中」
        station.setStatus(BikeDict.STATION_OPEN);
        station.setLongitude(req.longitude());
        station.setLatitude(req.latitude());
        station.setCreatedAt(LocalDateTime.now());
        stationRepository.save(station);

        return cardOf(station.getId());
    }

    @Transactional
    public Views.StationCard updateCapacity(Long stationId, StationDtos.StationCapacity req) {
        Station station = slotService.lockStation(stationId, "调整站点容量");
        long used = bikeRepository.countByStationId(station.getId());
        if (req.capacity() < used) {
            throw new BizException("调整站点容量失败：站点「" + station.getName() + "」当前已停 "
                    + used + " 辆，容量不能调到 " + req.capacity() + " 辆以下");
        }
        station.setCapacity(req.capacity());
        return cardOf(station.getId());
    }

    /**
     * 站点停用 / 启用。停用后该站点不能借车、不能还车、不能调入车辆。
     */
    @Transactional
    public Views.StationCard changeStatus(Long stationId, String target) {
        String status = target == null ? "" : target.trim().toUpperCase();
        if (!BikeDict.isValidStationStatus(status)) {
            throw new BizException("变更站点状态失败：站点状态取值非法：" + target);
        }
        Station station = slotService.lockStation(stationId, "变更站点状态");
        if (status.equals(station.getStatus())) {
            throw new BizException("变更站点状态失败：站点「" + station.getName() + "」已经是「"
                    + BikeDict.stationStatusName(status) + "」状态");
        }
        station.setStatus(status);
        return cardOf(station.getId());
    }

    /**
     * 给站点加桩位。桩位号在站内唯一。
     *
     * 桩位数与站点容量是两条独立的线：容量卡的是「这个站点最多停几辆车」，
     * 桩位是物理格子数，可以比容量多（这样容量满了但还有空桩时，
     * 「站点已满」这条规则才可能被触发）。
     */
    @Transactional
    public Views.StationCard addPile(Long stationId, StationDtos.PileCreate req) {
        String pileNo = req.pileNo() == null ? "" : req.pileNo().trim().toUpperCase();
        if (pileNo.isEmpty()) {
            throw new BizException("新增桩位失败：桩位号不能为空");
        }
        Station station = slotService.lockStation(stationId, "新增桩位");
        if (!BikeDict.STATION_OPEN.equals(station.getStatus())) {
            throw new BizException("新增桩位失败：站点「" + station.getName() + "」已停用，停用站点不能再加桩位");
        }
        if (pileRepository.findByStationIdAndPileNo(station.getId(), pileNo).isPresent()) {
            throw new BizException("新增桩位失败：站点「" + station.getName() + "」里已经有桩位 "
                    + pileNo + " 了，桩位号在站内必须唯一");
        }

        Pile pile = new Pile();
        pile.setStationId(station.getId());
        pile.setPileNo(pileNo);
        // 默认值放在 service：新桩位一律「空闲」
        pile.setStatus(BikeDict.PILE_IDLE);
        pile.setCreatedAt(LocalDateTime.now());
        pileRepository.save(pile);

        return cardOf(station.getId());
    }

    // ------------------------------------------------------------------
    // 拖拽调拨
    // ------------------------------------------------------------------

    /**
     * 调拨：把一辆在桩的车从它现在的桩位挪到目标站点的目标桩位上。
     *
     * 同时用到三处悲观锁：源站点行、目标站点行、目标桩位行、车辆行 ——
     * 保证「目标桩位空着」「目标站点还有容量」的判断和随后的写入之间不被并发插队。
     */
    @Transactional
    public Views.TransferResult transfer(StationDtos.Transfer req) {
        if (req.bikeId() == null) {
            throw new BizException("调拨失败：车辆不能为空");
        }
        Bike bike0 = bikeRepository.findById(req.bikeId())
                .orElseThrow(() -> new BizException("调拨失败：车辆不存在（id=" + req.bikeId() + "）"));

        // 只给了站点（把车丢在站点卡片上）→ 自动挑该站点第一个空闲桩位
        Long requestedPileId = req.toPileId();
        if (requestedPileId == null && req.toStationId() == null) {
            throw new BizException("调拨失败：目标桩位不能为空，请把车辆拖到某个桩位上");
        }
        final Long toPileId = requestedPileId == null
                ? firstFreePileId(req.toStationId())
                : requestedPileId;

        Pile targetPile0 = pileRepository.findById(toPileId)
                .orElseThrow(() -> new BizException("调拨失败：目标桩位不存在（id=" + toPileId + "）"));

        // 统一加锁顺序：站点（小 id 先锁）→ 桩位 → 车辆
        Long fromStationId = bike0.getStationId();
        Long toStationId = targetPile0.getStationId();
        Long firstStationId = fromStationId;
        Long secondStationId = toStationId;
        if (fromStationId != null && toStationId != null && fromStationId > toStationId) {
            firstStationId = toStationId;
            secondStationId = fromStationId;
        }
        slotService.lockStation(firstStationId, "调拨");
        if (secondStationId != null && !secondStationId.equals(firstStationId)) {
            slotService.lockStation(secondStationId, "调拨");
        }
        Pile targetPile = slotService.lockPile(toPileId, "调拨");
        Station targetStation = slotService.lockStation(targetPile.getStationId(), "调拨");
        Station fromStation = fromStationId == null ? null
                : stationRepository.findById(fromStationId).orElse(null);
        Bike bike = bikeRepository.findByIdForUpdate(req.bikeId())
                .orElseThrow(() -> new BizException("调拨失败：车辆不存在（id=" + req.bikeId() + "）"));

        if (!BikeDict.BIKE_PILE.equals(bike.getStatus())) {
            throw new BizException("调拨失败：车辆 " + bike.getCode() + " 当前状态为「"
                    + BikeDict.bikeStatusName(bike.getStatus()) + "」，只有「在桩」的车辆才能调拨");
        }
        if (targetPile.getId().equals(bike.getPileId())) {
            throw new BizException("调拨失败：车辆 " + bike.getCode() + " 已经在桩位「"
                    + targetPile.getPileNo() + "」上了，不需要调拨");
        }

        slotService.assertStationOpen(targetStation, "调拨");
        slotService.assertPileUsable(targetPile, "调拨");
        slotService.assertPileFree(targetPile, "调拨");
        if (!targetStation.getId().equals(bike.getStationId())) {
            slotService.assertStationHasRoom(targetStation, bike, "调拨");
        }

        String fromPileNo = bike.getPileId() == null ? null : pileRepository.findById(bike.getPileId())
                .map(Pile::getPileNo).orElse(null);

        slotService.releasePile(bike);
        slotService.place(bike, targetStation, targetPile);

        return new Views.TransferResult(
                "已把车辆 " + bike.getCode() + " 从「"
                        + (fromStation == null ? "无站点" : fromStation.getName()) + " / "
                        + (fromPileNo == null ? "无桩位" : fromPileNo) + "」调拨到「"
                        + targetStation.getName() + " / " + targetPile.getPileNo() + "」",
                bike.getId(),
                bike.getCode(),
                fromStation == null ? null : fromStation.getName(),
                fromPileNo,
                targetStation.getName(),
                targetPile.getPileNo());
    }

    private Long firstFreePileId(Long stationId) {
        Station station = slotService.requireStation(stationId, "调拨");
        Optional<Pile> free = pileRepository.findByStationIdOrderByPileNoAsc(station.getId()).stream()
                .filter(p -> BikeDict.PILE_IDLE.equals(p.getStatus()))
                .filter(p -> bikeRepository.findByPileId(p.getId()).isEmpty())
                .findFirst();
        return free.map(Pile::getId)
                .orElseThrow(() -> new BizException("调拨失败：站点「" + station.getName() + "」没有空闲桩位了"));
    }

    private Views.StationCard cardOf(Long stationId) {
        return cards(null).stream()
                .filter(c -> c.id().equals(stationId))
                .findFirst()
                .orElseThrow(() -> new BizException("站点不存在（id=" + stationId + "）"));
    }

    /** 给别的 service 复用：站点 id → 名称 */
    public Map<Long, String> stationNameMap() {
        return stationRepository.findAll().stream()
                .collect(Collectors.toMap(Station::getId, Station::getName,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
