package com.bike.station.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bike.station.common.BizException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.EvacuationItem;
import com.bike.station.domain.EvacuationOrder;
import com.bike.station.domain.EvacuationTarget;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Station;
import com.bike.station.domain.Worker;
import com.bike.station.dto.EvacuationDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.EvacuationItemRepository;
import com.bike.station.repository.EvacuationOrderRepository;
import com.bike.station.repository.EvacuationTargetRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.RentalRepository;
import com.bike.station.repository.StationRepository;
import com.bike.station.repository.WorkOrderRepository;
import com.bike.station.repository.WorkerRepository;

/**
 * 应急清站。
 *
 * 取舍原则（贯穿整个类）：
 *   在「尽量多迁出车辆」和「绝不突破接收站容量及桩位约束」之间，永远以后者为准 ——
 *   排不下 / 不可送的车宁可标为「暂无处安置 / 受阻」留在单里，也绝不伪造完整方案。
 *
 * 状态机：PENDING 待执行 →（执行）→ DONE 已完成（源站真正停用）
 *                              └→ PARTIAL 部分受阻（可加接收站、改方案后继续执行）
 *         PENDING → CANCELLED 已撤销（只允许在尚未迁出任何车辆时）。
 *
 * 并发口径：执行入口先对清站单行加悲观锁；锁内已 MOVED 的明细原样保留、不再迁移，
 * 未完成的才逐车处理。重复点击、刷新重提、两人同时执行，最终都收敛成同一份进度，
 * 每辆车最多成功迁出一次，容量与桩位由 station/pile/bike 行锁 + 一桩一车唯一索引兜底。
 */
@Service
public class EvacuationService {

    private static final DateTimeFormatter ORDER_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final EvacuationOrderRepository orderRepository;
    private final EvacuationTargetRepository targetRepository;
    private final EvacuationItemRepository itemRepository;
    private final StationRepository stationRepository;
    private final PileRepository pileRepository;
    private final BikeRepository bikeRepository;
    private final WorkerRepository workerRepository;
    private final WorkOrderRepository workOrderRepository;
    private final RentalRepository rentalRepository;
    private final SlotService slotService;

    public EvacuationService(EvacuationOrderRepository orderRepository,
                             EvacuationTargetRepository targetRepository,
                             EvacuationItemRepository itemRepository,
                             StationRepository stationRepository,
                             PileRepository pileRepository,
                             BikeRepository bikeRepository,
                             WorkerRepository workerRepository,
                             WorkOrderRepository workOrderRepository,
                             RentalRepository rentalRepository,
                             SlotService slotService) {
        this.orderRepository = orderRepository;
        this.targetRepository = targetRepository;
        this.itemRepository = itemRepository;
        this.stationRepository = stationRepository;
        this.pileRepository = pileRepository;
        this.bikeRepository = bikeRepository;
        this.workerRepository = workerRepository;
        this.workOrderRepository = workOrderRepository;
        this.rentalRepository = rentalRepository;
        this.slotService = slotService;
    }

    // ==================================================================
    // 创建向导：接收站实时能力
    // ==================================================================

    /**
     * 源站可选的接收站实时清单：除源站外所有运营中站点，带实时剩余容量、
     * 可用桩位、是否具备在岗且无未完工工单冲突的维修工、距源站直线距离。
     */
    public List<Views.ReceiverOption> receiverOptions(Long sourceStationId) {
        Station source = slotService.requireStation(sourceStationId, "查询接收站");
        List<Station> stations = stationRepository.findAllByOrderByCodeAsc();

        Map<Long, Long> bikeCountByStation = new HashMap<>();
        for (Bike b : bikeRepository.findAll()) {
            if (b.getStationId() != null) {
                bikeCountByStation.merge(b.getStationId(), 1L, Long::sum);
            }
        }
        Map<Long, Long> freePileByStation = freePileCounts();

        List<Views.ReceiverOption> options = new ArrayList<>();
        for (Station st : stations) {
            if (st.getId().equals(source.getId())) {
                continue;
            }
            // 已停用站点既不能调入也不该出现在接收站候选里
            if (!BikeDict.STATION_OPEN.equals(st.getStatus())) {
                continue;
            }
            long bikeCount = bikeCountByStation.getOrDefault(st.getId(), 0L);
            long remain = Math.max(0, st.getCapacity() - bikeCount);
            long freePile = freePileByStation.getOrDefault(st.getId(), 0L);
            WorkerInfo wi = faultWorker(st.getId(), "查询接收站");
            boolean faultCapable = BikeDict.STATION_OPEN.equals(st.getStatus()) && wi.faultCapable;
            options.add(new Views.ReceiverOption(
                    st.getId(),
                    st.getCode(),
                    st.getName(),
                    st.getDistrict(),
                    st.getCapacity(),
                    bikeCount,
                    remain,
                    freePile,
                    faultCapable,
                    wi.summary,
                    GeoUtils.distanceKm(source, st)));
        }
        options.sort(Comparator.comparing(
                (Views.ReceiverOption o) -> o.distanceKm() == null
                        ? BigDecimal.valueOf(Double.MAX_VALUE) : o.distanceKm()));
        return options;
    }

    // ==================================================================
    // 创建向导：方案预览（不落库）
    // ==================================================================

    /**
     * 预览迁移方案：与创建走同一套校验和分配规则，但不写库。
     * 前端第三步展示这份方案，运营人员可逐车调整后再调创建接口。
     * 走只读事务：preparePlan 里的 FOR UPDATE 行锁需要事务边界，同时也防止预览过程中
     * 源站 / 接收站被并发改掉。
     */
    @Transactional(readOnly = true)
    public Views.EvacuationPlanPreview preview(EvacuationDtos.Preview req) {
        PreparedPlan plan = preparePlan(req == null ? null : req.sourceStationId(),
                req == null ? null : req.targetStationIds(), req == null ? null : req.faultBikeIds());
        return buildPreview(plan);
    }

    // ==================================================================
    // 创建清站单（生成可人工调整的迁移方案；排不下就明确留空，不伪造完整方案）
    // ==================================================================

    @Transactional
    public Views.EvacuationDetail create(EvacuationDtos.Create req) {
        PreparedPlan plan = preparePlan(req == null ? null : req.sourceStationId(),
                req == null ? null : req.targetStationIds(), req == null ? null : req.faultBikeIds());

        LocalDateTime now = LocalDateTime.now();
        EvacuationOrder order = new EvacuationOrder();
        order.setOrderNo("CL" + now.format(ORDER_NO_FORMAT));
        order.setSourceStationId(plan.source.getId());
        order.setStatus(BikeDict.EVAC_PENDING);
        if (req.remark() != null && !req.remark().isBlank()) {
            order.setRemark(req.remark().trim());
        }
        order.setCreatedAt(now);
        orderRepository.save(order);

        for (Station t : plan.targets) {
            EvacuationTarget et = new EvacuationTarget();
            et.setEvacuationId(order.getId());
            et.setStationId(t.getId());
            et.setDistanceKm(plan.distances.get(t.getId()));
            et.setFaultCapable(plan.faultCapable.getOrDefault(t.getId(), false));
            et.setCreatedAt(now);
            targetRepository.save(et);
        }

        // 人工覆盖（在预览方案上做的调整）
        Map<Long, EvacuationDtos.ItemPlanOverride> overrideByBike = new HashMap<>();
        if (req.overrides() != null) {
            for (EvacuationDtos.ItemPlanOverride ov : req.overrides()) {
                if (ov != null && ov.bikeId() != null) {
                    overrideByBike.put(ov.bikeId(), ov);
                }
            }
        }
        Set<Long> targetIds = plan.targets.stream().map(Station::getId).collect(Collectors.toSet());

        for (PreparedBike pb : plan.bikes) {
            EvacuationItem item = new EvacuationItem();
            item.setEvacuationId(order.getId());
            item.setBikeId(pb.bike.getId());
            item.setBikeCode(pb.bike.getCode());
            item.setFaultFlag(pb.fault);
            item.setItemStatus(BikeDict.ITEM_PLANNED);
            item.setCreatedAt(now);

            EvacuationDtos.ItemPlanOverride ov = overrideByBike.get(pb.bike.getId());
            if (ov != null) {
                if (ov.planStationId() != null) {
                    if (!targetIds.contains(ov.planStationId())) {
                        throw new BizException("创建清站单失败：车辆 " + pb.bike.getCode()
                                + " 的人工去向站点不在所选接收站内");
                    }
                    item.setPlanStationId(ov.planStationId());
                    item.setPlanPileId(ov.planPileId());
                } else {
                    item.setPlanStationId(null);
                    item.setPlanPileId(null);
                }
                item.setManualFlag(true);
            } else {
                item.setPlanStationId(pb.planStationId);
                item.setPlanPileId(null);
                item.setManualFlag(false);
            }
            itemRepository.save(item);
        }

        return detail(order.getId());
    }

    /** preparePlan 的中间产物（不与具体实体/持久化绑定，预览与创建共用）。 */
    private static final class PreparedPlan {
        Station source;
        List<Station> targets;
        List<PreparedBike> bikes;
        Map<Long, BigDecimal> distances;
        Map<Long, Boolean> faultCapable;
    }

    private static final class PreparedBike {
        Bike bike;
        boolean fault;
        Long planStationId;
    }

    /**
     * 校验源站 / 接收站，锁定源站与接收站行，按规则在内存里生成方案。
     * 不写任何表 —— 创建时由调用方落库；预览时直接转成视图。
     */
    private PreparedPlan preparePlan(Long sourceStationId, List<Long> targetStationIds, List<Long> faultBikeIds) {
        if (sourceStationId == null) {
            throw new BizException("创建清站单失败：源站点不能为空");
        }
        Station source = slotService.lockStation(sourceStationId, "创建清站单");
        slotService.assertStationOpen(source, "创建清站单");
        if (orderRepository.findActiveBySourceStationId(source.getId()).isPresent()) {
            throw new BizException("创建清站单失败：站点「" + source.getName()
                    + "」已经有一张未完结的清站单，请先执行完成或撤销后再建");
        }

        List<Long> targetIds = targetStationIds == null ? List.of()
                : targetStationIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (targetIds.isEmpty()) {
            throw new BizException("创建清站单失败：请至少选择一个运营中的接收站");
        }
        if (targetIds.contains(source.getId())) {
            throw new BizException("创建清站单失败：源站不能同时作为接收站");
        }

        List<Station> targets = new ArrayList<>();
        for (Long id : targetIds) {
            Station t = slotService.lockStation(id, "创建清站单");
            slotService.assertStationOpen(t, "创建清站单");
            targets.add(t);
        }

        List<Bike> sourceBikes = bikeRepository.findByStationIdOrderByPileIdAsc(source.getId()).stream()
                .filter(b -> BikeDict.BIKE_PILE.equals(b.getStatus()))
                .sorted(Comparator.comparing(Bike::getId))
                .toList();
        if (sourceBikes.isEmpty()) {
            throw new BizException("创建清站单失败：站点「" + source.getName()
                    + "」当前没有在桩车辆，无需清站；确认无在途租借与未关闭工单后可直接停用");
        }

        Set<Long> manualFault = faultBikeIds == null ? Set.of() : new HashSet<>(faultBikeIds);
        Set<Long> autoFaultBikeIds = bikesWithUnclosedOrders(
                sourceBikes.stream().map(Bike::getId).toList());

        PreparedPlan plan = new PreparedPlan();
        plan.source = source;
        plan.targets = targets;
        plan.distances = new HashMap<>();
        plan.faultCapable = new HashMap<>();
        for (Station t : targets) {
            plan.distances.put(t.getId(), GeoUtils.distanceKm(source, t));
            plan.faultCapable.put(t.getId(), faultWorker(t.getId(), "创建清站单").faultCapable);
        }

        Map<Long, Integer> remainCapacity = new HashMap<>();
        Map<Long, List<Long>> freePiles = new HashMap<>();
        Map<Long, Long> bikeCountByStation = bikeCounts();
        Set<Long> occupiedPileIds = occupiedPileIds();
        for (Station t : targets) {
            remainCapacity.put(t.getId(),
                    Math.max(0, t.getCapacity() - bikeCountByStation.getOrDefault(t.getId(), 0L).intValue()));
            freePiles.put(t.getId(), new ArrayList<>(freePileIds(t.getId(), occupiedPileIds)));
        }

        plan.bikes = new ArrayList<>();
        for (Bike b : sourceBikes) {
            PreparedBike pb = new PreparedBike();
            pb.bike = b;
            pb.fault = manualFault.contains(b.getId()) || autoFaultBikeIds.contains(b.getId());
            pb.planStationId = null;
            plan.bikes.add(pb);
        }

        // 内存版自动分配（直接写 PreparedBike.planStationId，逐辆扣减容量/空桩账本）
        autoAssign(plan.bikes, targets, remainCapacity, freePiles, plan.distances, plan.faultCapable);
        return plan;
    }

    private void autoAssign(List<PreparedBike> bikes,
                            List<Station> targets,
                            Map<Long, Integer> remainCapacity,
                            Map<Long, List<Long>> freePiles,
                            Map<Long, BigDecimal> distances,
                            Map<Long, Boolean> faultCapable) {
        List<PreparedBike> faults = bikes.stream().filter(p -> p.fault)
                .sorted(Comparator.comparing(p -> p.bike.getId())).toList();
        List<PreparedBike> normals = bikes.stream().filter(p -> !p.fault)
                .sorted(Comparator.comparing(p -> p.bike.getId())).toList();
        assignPrepared(faults, remainCapacity, freePiles, distances, faultCapable, true);
        assignPrepared(normals, remainCapacity, freePiles, distances, faultCapable, false);
    }

    private void assignPrepared(List<PreparedBike> group,
                                Map<Long, Integer> remainCapacity,
                                Map<Long, List<Long>> freePiles,
                                Map<Long, BigDecimal> distances,
                                Map<Long, Boolean> faultCapable,
                                boolean faultOnly) {
        for (PreparedBike pb : group) {
            List<Long> candidates = remainCapacity.keySet().stream()
                    .filter(sid -> remainCapacity.getOrDefault(sid, 0) > 0)
                    .filter(sid -> !freePiles.getOrDefault(sid, List.of()).isEmpty())
                    .filter(sid -> !faultOnly || faultCapable.getOrDefault(sid, false))
                    .toList();
            Long chosen = nearest(candidates, distances);
            if (chosen != null) {
                pb.planStationId = chosen;
                consume(remainCapacity, freePiles, chosen, null);
            }
        }
    }

    /** 把内存方案转成预览视图（明细走与详情一致的组装逻辑）。 */
    private Views.EvacuationPlanPreview buildPreview(PreparedPlan plan) {
        Map<Long, Station> stations = stationMap();
        Map<Long, Long> bikeCounts = bikeCounts();
        Map<Long, Long> freePileCounts = freePileCounts();

        List<Views.EvacuationTargetView> targetViews = new ArrayList<>();
        for (Station t : plan.targets) {
            targetViews.add(new Views.EvacuationTargetView(
                    t.getId(),
                    t.getCode(),
                    t.getName(),
                    plan.distances.get(t.getId()),
                    plan.faultCapable.getOrDefault(t.getId(), false),
                    true,
                    plan.faultCapable.getOrDefault(t.getId(), false),
                    Math.max(0, t.getCapacity() - bikeCounts.getOrDefault(t.getId(), 0L)),
                    freePileCounts.getOrDefault(t.getId(), 0L),
                    0, 0));
        }

        Map<Long, Pile> piles = pileMap();
        List<Views.EvacuationItemView> itemViews = new ArrayList<>();
        for (PreparedBike pb : plan.bikes) {
            Station ps = pb.planStationId == null ? null : stations.get(pb.planStationId);
            itemViews.add(new Views.EvacuationItemView(
                    null,
                    pb.bike.getId(),
                    pb.bike.getCode(),
                    pb.bike.getModel(),
                    pb.bike.getStatus(),
                    BikeDict.bikeStatusName(pb.bike.getStatus()),
                    pb.fault,
                    pb.planStationId,
                    ps == null ? null : ps.getName(),
                    null, null, null, null, null, null,
                    BikeDict.ITEM_PLANNED,
                    BikeDict.itemStatusName(BikeDict.ITEM_PLANNED),
                    pb.planStationId == null ? "暂时无处安置：所选接收站容量/可用桩位不足" : null,
                    null));
        }

        int planned = (int) plan.bikes.stream().filter(p -> p.planStationId != null).count();
        int faultCount = (int) plan.bikes.stream().filter(p -> p.fault).count();
        return new Views.EvacuationPlanPreview(
                plan.source.getId(),
                plan.source.getCode(),
                plan.source.getName(),
                plan.bikes.size(),
                planned,
                plan.bikes.size() - planned,
                faultCount,
                itemViews,
                targetViews);
    }

    // ==================================================================
    // 列表 / 详情
    // ==================================================================

    public List<Views.EvacuationListItem> list(String status) {
        List<EvacuationOrder> orders;
        if (status == null || status.isBlank()) {
            orders = orderRepository.findAllByOrderByCreatedAtDesc();
        } else {
            if (!BikeDict.isValidEvacStatus(status)) {
                throw new BizException("清站单状态取值非法：" + status);
            }
            orders = orderRepository.findByStatusOrderByCreatedAtDesc(status);
        }
        Map<Long, Station> stations = stationMap();
        List<Views.EvacuationListItem> list = new ArrayList<>(orders.size());
        for (EvacuationOrder o : orders) {
            List<EvacuationItem> items = itemRepository.findByEvacuationIdOrderByIdAsc(o.getId());
            int total = items.size();
            int moved = (int) items.stream().filter(i -> BikeDict.ITEM_MOVED.equals(i.getItemStatus())).count();
            int blocked = (int) items.stream().filter(i -> BikeDict.ITEM_BLOCKED.equals(i.getItemStatus())).count();
            int unplaced = (int) items.stream()
                    .filter(i -> !BikeDict.ITEM_MOVED.equals(i.getItemStatus()) && i.getPlanStationId() == null).count();
            Station src = stations.get(o.getSourceStationId());
            list.add(new Views.EvacuationListItem(
                    o.getId(),
                    o.getOrderNo(),
                    o.getSourceStationId(),
                    src == null ? null : src.getCode(),
                    src == null ? null : src.getName(),
                    src == null ? null : src.getStatus(),
                    o.getStatus(),
                    BikeDict.evacStatusName(o.getStatus()),
                    total,
                    moved,
                    blocked,
                    unplaced,
                    o.getFinishBlocker(),
                    o.getCreatedAt(),
                    o.getExecutedAt(),
                    o.getFinishedAt()));
        }
        return list;
    }

    public Views.EvacuationDetail get(Long id) {
        return detail(id);
    }

    // ==================================================================
    // 人工调整方案 / 补选接收站
    // ==================================================================

    /**
     * 调整某辆车的计划去向。只允许在 PENDING / PARTIAL 状态改；
     * 已迁出的行不可改。人工指定后只服从硬约束（运营中、容量、桩位），
     * 不再强制维修能力 —— manualFlag 让执行时跳过自动改派。
     */
    @Transactional
    public Views.EvacuationDetail adjustItem(Long orderId, EvacuationDtos.ItemAdjust req) {
        if (req == null || req.itemId() == null) {
            throw new BizException("调整方案失败：请指定要调整的车辆");
        }
        EvacuationOrder order = lockOrder(orderId, "调整方案");
        if (BikeDict.EVAC_DONE.equals(order.getStatus())
                || BikeDict.EVAC_CANCELLED.equals(order.getStatus())) {
            throw new BizException("调整方案失败：清站单已" + BikeDict.evacStatusName(order.getStatus())
                    + "，方案不能再改");
        }
        EvacuationItem item = itemRepository.findByIdAndEvacuationId(req.itemId(), orderId)
                .orElseThrow(() -> new BizException("调整方案失败：该车辆不在这张清站单里"));
        if (BikeDict.ITEM_MOVED.equals(item.getItemStatus())) {
            throw new BizException("调整方案失败：车辆 " + item.getBikeCode()
                    + " 已经迁出，实际去向不能再改");
        }

        Set<Long> targetStationIds = targetRepository.findByEvacuationIdOrderByIdAsc(orderId).stream()
                .map(EvacuationTarget::getStationId).collect(Collectors.toSet());

        if (req.planStationId() == null) {
            // 改回「暂无处安置」
            item.setPlanStationId(null);
            item.setPlanPileId(null);
            item.setManualFlag(true);
        } else {
            if (!targetStationIds.contains(req.planStationId())) {
                throw new BizException("调整方案失败：请先把该站点加入接收站，再把车分配过去");
            }
            Station target = slotService.lockStation(req.planStationId(), "调整方案");
            slotService.assertStationOpen(target, "调整方案");
            // 软容量提示按实时口径校验（计划中可能多辆车同去一站，按已计划量折算）
            long planned = itemRepository.findByEvacuationIdOrderByIdAsc(orderId).stream()
                    .filter(i -> !i.getId().equals(item.getId()))
                    .filter(i -> !BikeDict.ITEM_MOVED.equals(i.getItemStatus()))
                    .filter(i -> req.planStationId().equals(i.getPlanStationId()))
                    .count();
            long usedNow = bikeRepository.countByStationId(target.getId());
            long movedIn = itemRepository.findByEvacuationIdOrderByIdAsc(orderId).stream()
                    .filter(i -> BikeDict.ITEM_MOVED.equals(i.getItemStatus()))
                    .filter(i -> req.planStationId().equals(i.getActualStationId()))
                    .count();
            long projected = usedNow + planned + movedIn + 1;
            if (projected > target.getCapacity()) {
                throw new BizException("调整方案失败：站点「" + target.getName() + "」容量 "
                        + target.getCapacity() + "，加上这辆车后将达到 " + projected
                        + " 辆，清站不能突破接收站容量");
            }
            if (req.planPileId() != null) {
                Pile pile = pileRepository.findById(req.planPileId())
                        .orElseThrow(() -> new BizException("调整方案失败：目标桩位不存在"));
                if (!pile.getStationId().equals(target.getId())) {
                    throw new BizException("调整方案失败：该桩位不属于站点「" + target.getName() + "」");
                }
                if (BikeDict.PILE_DISABLED.equals(pile.getStatus())) {
                    throw new BizException("调整方案失败：桩位「" + pile.getPileNo() + "」已停用");
                }
                Bike occupant = bikeRepository.findByPileId(pile.getId()).orElse(null);
                if (occupant != null) {
                    throw new BizException("调整方案失败：桩位「" + pile.getPileNo() + "」已被车辆 "
                            + occupant.getCode() + " 实际占用");
                }
            }
            item.setPlanStationId(req.planStationId());
            item.setPlanPileId(req.planPileId());
            item.setManualFlag(true);
        }
        // 重新纳入计划后清掉上一轮的受阻痕迹
        if (BikeDict.ITEM_BLOCKED.equals(item.getItemStatus())) {
            item.setItemStatus(BikeDict.ITEM_PLANNED);
        }
        item.setFailReason(null);
        return detail(orderId);
    }

    /**
     * 部分受阻后补选接收站。加入后会尝试把当前「暂无处安置」的车按原规则自动补分，
     * 已经人工指定过去向的车不动。
     */
    @Transactional
    public Views.EvacuationDetail addTargets(Long orderId, EvacuationDtos.AddTargets req) {
        EvacuationOrder order = lockOrder(orderId, "补选接收站");
        if (BikeDict.EVAC_DONE.equals(order.getStatus())
                || BikeDict.EVAC_CANCELLED.equals(order.getStatus())) {
            throw new BizException("补选接收站失败：清站单已" + BikeDict.evacStatusName(order.getStatus()));
        }
        List<Long> ids = req == null || req.targetStationIds() == null ? List.of()
                : req.targetStationIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BizException("补选接收站失败：请至少选择一个站点");
        }
        Station source = stationRepository.findById(order.getSourceStationId())
                .orElseThrow(() -> new BizException("源站点不存在"));
        Set<Long> exist = targetRepository.findByEvacuationIdOrderByIdAsc(orderId).stream()
                .map(EvacuationTarget::getStationId).collect(Collectors.toSet());

        LocalDateTime now = LocalDateTime.now();
        Map<Long, Station> newly = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id.equals(source.getId())) {
                throw new BizException("补选接收站失败：源站不能作为接收站");
            }
            if (exist.contains(id)) {
                continue;
            }
            Station t = slotService.lockStation(id, "补选接收站");
            slotService.assertStationOpen(t, "补选接收站");
            EvacuationTarget et = new EvacuationTarget();
            et.setEvacuationId(order.getId());
            et.setStationId(t.getId());
            et.setDistanceKm(GeoUtils.distanceKm(source, t));
            et.setFaultCapable(faultWorker(t.getId(), "补选接收站").faultCapable);
            et.setCreatedAt(now);
            targetRepository.save(et);
            newly.put(t.getId(), t);
        }

        if (!newly.isEmpty()) {
            // 用全部接收站重建账本，给仍未安置（且未被人工强制留空）的车补分
            Map<Long, Station> allTargets = new LinkedHashMap<>();
            for (EvacuationTarget et : targetRepository.findByEvacuationIdOrderByIdAsc(orderId)) {
                stationRepository.findById(et.getStationId()).ifPresent(s -> allTargets.put(s.getId(), s));
            }
            Map<Long, Integer> remain = new HashMap<>();
            Map<Long, List<Long>> free = new HashMap<>();
            Map<Long, Long> counts = bikeCounts();
            Set<Long> occupied = occupiedPileIds();
            for (Station t : allTargets.values()) {
                remain.put(t.getId(),
                        Math.max(0, t.getCapacity() - counts.getOrDefault(t.getId(), 0L).intValue()));
                free.put(t.getId(), new ArrayList<>(freePileIds(t.getId(), occupied)));
            }
            // 容量账本已按【实时库】建好（已迁车已经物理在目标站、计入实时计数）：
            //  · MOVED 行不重复占容量，只把实际落点桩从空桩池摘除（建池时也已排除，保险起见）；
            //  · 待迁且已有计划去向的车还没物理移动，容量未计入实时库 —— 这里先占一个额度，
            //    人工指定了具体桩位的再摘除该桩；自动方案不预扣具体桩位，执行时实时选。
            List<EvacuationItem> items = itemRepository.findByEvacuationIdOrderByIdAsc(orderId);
            for (EvacuationItem it : items) {
                if (BikeDict.ITEM_MOVED.equals(it.getItemStatus())) {
                    List<Long> pool = free.get(it.getActualStationId());
                    if (pool != null) {
                        pool.remove(it.getActualPileId());
                    }
                } else if (it.getPlanStationId() != null) {
                    remain.merge(it.getPlanStationId(), -1, Integer::sum);
                    if (it.getPlanPileId() != null) {
                        List<Long> pool = free.get(it.getPlanStationId());
                        if (pool != null) {
                            pool.remove(it.getPlanPileId());
                        }
                    }
                }
            }
            // 给「暂无去向、且不是人工强制留空」的车补分（含上一轮受阻的车）。
            // 账本只用于容量排序；具体桩位执行时实时锁，这里不预扣桩位。
            List<EvacuationItem> toAssign = items.stream()
                    .filter(it -> !BikeDict.ITEM_MOVED.equals(it.getItemStatus()))
                    .filter(it -> it.getPlanStationId() == null)
                    .filter(it -> !Boolean.TRUE.equals(it.getManualFlag()))
                    .toList();
            Map<Long, BigDecimal> distances = new HashMap<>();
            Map<Long, Boolean> faultCapable = new HashMap<>();
            for (EvacuationTarget et : targetRepository.findByEvacuationIdOrderByIdAsc(orderId)) {
                distances.put(et.getStationId(), et.getDistanceKm());
                faultCapable.put(et.getStationId(), Boolean.TRUE.equals(et.getFaultCapable()));
            }
            assignAutoPlans(toAssign, allTargets, remain, free, distances, faultCapable);
        }
        return detail(orderId);
    }

    // ==================================================================
    // 撤销（只允许在尚未迁出任何车辆时）
    // ==================================================================

    @Transactional
    public Views.EvacuationDetail cancel(Long orderId) {
        EvacuationOrder order = lockOrder(orderId, "撤销清站单");
        long moved = itemRepository.countByEvacuationIdAndItemStatus(orderId, BikeDict.ITEM_MOVED);
        if (moved > 0 || !BikeDict.EVAC_PENDING.equals(order.getStatus())) {
            throw new BizException("撤销失败：已经迁出 " + moved + " 辆车，清站单只能在尚未迁出任何车辆时撤销");
        }
        order.setStatus(BikeDict.EVAC_CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        return detail(orderId);
    }

    // ==================================================================
    // 执行：逐车迁移，部分受阻不回滚，每辆车最多成功一次
    // ==================================================================

    /**
     * 执行入口对清站单行加悲观锁：重复点击、刷新重提、两人同时执行时，
     * 后来的请求拿到锁后看到的是最新状态；已 MOVED 的明细不会被再次迁移。
     */
    @Transactional
    public Views.EvacuationExecuteResult execute(Long orderId) {
        EvacuationOrder order = lockOrder(orderId, "执行清站");
        if (BikeDict.EVAC_DONE.equals(order.getStatus())) {
            // 已完成是终态：重复执行直接回最新进度，不做任何写入
            return executeResult(order, 0, 0, true, "清站单已完成，源站已停用");
        }
        if (BikeDict.EVAC_CANCELLED.equals(order.getStatus())) {
            throw new BizException("执行失败：清站单已撤销，不能再执行");
        }

        LocalDateTime now = LocalDateTime.now();
        if (order.getStartedAt() == null) {
            order.setStartedAt(now);
        }
        order.setExecutedAt(now);
        order.setStatus(BikeDict.EVAC_RUNNING);

        Station source = stationRepository.findById(order.getSourceStationId())
                .orElseThrow(() -> new BizException("执行失败：源站点不存在"));

        // 接收站（执行时以实时状态重新判定，而不是信任创建时快照）
        List<Long> targetStationIds = targetRepository.findByEvacuationIdOrderByIdAsc(order.getId()).stream()
                .map(EvacuationTarget::getStationId).toList();
        Map<Long, Station> stationById = new HashMap<>();
        // 统一上锁顺序：所有涉及站点按 id 升序（源站也并入），杜绝两条流程互等死锁
        List<Long> stationIds = new ArrayList<>(targetStationIds);
        if (!stationIds.contains(source.getId())) {
            stationIds.add(source.getId());
        }
        Collections.sort(stationIds);
        for (Long sid : stationIds) {
            stationById.put(sid, slotService.lockStation(sid, "执行清站"));
        }
        Station lockedSource = stationById.get(source.getId());

        // 执行时的实时维修能力与距离（不信任创建时快照）
        Map<Long, BigDecimal> distances = new HashMap<>();
        Map<Long, Boolean> liveFaultCapable = new HashMap<>();
        for (Long sid : targetStationIds) {
            distances.put(sid, GeoUtils.distanceKm(lockedSource, stationById.get(sid)));
            liveFaultCapable.put(sid, faultWorker(sid, "执行清站").faultCapable);
        }

        // 桩位账本：每个接收站当前可用空桩（DB 实时）+ 容量余量；逐车扣减。
        // 账本直接以【实时库】为基准 —— 上几轮已迁入的车已经计入 bikeCounts / 占用桩位，
        // 不能再为本单 MOVED 行重复扣减，否则重复执行时会凭空少算容量、少算空桩。
        Set<Long> occupiedPileIds = occupiedPileIds();
        Map<Long, Integer> remainCapacity = new HashMap<>();
        Map<Long, List<Long>> freePilePool = new HashMap<>();
        Map<Long, Long> bikeCounts = bikeCounts();
        for (Long sid : targetStationIds) {
            remainCapacity.put(sid, Math.max(0,
                    stationById.get(sid).getCapacity() - bikeCounts.getOrDefault(sid, 0L).intValue()));
            freePilePool.put(sid, new ArrayList<>(freePileIds(sid, occupiedPileIds)));
        }

        // 待处理行加锁，顺序即执行顺序：故障车优先、再按 id。
        // 直接按这个顺序逐车处理（不能按目标站分组），否则普通车可能先占掉容量，
        // 让本该优先的故障车在改派时无处可去。
        List<EvacuationItem> pending = itemRepository.findPendingForUpdate(order.getId());

        List<EvacuationItem> unplaced = new ArrayList<>();
        int movedThisRound = 0;
        int blockedThisRound = 0;

        for (EvacuationItem item : pending) {
            if (item.getPlanStationId() == null) {
                // 方案里就明确「暂无处安置」的车：本轮不动，单独留单，绝不硬塞
                unplaced.add(item);
                continue;
            }
            Station plannedTarget = stationById.get(item.getPlanStationId());
            MoveOutcome outcome = moveOne(item, lockedSource, plannedTarget, stationById,
                    remainCapacity, freePilePool, distances, liveFaultCapable, now);
            if (outcome == MoveOutcome.MOVED) {
                movedThisRound++;
            } else {
                blockedThisRound++;
            }
        }
        // 暂无处安置的车保持 PLANNED（不是失败，是方案里就明确排不下）
        for (EvacuationItem it : unplaced) {
            it.setFailReason("暂时无处安置：所选接收站的容量 / 可用桩位不足，请补选接收站或调整方案");
            if (BikeDict.ITEM_BLOCKED.equals(it.getItemStatus())) {
                // 上一轮受阻、这轮仍无去向：保留受阻态
            } else {
                it.setItemStatus(BikeDict.ITEM_PLANNED);
            }
        }

        // ---------------- 单据状态与完成判定 ----------------
        List<EvacuationItem> afterItems = itemRepository.findByEvacuationIdOrderByIdAsc(order.getId());
        long movedTotal = afterItems.stream().filter(i -> BikeDict.ITEM_MOVED.equals(i.getItemStatus())).count();
        long blockedTotal = afterItems.stream().filter(i -> BikeDict.ITEM_BLOCKED.equals(i.getItemStatus())).count();
        long plannedTotal = afterItems.stream().filter(i -> BikeDict.ITEM_PLANNED.equals(i.getItemStatus())).count();

        boolean sourceClosed = false;
        String blocker = null;

        if (movedTotal == afterItems.size()) {
            // 明细全部迁出，还要过源站四道关：站内无车（含维修区）、无在途租借、
            // 无未关闭工单、无仍占用桩位 —— 任一不过都只能停在 PARTIAL
            List<String> blockers = sourceFinishBlockers(lockedSource);
            if (blockers.isEmpty()) {
                lockedSource.setStatus(BikeDict.STATION_CLOSED);
                order.setStatus(BikeDict.EVAC_DONE);
                order.setFinishedAt(LocalDateTime.now());
                order.setFinishBlocker(null);
                sourceClosed = true;
            } else {
                blocker = String.join("；", blockers);
                order.setStatus(BikeDict.EVAC_PARTIAL);
                order.setFinishBlocker(blocker);
            }
        } else {
            List<String> notes = new ArrayList<>();
            if (blockedTotal + plannedTotal > 0) {
                notes.add("仍有 " + (blockedTotal + plannedTotal) + " 辆车未迁出（受阻 "
                        + blockedTotal + " / 暂无处安置或待执行 " + plannedTotal + "）");
            }
            List<String> stationBlockers = sourceFinishBlockers(lockedSource);
            notes.addAll(stationBlockers);
            blocker = String.join("；", notes);
            order.setStatus(BikeDict.EVAC_PARTIAL);
            order.setFinishBlocker(blocker);
        }

        String message = sourceClosed
                ? "清站完成：" + movedTotal + " 辆车全部迁出，源站「" + lockedSource.getName() + "」已停用"
                : "本轮迁出 " + movedThisRound + " 辆，受阻 " + blockedThisRound
                        + " 辆；源站暂不能停用（" + blocker + "）";
        return executeResult(order, movedThisRound, blockedThisRound, sourceClosed, message);
    }

    // ==================================================================
    // 执行：单车迁移
    // ==================================================================

    private enum MoveOutcome { MOVED, BLOCKED }

    /**
     * 迁移一辆车。所有校验都在 station/pile/bike 行锁内重新做一遍，
     * 方案生成后车辆被借走、桩位被占、接收站停用都会在这里被抓到，只把这辆车标受阻，
     * 不抛异常、不影响其他车。
     */
    private MoveOutcome moveOne(EvacuationItem item,
                                Station source,
                                Station plannedTarget,
                                Map<Long, Station> stationById,
                                Map<Long, Integer> remainCapacity,
                                Map<Long, List<Long>> freePilePool,
                                Map<Long, BigDecimal> distances,
                                Map<Long, Boolean> liveFaultCapable,
                                LocalDateTime now) {
        if (plannedTarget == null) {
            return block(item, "计划接收站不存在或已被移除");
        }
        Long targetStationId = plannedTarget.getId();

        // 自动方案且车辆是故障车：执行时重新校验目标站维修能力，失效就自动改派
        boolean rerouted = false;
        if (Boolean.TRUE.equals(item.getFaultFlag()) && !Boolean.TRUE.equals(item.getManualFlag())) {
            if (!liveFaultCapable.getOrDefault(targetStationId, false)) {
                Long alt = pickAutoStation(remainCapacity, freePilePool, distances,
                        liveFaultCapable, true);
                if (alt != null) {
                    targetStationId = alt;
                    rerouted = true;
                } else {
                    return block(item, "计划接收站「" + plannedTarget.getName()
                            + "」已不具备在岗且无工单冲突的维修工，且没有其他可接故障车的站点");
                }
            }
        }
        Station target = stationById.get(targetStationId);

        if (!BikeDict.STATION_OPEN.equals(target.getStatus())) {
            if (!Boolean.TRUE.equals(item.getManualFlag()) && !rerouted) {
                Long alt = pickAutoStation(remainCapacity, freePilePool, distances,
                        liveFaultCapable, Boolean.TRUE.equals(item.getFaultFlag()));
                if (alt != null) {
                    targetStationId = alt;
                    target = stationById.get(alt);
                    rerouted = true;
                }
            }
            if (!BikeDict.STATION_OPEN.equals(target.getStatus())) {
                return block(item, "接收站「" + target.getName() + "」已停用，车辆不能调入");
            }
        }

        // 容量（实时 + 本批量账本，绝不突破）
        if (remainCapacity.getOrDefault(targetStationId, 0) <= 0) {
            if (!Boolean.TRUE.equals(item.getManualFlag()) && !rerouted) {
                Long alt = pickAutoStation(remainCapacity, freePilePool, distances,
                        liveFaultCapable, Boolean.TRUE.equals(item.getFaultFlag()));
                if (alt != null) {
                    targetStationId = alt;
                    target = stationById.get(alt);
                    rerouted = true;
                }
            }
            if (remainCapacity.getOrDefault(targetStationId, 0) <= 0) {
                return block(item, "接收站「" + target.getName() + "」剩余容量不足，清站不能超容量安置");
            }
        }

        // 目标桩位：
        //  · 人工指定桩位 → 锁内重验；失效则受阻（运营人员的硬约束失败，不偷偷换桩）；
        //  · 自动方案 → 从该站空桩池按序逐个锁定重验，取第一个真正可用的桩。
        Pile targetPile;
        Long pinnedPileId = !rerouted && item.getPlanPileId() != null ? item.getPlanPileId() : null;
        if (pinnedPileId != null) {
            targetPile = lockAndValidatePile(pinnedPileId, targetStationId);
            if (targetPile == null) {
                if (Boolean.TRUE.equals(item.getManualFlag())) {
                    return block(item, "指定的目标桩位已停用或被占用");
                }
                targetPile = lockNextFreePile(freePilePool, targetStationId);
            }
        } else {
            targetPile = lockNextFreePile(freePilePool, targetStationId);
        }
        if (targetPile == null) {
            return block(item, "接收站「" + target.getName() + "」没有可用空桩位");
        }

        // 锁源桩 + 车辆（按 pile id / bike id 升序，和借车流程同一套顺序，避免互等）
        Bike bike = bikeRepository.findByIdForUpdate(item.getBikeId())
                .orElse(null);
        if (bike == null) {
            return block(item, "车辆台账中已不存在");
        }
        Pile sourcePile = bike.getPileId() == null ? null
                : slotService.lockPile(bike.getPileId(), "执行清站");

        // 车辆当前状态：方案生成后这辆车可能已被借走 / 已被调拨到别站 / 送修 / 报废
        if (BikeDict.BIKE_RIDING.equals(bike.getStatus())) {
            return block(item, "车辆在方案生成后被借走，当前骑行中，等还车后再执行");
        }
        if (!BikeDict.BIKE_PILE.equals(bike.getStatus())) {
            return block(item, "车辆已不在桩（当前状态："
                    + BikeDict.bikeStatusName(bike.getStatus()) + "），无法按清站方案迁移");
        }
        if (!source.getId().equals(bike.getStationId())) {
            // 车曾被借走又还到了别的站（或已被其他流程调拨走）：它已经在别的站点安全入桩，
            // 不是清站流程迁的，但「实际去向」如实记录成当前落点并计为已迁出 ——
            // 既不重复迁移、不重复占容量，也不让一辆早已不在源站的车把单据永久卡死。
            Station actualStation = stationRepository.findById(bike.getStationId()).orElse(null);
            if (actualStation != null && bike.getPileId() != null) {
                item.setPlanStationId(actualStation.getId());
                item.setPlanPileId(null);
                item.setActualStationId(actualStation.getId());
                item.setActualPileId(bike.getPileId());
                item.setItemStatus(BikeDict.ITEM_MOVED);
                item.setFailReason(null);
                item.setMovedAt(now);
                // 车不在本流程的目标站账本里（库内实时计数已包含它），不能再扣本批容量/桩位
                return MoveOutcome.MOVED;
            }
            return block(item, "车辆已不在源站「" + source.getName() + "」且未正常入桩，状态不一致");
        }
        if (sourcePile == null || bike.getPileId() == null) {
            return block(item, "车辆当前没有占用源站桩位，状态不一致");
        }

        // 同一事务内迁移：摘源桩 → 落目标桩。bike.pile_id 唯一索引兜底一桩一车
        slotService.releasePile(bike);
        slotService.place(bike, target, targetPile);

        item.setPlanStationId(targetStationId);
        item.setPlanPileId(null);
        item.setActualStationId(targetStationId);
        item.setActualPileId(targetPile.getId());
        item.setItemStatus(BikeDict.ITEM_MOVED);
        item.setFailReason(null);
        item.setMovedAt(now);

        // 这个桩若也在内存空桩池里（自动选出来的就是），同步摘除；人工指定的不在池中无妨
        consume(remainCapacity, freePilePool, targetStationId, targetPile.getId());
        return MoveOutcome.MOVED;
    }

    /** 锁定指定桩并校验：属于该站、未停用、无车占用；不满足返回 null（不抛异常）。 */
    private Pile lockAndValidatePile(Long pileId, Long stationId) {
        Pile pile = slotService.lockPile(pileId, "执行清站");
        if (!pile.getStationId().equals(stationId)) {
            return null;
        }
        if (BikeDict.PILE_DISABLED.equals(pile.getStatus())) {
            return null;
        }
        if (bikeRepository.findByPileId(pileId).isPresent()) {
            return null;
        }
        return pile;
    }

    /** 从内存空桩池里按序取桩、锁定并实时校验，返回第一个可用的；全部失效返回 null。 */
    private Pile lockNextFreePile(Map<Long, List<Long>> freePilePool, Long stationId) {
        List<Long> pool = freePilePool.get(stationId);
        while (pool != null && !pool.isEmpty()) {
            Long candidate = pool.remove(0);
            Pile pile = lockAndValidatePile(candidate, stationId);
            if (pile != null) {
                return pile;
            }
        }
        return null;
    }

    private MoveOutcome block(EvacuationItem item, String reason) {
        item.setItemStatus(BikeDict.ITEM_BLOCKED);
        item.setFailReason(reason);
        return MoveOutcome.BLOCKED;
    }

    /**
     * 执行时自动改派：在接收站里挑最近的一站（还有容量、有空桩；
     * faultOnly=true 时还必须实时具备在岗且无工单冲突的维修工）。
     */
    private Long pickAutoStation(Map<Long, Integer> remainCapacity,
                                 Map<Long, List<Long>> freePilePool,
                                 Map<Long, BigDecimal> distances,
                                 Map<Long, Boolean> liveFaultCapable,
                                 boolean faultOnly) {
        List<Long> candidates = remainCapacity.keySet().stream()
                .filter(sid -> remainCapacity.getOrDefault(sid, 0) > 0)
                .filter(sid -> !freePilePool.getOrDefault(sid, List.of()).isEmpty())
                .filter(sid -> !faultOnly || liveFaultCapable.getOrDefault(sid, false))
                .toList();
        return nearest(candidates, distances);
    }

    // ==================================================================
    // 方案自动分配
    // ==================================================================

    /**
     * 自动分配规则：故障车优先送到具备在岗维修工且无工单冲突的站点、普通车就近；
     * 两类内部都按距离从近到远，容量与空桩逐辆扣减，排不下就留空（planStationId=null）。
     *
     * @param distances    每个接收站距源站的直线距离
     * @param faultCapable 每个接收站是否具备故障车接收能力（创建快照 / 执行实时）
     */
    private void assignAutoPlans(List<EvacuationItem> items,
                                 Map<Long, Station> targets,
                                 Map<Long, Integer> remainCapacity,
                                 Map<Long, List<Long>> freePiles,
                                 Map<Long, BigDecimal> distances,
                                 Map<Long, Boolean> faultCapable) {
        List<EvacuationItem> faults = items.stream()
                .filter(i -> Boolean.TRUE.equals(i.getFaultFlag()))
                .sorted(Comparator.comparing(EvacuationItem::getId))
                .toList();
        List<EvacuationItem> normals = items.stream()
                .filter(i -> !Boolean.TRUE.equals(i.getFaultFlag()))
                .sorted(Comparator.comparing(EvacuationItem::getId))
                .toList();

        assignGroup(faults, remainCapacity, freePiles, distances, faultCapable, true);
        assignGroup(normals, remainCapacity, freePiles, distances, faultCapable, false);
    }

    private void assignGroup(List<EvacuationItem> group,
                             Map<Long, Integer> remainCapacity,
                             Map<Long, List<Long>> freePiles,
                             Map<Long, BigDecimal> distances,
                             Map<Long, Boolean> faultCapable,
                             boolean faultOnly) {
        for (EvacuationItem item : group) {
            // 进到这里的都是「暂无去向」的车；人工已指定过去向的车由调用方先扣账本，不在本批内。
            List<Long> candidates = remainCapacity.keySet().stream()
                    .filter(sid -> remainCapacity.getOrDefault(sid, 0) > 0)
                    .filter(sid -> !freePiles.getOrDefault(sid, List.of()).isEmpty())
                    .filter(sid -> !faultOnly || faultCapable.getOrDefault(sid, false))
                    .toList();
            Long chosen = nearest(candidates, distances);
            if (chosen == null) {
                // 故障车在维修能力站放不下也绝不偷偷降级到无维修能力站；
                // 普通车所有站都满同样留空 —— 明确「暂无处安置」，不伪造完整方案
                item.setPlanStationId(null);
                item.setPlanPileId(null);
                continue;
            }
            item.setPlanStationId(chosen);
            // 不预先占死具体桩位（执行时实时选），避免方案生成后桩位变化导致大批误判
            item.setPlanPileId(null);
            // 上一轮受阻的车拿到新去向，恢复为「已计划」并清掉旧失败原因
            if (BikeDict.ITEM_BLOCKED.equals(item.getItemStatus())) {
                item.setItemStatus(BikeDict.ITEM_PLANNED);
            }
            item.setFailReason(null);
            // 只占容量额度；空桩不在此预扣（建池时已按实时库排除占桩，执行时逐辆实时锁桩）
            remainCapacity.merge(chosen, -1, Integer::sum);
        }
    }

    /** 在候选站点里取距源站最近的一个（距离缺失的排最后）。 */
    private Long nearest(List<Long> candidateIds, Map<Long, BigDecimal> distances) {
        return candidateIds.stream()
                .min(Comparator.comparing(
                        (Long sid) -> distances.getOrDefault(sid, BigDecimal.valueOf(Double.MAX_VALUE))))
                .orElse(null);
    }

    private void consume(Map<Long, Integer> remainCapacity,
                         Map<Long, List<Long>> freePiles,
                         Long stationId, Long pileId) {
        if (stationId == null) {
            return;
        }
        remainCapacity.merge(stationId, -1, Integer::sum);
        List<Long> pool = freePiles.get(stationId);
        if (pool != null) {
            if (pileId != null) {
                pool.remove(pileId);
            } else if (!pool.isEmpty()) {
                pool.remove(0);
            }
        }
    }

    // ==================================================================
    // 完成判定
    // ==================================================================

    /**
     * 源站能否真正停用：
     *   1. 站内没有任何车（含维修区，清站只迁在桩车，维修车会在这里被拦住）；
     *   2. 没有以该站为起点、仍在途的租借；
     *   3. 站内车辆没有未关闭工单；
     *   4. 没有仍被占用的桩位。
     * 返回所有不满足项的中文说明；空列表表示可以停用。
     */
    private List<String> sourceFinishBlockers(Station source) {
        List<String> blockers = new ArrayList<>();

        long bikesAtStation = bikeRepository.countByStationId(source.getId());
        if (bikesAtStation > 0) {
            blockers.add("源站仍有 " + bikesAtStation + " 辆车（含维修区），车辆未全部迁出");
        }

        long riding = rentalRepository.countByStartStationIdAndStatus(
                source.getId(), BikeDict.RENTAL_RIDING);
        if (riding > 0) {
            blockers.add("源站还有 " + riding + " 张在途租借未还车");
        }

        List<Long> bikeIds = bikeRepository.findByStationIdOrderByPileIdAsc(source.getId()).stream()
                .map(Bike::getId).toList();
        if (!bikeIds.isEmpty()) {
            long unclosed = workOrderRepository.countByBikeIdInAndStatusIn(
                    bikeIds, BikeDict.ORDER_UNCLOSED_STATUS);
            if (unclosed > 0) {
                blockers.add("站内车辆还有 " + unclosed + " 张未关闭工单");
            }
        }

        long occupiedPiles = pileRepository.findByStationIdOrderByPileNoAsc(source.getId()).stream()
                .filter(p -> BikeDict.PILE_OCCUPIED.equals(p.getStatus()))
                .count();
        if (occupiedPiles > 0) {
            blockers.add("源站仍有 " + occupiedPiles + " 个桩位被占用");
        }
        return blockers;
    }

    // ==================================================================
    // 组装详情
    // ==================================================================

    private Views.EvacuationDetail detail(Long id) {
        EvacuationOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("清站单不存在（id=" + id + "）"));
        Map<Long, Station> stations = stationMap();
        Map<Long, Bike> bikes = bikeMap();
        Map<Long, Pile> piles = pileMap();

        Station source = stations.get(order.getSourceStationId());
        List<EvacuationTarget> targets = targetRepository.findByEvacuationIdOrderByIdAsc(id);
        List<EvacuationItem> items = itemRepository.findByEvacuationIdOrderByIdAsc(id);

        Map<Long, Long> bikeCounts = bikeCounts();
        Map<Long, Long> freePileCounts = freePileCounts();

        // 每个接收站的计划/实入数量
        Map<Long, Long> plannedIn = new HashMap<>();
        Map<Long, Long> movedIn = new HashMap<>();
        for (EvacuationItem it : items) {
            if (it.getPlanStationId() != null && !BikeDict.ITEM_MOVED.equals(it.getItemStatus())) {
                plannedIn.merge(it.getPlanStationId(), 1L, Long::sum);
            }
            if (it.getActualStationId() != null) {
                movedIn.merge(it.getActualStationId(), 1L, Long::sum);
            }
        }

        List<Views.EvacuationTargetView> targetViews = new ArrayList<>();
        Set<Long> targetIdSet = new HashSet<>();
        for (EvacuationTarget t : targets) {
            Station st = stations.get(t.getStationId());
            targetIdSet.add(t.getStationId());
            boolean open = st != null && BikeDict.STATION_OPEN.equals(st.getStatus());
            boolean liveFault = st != null && open && faultWorker(st.getId(), "查询清站单").faultCapable;
            targetViews.add(new Views.EvacuationTargetView(
                    t.getStationId(),
                    st == null ? null : st.getCode(),
                    st == null ? "已删除站点" : st.getName(),
                    t.getDistanceKm(),
                    Boolean.TRUE.equals(t.getFaultCapable()),
                    open,
                    liveFault,
                    st == null ? 0 : Math.max(0, st.getCapacity() - bikeCounts.getOrDefault(st.getId(), 0L)),
                    freePileCounts.getOrDefault(t.getStationId(), 0L),
                    plannedIn.getOrDefault(t.getStationId(), 0L),
                    movedIn.getOrDefault(t.getStationId(), 0L)));
        }

        List<Views.EvacuationItemView> itemViews = new ArrayList<>();
        for (EvacuationItem it : items) {
            Bike b = bikes.get(it.getBikeId());
            Station planSt = it.getPlanStationId() == null ? null : stations.get(it.getPlanStationId());
            Pile planPile = it.getPlanPileId() == null ? null : piles.get(it.getPlanPileId());
            Station actSt = it.getActualStationId() == null ? null : stations.get(it.getActualStationId());
            Pile actPile = it.getActualPileId() == null ? null : piles.get(it.getActualPileId());
            itemViews.add(new Views.EvacuationItemView(
                    it.getId(),
                    it.getBikeId(),
                    it.getBikeCode(),
                    b == null ? null : b.getModel(),
                    b == null ? null : b.getStatus(),
                    b == null ? null : BikeDict.bikeStatusName(b.getStatus()),
                    Boolean.TRUE.equals(it.getFaultFlag()),
                    it.getPlanStationId(),
                    planSt == null ? null : planSt.getName(),
                    it.getPlanPileId(),
                    planPile == null ? null : planPile.getPileNo(),
                    it.getActualStationId(),
                    actSt == null ? null : actSt.getName(),
                    it.getActualPileId(),
                    actPile == null ? null : actPile.getPileNo(),
                    it.getItemStatus(),
                    BikeDict.itemStatusName(it.getItemStatus()),
                    it.getFailReason(),
                    it.getMovedAt()));
        }

        int total = items.size();
        int moved = (int) items.stream().filter(i -> BikeDict.ITEM_MOVED.equals(i.getItemStatus())).count();
        int blocked = (int) items.stream().filter(i -> BikeDict.ITEM_BLOCKED.equals(i.getItemStatus())).count();
        int planned = (int) items.stream().filter(i -> BikeDict.ITEM_PLANNED.equals(i.getItemStatus())).count();
        int unplaced = (int) items.stream()
                .filter(i -> !BikeDict.ITEM_MOVED.equals(i.getItemStatus()) && i.getPlanStationId() == null).count();

        // 源站实时体检（完成判定四项的当前值）
        long sourceBikeCount = bikeRepository.countByStationId(order.getSourceStationId());
        long sourceRentals = rentalRepository.countByStartStationIdAndStatus(
                order.getSourceStationId(), BikeDict.RENTAL_RIDING);
        List<Long> sourceBikeIds = bikeRepository
                .findByStationIdOrderByPileIdAsc(order.getSourceStationId()).stream().map(Bike::getId).toList();
        long sourceUnclosed = sourceBikeIds.isEmpty() ? 0
                : workOrderRepository.countByBikeIdInAndStatusIn(sourceBikeIds, BikeDict.ORDER_UNCLOSED_STATUS);
        long sourceOccupied = pileRepository.findByStationIdOrderByPileNoAsc(order.getSourceStationId()).stream()
                .filter(p -> BikeDict.PILE_OCCUPIED.equals(p.getStatus())).count();

        // 可补选的接收站（除源站、除已选）
        List<Views.ReceiverOption> available = receiverOptions(order.getSourceStationId()).stream()
                .filter(o -> !targetIdSet.contains(o.stationId()))
                .toList();

        return new Views.EvacuationDetail(
                order.getId(),
                order.getOrderNo(),
                order.getSourceStationId(),
                source == null ? null : source.getCode(),
                source == null ? null : source.getName(),
                source == null ? null : source.getStatus(),
                order.getStatus(),
                BikeDict.evacStatusName(order.getStatus()),
                order.getRemark(),
                order.getFinishBlocker(),
                total,
                moved,
                blocked,
                planned,
                unplaced,
                sourceBikeCount,
                sourceRentals,
                sourceUnclosed,
                sourceOccupied,
                order.getCreatedAt(),
                order.getStartedAt(),
                order.getExecutedAt(),
                order.getFinishedAt(),
                order.getCancelledAt(),
                targetViews,
                itemViews,
                available);
    }

    private Views.EvacuationExecuteResult executeResult(EvacuationOrder order,
                                                        int movedThisRound,
                                                        int blockedThisRound,
                                                        boolean sourceClosed,
                                                        String message) {
        Views.EvacuationDetail d = detail(order.getId());
        return new Views.EvacuationExecuteResult(
                message,
                order.getOrderNo(),
                order.getStatus(),
                BikeDict.evacStatusName(order.getStatus()),
                movedThisRound,
                blockedThisRound,
                d.movedCount(),
                d.blockedCount(),
                d.plannedCount(),
                sourceClosed,
                order.getFinishBlocker(),
                d.items());
    }

    // ==================================================================
    // 内部只读工具
    // ==================================================================

    private EvacuationOrder lockOrder(Long id, String action) {
        if (id == null) {
            throw new BizException(action + "失败：清站单不能为空");
        }
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException(action + "失败：清站单不存在（id=" + id + "）"));
    }

    private record WorkerInfo(boolean faultCapable, String summary) {
    }

    /**
     * 一个站点是否「具备在岗维修工且没有未完成工单冲突」：
     * 至少有一名 ON_DUTY 且名下没有 ASSIGNED 未完工工单的常驻维修工。
     * summary 给页面直接显示判定依据。
     */
    private WorkerInfo faultWorker(Long stationId, String action) {
        List<Worker> onDuty = workerRepository
                .findByStationIdAndStatusOrderByIdAsc(stationId, BikeDict.WORKER_ON_DUTY);
        if (onDuty.isEmpty()) {
            return new WorkerInfo(false, "无在岗维修工");
        }
        List<String> free = new ArrayList<>();
        List<String> busy = new ArrayList<>();
        for (Worker w : onDuty) {
            long open = workOrderRepository.countByWorkerIdAndStatusIn(
                    w.getId(), List.of(BikeDict.ORDER_ASSIGNED));
            if (open > 0) {
                busy.add(w.getName() + "（" + open + " 单在途）");
            } else {
                free.add(w.getName());
            }
        }
        if (!free.isEmpty()) {
            String summary = "在岗维修工 " + String.join("、", free) + "，无未完工工单冲突";
            return new WorkerInfo(true, summary);
        }
        return new WorkerInfo(false, "在岗维修工均有未完工工单冲突：" + String.join("、", busy));
    }

    private Set<Long> bikesWithUnclosedOrders(List<Long> bikeIds) {
        Set<Long> set = new HashSet<>();
        for (Long id : bikeIds) {
            if (workOrderRepository.countByBikeIdAndStatusIn(id, BikeDict.ORDER_UNCLOSED_STATUS) > 0) {
                set.add(id);
            }
        }
        return set;
    }

    private Map<Long, Station> stationMap() {
        Map<Long, Station> map = new HashMap<>();
        for (Station s : stationRepository.findAll()) {
            map.put(s.getId(), s);
        }
        return map;
    }

    private Map<Long, Bike> bikeMap() {
        Map<Long, Bike> map = new HashMap<>();
        for (Bike b : bikeRepository.findAll()) {
            map.put(b.getId(), b);
        }
        return map;
    }

    private Map<Long, Pile> pileMap() {
        Map<Long, Pile> map = new HashMap<>();
        for (Pile p : pileRepository.findAll()) {
            map.put(p.getId(), p);
        }
        return map;
    }

    private Map<Long, Long> bikeCounts() {
        Map<Long, Long> map = new HashMap<>();
        for (Bike b : bikeRepository.findAll()) {
            if (b.getStationId() != null) {
                map.merge(b.getStationId(), 1L, Long::sum);
            }
        }
        return map;
    }

    /** 站点实时可用空桩：状态 IDLE 且没有车占用（一桩一车以 bike.pile_id 为准）。 */
    private Map<Long, Long> freePileCounts() {
        Map<Long, Long> map = new HashMap<>();
        for (Pile p : pileRepository.findAll()) {
            if (BikeDict.PILE_IDLE.equals(p.getStatus())) {
                map.merge(p.getStationId(), 1L, Long::sum);
            }
        }
        return map;
    }

    /** 当前被车占用的全部桩位 id（一桩一车以 bike.pile_id 为准，一次查全表共用）。 */
    private Set<Long> occupiedPileIds() {
        return bikeRepository.findAll().stream()
                .map(Bike::getPileId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** 站点实时可用空桩 id（按桩位号排序）：状态 IDLE 且没有车占用。 */
    private List<Long> freePileIds(Long stationId, Set<Long> occupiedPileIds) {
        return pileRepository.findByStationIdOrderByPileNoAsc(stationId).stream()
                .filter(p -> BikeDict.PILE_IDLE.equals(p.getStatus()))
                .filter(p -> !occupiedPileIds.contains(p.getId()))
                .map(Pile::getId)
                .sorted()
                .toList();
    }
}
