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
import com.bike.station.dto.ClearanceDtos;
import com.bike.station.dto.Views;
import com.bike.station.service.ClearanceService;

import jakarta.validation.Valid;

/**
 * 模块五：应急清站。
 *
 *  - POST /preview           建单前看方案（不落库）
 *  - POST /                  创建清站单（方案一起落库，可保存为待执行）
 *  - GET  /                  清站单列表
 *  - GET  /{id}              清站单详情（每辆车的计划/实际去向、受阻原因、完成闸门）
 *  - POST /{id}/items/{itemId}/adjust  人工调整某辆车的计划去向
 *  - POST /{id}/execute      执行（幂等：重复点击/刷新重提/双人同点，每辆车最多迁出一次）
 *  - POST /{id}/cancel       撤销（仅在尚未迁出任何车辆时）
 */
@RestController
@RequestMapping("/api/clearances")
public class ClearanceController {

    private final ClearanceService clearanceService;

    public ClearanceController(ClearanceService clearanceService) {
        this.clearanceService = clearanceService;
    }

    @GetMapping
    public ApiResponse<List<Views.ClearanceListItem>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(clearanceService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.ClearanceOrderView> detail(@PathVariable Long id) {
        return ApiResponse.success(clearanceService.detail(id));
    }

    /** 建单前的方案预览：待迁车辆、接收站实时容量/空桩/维修工能力、自动分配结果 */
    @PostMapping("/preview")
    public ApiResponse<Views.ClearancePreviewView> preview(@Valid @RequestBody ClearanceDtos.ClearanceCreate req) {
        return ApiResponse.success("方案已生成", clearanceService.preview(req));
    }

    @PostMapping
    public ApiResponse<Views.ClearanceOrderView> create(@Valid @RequestBody ClearanceDtos.ClearanceCreate req) {
        return ApiResponse.success("清站单已创建（待执行）", clearanceService.create(req));
    }

    /** 人工调整某辆车的计划去向（可改派到接收站范围内的站点，或改回暂时无处安置） */
    @PostMapping("/{id}/items/{itemId}/adjust")
    public ApiResponse<Views.ClearanceOrderView> adjust(@PathVariable Long id,
                                                        @PathVariable Long itemId,
                                                        @RequestBody ClearanceDtos.ItemAdjust req) {
        return ApiResponse.success("方案已调整", clearanceService.adjustItem(id, itemId, req));
    }

    /**
     * 执行清站。幂等：重复点击、刷新后再次提交、两名运营人员同时执行，
     * 已成功迁出的车辆原样保留，另一请求只会拿到最新进度，不会重复占用容量。
     */
    @PostMapping("/{id}/execute")
    public ApiResponse<Views.ClearanceOrderView> execute(@PathVariable Long id) {
        Views.ClearanceOrderView view = clearanceService.execute(id);
        String message;
        if ("COMPLETED".equals(view.status())) {
            message = "清站完成，源站已停用";
        } else if (view.executingAt() != null) {
            // 另一个执行请求仍在跑：本请求没有重复迁移任何车，只返回最新进度
            message = "另一个执行请求正在处理中，这里是最新进度";
        } else {
            message = "执行完毕：" + (view.lastExecuteResult() == null ? "" : view.lastExecuteResult());
        }
        return ApiResponse.success(message, view);
    }

    /** 撤销：只允许发生在尚未迁出任何车辆时 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Views.ClearanceOrderView> cancel(@PathVariable Long id) {
        return ApiResponse.success("清站单已撤销", clearanceService.cancel(id));
    }
}
