package com.bike.station.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bike.station.common.ApiResponse;
import com.bike.station.domain.Worker;
import com.bike.station.dto.Views;
import com.bike.station.dto.WorkOrderDtos;
import com.bike.station.service.WorkOrderService;

/**
 * 模块四：运维工单。
 */
@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    public WorkOrderController(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @GetMapping
    public ApiResponse<List<Views.WorkOrderView>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(workOrderService.list(status));
    }

    /** 派单下拉框用 */
    @GetMapping("/workers")
    public ApiResponse<List<Worker>> workers() {
        return ApiResponse.success(workOrderService.workers());
    }

    /** 上报故障（只有骑行中的车辆能上报） */
    @PostMapping
    public ApiResponse<Views.WorkOrderView> report(@RequestBody WorkOrderDtos.Report req) {
        return ApiResponse.success("故障已上报", workOrderService.report(req));
    }

    /** 派单 */
    @PostMapping("/{id}/assign")
    public ApiResponse<Views.WorkOrderView> assign(@PathVariable Long id,
                                                   @RequestBody WorkOrderDtos.Assign req) {
        return ApiResponse.success("工单已派单", workOrderService.assign(id, req));
    }

    /** 修复 */
    @PostMapping("/{id}/fix")
    public ApiResponse<Views.WorkOrderView> fix(@PathVariable Long id,
                                                @RequestBody WorkOrderDtos.Fix req) {
        return ApiResponse.success("工单已修复", workOrderService.fix(id, req));
    }

    /** 关闭 */
    @PostMapping("/{id}/close")
    public ApiResponse<Views.WorkOrderView> close(@PathVariable Long id) {
        return ApiResponse.success("工单已关闭", workOrderService.close(id));
    }
}
