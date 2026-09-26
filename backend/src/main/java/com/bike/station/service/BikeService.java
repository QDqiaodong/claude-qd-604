package com.bike.station.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bike.station.common.BizException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.ClearanceDict;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Station;
import com.bike.station.dto.BikeDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.ClearanceOrderRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.RentalRepository;
import com.bike.station.repository.StationRepository;
import com.bike.station.repository.WorkOrderRepository;

/**
 * 车辆台账。
 *
 * 规则：
 *  1. 车辆编号唯一，且必须是 BK- 加 4 位数字；
 *  2. 状态机：在桩 PILE → 骑行中 RIDING / 维修中 REPAIR → 报废 SCRAPPED（终态）；
 *  3. 「骑行中」只由借车产生、「报废」只走报废接口，PUT 只能做 在桩↔维修中 的人工迁移；
 *  4. 报废是终态，报废后任何修改都被拒绝；
 *  5. 车辆位置变更（换桩位、回桩）要过 站点停用 / 桩位停用 / 一桩一车 / 站点容量 四道校验。
 */
@Service
public class BikeService {

    private final BikeRepository bikeRepository;
    private final PileRepository pileRepository;
    private final StationRepository stationRepository;
    private final RentalRepository rentalRepository;
    private final WorkOrderRepository workOrderRepository;
    private final ClearanceOrderRepository clearanceOrderRepository;
    private final SlotService slotService;

    public BikeService(BikeRepository bikeRepository,
                       PileRepository pileRepository,
                       StationRepository stationRepository,
                       RentalRepository rentalRepository,
                       WorkOrderRepository workOrderRepository,
                       ClearanceOrderRepository clearanceOrderRepository,
                       SlotService slotService) {
        this.bikeRepository = bikeRepository;
        this.pileRepository = pileRepository;
        this.stationRepository = stationRepository;
        this.rentalRepository = rentalRepository;
        this.workOrderRepository = workOrderRepository;
        this.clearanceOrderRepository = clearanceOrderRepository;
        this.slotService = slotService;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<Views.BikeView> list(String status, String keyword) {
        List<Bike> bikes;
        String kw = keyword == null ? null : keyword.trim();
        if (status != null && !status.isBlank()) {
            if (!BikeDict.isValidBikeStatus(status)) {
                throw new BizException("车辆状态取值非法：" + status);
            }
            bikes = bikeRepository.findByStatusOrderByIdAsc(status);
        } else {
            bikes = bikeRepository.findAllByOrderByIdAsc();
        }
        if (kw != null && !kw.isEmpty()) {
            String upper = kw.toUpperCase();
            bikes = bikes.stream()
                    .filter(b -> b.getCode().toUpperCase().contains(upper)
                            || b.getModel().toUpperCase().contains(upper))
                    .collect(Collectors.toList());
        }
        return toViews(bikes);
    }

    public Views.BikeView get(Long id) {
        return toView(requireBike(id), nameMap(), pileNoMap());
    }

    /** 顶部概览：给页面顶栏显示的数字 */
    public Views.Overview overview() {
        long pileTotal = pileRepository.count();
        long pileIdle = pileRepository.countByStatus(BikeDict.PILE_IDLE);
        long pileOccupied = pileRepository.countByStatus(BikeDict.PILE_OCCUPIED);
        return new Views.Overview(
                bikeRepository.count(),
                bikeRepository.countByStatus(BikeDict.BIKE_PILE),
                bikeRepository.countByStatus(BikeDict.BIKE_RIDING),
                bikeRepository.countByStatus(BikeDict.BIKE_REPAIR),
                bikeRepository.countByStatus(BikeDict.BIKE_SCRAPPED),
                stationRepository.count(),
                stationRepository.countByStatus(BikeDict.STATION_OPEN),
                pileTotal,
                pileIdle,
                pileOccupied,
                rentalRepository.countByStatus(BikeDict.RENTAL_RIDING),
                rentalRepository.countByStatus(BikeDict.RENTAL_SETTLED),
                workOrderRepository.countByStatus(BikeDict.ORDER_REPORTED),
                workOrderRepository.countByStatus(BikeDict.ORDER_ASSIGNED),
                clearanceOrderRepository.countByStatusIn(ClearanceDict.ORDER_ACTIVE),
                clearanceOrderRepository.countByStatusIn(List.of(ClearanceDict.ORDER_PARTIALLY_BLOCKED)));
    }

    // ------------------------------------------------------------------
    // 新增
    // ------------------------------------------------------------------

    /**
     * 新增车辆。默认值全部在这里赋：status=在桩、totalRides=0、createdAt=now。
     * 实体上不写 Java 默认值，就是为了让 PUT 局部更新时不会被刷回默认值。
     */
    @Transactional
    public Views.BikeView create(BikeDtos.BikeCreate req) {
        String code = req.code() == null ? "" : req.code().trim().toUpperCase();
        String model = req.model() == null ? "" : req.model().trim();
        if (code.isEmpty()) {
            throw new BizException("新增车辆失败：车辆编号不能为空");
        }
        if (!code.matches("^BK-\\d{4}$")) {
            throw new BizException("新增车辆失败：车辆编号必须形如 BK-1001（BK- 加 4 位数字），当前为 " + code);
        }
        if (model.isEmpty()) {
            throw new BizException("新增车辆失败：车型不能为空");
        }
        if (bikeRepository.existsByCode(code)) {
            throw new BizException("新增车辆失败：车辆编号 " + code + " 已存在，车辆编号必须唯一");
        }

        Bike bike = new Bike();
        bike.setCode(code);
        bike.setModel(model);
        // ↓↓↓ 默认值统一放在 service，不放实体
        bike.setStatus(BikeDict.BIKE_PILE);
        bike.setTotalRides(0);
        bike.setCreatedAt(LocalDateTime.now());
        bike.setUpdatedAt(LocalDateTime.now());

        Long pileId = req.pileId();
        Long stationId = req.stationId();
        if (pileId == null && stationId != null) {
            throw new BizException("新增车辆失败：只指定了站点没指定桩位，车辆上桩必须落到具体桩位");
        }

        if (pileId != null) {
            Pile pile = slotService.lockPile(pileId, "新增车辆");
            Station station = slotService.lockStation(pile.getStationId(), "新增车辆");
            if (stationId != null && !stationId.equals(station.getId())) {
                throw new BizException("新增车辆失败：桩位「" + pile.getPileNo() + "」属于站点「"
                        + station.getName() + "」，与传入的站点不一致");
            }
            slotService.assertStationOpen(station, "新增车辆");
            slotService.assertPileUsable(pile, "新增车辆");
            slotService.assertPileFree(pile, "新增车辆");
            slotService.assertStationHasRoom(station, null, "新增车辆");
            slotService.place(bike, station, pile);
        }

        Bike saved = bikeRepository.save(bike);
        return toView(saved, nameMap(), pileNoMap());
    }

    // ------------------------------------------------------------------
    // 局部更新
    // ------------------------------------------------------------------

    /**
     * 局部更新：null 表示「这一项不改」。
     * 因为实体没有 Java 默认值，未被传的字段不会被刷成默认值。
     */
    @Transactional
    public Views.BikeView update(Long id, BikeDtos.BikeUpdate req) {
        Bike bike = lockBike(id);

        if (BikeDict.isScrapped(bike.getStatus())) {
            throw new BizException("修改车辆失败：车辆 " + bike.getCode()
                    + " 已报废，报废是终态，不能再做任何修改");
        }

        String targetStatus = req.status() == null || req.status().isBlank()
                ? bike.getStatus()
                : req.status().trim().toUpperCase();
        if (!BikeDict.isValidBikeStatus(targetStatus)) {
            throw new BizException("修改车辆失败：车辆状态取值非法：" + req.status());
        }

        boolean statusChanging = !targetStatus.equals(bike.getStatus());
        if (statusChanging) {
            if (BikeDict.BIKE_RIDING.equals(targetStatus)) {
                throw new BizException("修改车辆失败：「骑行中」只能由借车产生，请调用 POST /api/rentals/borrow");
            }
            if (BikeDict.BIKE_SCRAPPED.equals(targetStatus)) {
                throw new BizException("修改车辆失败：报废请调用 POST /api/bikes/" + bike.getId() + "/scrap");
            }
            if (!BikeDict.canManuallyMove(bike.getStatus(), targetStatus)) {
                throw new BizException("修改车辆失败：不允许把车辆状态从「"
                        + BikeDict.bikeStatusName(bike.getStatus()) + "」改为「"
                        + BikeDict.bikeStatusName(targetStatus) + "」");
            }
        }

        if (BikeDict.BIKE_PILE.equals(targetStatus)) {
            Long targetPileId = req.pileId() != null ? req.pileId() : bike.getPileId();
            if (targetPileId == null) {
                throw new BizException("修改车辆失败：把车辆置为「在桩」必须指定桩位");
            }
            boolean locationChanging = !targetPileId.equals(bike.getPileId());
            if (locationChanging || statusChanging) {
                Pile pile = slotService.lockPile(targetPileId, "修改车辆");
                Station station = slotService.lockStation(pile.getStationId(), "修改车辆");
                slotService.assertStationOpen(station, "修改车辆");
                slotService.assertPileUsable(pile, "修改车辆");
                if (locationChanging) {
                    slotService.assertPileFree(pile, "修改车辆");
                }
                if (!station.getId().equals(bike.getStationId())) {
                    slotService.assertStationHasRoom(station, bike, "修改车辆");
                }
                slotService.releasePile(bike);
                bike.setStatus(BikeDict.BIKE_PILE);
                bike.setScrapReason(null);
                slotService.place(bike, station, pile);
            }
        } else if (BikeDict.BIKE_REPAIR.equals(targetStatus) && statusChanging) {
            // 送修：车离桩但留在站点维修区，所以 stationId 保留、pileId 清掉
            Station station = bike.getStationId() == null
                    ? null
                    : slotService.lockStation(bike.getStationId(), "修改车辆");
            if (station != null) {
                slotService.assertStationOpen(station, "修改车辆");
            }
            slotService.releasePile(bike);
            bike.setStatus(BikeDict.BIKE_REPAIR);
            bike.setUpdatedAt(LocalDateTime.now());
        }

        if (req.model() != null && !req.model().isBlank()) {
            bike.setModel(req.model().trim());
        }
        if (req.scrapReason() != null) {
            bike.setScrapReason(req.scrapReason().trim());
        }
        bike.setUpdatedAt(LocalDateTime.now());

        return toView(bike, nameMap(), pileNoMap());
    }

    // ------------------------------------------------------------------
    // 报废（终态）
    // ------------------------------------------------------------------

    @Transactional
    public Views.BikeView scrap(Long id, BikeDtos.BikeScrap req) {
        Bike bike = lockBike(id);
        if (BikeDict.isScrapped(bike.getStatus())) {
            throw new BizException("报废失败：车辆 " + bike.getCode()
                    + " 已经是「报废」状态，报废是终态，不能重复报废");
        }
        String reason = req == null || req.reason() == null || req.reason().isBlank()
                ? "运营方判定报废"
                : req.reason().trim();
        if (reason.length() > 128) {
            throw new BizException("报废失败：报废原因长度不能超过 128");
        }

        slotService.releasePile(bike);
        bike.setStatus(BikeDict.BIKE_SCRAPPED);
        bike.setStationId(null);
        bike.setPileId(null);
        bike.setScrapReason(reason);
        bike.setUpdatedAt(LocalDateTime.now());

        return toView(bike, nameMap(), pileNoMap());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private Bike requireBike(Long id) {
        if (id == null) {
            throw new BizException("车辆不存在：车辆不能为空");
        }
        return bikeRepository.findById(id)
                .orElseThrow(() -> new BizException("车辆不存在（id=" + id + "）"));
    }

    /** 【悲观锁】锁 bike 表的这一行：改状态、改桩位、报废都先锁它 */
    private Bike lockBike(Long id) {
        if (id == null) {
            throw new BizException("车辆不存在：车辆不能为空");
        }
        return bikeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("车辆不存在（id=" + id + "）"));
    }

    private Map<Long, Station> nameMap() {
        return stationRepository.findAll().stream()
                .collect(Collectors.toMap(Station::getId, Function.identity(), (a, b) -> a, HashMap::new));
    }

    private Map<Long, String> pileNoMap() {
        Map<Long, String> map = new LinkedHashMap<>();
        for (Pile p : pileRepository.findAll()) {
            map.put(p.getId(), p.getPileNo());
        }
        return map;
    }

    private List<Views.BikeView> toViews(List<Bike> bikes) {
        Map<Long, Station> stations = nameMap();
        Map<Long, String> piles = pileNoMap();
        List<Views.BikeView> list = new ArrayList<>(bikes.size());
        for (Bike b : bikes) {
            list.add(toView(b, stations, piles));
        }
        return list;
    }

    private Views.BikeView toView(Bike b, Map<Long, Station> stations, Map<Long, String> piles) {
        Station station = b.getStationId() == null ? null : stations.get(b.getStationId());
        return new Views.BikeView(
                b.getId(),
                b.getCode(),
                b.getModel(),
                b.getStatus(),
                BikeDict.bikeStatusName(b.getStatus()),
                b.getStationId(),
                station == null ? null : station.getCode(),
                station == null ? null : station.getName(),
                b.getPileId(),
                b.getPileId() == null ? null : piles.get(b.getPileId()),
                b.getTotalRides(),
                b.getScrapReason(),
                b.getCreatedAt(),
                b.getUpdatedAt());
    }
}
