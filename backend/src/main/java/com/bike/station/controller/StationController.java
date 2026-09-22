package com.bike.station.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bike.station.common.ApiResponse;
import com.bike.station.dto.StationDtos;
import com.bike.station.dto.Views;
import com.bike.station.service.StationService;

import jakarta.validation.Valid;

/**
 * 模块二：站点与桩位（含拖拽调拨）。
 */
@RestController
@RequestMapping("/api/stations")
public class StationController {

    private final StationService stationService;

    public StationController(StationService stationService) {
        this.stationService = stationService;
    }

    /** 站点卡片列表：卡片里带桩位格，桩位格里带车 —— 前端拖拽调拨就吃这个结构 */
    @GetMapping
    public ApiResponse<List<Views.StationCard>> cards(@RequestParam(required = false) String keyword) {
        return ApiResponse.success(stationService.cards(keyword));
    }

    @GetMapping("/{id}/piles")
    public ApiResponse<List<Views.PileView>> piles(@PathVariable Long id) {
        return ApiResponse.success(stationService.piles(id));
    }

    @PostMapping
    public ApiResponse<Views.StationCard> create(@Valid @RequestBody StationDtos.StationCreate req) {
        return ApiResponse.success("站点已开通", stationService.createStation(req));
    }

    @PutMapping("/{id}/capacity")
    public ApiResponse<Views.StationCard> capacity(@PathVariable Long id,
                                                   @Valid @RequestBody StationDtos.StationCapacity req) {
        return ApiResponse.success("站点容量已调整", stationService.updateCapacity(id, req));
    }

    /** 站点停用 / 启用 */
    @PostMapping("/{id}/status")
    public ApiResponse<Views.StationCard> status(@PathVariable Long id,
                                                 @Valid @RequestBody StationDtos.StationStatus req) {
        return ApiResponse.success("站点状态已变更", stationService.changeStatus(id, req.status()));
    }

    @PostMapping("/{id}/piles")
    public ApiResponse<Views.StationCard> addPile(@PathVariable Long id,
                                                  @Valid @RequestBody StationDtos.PileCreate req) {
        return ApiResponse.success("桩位已新增", stationService.addPile(id, req));
    }

    /** 拖拽调拨：把车从 A 站挪到 B 站（或同站换桩位） */
    @PostMapping("/transfer")
    public ApiResponse<Views.TransferResult> transfer(@RequestBody StationDtos.Transfer req) {
        return ApiResponse.success(stationService.transfer(req));
    }
}
