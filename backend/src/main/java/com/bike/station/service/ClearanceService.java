package com.bike.station.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bike.station.common.BizException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.ClearanceDict;
import com.bike.station.domain.ClearanceItem;
import com.bike.station.domain.ClearanceOrder;
import com.bike.station.domain.ClearanceReceiver;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Station;
import com.bike.station.domain.Worker;
import com.bike.station.dto.ClearanceDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.ClearanceItemRepository;
import com.bike.station.repository.ClearanceOrderRepository;
import com.bike.station.repository.ClearanceReceiverRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.RentalRepository;
import com.bike.station.repository.StationRepository;
import com.bike.station.repository.WorkOrderRepository;
import com.bike.station.repository.WorkerRepository;

/**
 * 应急清站（模块五）。
 *
 * 一条不可动摇的取舍：在「尽量多迁出车辆」和「绝不突破接收站容量及桩位约束」之间，
 * 永远以后者为准 —— 接收能力不足时明确把车列为「暂时无处安置」，
 * 绝不能伪造一张看起来全部迁完的方案，更不能把源站标成已清空。
 *
 * 方案原则：
 *  1. 故障车（维修中，或挂着 REPORTED/ASSIGNED 工单）优先送到
 *     「有在岗维修工且这些维修工名下都没有未完成工单」的接收站；
 *  2. 普通车按距源站直线距离就近安置；
 *  3. 方案可人工逐辆调整（也可显式改回无处安置）。
 *
 * 执行口径：
 *  - 一次执行在单个事务内逐辆迁移，某一辆失败只把该辆标 BLOCKED、车与统计原样保留，
 *    其它可迁车辆照常完成，整单不回滚；
 *  - 车辆当前状态、接收站是否仍运营、容量、桩位可用性、工单占用都在锁内实时重判；
 *  - 每辆车最多成功迁出一次（MIGRATED 行有唯一的 actual 落点，重复/并发执行只回进度）；
 *  - 只有所有车辆迁出且源站没有在途租借、未关闭工单、占用桩位，才 COMPLETED 并停用源站；
 *  - 撤销只允许发生在尚未迁出任何车辆时。
 *
 * 加锁顺序沿用全仓约定「站点 → 桩位 → 车辆 → 订单/工单」，
 * 清站单行锁是整次执行的串行锚点，最先获取。
 */
@Service
public class ClearanceService {

    private static final DateTimeFormatter ORDER_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ClearanceOrderRepository orderRepository;
    private final ClearanceItemRepository itemRepository;
    private final ClearanceReceiverRepository receiverRepository;
    private final StationRepository stationRepository;
    private final PileRepository pileRepository;
    private final BikeRepository bikeRepository;
    private final WorkerRepository workerRepository;
    private final WorkOrderRepository workOrderRepository;
    private final RentalRepository rentalRepository;
    private final SlotService slotService;
    private final PlatformTransactionManager transactionManager;

    public ClearanceService(ClearanceOrderRepository orderRepository,
                            ClearanceItemRepository itemRepository,
                            ClearanceReceiverRepository receiverRepository,
                            StationRepository stationRepository,
                            PileRepository pileRepository,
                            BikeRepository bikeRepository,
                            WorkerRepository workerRepository,
                            WorkOrderRepository workOrderRepository,
                            RentalRepository rentalRepository,
                            SlotService slotService,
                            PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.receiverRepository = receiverRepository;
        this.stationRepository = stationRepository;
        this.pileRepository = pileRepository;
        this.bikeRepository = bikeRepository;
        this.workerRepository = workerRepository;
        this.workOrderRepository = workOrderRepository;
        this.rentalRepository = rentalRepository;
        this.slotService = slotService;
        this.transactionManager = transactionManager;
    }

    /**
     * 在一个独立的【只读】事务里组装详情：当另一个执行事务正在跑时，
     * 这里读到的是数据库已提交的最新进度，不会带上当前事务持久化上下文里的旧快照。
     */
    private Views.ClearanceOrderView freshView(Long orderId) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setReadOnly(true);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt.execute(status -> toOrderView(requireOrder(orderId)));
    }

    // ==================================================================
    // 查询：列表 / 详情 / 方案预览
    // ==================================================================

    public List<Views.ClearanceListItem> list(String status) {
        List<ClearanceOrder> orders;
        if (status == null || status.isBlank()) {
            orders = orderRepository.findAllByOrderByIdDesc();
        } else {
            if (!ClearanceDict.isValidOrderStatus(status)) {
                throw new BizException("清站单状态取值非法：" + status);
            }
            orders = orderRepository.findByStatusOrderByIdDesc(status.trim().toUpperCase());
        }
        Map<Long, Station> stations = stationMap();
        Map<Long, List<ClearanceReceiver>> receivers = receiversByOrder(
                orders.stream().map(ClearanceOrder::getId).toList());
        List<Views.ClearanceListItem> list = new ArrayList<>(orders.size());
        for (ClearanceOrder o : orders) {
            Station src = stations.get(o.getSourceStationId());
            List<String> names = receivers.getOrDefault(o.getId(), List.of()).stream()
                    .map(r -> {
                        Station s = stations.get(r.getStationId());
                        return s == null ? "#" + r.getStationId() : s.getName();
                    })
                    .toList();
            list.add(new Views.ClearanceListItem(
                    o.getId(),
                    o.getOrderNo(),
                    o.getSourceStationId(),
                    src == null ? null : src.getCode(),
                    src == null ? null : src.getName(),
                    src == null ? null : src.getStatus(),
                    o.getStatus(),
                    ClearanceDict.orderStatusName(o.getStatus()),
                    nz(o.getTotalCount()),
                    nz(o.getPlannedCount()),
                    nz(o.getMigratedCount()),
                    nz(o.getBlockedCount()),
                    o.getPlanRemark(),
                    o.getLastExecuteResult(),
                    o.getLastExecuteTime(),
                    o.getCompletedTime(),
                    o.getCancelledTime(),
                    o.getCreatedAt(),
                    names));
        }
        return list;
    }

    public Views.ClearanceOrderView detail(Long id) {
        ClearanceOrder order = requireOrder(id);
        return toOrderView(order);
    }

    /**
     * 建单前的方案预览（不落库）。运营人选好源站和接收站后，
     * 先看到源站待迁车辆、各接收站实时剩余容量/空桩/维修工能力与自动分配结果。
     */
    public Views.ClearancePreviewView preview(ClearanceDtos.ClearanceCreate req) {
        PreviewInput input = prepareInput(req);
        PlanBundle plan = buildPlan(input.source(), input.receivers());
        return new Views.ClearancePreviewView(
                input.source().getId(),
                input.source().getCode(),
                input.source().getName(),
                plan.assignments().size(),
                plan.plannedCount(),
                plan.assignments().size() - plan.plannedCount(),
                plan.remark(),
                plan.stationInfos(),
                toBikeViews(plan.assignments(), plan.liveContext().bikes(), plan.liveContext()));
    }

    // ==================================================================
    // 创建清站单
    // ==================================================================

    @Transactional
    public Views.ClearanceOrderView create(ClearanceDtos.ClearanceCreate req) {
        PreviewInput input = prepareInput(req);

        List<ClearanceOrder> active = orderRepository.findBySourceStationIdAndStatusInOrderByIdDesc(
                input.source().getId(), ClearanceDict.ORDER_ACTIVE);
        if (!active.isEmpty()) {
            throw new BizException("创建清站单失败：站点「" + input.source().getName()
                    + "」已有一张进行中的清站单 " + active.get(0).getOrderNo()
                    + "，同一站点不能同时建两张清站单");
        }

        PlanBundle plan = buildPlan(input.source(), input.receivers());
        if (plan.assignments().isEmpty()) {
            throw new BizException("创建清站单失败：站点「" + input.source().getName()
                    + "」当前没有在站车辆（在桩或维修区），不需要清站");
        }

        LocalDateTime now = LocalDateTime.now();
        ClearanceOrder order = new ClearanceOrder();
        order.setOrderNo("CS" + now.format(ORDER_NO_FORMAT));
        order.setSourceStationId(input.source().getId());
        order.setStatus(ClearanceDict.ORDER_PENDING);
        order.setTotalCount(plan.assignments().size());
        order.setPlannedCount(plan.plannedCount());
        order.setMigratedCount(0);
        order.setBlockedCount(plan.assignments().size() - plan.plannedCount());
        order.setPlanRemark(plan.remark());
        order.setCreatedAt(now);
        orderRepository.save(order);

        int seq = 1;
        for (Station receiver : input.receivers()) {
            ClearanceReceiver r = new ClearanceReceiver();
            r.setClearanceId(order.getId());
            r.setStationId(receiver.getId());
            r.setSeq(seq++);
            r.setCreatedAt(now);
            receiverRepository.save(r);
        }

        for (Assignment a : plan.assignments()) {
            ClearanceItem item = new ClearanceItem();
            item.setClearanceId(order.getId());
            item.setBikeId(a.bikeId());
            item.setBikeKind(a.kind());
            item.setItemStatus(a.planStationId() == null
                    ? ClearanceDict.ITEM_UNPLACED : ClearanceDict.ITEM_PLANNED);
            item.setPlanStationId(a.planStationId());
            item.setPlanPileId(a.planPileId());
            item.setFailReason(a.reason());
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            itemRepository.save(item);
        }

        return toOrderView(requireOrder(order.getId()));
    }

    // ==================================================================
    // 人工调整某辆车的计划去向
    // ==================================================================

    @Transactional
    public Views.ClearanceOrderView adjustItem(Long orderId, Long itemId, ClearanceDtos.ItemAdjust req) {
        ClearanceOrder order = lockOrder(orderId);
        if (!ClearanceDict.isActive(order.getStatus())) {
            throw new BizException("调整方案失败：清站单 " + order.getOrderNo()
                    + " 当前为「" + ClearanceDict.orderStatusName(order.getStatus())
                    + "」状态，不能再调整方案");
        }
        ClearanceItem item = itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new BizException("调整方案失败：车辆明细不存在（id=" + itemId + "）"));
        if (!item.getClearanceId().equals(order.getId())) {
            throw new BizException("调整方案失败：该车辆不属于清站单 " + order.getOrderNo());
        }
        if (ClearanceDict.ITEM_MIGRATED.equals(item.getItemStatus())) {
            throw new BizException("调整方案失败：这辆车已经迁出，迁出结果不允许修改");
        }
        if (req == null || (req.targetStationId() == null && !Boolean.TRUE.equals(req.unplace()))) {
            throw new BizException("调整方案失败：请选择一个接收站，或明确改为「暂时无处安置」");
        }

        // 无锁预读车辆，只为提前失败；真正的车辆行锁放到站点/桩位锁之后，
        // 与全仓「站点 → 桩位 → 车辆」的加锁顺序保持一致，避免和借还/调拨互锁。
        Bike bikeProbe = bikeRepository.findById(item.getBikeId())
                .orElseThrow(() -> new BizException("调整方案失败：车辆不存在（id=" + item.getBikeId() + "）"));

        LocalDateTime now = LocalDateTime.now();

        if (Boolean.TRUE.equals(req.unplace()) && req.targetStationId() == null) {
            // 显式改回「无处安置」：只改方案行，不碰站点/桩位/车辆
            item.setPlanStationId(null);
            item.setPlanPileId(null);
            item.setItemStatus(ClearanceDict.ITEM_UNPLACED);
            item.setFailReason("运营人员调整：暂时无处安置");
            item.setUpdatedAt(now);
            recount(order);
            return toOrderView(order);
        }

        Long targetStationId = req.targetStationId();
        Station target = stationRepository.findById(targetStationId)
                .orElseThrow(() -> new BizException("调整方案失败：接收站不存在（id=" + targetStationId + "）"));
        if (targetStationId.equals(order.getSourceStationId())) {
            throw new BizException("调整方案失败：接收站不能是源站本身");
        }
        boolean isReceiver = receiverRepository.findByClearanceIdOrderBySeqAsc(order.getId()).stream()
                .anyMatch(r -> r.getStationId().equals(targetStationId));
        if (!isReceiver) {
            throw new BizException("调整方案失败：站点「" + target.getName()
                    + "」不在本清站单的接收站名单里，请先在接收站范围内选择");
        }

        // 校验全部在锁内实时重判（站点仍运营、容量、桩位、维修工工单冲突只是优先原则，不硬拦人工指定）
        Station lockedTarget = slotService.lockStation(targetStationId, "调整清站方案");
        if (!BikeDict.STATION_OPEN.equals(lockedTarget.getStatus())) {
            throw new BizException("调整方案失败：接收站「" + lockedTarget.getName() + "」已停用");
        }

        // 站点锁之后再锁车辆行（顺序：站点 → 车辆），与借还/调拨一致
        Bike bike = bikeRepository.findByIdForUpdate(item.getBikeId())
                .orElseThrow(() -> new BizException("调整方案失败：车辆不存在（id=" + item.getBikeId() + "）"));
        boolean needsPile = BikeDict.BIKE_PILE.equals(bike.getStatus());
        if (!needsPile && !BikeDict.BIKE_REPAIR.equals(bike.getStatus())) {
            throw new BizException("调整方案失败：车辆 " + bike.getCode() + " 当前状态为「"
                    + BikeDict.bikeStatusName(bike.getStatus()) + "」，不在源站，不能调整进清站方案");
        }

        Long pileId = null;
        if (needsPile) {
            Pile pile;
            if (req.targetPileId() != null) {
                pile = slotService.lockPile(req.targetPileId(), "调整清站方案");
                if (!pile.getStationId().equals(targetStationId)) {
                    throw new BizException("调整方案失败：所选桩位不属于站点「" + lockedTarget.getName() + "」");
                }
                slotService.assertPileUsable(pile, "调整清站方案");
                slotService.assertPileFree(pile, "调整清站方案");
            } else {
                // 自动挑桩也在桩位行锁内挑，避免方案刚存好就被并发还车占走（执行时仍会再锁内重判一次）
                pile = pileRepository.findByStationIdForUpdate(lockedTarget.getId()).stream()
                        .filter(p -> !BikeDict.PILE_DISABLED.equals(p.getStatus()))
                        .filter(p -> bikeRepository.findByPileId(p.getId()).isEmpty())
                        .min(Comparator.comparing(Pile::getId))
                        .orElse(null);
                if (pile == null) {
                    throw new BizException("调整方案失败：站点「" + lockedTarget.getName()
                            + "」当前没有空闲可用桩位，在桩车辆必须落到具体桩位");
                }
            }
            pileId = pile.getId();
        }
        // 容量按「本单计划中会进入该站的其它车」一起预估，保证方案本身不破容量
        int plannedIncoming = 0;
        for (ClearanceItem other : itemRepository.findByClearanceIdOrderByIdAsc(order.getId())) {
            if (other.getId().equals(item.getId()) || other.getItemStatus() == null) {
                continue;
            }
            if (ClearanceDict.ITEM_MIGRATED.equals(other.getItemStatus())) {
                continue;
            }
            if (targetStationId.equals(other.getPlanStationId())) {
                plannedIncoming++;
            }
        }
        long used = bikeRepository.countByStationId(targetStationId);
        long room = lockedTarget.getCapacity() - used - plannedIncoming;
        if (room <= 0) {
            throw new BizException("调整方案失败：站点「" + lockedTarget.getName()
                    + "」剩余容量不足（容量 " + lockedTarget.getCapacity() + "，站内已停 " + used
                    + " 辆，本单还计划迁入 " + plannedIncoming + " 辆）");
        }

        // 故障车人工改派到「不具备维修能力」的站时只给提示性原因，仍尊重人工决定
        String note = null;
        if (ClearanceDict.KIND_FAULT.equals(item.getBikeKind())) {
            String faultBlock = faultBlockReason(targetStationId);
            if (faultBlock != null) {
                note = "人工指定：该站不具备故障车接收条件（" + faultBlock + "），已按运营人员决定保留";
            }
        }

        item.setPlanStationId(targetStationId);
        item.setPlanPileId(pileId);
        item.setItemStatus(ClearanceDict.ITEM_PLANNED);
        item.setFailReason(note);
        item.setUpdatedAt(now);
        recount(order);
        return toOrderView(order);
    }

    // ==================================================================
    // 执行（幂等：重复点击 / 刷新重提 / 双人同点，每辆车最多迁出一次）
    // ==================================================================

    /** 执行令牌的最长存活时间：超过它仍未清，认为上次执行进程已死，允许接管 */
    private static final int EXECUTE_TOKEN_TTL_SECONDS = 120;

    @Transactional
    public Views.ClearanceOrderView execute(Long orderId) {
        ClearanceOrder order = lockOrder(orderId);
        if (ClearanceDict.ORDER_COMPLETED.equals(order.getStatus())
                || ClearanceDict.ORDER_CANCELLED.equals(order.getStatus())) {
            throw new BizException("执行清站失败：清站单 " + order.getOrderNo()
                    + " 当前为「" + ClearanceDict.orderStatusName(order.getStatus())
                    + "」状态，不能再执行");
        }

        // 串行锚点：行锁保证同一时刻只有一个执行请求进入临界区。
        //  - 另一个执行正在进行（token 仍新鲜）→ 不重复迁移任何车，直接返回最新进度；
        //  - token 已超时未清（上次进程崩溃）→ 接管，继续把没迁完的车迁完。
        if (order.getExecutingToken() != null) {
            boolean stale = order.getExecutingAt() == null
                    || order.getExecutingAt().plusSeconds(EXECUTE_TOKEN_TTL_SECONDS)
                            .isBefore(LocalDateTime.now());
            if (!stale) {
                // 另一个执行请求仍在跑：不能在当前事务里直接读持久化上下文（可能是旧快照），
                // 用独立只读事务组装视图，返回给调用方另一个执行事务【已提交】的最新进度
                return freshView(order.getId());
            }
            order.setExecutingToken(null);
            order.setExecutingAt(null);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        order.setExecutingToken(token);
        order.setExecutingAt(LocalDateTime.now());
        order.setStatus(ClearanceDict.ORDER_RUNNING);

        // 1) 锁定全部相关站点（源站 + 仍有效的接收站），统一按 id 升序，避免与借还/调拨死锁
        List<Long> receiverIds = receiverRepository.findByClearanceIdOrderBySeqAsc(order.getId()).stream()
                .map(ClearanceReceiver::getStationId)
                .toList();
        Set<Long> stationIds = new HashSet<>(receiverIds);
        stationIds.add(order.getSourceStationId());
        Map<Long, Station> lockedStations = new HashMap<>();
        for (Long sid : stationIds.stream().sorted().toList()) {
            lockedStations.put(sid, slotService.lockStation(sid, "应急清站"));
        }
        Station source = lockedStations.get(order.getSourceStationId());

        // 2) 锁定各站桩位行与车辆行，得到锁内最新视图（容量计数/空桩/占用都基于它们）
        Map<Long, List<Pile>> pilesByStation = new HashMap<>();
        Map<Long, List<Bike>> bikesByStation = new HashMap<>();
        for (Long sid : stationIds.stream().sorted().toList()) {
            pilesByStation.put(sid, pileRepository.findByStationIdForUpdate(sid));
            bikesByStation.put(sid, bikeRepository.findByStationIdForUpdate(sid));
        }

        // 3) 实时容量账本就是 bikesByStation 本身：车一迁走就同步移行，
        //    它既不会少算（已迁入的车在里面）也不会重复算（不另外加计数器）。
        // 本次执行已占用的桩位
        Set<Long> takenPileIds = new HashSet<>();
        // 本次执行已占用的「空闲维修工」名额（一名空闲维修工本次只接一辆故障车）
        Set<Long> takenWorkerIds = new HashSet<>();

        List<ClearanceItem> pending = itemRepository.findByClearanceIdAndItemStatusInForUpdate(
                orderId, List.of(ClearanceDict.ITEM_PLANNED,
                        ClearanceDict.ITEM_UNPLACED,
                        ClearanceDict.ITEM_BLOCKED));
        // 故障车优先，同类按 bikeId 升序，保证加锁顺序稳定
        pending.sort(Comparator
                .comparing((ClearanceItem i) -> ClearanceDict.KIND_FAULT.equals(i.getBikeKind()) ? 0 : 1)
                .thenComparing(ClearanceItem::getBikeId));

        int succeeded = 0;

        for (ClearanceItem item : pending) {
            Bike bike = bikeRepository.findByIdForUpdate(item.getBikeId()).orElse(null);
            String result;
            if (bike == null) {
                result = blockItem(item, "车辆已不存在（台账中查无此车），无法迁出");
            } else if (BikeDict.isScrapped(bike.getStatus())) {
                // 报废车不占站点、不占桩，视为已离开源站体系，账实一致
                item.setItemStatus(ClearanceDict.ITEM_MIGRATED);
                item.setActualStationId(null);
                item.setActualPileId(null);
                item.setFailReason(null);
                item.setMigratedAt(LocalDateTime.now());
                item.setUpdatedAt(LocalDateTime.now());
                result = "migrated-scrapped";
            } else if (bike.getStationId() != null && !bike.getStationId().equals(source.getId())) {
                // 车已经在别的站（被人工调拨等），实际落点以车辆当前位置为准，计为已迁出
                item.setItemStatus(ClearanceDict.ITEM_MIGRATED);
                item.setActualStationId(bike.getStationId());
                item.setActualPileId(bike.getPileId());
                item.setFailReason(null);
                item.setMigratedAt(LocalDateTime.now());
                item.setUpdatedAt(LocalDateTime.now());
                result = "migrated-elsewhere";
            } else {
                boolean strict = ClearanceDict.ITEM_PLANNED.equals(item.getItemStatus())
                        && item.getPlanStationId() != null;
                result = migrateOne(item, bike, source, lockedStations, pilesByStation, bikesByStation,
                        takenPileIds, takenWorkerIds, strict);
            }

            if ("ok".equals(result)) {
                succeeded++;
            }
        }

        // 4) 重新计数并判定整单状态（完成闸门在锁内实时判定）
        recount(order);
        order.setExecutingToken(null);
        order.setExecutingAt(null);
        order.setLastExecuteTime(LocalDateTime.now());

        boolean allMigrated = order.getTotalCount() != null
                && order.getMigratedCount() >= order.getTotalCount();
        Views.ClearanceGate gate = allMigrated
                ? gateOf(source,
                        bikesByStation.getOrDefault(source.getId(), List.of()),
                        pilesByStation.getOrDefault(source.getId(), List.of()))
                : null;
        if (allMigrated && gate != null
                && gate.bikesAtStation() == 0
                && gate.ridingRentals() == 0
                && gate.openWorkOrders() == 0
                && gate.occupiedPiles() == 0) {
            order.setStatus(ClearanceDict.ORDER_COMPLETED);
            order.setBlockedCount(0);
            order.setCompletedTime(LocalDateTime.now());
            // 只有在这里，源站才真正停用 —— 受阻单永远走不到这一步
            source.setStatus(BikeDict.STATION_CLOSED);
            order.setLastExecuteResult("清站完成：" + order.getTotalCount() + " 辆车全部迁出，源站「"
                    + source.getName() + "」已停用");
        } else {
            List<String> why = new ArrayList<>();
            if (!allMigrated) {
                int left = order.getTotalCount() - order.getMigratedCount();
                why.add("仍有 " + left + " 辆车未迁出（受阻 "
                        + countStatus(order, ClearanceDict.ITEM_BLOCKED) + "、无处安置 "
                        + countStatus(order, ClearanceDict.ITEM_UNPLACED) + "）");
            }
            if (allMigrated && gate != null) {
                if (gate.bikesAtStation() > 0) {
                    why.add("源站仍有 " + gate.bikesAtStation() + " 辆车在站");
                }
                if (gate.ridingRentals() > 0) {
                    why.add("源站还有 " + gate.ridingRentals() + " 张在途租借未还（等车辆还到其它站后再执行）");
                }
                if (gate.openWorkOrders() > 0) {
                    why.add("源站车辆还有 " + gate.openWorkOrders() + " 张未关闭工单（请先在运维工单中处理）");
                }
                if (gate.occupiedPiles() > 0) {
                    why.add("源站仍有 " + gate.occupiedPiles() + " 个被占用桩位");
                }
            }
            order.setStatus(ClearanceDict.ORDER_PARTIALLY_BLOCKED);
            String msg;
            if (order.getMigratedCount() == 0 && why.size() == 1) {
                // 一辆都没迁出去（例如全部暂时无处安置）：仍是可反复执行的受阻单，
                // 但文案明确这次执行没有产生迁移
                msg = "本次未能迁出车辆；" + String.join("；", why)
                        + "。方案已保存，源站保持运营中，条件具备后可再次执行";
            } else {
                msg = "本次迁出 " + succeeded + " 辆；" + String.join("；", why)
                        + "。源站保持运营中，可处理后再次执行";
            }
            if (msg.length() > 255) {
                msg = msg.substring(0, 255);
            }
            order.setLastExecuteResult(msg);
        }

        return toOrderView(order);
    }

    /**
     * 迁移一辆车。返回 "ok" / "unplaced" / "blocked:..."。
     * strict=true 表示这辆车带着人工/方案确认过的计划站，只认计划站、不做就近兜底；
     * UNPLACED / BLOCKED 的车则在实时约束下重新自动分配（尽量多迁出，但不破约束）。
     */
    private String migrateOne(ClearanceItem item,
                              Bike bike,
                              Station source,
                              Map<Long, Station> lockedStations,
                              Map<Long, List<Pile>> pilesByStation,
                              Map<Long, List<Bike>> bikesByStation,
                              Set<Long> takenPileIds,
                              Set<Long> takenWorkerIds,
                              boolean strict) {
        return migrateOne(item, bike, source, lockedStations, pilesByStation, bikesByStation,
                takenPileIds, takenWorkerIds, strict, false);
    }

    /**
     * @param strict        是否带确认过的计划站
     * @param alreadyRerouted 是否已经实时改派过一次（防递归：改派后再失败就直接判受阻/无处安置）
     */
    private String migrateOne(ClearanceItem item,
                              Bike bike,
                              Station source,
                              Map<Long, Station> lockedStations,
                              Map<Long, List<Pile>> pilesByStation,
                              Map<Long, List<Bike>> bikesByStation,
                              Set<Long> takenPileIds,
                              Set<Long> takenWorkerIds,
                              boolean strict,
                              boolean alreadyRerouted) {
        boolean isFault = ClearanceDict.KIND_FAULT.equals(item.getBikeKind());
        boolean needsPile = BikeDict.BIKE_PILE.equals(bike.getStatus());

        // 车辆当前状态先过一道：在桩车必须仍在源站桩位上；维修车必须仍挂在源站
        if (needsPile) {
            if (!BikeDict.BIKE_PILE.equals(bike.getStatus())
                    || bike.getStationId() == null
                    || !bike.getStationId().equals(source.getId())
                    || bike.getPileId() == null) {
                return blockItem(item, "车辆 " + bike.getCode()
                        + " 已不在源站桩位（可能已被借走或移动），本次无法迁出");
            }
        } else if (BikeDict.BIKE_REPAIR.equals(bike.getStatus())) {
            if (bike.getStationId() == null || !bike.getStationId().equals(source.getId())) {
                return blockItem(item, "车辆 " + bike.getCode()
                        + " 已不在源站维修区，本次无法迁出");
            }
        } else {
            return blockItem(item, "车辆 " + bike.getCode() + " 当前状态为「"
                    + BikeDict.bikeStatusName(bike.getStatus()) + "」，无法按清站迁移");
        }

        Long targetStationId = item.getPlanStationId();
        Long targetPileId = item.getPlanPileId();

        if (!strict && targetStationId == null) {
            // 仅对「无处安置 / 上次受阻且无指定站」的车在锁内实时重算落点；
            // 注意：reroute 已经把 alt 写进 item 后会以 strict=true 进来，这里不能再重算一遍
            Assignment a = findLiveTarget(source, item.getBikeKind(), needsPile, lockedStations,
                    pilesByStation, bikesByStation, takenPileIds, takenWorkerIds);
            if (a == null) {
                item.setItemStatus(ClearanceDict.ITEM_UNPLACED);
                item.setPlanStationId(null);
                item.setPlanPileId(null);
                item.setFailReason(isFault
                        ? "所选接收站当前没有「在岗且无未完成工单冲突」的维修能力，或容量/桩位不足"
                        : "所选接收站当前剩余容量或空闲桩位不足");
                item.setUpdatedAt(LocalDateTime.now());
                return "unplaced";
            }
            targetStationId = a.planStationId();
            targetPileId = a.planPileId();
            item.setFailReason(null);
        }

        Station target = lockedStations.get(targetStationId);
        if (target == null) {
            return blockItem(item, "计划接收站已不在本单接收名单内");
        }
        if (!BikeDict.STATION_OPEN.equals(target.getStatus())) {
            return blockItem(item, "计划接收站「" + target.getName() + "」已停用，车辆暂留源站");
        }

        // 容量实时校验：bikesByStation 是随迁移同步更新的锁内视图，
        // 已在站的车 + 本次已迁入的车都在里面，不另加计数器，避免重复扣减容量。
        long usedNow = bikesByStation.getOrDefault(target.getId(), List.of()).size();
        if (usedNow >= target.getCapacity()) {
            if (strict && !alreadyRerouted) {
                // 计划站满了：在接收站范围内实时改派；仍无余量才让这辆车受阻（不影响其它车）
                String rerouted = reroute(item, bike, source, lockedStations, pilesByStation, bikesByStation,
                        takenPileIds, takenWorkerIds, needsPile);
                if (rerouted != null) {
                    return rerouted;
                }
                return blockItem(item, "计划接收站「" + target.getName() + "」容量已满（容量 "
                        + target.getCapacity() + "，当前已停 " + usedNow
                        + "），其它接收站也暂无余量");
            }
            return requeueUnplaced(item, target, needsPile);
        }

        // 故障车：实时确认目标站仍有「在岗且无未完成工单冲突」的维修工名额
        Long chosenWorkerId = null;
        if (isFault) {
            List<Worker> onDuty = workerRepository
                    .findByStationIdAndStatusOrderByIdAsc(target.getId(), BikeDict.WORKER_ON_DUTY);
            for (Worker w : onDuty) {
                if (takenWorkerIds.contains(w.getId())) {
                    continue;
                }
                long openOrders = workOrderRepository.countByWorkerIdAndStatusIn(
                        w.getId(), BikeDict.ORDER_OPEN_STATUS);
                if (openOrders == 0) {
                    chosenWorkerId = w.getId();
                    break;
                }
            }
            if (chosenWorkerId == null) {
                // 计划站失去维修能力：在接收站范围内找仍具备能力的站，找不到才受阻
                String rerouted = alreadyRerouted ? null : reroute(item, bike, source, lockedStations, pilesByStation, bikesByStation,
                        takenPileIds, takenWorkerIds, needsPile);
                if (rerouted != null) {
                    return rerouted;
                }
                String reason = faultBlockReason(target.getId());
                return blockItem(item, "目标站「" + target.getName() + "」当前没有在岗且无未完成工单冲突的维修工"
                        + (reason == null ? "" : "（" + reason + "）") + "，其它接收站也不具备故障车接收条件");
            }
        }

        Pile targetPile = null;
        if (needsPile) {
            List<Bike> targetBikes = bikesByStation.getOrDefault(target.getId(), List.of());
            if (targetPileId == null) {
                // 计划只到站：实时挑一个空桩
                targetPile = pickFreePileLive(targetBikes, pilesByStation.get(target.getId()), takenPileIds);
                if (targetPile == null) {
                    if (strict && !alreadyRerouted) {
                        String rerouted = reroute(item, bike, source, lockedStations, pilesByStation, bikesByStation,
                                takenPileIds, takenWorkerIds, true);
                        if (rerouted != null) {
                            return rerouted;
                        }
                    } else {
                        return requeueUnplaced(item, target, true);
                    }
                    return blockItem(item, "目标站「" + target.getName()
                            + "」当前没有空闲可用桩位，其它接收站也没有空桩");
                }
            } else {
                Long pileId = targetPileId;
                targetPile = pilesByStation.getOrDefault(target.getId(), List.of()).stream()
                        .filter(p -> p.getId().equals(pileId)).findFirst().orElse(null);
                if (targetPile == null) {
                    return blockItem(item, "计划桩位不属于计划接收站「" + target.getName() + "」");
                }
                if (BikeDict.PILE_DISABLED.equals(targetPile.getStatus())
                        || takenPileIds.contains(targetPile.getId())
                        || pileOccupant(targetBikes, targetPile.getId(), bike.getId()) != null) {
                    String why = BikeDict.PILE_DISABLED.equals(targetPile.getStatus())
                            ? "计划桩位「" + targetPile.getPileNo() + "」已停用"
                            : "计划桩位「" + targetPile.getPileNo() + "」已被其它车辆占用";
                    if (strict && !alreadyRerouted) {
                        String rerouted = reroute(item, bike, source, lockedStations, pilesByStation, bikesByStation,
                                takenPileIds, takenWorkerIds, true);
                        if (rerouted != null) {
                            return rerouted;
                        }
                        return blockItem(item, why + "，其它接收站也暂无可用落点");
                    }
                    return requeueUnplaced(item, target, true);
                }
            }
        }

        // —— 全部约束通过：真正迁移（bike 与 pile 在同一事务里一起改，不留半迁移）——
        if (needsPile) {
            slotService.releasePile(bike);
            slotService.place(bike, target, targetPile);
            takenPileIds.add(targetPile.getId());
        } else {
            // 维修区车辆：进目标站维修区，不占桩
            bike.setStationId(target.getId());
            bike.setPileId(null);
            bike.setUpdatedAt(LocalDateTime.now());
        }
        // 同步更新锁内账本，保证本事务后续车辆看到的容量就是迁移后的最新值
        bikesByStation.get(source.getId()).removeIf(b -> b.getId().equals(bike.getId()));
        bikesByStation.computeIfAbsent(target.getId(), k -> new ArrayList<>()).add(bike);
        if (chosenWorkerId != null) {
            takenWorkerIds.add(chosenWorkerId);
        }

        item.setItemStatus(ClearanceDict.ITEM_MIGRATED);
        boolean changedDestination = item.getPlanStationId() != null
                && !target.getId().equals(item.getPlanStationId());
        item.setPlanStationId(target.getId());
        item.setPlanPileId(targetPile == null ? null : targetPile.getId());
        item.setActualStationId(target.getId());
        item.setActualPileId(targetPile == null ? null : targetPile.getId());
        // 执行时因实时约束改派过落点：记录原因，页面仍能看到计划与实际去向的差异
        item.setFailReason(changedDestination
                ? "执行时按实时容量/桩位/工单占用就近改派" : null);
        item.setMigratedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        return "ok";
    }

    /**
     * 计划落点在执行时失效（容量满 / 桩位被占或停用 / 维修工冲突）时，
     * 在接收站范围内实时另找一个可行落点并完成迁移。
     * 找到并迁完返回 "ok"；无处可去返回 null（由调用方决定记 BLOCKED）。
     * 这是「尽量多迁出」的兜底，但 findLiveTarget 内部仍严格守住容量与桩位约束。
     */
    private String reroute(ClearanceItem item,
                           Bike bike,
                           Station source,
                           Map<Long, Station> lockedStations,
                           Map<Long, List<Pile>> pilesByStation,
                           Map<Long, List<Bike>> bikesByStation,
                           Set<Long> takenPileIds,
                           Set<Long> takenWorkerIds,
                           boolean needsPile) {
        Assignment alt = findLiveTarget(source, item.getBikeKind(), needsPile, lockedStations,
                pilesByStation, bikesByStation, takenPileIds, takenWorkerIds);
        if (alt == null) {
            return null;
        }
        item.setPlanStationId(alt.planStationId());
        item.setPlanPileId(alt.planPileId());
        // alt 已写入 item：以 strict + alreadyRerouted 路径完成迁移，失败不再二次改派
        return migrateOne(item, bike, source, lockedStations, pilesByStation, bikesByStation,
                takenPileIds, takenWorkerIds, true, true);
    }

    /** strict 模式下目标站临时不可用时不擅自改派（尊重计划），记受阻；非 strict 兜底回到无处安置 */
    private String requeueUnplaced(ClearanceItem item, Station target, boolean needsPile) {
        item.setItemStatus(ClearanceDict.ITEM_UNPLACED);
        item.setPlanStationId(null);
        item.setPlanPileId(null);
        item.setFailReason("接收站「" + target.getName() + "」"
                + (needsPile ? "剩余容量或空闲桩位不足" : "剩余容量不足"));
        item.setUpdatedAt(LocalDateTime.now());
        return "unplaced";
    }

    private String blockItem(ClearanceItem item, String reason) {
        item.setItemStatus(ClearanceDict.ITEM_BLOCKED);
        item.setFailReason(truncate(reason, 255));
        item.setUpdatedAt(LocalDateTime.now());
        return "blocked";
    }

    // ==================================================================
    // 撤销（仅在尚未迁出任何车辆时）
    // ==================================================================

    @Transactional
    public Views.ClearanceOrderView cancel(Long orderId) {
        ClearanceOrder order = lockOrder(orderId);
        if (ClearanceDict.ORDER_CANCELLED.equals(order.getStatus())) {
            throw new BizException("撤销清站失败：清站单 " + order.getOrderNo() + " 已经撤销");
        }
        if (ClearanceDict.ORDER_COMPLETED.equals(order.getStatus())) {
            throw new BizException("撤销清站失败：清站单 " + order.getOrderNo() + " 已完成，不能撤销");
        }
        if (order.getMigratedCount() != null && order.getMigratedCount() > 0) {
            throw new BizException("撤销清站失败：清站单 " + order.getOrderNo() + " 已迁出 "
                    + order.getMigratedCount() + " 辆车，已迁出的结果不能撤销，请继续执行或保留单据");
        }
        order.setStatus(ClearanceDict.ORDER_CANCELLED);
        order.setExecutingToken(null);
        order.setExecutingAt(null);
        order.setCancelledTime(LocalDateTime.now());
        order.setLastExecuteResult("清站单已撤销（未迁出任何车辆）");
        return toOrderView(order);
    }

    // ==================================================================
    // 方案生成（预览与建单共用，事务内读一致快照）
    // ==================================================================

    private PreviewInput prepareInput(ClearanceDtos.ClearanceCreate req) {
        if (req == null || req.sourceStationId() == null) {
            throw new BizException("创建清站单失败：源站不能为空");
        }
        Station source = stationRepository.findById(req.sourceStationId())
                .orElseThrow(() -> new BizException("创建清站单失败：源站不存在（id="
                        + req.sourceStationId() + "）"));
        List<Long> raw = req.receiverStationIds() == null ? List.of() : req.receiverStationIds();
        LinkedHashMap<Long, Long> distinct = new LinkedHashMap<>();
        int seq = 1;
        for (Long sid : raw) {
            if (sid != null && !distinct.containsKey(sid)) {
                distinct.put(sid, (long) seq++);
            }
        }
        if (distinct.isEmpty()) {
            throw new BizException("创建清站单失败：至少选择一个接收站");
        }
        if (distinct.containsKey(source.getId())) {
            throw new BizException("创建清站单失败：源站「" + source.getName() + "」不能同时作为接收站");
        }
        List<Station> receivers = new ArrayList<>();
        for (Long sid : distinct.keySet()) {
            Station s = stationRepository.findById(sid)
                    .orElseThrow(() -> new BizException("创建清站单失败：接收站不存在（id=" + sid + "）"));
            receivers.add(s);
        }
        return new PreviewInput(source, receivers);
    }

    /**
     * 生成方案：
     *  - 待迁车 = 源站在桩（PILE）+ 维修区（REPAIR）车辆；
     *  - 故障车优先（REPAIR 或挂未完工工单），送往 faultCapable 的接收站；
     *  - 普通车按距离就近；
     *  - 容量与桩位任一不足就明确列为「无处安置」。
     */
    private PlanBundle buildPlan(Station source, List<Station> receivers) {
        List<Bike> waiting = new ArrayList<>();
        for (Bike b : bikeRepository.findByStationIdOrderByPileIdAsc(source.getId())) {
            if (BikeDict.BIKE_PILE.equals(b.getStatus()) || BikeDict.BIKE_REPAIR.equals(b.getStatus())) {
                waiting.add(b);
            }
        }
        List<Long> waitingIds = waiting.stream().map(Bike::getId).toList();
        Set<Long> faultIds = waitingIds.isEmpty() ? Set.of()
                : new HashSet<>(workOrderRepository.findFaultBikeIds(waitingIds, BikeDict.ORDER_OPEN_STATUS));
        for (Bike b : waiting) {
            if (BikeDict.BIKE_REPAIR.equals(b.getStatus())) {
                faultIds.add(b.getId());
            }
        }

        // 实时接收站信息（容量/空桩/维修工）
        Map<Long, List<Pile>> pilesByStation = new HashMap<>();
        Map<Long, String> faultBlockReasons = new HashMap<>();
        List<Views.ClearanceStationInfo> infos = new ArrayList<>();
        int seq = 1;
        for (Station s : receivers) {
            List<Pile> piles = pileRepository.findByStationIdOrderByPileNoAsc(s.getId());
            pilesByStation.put(s.getId(), piles);
            long freePiles = piles.stream()
                    .filter(p -> !BikeDict.PILE_DISABLED.equals(p.getStatus()))
                    .filter(p -> bikeRepository.findByPileId(p.getId()).isEmpty())
                    .count();
            long bikeCount = bikeRepository.countByStationId(s.getId());
            List<String> freeWorkers = new ArrayList<>();
            String faultBlock = null;
            if (!BikeDict.STATION_OPEN.equals(s.getStatus())) {
                faultBlock = "站点已停用";
            } else {
                faultBlock = faultBlockReason(s.getId());
                if (faultBlock == null) {
                    for (Worker w : workerRepository.findByStationIdAndStatusOrderByIdAsc(
                            s.getId(), BikeDict.WORKER_ON_DUTY)) {
                        if (workOrderRepository.countByWorkerIdAndStatusIn(
                                w.getId(), BikeDict.ORDER_OPEN_STATUS) == 0) {
                            freeWorkers.add(w.getName());
                        }
                    }
                }
            }
            faultBlockReasons.put(s.getId(), faultBlock);
            infos.add(new Views.ClearanceStationInfo(
                    s.getId(),
                    s.getCode(),
                    s.getName(),
                    s.getStatus(),
                    s.getCapacity(),
                    bikeCount,
                    freePiles,
                    BikeDict.STATION_OPEN.equals(s.getStatus()) && faultBlock == null,
                    freeWorkers,
                    faultBlock,
                    distanceKm(source, s),
                    seq++));
        }

        // 容量/桩位的方案内账本（已排进去的车也要占名额）
        Map<Long, Integer> planIncoming = new HashMap<>();
        Set<Long> planPiles = new HashSet<>();

        List<Bike> ordered = new ArrayList<>(waiting);
        ordered.sort(Comparator
                .comparing((Bike b) -> faultIds.contains(b.getId()) ? 0 : 1)
                .thenComparing(Bike::getId));

        List<Assignment> assignments = new ArrayList<>();
        int plannedCount = 0;
        List<String> faultMiss = new ArrayList<>();
        List<String> normalMiss = new ArrayList<>();

        for (Bike b : ordered) {
            boolean fault = faultIds.contains(b.getId());
            boolean needsPile = BikeDict.BIKE_PILE.equals(b.getStatus());
            Assignment a = assignOne(b.getId(), fault, needsPile, receivers, pilesByStation,
                    planIncoming, planPiles, faultBlockReasons, source);
            assignments.add(a);
            if (a.planStationId() != null) {
                plannedCount++;
                planIncoming.merge(a.planStationId(), 1, Integer::sum);
                if (a.planPileId() != null) {
                    planPiles.add(a.planPileId());
                }
            } else if (fault) {
                faultMiss.add(b.getCode());
            } else {
                normalMiss.add(b.getCode());
            }
        }

        StringBuilder remark = new StringBuilder();
        if (plannedCount == assignments.size()) {
            remark.append("全部 ").append(assignments.size()).append(" 辆车均已安排去向");
            if (anyFault(assignments)) {
                remark.append("（故障车已优先送往具备在岗维修工的站点）");
            }
        } else {
            remark.append("共 ").append(assignments.size()).append(" 辆待迁，已安排 ").append(plannedCount)
                    .append(" 辆，").append(assignments.size() - plannedCount).append(" 辆暂时无处安置");
            if (!faultMiss.isEmpty()) {
                remark.append("；故障车 ").append(String.join("、", faultMiss))
                        .append(" 缺少具备在岗维修工且无工单冲突的接收站");
            }
            if (!normalMiss.isEmpty()) {
                remark.append("；普通车 ").append(String.join("、", normalMiss)).append(" 受接收站容量/空桩限制");
            }
            remark.append("。可保存为待执行，但源站不会被标成已清空");
        }

        Map<Long, Bike> bikeById = waiting.stream().collect(Collectors.toMap(Bike::getId, b -> b));
        return new PlanBundle(assignments, plannedCount, truncate(remark.toString(), 255),
                infos, new LiveContext(bikeById, stationsToMap(receivers), pilesByStation));
    }

    private Assignment assignOne(Long bikeId,
                                 boolean fault,
                                 boolean needsPile,
                                 List<Station> receivers,
                                 Map<Long, List<Pile>> pilesByStation,
                                 Map<Long, Integer> planIncoming,
                                 Set<Long> planPiles,
                                 Map<Long, String> faultBlockReasons,
                                 Station source) {
        List<Station> candidates = receivers.stream()
                .filter(s -> BikeDict.STATION_OPEN.equals(s.getStatus()))
                .filter(s -> !fault || faultBlockReasons.get(s.getId()) == null)
                .sorted(Comparator.comparingDouble((Station s) -> {
                    Double d = distanceKm(source, s);
                    return d == null ? Double.MAX_VALUE : d;
                }).thenComparing(Station::getId))
                .toList();

        for (Station s : candidates) {
            long used = bikeRepository.countByStationId(s.getId());
            int incoming = planIncoming.getOrDefault(s.getId(), 0);
            if (used + incoming >= s.getCapacity()) {
                continue;
            }
            Long pileId = null;
            if (needsPile) {
                // 空桩口径与全仓一致：bike.pile_id 才是真正的占用关系（一桩一车），
                // pile.status 只是快照 —— 不能只看 IDLE，否则会把两辆车排到同一个桩上。
                Pile free = pilesByStation.getOrDefault(s.getId(), List.of()).stream()
                        .filter(p -> !BikeDict.PILE_DISABLED.equals(p.getStatus()))
                        .filter(p -> !planPiles.contains(p.getId()))
                        .filter(p -> bikeRepository.findByPileId(p.getId()).isEmpty())
                        .min(Comparator.comparing(Pile::getId))
                        .orElse(null);
                if (free == null) {
                    continue;
                }
                pileId = free.getId();
            }
            return new Assignment(bikeId,
                    fault ? ClearanceDict.KIND_FAULT : ClearanceDict.KIND_NORMAL,
                    s.getId(), pileId, null);
        }

        String reason = fault
                ? "所选接收站中没有「运营中、有在岗维修工且无未完成工单冲突」且容量"
                        + (needsPile ? "/空桩" : "") + "充足的站点"
                : "所选接收站容量" + (needsPile ? "或空闲桩位" : "") + "均不足";
        return new Assignment(bikeId,
                fault ? ClearanceDict.KIND_FAULT : ClearanceDict.KIND_NORMAL,
                null, null, reason);
    }

    /** 执行期实时寻找落点（与方案同一套原则，但全部基于锁内数据） */
    private Assignment findLiveTarget(Station source,
                                      String kind,
                                      boolean needsPile,
                                      Map<Long, Station> lockedStations,
                                      Map<Long, List<Pile>> pilesByStation,
                                      Map<Long, List<Bike>> bikesByStation,
                                      Set<Long> takenPileIds,
                                      Set<Long> takenWorkerIds) {
        List<Station> candidates = new ArrayList<>();
        for (Station s : lockedStations.values()) {
            if (s.getId().equals(source.getId())) {
                continue;
            }
            if (!BikeDict.STATION_OPEN.equals(s.getStatus())) {
                continue;
            }
            List<Bike> stationBikes = bikesByStation.getOrDefault(s.getId(), List.of());
            if ((long) stationBikes.size() >= s.getCapacity()) {
                continue;
            }
            if (ClearanceDict.KIND_FAULT.equals(kind)) {
                boolean hasFreeWorker = workerRepository
                        .findByStationIdAndStatusOrderByIdAsc(s.getId(), BikeDict.WORKER_ON_DUTY)
                        .stream()
                        .filter(w -> !takenWorkerIds.contains(w.getId()))
                        .anyMatch(w -> workOrderRepository.countByWorkerIdAndStatusIn(
                                w.getId(), BikeDict.ORDER_OPEN_STATUS) == 0);
                if (!hasFreeWorker) {
                    continue;
                }
            }
            if (needsPile
                    && pickFreePileLive(stationBikes, pilesByStation.get(s.getId()), takenPileIds) == null) {
                continue;
            }
            candidates.add(s);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // 普通车就近（按源站距离），无坐标时退化为按站点 id 稳定排序
        candidates.sort(Comparator.comparingDouble((Station s) -> {
            Double d = distanceKm(source, s);
            return d == null ? Double.MAX_VALUE : d;
        }).thenComparing(Station::getId));
        Station pick = candidates.get(0);
        Pile pile = needsPile
                ? pickFreePileLive(bikesByStation.getOrDefault(pick.getId(), List.of()),
                        pilesByStation.get(pick.getId()), takenPileIds)
                : null;
        return new Assignment(null, kind, pick.getId(), pile == null ? null : pile.getId(), null);
    }

    /** 从锁内的桩位/车辆视图里挑第一个空闲可用桩位（空 = 桩未停用且没有车占用） */
    private Pile pickFreePileLive(List<Bike> stationBikes,
                                  List<Pile> stationPiles,
                                  Set<Long> takenPileIds) {
        if (stationPiles == null) {
            return null;
        }
        return stationPiles.stream()
                .filter(p -> !BikeDict.PILE_DISABLED.equals(p.getStatus()))
                .filter(p -> !takenPileIds.contains(p.getId()))
                .filter(p -> stationBikes.stream().noneMatch(b -> p.getId().equals(b.getPileId())))
                .min(Comparator.comparing(Pile::getId))
                .orElse(null);
    }

    /** 桩位当前的占用车辆（排除正在迁出的这辆自己）；无占用返回 null */
    private Bike pileOccupant(List<Bike> stationBikes, Long pileId, Long selfBikeId) {
        return stationBikes.stream()
                .filter(b -> pileId.equals(b.getPileId()))
                .filter(b -> !b.getId().equals(selfBikeId))
                .findFirst().orElse(null);
    }

    /** 某站为何不具备故障车接收能力；返回 null 表示具备 */
    private String faultBlockReason(Long stationId) {
        List<Worker> onDuty = workerRepository.findByStationIdAndStatusOrderByIdAsc(
                stationId, BikeDict.WORKER_ON_DUTY);
        if (onDuty.isEmpty()) {
            return "没有在岗驻站维修工";
        }
        boolean allBusy = true;
        for (Worker w : onDuty) {
            if (workOrderRepository.countByWorkerIdAndStatusIn(
                    w.getId(), BikeDict.ORDER_OPEN_STATUS) == 0) {
                allBusy = false;
                break;
            }
        }
        return allBusy ? "在岗维修工名下都有未完成工单" : null;
    }

    // ==================================================================
    // 完成闸门
    // ==================================================================

    private Views.ClearanceGate gateOf(Station source, ClearanceOrder order) {
        return gateOf(source,
                bikeRepository.findByStationIdOrderByPileIdAsc(source.getId()),
                pileRepository.findByStationIdOrderByPileNoAsc(source.getId()));
    }

    /**
     * 完成闸门（执行事务内用锁内视图调用）：
     * 在站车辆、占用桩位直接取锁内行；在途租借与未关闭工单实时 count。
     */
    private Views.ClearanceGate gateOf(Station source, List<Bike> bikesAtSource, List<Pile> pilesAtSource) {
        long bikes = bikesAtSource.size();
        long riding = rentalRepository.countByStartStationIdAndStatus(
                source.getId(), BikeDict.RENTAL_RIDING);
        List<Long> bikeIdsAtStation = bikesAtSource.stream().map(Bike::getId).toList();
        long openOrders = bikeIdsAtStation.isEmpty() ? 0
                : workOrderRepository.findOpenBikeIds(bikeIdsAtStation).size();
        long occupied = pilesAtSource.stream()
                .filter(p -> BikeDict.PILE_OCCUPIED.equals(p.getStatus()))
                .count();
        return new Views.ClearanceGate(bikes, riding, openOrders, occupied);
    }

    // ==================================================================
    // 视图装配
    // ==================================================================

    private Views.ClearanceOrderView toOrderView(ClearanceOrder order) {
        Map<Long, Station> stations = stationMap();
        Station source = stations.get(order.getSourceStationId());
        List<ClearanceReceiver> receivers = receiverRepository
                .findByClearanceIdOrderBySeqAsc(order.getId());
        Map<Long, List<Pile>> pilesByStation = new HashMap<>();
        List<Views.ClearanceStationInfo> infos = new ArrayList<>();
        int seq = 1;
        for (ClearanceReceiver r : receivers) {
            Station s = stations.get(r.getStationId());
            if (s == null) {
                continue;
            }
            List<Pile> piles = pileRepository.findByStationIdOrderByPileNoAsc(s.getId());
            pilesByStation.put(s.getId(), piles);
            long freePiles = piles.stream()
                    .filter(p -> !BikeDict.PILE_DISABLED.equals(p.getStatus()))
                    .filter(p -> bikeRepository.findByPileId(p.getId()).isEmpty())
                    .count();
            String faultBlock = BikeDict.STATION_OPEN.equals(s.getStatus())
                    ? faultBlockReason(s.getId()) : "站点已停用";
            List<String> freeWorkers = new ArrayList<>();
            if (faultBlock == null) {
                for (Worker w : workerRepository.findByStationIdAndStatusOrderByIdAsc(
                        s.getId(), BikeDict.WORKER_ON_DUTY)) {
                    if (workOrderRepository.countByWorkerIdAndStatusIn(
                            w.getId(), BikeDict.ORDER_OPEN_STATUS) == 0) {
                        freeWorkers.add(w.getName());
                    }
                }
            }
            infos.add(new Views.ClearanceStationInfo(
                    s.getId(), s.getCode(), s.getName(), s.getStatus(), s.getCapacity(),
                    bikeRepository.countByStationId(s.getId()), freePiles,
                    BikeDict.STATION_OPEN.equals(s.getStatus()) && faultBlock == null,
                    freeWorkers, faultBlock,
                    source == null ? null : distanceKm(source, s), seq++));
        }

        List<ClearanceItem> items = itemRepository.findByClearanceIdOrderByIdAsc(order.getId());
        List<Long> bikeIds = items.stream().map(ClearanceItem::getBikeId).toList();
        Map<Long, Bike> bikeMap = bikeIds.isEmpty() ? Map.of()
                : bikeRepository.findByIdInOrderByIdAsc(bikeIds).stream()
                        .collect(Collectors.toMap(Bike::getId, b -> b, (a, b) -> a));
        Map<Long, String> pileNoMap = new HashMap<>();
        for (Pile p : pileRepository.findAll()) {
            pileNoMap.put(p.getId(), p.getPileNo());
        }
        List<Views.ClearanceBikeView> bikeViews = items.stream()
                .map(i -> toBikeView(i, bikeMap.get(i.getBikeId()), stations, pileNoMap))
                .toList();

        Station lockedSource = source == null ? null
                : stationRepository.findById(source.getId()).orElse(null);
        Views.ClearanceGate gate = lockedSource == null ? null
                : gateOf(lockedSource, order);

        return new Views.ClearanceOrderView(
                order.getId(),
                order.getOrderNo(),
                order.getSourceStationId(),
                source == null ? null : source.getCode(),
                source == null ? null : source.getName(),
                source == null ? null : source.getStatus(),
                order.getStatus(),
                ClearanceDict.orderStatusName(order.getStatus()),
                nz(order.getTotalCount()),
                nz(order.getPlannedCount()),
                nz(order.getMigratedCount()),
                nz(order.getBlockedCount()),
                order.getPlanRemark(),
                order.getLastExecuteResult(),
                order.getExecutingAt(),
                order.getLastExecuteTime(),
                order.getCompletedTime(),
                order.getCancelledTime(),
                order.getCreatedAt(),
                infos,
                bikeViews,
                gate);
    }

    private List<Views.ClearanceBikeView> toBikeViews(List<Assignment> assignments,
                                                      Map<Long, Bike> bikeMap,
                                                      LiveContext ctx) {
        List<Views.ClearanceBikeView> out = new ArrayList<>(assignments.size());
        for (Assignment a : assignments) {
            Bike b = bikeMap.get(a.bikeId());
            out.add(new Views.ClearanceBikeView(
                    null,
                    a.bikeId(),
                    b == null ? null : b.getCode(),
                    b == null ? null : b.getModel(),
                    b == null ? null : b.getStatus(),
                    b == null ? null : BikeDict.bikeStatusName(b.getStatus()),
                    a.kind(),
                    ClearanceDict.kindName(a.kind()),
                    a.planStationId() == null ? ClearanceDict.ITEM_UNPLACED : ClearanceDict.ITEM_PLANNED,
                    a.planStationId() == null
                            ? ClearanceDict.itemStatusName(ClearanceDict.ITEM_UNPLACED)
                            : ClearanceDict.itemStatusName(ClearanceDict.ITEM_PLANNED),
                    a.planStationId(),
                    a.planStationId() == null ? null : ctx.stations().get(a.planStationId()).getName(),
                    a.planPileId(),
                    pileNo(ctx, a.planPileId()),
                    null, null, null, null,
                    a.reason(), null));
        }
        return out;
    }

    private String pileNo(LiveContext ctx, Long pileId) {
        if (pileId == null) {
            return null;
        }
        return ctx.piles().values().stream()
                .flatMap(List::stream)
                .filter(p -> p.getId().equals(pileId))
                .map(Pile::getPileNo)
                .findFirst().orElse(null);
    }

    private Views.ClearanceBikeView toBikeView(ClearanceItem i,
                                               Bike b,
                                               Map<Long, Station> stations,
                                               Map<Long, String> pileNoMap) {
        Long planStation = i.getPlanStationId();
        Long actualStation = i.getActualStationId();
        return new Views.ClearanceBikeView(
                i.getId(),
                i.getBikeId(),
                b == null ? null : b.getCode(),
                b == null ? null : b.getModel(),
                b == null ? null : b.getStatus(),
                b == null ? null : BikeDict.bikeStatusName(b.getStatus()),
                i.getBikeKind(),
                ClearanceDict.kindName(i.getBikeKind()),
                i.getItemStatus(),
                ClearanceDict.itemStatusName(i.getItemStatus()),
                planStation,
                planStation == null ? null : nameOf(stations, planStation),
                i.getPlanPileId(),
                i.getPlanPileId() == null ? null : pileNoMap.get(i.getPlanPileId()),
                actualStation,
                actualStation == null ? null : nameOf(stations, actualStation),
                i.getActualPileId(),
                i.getActualPileId() == null ? null : pileNoMap.get(i.getActualPileId()),
                i.getFailReason(),
                i.getMigratedAt());
    }

    private String nameOf(Map<Long, Station> stations, Long id) {
        Station s = stations.get(id);
        return s == null ? null : s.getName();
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private ClearanceOrder requireOrder(Long id) {
        if (id == null) {
            throw new BizException("清站单不能为空");
        }
        return orderRepository.findById(id)
                .orElseThrow(() -> new BizException("清站单不存在（id=" + id + "）"));
    }

    private ClearanceOrder lockOrder(Long id) {
        if (id == null) {
            throw new BizException("清站单不能为空");
        }
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("清站单不存在（id=" + id + "）"));
    }

    /** 按明细状态重算整单计数 */
    private void recount(ClearanceOrder order) {
        long migrated = itemRepository.countByClearanceIdAndItemStatus(
                order.getId(), ClearanceDict.ITEM_MIGRATED);
        long blocked = itemRepository.countByClearanceIdAndItemStatus(
                order.getId(), ClearanceDict.ITEM_BLOCKED);
        long unplaced = itemRepository.countByClearanceIdAndItemStatus(
                order.getId(), ClearanceDict.ITEM_UNPLACED);
        long planned = itemRepository.countByClearanceIdAndItemStatus(
                order.getId(), ClearanceDict.ITEM_PLANNED);
        order.setMigratedCount((int) migrated);
        order.setBlockedCount((int) (blocked + unplaced));
        order.setPlannedCount((int) planned);
    }

    private int countStatus(ClearanceOrder order, String status) {
        return (int) itemRepository.countByClearanceIdAndItemStatus(order.getId(), status);
    }

    private Map<Long, Station> stationMap() {
        return stationRepository.findAll().stream()
                .collect(Collectors.toMap(Station::getId, s -> s, (a, b) -> a, HashMap::new));
    }

    private Map<Long, Station> stationsToMap(List<Station> stations) {
        return stations.stream().collect(Collectors.toMap(Station::getId, s -> s, (a, b) -> a));
    }

    private Map<Long, List<ClearanceReceiver>> receiversByOrder(List<Long> orderIds) {
        Map<Long, List<ClearanceReceiver>> map = new HashMap<>();
        for (Long id : orderIds) {
            map.put(id, receiverRepository.findByClearanceIdOrderBySeqAsc(id));
        }
        return map;
    }

    private boolean anyFault(List<Assignment> assignments) {
        return assignments.stream().anyMatch(a -> ClearanceDict.KIND_FAULT.equals(a.kind()));
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 两站间直线距离（haversine，单位公里）。任一站没有经纬度时返回 null，
     * 调用方退化为按站点 id 排序 —— 就近原则在没有坐标时不报错、不编造距离。
     */
    static Double distanceKm(Station a, Station b) {
        if (a == null || b == null || a.getLongitude() == null || a.getLatitude() == null
                || b.getLongitude() == null || b.getLatitude() == null) {
            return null;
        }
        double lat1 = a.getLatitude().doubleValue();
        double lon1 = a.getLongitude().doubleValue();
        double lat2 = b.getLatitude().doubleValue();
        double lon2 = b.getLongitude().doubleValue();
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    // ------------------------------------------------------------------
    // 内部小结构
    // ------------------------------------------------------------------

    private record PreviewInput(Station source, List<Station> receivers) {
    }

    private record Assignment(Long bikeId, String kind, Long planStationId, Long planPileId, String reason) {
    }

    private record PlanBundle(List<Assignment> assignments,
                              int plannedCount,
                              String remark,
                              List<Views.ClearanceStationInfo> stationInfos,
                              LiveContext liveContext) {
    }

    private record LiveContext(Map<Long, Bike> bikes,
                               Map<Long, Station> stations,
                               Map<Long, List<Pile>> piles) {
    }
}
