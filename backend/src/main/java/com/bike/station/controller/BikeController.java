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
import com.bike.station.dto.BikeDtos;
import com.bike.station.dto.Views;
import com.bike.station.service.BikeService;

import jakarta.validation.Valid;

/**
 * 模块一：车辆台账。
 */
@RestController
@RequestMapping("/api/bikes")
public class BikeController {

    private final BikeService bikeService;

    public BikeController(BikeService bikeService) {
        this.bikeService = bikeService;
    }

    /** 车辆列表（可按状态筛选、按编号/车型模糊查） */
    @GetMapping
    public ApiResponse<List<Views.BikeView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(bikeService.list(status, keyword));
    }

    /** 顶部概览数字 */
    @GetMapping("/stats")
    public ApiResponse<Views.Overview> stats() {
        return ApiResponse.success(bikeService.overview());
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.BikeView> detail(@PathVariable Long id) {
        return ApiResponse.success(bikeService.get(id));
    }

    @PostMapping
    public ApiResponse<Views.BikeView> create(@Valid @RequestBody BikeDtos.BikeCreate req) {
        return ApiResponse.success("车辆已入账", bikeService.create(req));
    }

    /** 局部更新：没传的字段保持原值 */
    @PutMapping("/{id}")
    public ApiResponse<Views.BikeView> update(@PathVariable Long id,
                                              @RequestBody BikeDtos.BikeUpdate req) {
        return ApiResponse.success("车辆已更新", bikeService.update(id, req));
    }

    /** 报废：终态，不可逆 */
    @PostMapping("/{id}/scrap")
    public ApiResponse<Views.BikeView> scrap(@PathVariable Long id,
                                             @RequestBody(required = false) BikeDtos.BikeScrap req) {
        return ApiResponse.success("车辆已报废", bikeService.scrap(id, req));
    }
}
