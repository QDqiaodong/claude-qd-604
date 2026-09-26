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
import com.bike.station.dto.EvacuationDtos;
import com.bike.station.dto.Views;
import com.bike.station.service.EvacuationService;

/**
 * 模块五：应急清站。
 *
 * 清站单列表、详情、创建、执行、撤销、人工调整方案、部分受阻后补选接收站。
 */
@RestController
@RequestMapping("/api/evacuations")
public class EvacuationController {

    private final EvacuationService evacuationService;

    public EvacuationController(EvacuationService evacuationService) {
        this.evacuationService = evacuationService;
    }

    /** 清站单列表（可按状态过滤） */
    @GetMapping
    public ApiResponse<List<Views.EvacuationListItem>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(evacuationService.list(status));
    }

    /** 清站单详情：源站实时体检 + 接收站实时能力 + 每辆车计划/实际/失败原因 */
    @GetMapping("/{id}")
    public ApiResponse<Views.EvacuationDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(evacuationService.get(id));
    }

    /** 创建向导：某源站可选择的接收站及实时剩余容量 / 可用桩位 / 维修能力 */
    @GetMapping("/receivers")
    public ApiResponse<List<Views.ReceiverOption>> receivers(@RequestParam Long sourceStationId) {
        return ApiResponse.success(evacuationService.receiverOptions(sourceStationId));
    }

    /** 创建向导第三步：预览自动生成、可人工调整的迁移方案（不写库） */
    @PostMapping("/preview")
    public ApiResponse<Views.EvacuationPlanPreview> preview(@RequestBody EvacuationDtos.Preview req) {
        return ApiResponse.success(evacuationService.preview(req));
    }

    /** 创建清站单（同时生成可人工调整的迁移方案；排不下的车明确留空） */
    @PostMapping
    public ApiResponse<Views.EvacuationDetail> create(@RequestBody EvacuationDtos.Create req) {
        return ApiResponse.success("清站单已创建（待执行）", evacuationService.create(req));
    }

    /**
     * 执行迁移。重复点击、刷新重提、两人同时执行都安全：
     * 每辆车最多成功迁出一次，已成功的结果原样保留，后到的请求只拿到最新进度。
     */
    @PostMapping("/{id}/execute")
    public ApiResponse<Views.EvacuationExecuteResult> execute(@PathVariable Long id) {
        return ApiResponse.success("本轮执行结束", evacuationService.execute(id));
    }

    /** 撤销：只允许在尚未迁出任何车辆时 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Views.EvacuationDetail> cancel(@PathVariable Long id) {
        return ApiResponse.success("清站单已撤销", evacuationService.cancel(id));
    }

    /** 人工调整某辆车的计划去向（可改为暂无处安置） */
    @PostMapping("/{id}/items/adjust")
    public ApiResponse<Views.EvacuationDetail> adjustItem(@PathVariable Long id,
                                                          @RequestBody EvacuationDtos.ItemAdjust req) {
        return ApiResponse.success("方案已调整", evacuationService.adjustItem(id, req));
    }

    /** 部分受阻后补选接收站（加入后自动给仍无去向的车补分） */
    @PostMapping("/{id}/targets")
    public ApiResponse<Views.EvacuationDetail> addTargets(@PathVariable Long id,
                                                          @RequestBody EvacuationDtos.AddTargets req) {
        return ApiResponse.success("接收站已补充", evacuationService.addTargets(id, req));
    }
}
