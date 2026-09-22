package com.bike.station.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bike.station.common.BizException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.WorkOrder;
import com.bike.station.domain.Worker;
import com.bike.station.dto.Views;
import com.bike.station.dto.WorkOrderDtos;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.WorkOrderRepository;
import com.bike.station.repository.WorkerRepository;

/**
 * 运维工单。
 *
 * 规则：
 *  1. 故障上报 → 派单 → 修复 → 关闭，单向流程，不能跳级、不能回退；
 *  2. 只有「骑行中」的车辆能上报故障（车停在桩上、维修中、已报废的车一律拒）；
 *  3. 同一辆车不能同时挂着两张未完工的工单；
 *  4. 同一维修工的在途工单时段不能重叠；
 *  5. 休息中（OFF_DUTY）的维修工不能被派单。
 */
@Service
public class WorkOrderService {

    private static final DateTimeFormatter ORDER_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final WorkOrderRepository workOrderRepository;
    private final BikeRepository bikeRepository;
    private final WorkerRepository workerRepository;

    public WorkOrderService(WorkOrderRepository workOrderRepository,
                            BikeRepository bikeRepository,
                            WorkerRepository workerRepository) {
        this.workOrderRepository = workOrderRepository;
        this.bikeRepository = bikeRepository;
        this.workerRepository = workerRepository;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<Views.WorkOrderView> list(String status) {
        List<WorkOrder> orders;
        if (status == null || status.isBlank()) {
            orders = workOrderRepository.findAllByOrderByReportTimeDesc();
        } else {
            if (!BikeDict.isValidOrderStatus(status)) {
                throw new BizException("工单状态取值非法：" + status);
            }
            orders = workOrderRepository.findByStatusOrderByReportTimeDesc(status);
        }
        return toViews(orders);
    }

    public List<Worker> workers() {
        return workerRepository.findAllByOrderByIdAsc();
    }

    // ------------------------------------------------------------------
    // 上报故障
    // ------------------------------------------------------------------

    /**
     * 上报故障。锁住 bike 表的这一行，保证「车是不是骑行中」「有没有重复工单」
     * 这两条判断与建单之间不会被并发插队。
     */
    @Transactional
    public Views.WorkOrderView report(WorkOrderDtos.Report req) {
        if (req == null || req.bikeId() == null) {
            throw new BizException("上报故障失败：车辆不能为空");
        }
        String faultType = req.faultType() == null ? "" : req.faultType().trim();
        if (faultType.isEmpty()) {
            throw new BizException("上报故障失败：故障类型不能为空");
        }
        if (faultType.length() > 32) {
            throw new BizException("上报故障失败：故障类型长度不能超过 32");
        }
        String level = req.level() == null ? "MID" : req.level().trim().toUpperCase();
        if (!BikeDict.isValidFaultLevel(level)) {
            throw new BizException("上报故障失败：故障等级取值非法：" + req.level());
        }

        // 【悲观锁】锁 bike 表的这一行
        Bike bike = bikeRepository.findByIdForUpdate(req.bikeId())
                .orElseThrow(() -> new BizException("上报故障失败：车辆不存在（id=" + req.bikeId() + "）"));

        if (!BikeDict.BIKE_RIDING.equals(bike.getStatus())) {
            throw new BizException("上报故障失败：车辆 " + bike.getCode() + " 当前状态为「"
                    + BikeDict.bikeStatusName(bike.getStatus()) + "」，只有骑行中的车辆才能上报故障");
        }
        long openOrders = workOrderRepository.countByBikeIdAndStatusIn(
                bike.getId(), BikeDict.ORDER_OPEN_STATUS);
        if (openOrders > 0) {
            throw new BizException("上报故障失败：车辆 " + bike.getCode()
                    + " 已经有一张未完成的工单了，同一辆车不能同时挂两张未完工工单");
        }

        LocalDateTime now = LocalDateTime.now();
        WorkOrder order = new WorkOrder();
        order.setOrderNo("WO" + now.format(ORDER_NO_FORMAT));
        order.setBikeId(bike.getId());
        order.setFaultType(faultType);
        order.setLevel(level);
        // 默认值统一在 service 赋：新工单就是「已上报」，还没有维修工与计划时段
        order.setStatus(BikeDict.ORDER_REPORTED);
        order.setWorkerId(null);
        order.setPlanStart(null);
        order.setPlanEnd(null);
        order.setReportTime(now);
        workOrderRepository.save(order);

        return toView(order, bikeCodeMap(), workerNameMap());
    }

    // ------------------------------------------------------------------
    // 派单
    // ------------------------------------------------------------------

    /**
     * 派单。锁住「维修工行」+「本单行」+「该维修工名下时段相交的未完工工单行」，
     * 让「时段不重叠」的判断和写入变成串行区间。
     */
    @Transactional
    public Views.WorkOrderView assign(Long orderId, WorkOrderDtos.Assign req) {
        if (orderId == null) {
            throw new BizException("派单失败：工单不能为空");
        }
        if (req == null || req.workerId() == null) {
            throw new BizException("派单失败：维修工不能为空");
        }
        if (req.planStart() == null || req.planEnd() == null) {
            throw new BizException("派单失败：计划开始时间与计划结束时间都不能为空");
        }
        if (!req.planEnd().isAfter(req.planStart())) {
            throw new BizException("派单失败：计划结束时间必须晚于计划开始时间");
        }

        // 【悲观锁】先锁维修工行，再锁工单行，最后锁该维修工名下时段相交的工单行
        Worker worker = workerRepository.findByIdForUpdate(req.workerId())
                .orElseThrow(() -> new BizException("派单失败：维修工不存在（id=" + req.workerId() + "）"));
        WorkOrder order = workOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("派单失败：工单不存在（id=" + orderId + "）"));

        if (!BikeDict.ORDER_REPORTED.equals(order.getStatus())) {
            throw new BizException("派单失败：工单 " + order.getOrderNo() + " 当前状态为「"
                    + BikeDict.orderStatusName(order.getStatus()) + "」，只有「已上报」的工单才能派单");
        }
        if (!BikeDict.WORKER_ON_DUTY.equals(worker.getStatus())) {
            throw new BizException("派单失败：维修工「" + worker.getName() + "」当前为「"
                    + BikeDict.workerStatusName(worker.getStatus()) + "」状态，不能派单");
        }

        List<WorkOrder> overlapping = workOrderRepository.findOverlappingForUpdate(
                worker.getId(), BikeDict.ORDER_OPEN_STATUS, req.planStart(), req.planEnd());
        for (WorkOrder o : overlapping) {
            if (o.getId().equals(order.getId())) {
                continue;
            }
            throw new BizException("派单失败：维修工「" + worker.getName() + "」在 "
                    + o.getPlanStart().format(TIME_FORMAT) + " ~ " + o.getPlanEnd().format(TIME_FORMAT)
                    + " 已经有在途工单 " + o.getOrderNo() + "，同一维修工的在途时段不能重叠");
        }

        order.setWorkerId(worker.getId());
        order.setPlanStart(req.planStart());
        order.setPlanEnd(req.planEnd());
        order.setAssignTime(LocalDateTime.now());
        order.setStatus(BikeDict.ORDER_ASSIGNED);

        return toView(order, bikeCodeMap(), workerNameMap());
    }

    // ------------------------------------------------------------------
    // 修复 / 关闭
    // ------------------------------------------------------------------

    @Transactional
    public Views.WorkOrderView fix(Long orderId, WorkOrderDtos.Fix req) {
        WorkOrder order = lockOrder(orderId, "修复");

        if (!BikeDict.ORDER_ASSIGNED.equals(order.getStatus())) {
            throw new BizException("修复失败：工单 " + order.getOrderNo() + " 当前状态为「"
                    + BikeDict.orderStatusName(order.getStatus()) + "」，只有「已派单」的工单才能修复");
        }
        String note = req == null || req.fixNote() == null ? "" : req.fixNote().trim();
        if (note.length() > 255) {
            throw new BizException("修复失败：维修说明长度不能超过 255");
        }
        if (note.isEmpty()) {
            throw new BizException("修复失败：维修说明不能为空");
        }

        order.setStatus(BikeDict.ORDER_FIXED);
        order.setFixTime(LocalDateTime.now());
        order.setFixNote(note);

        return toView(order, bikeCodeMap(), workerNameMap());
    }

    @Transactional
    public Views.WorkOrderView close(Long orderId) {
        WorkOrder order = lockOrder(orderId, "关闭");
        if (!BikeDict.ORDER_FIXED.equals(order.getStatus())) {
            throw new BizException("关闭失败：工单 " + order.getOrderNo() + " 当前状态为「"
                    + BikeDict.orderStatusName(order.getStatus()) + "」，只有「已修复」的工单才能关闭");
        }
        order.setStatus(BikeDict.ORDER_CLOSED);
        return toView(order, bikeCodeMap(), workerNameMap());
    }

    /** 【悲观锁】锁 work_order 表的这一行 */
    private WorkOrder lockOrder(Long orderId, String action) {
        if (orderId == null) {
            throw new BizException(action + "失败：工单不能为空");
        }
        return workOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException(action + "失败：工单不存在（id=" + orderId + "）"));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private List<Views.WorkOrderView> toViews(List<WorkOrder> orders) {
        Map<Long, String> bikes = bikeCodeMap();
        Map<Long, String> workers = workerNameMap();
        List<Views.WorkOrderView> list = new ArrayList<>(orders.size());
        for (WorkOrder o : orders) {
            list.add(toView(o, bikes, workers));
        }
        return list;
    }

    private Views.WorkOrderView toView(WorkOrder o, Map<Long, String> bikes, Map<Long, String> workers) {
        return new Views.WorkOrderView(
                o.getId(),
                o.getOrderNo(),
                o.getBikeId(),
                bikes.get(o.getBikeId()),
                o.getFaultType(),
                o.getLevel(),
                BikeDict.faultLevelName(o.getLevel()),
                o.getStatus(),
                BikeDict.orderStatusName(o.getStatus()),
                o.getWorkerId(),
                o.getWorkerId() == null ? null : workers.get(o.getWorkerId()),
                o.getPlanStart(),
                o.getPlanEnd(),
                o.getReportTime(),
                o.getAssignTime(),
                o.getFixTime(),
                o.getFixNote());
    }

    private Map<Long, String> bikeCodeMap() {
        Map<Long, String> map = new LinkedHashMap<>();
        for (Bike b : bikeRepository.findAll()) {
            map.put(b.getId(), b.getCode());
        }
        return map;
    }

    private Map<Long, String> workerNameMap() {
        Map<Long, String> map = new LinkedHashMap<>();
        for (Worker w : workerRepository.findAll()) {
            map.put(w.getId(), w.getName());
        }
        return map;
    }
}
