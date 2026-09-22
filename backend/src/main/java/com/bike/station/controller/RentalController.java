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
import com.bike.station.domain.BikeUser;
import com.bike.station.dto.RentalDtos;
import com.bike.station.dto.Views;
import com.bike.station.service.RentalService;

/**
 * 模块三：租还与计费。
 */
@RestController
@RequestMapping("/api/rentals")
public class RentalController {

    private final RentalService rentalService;

    public RentalController(RentalService rentalService) {
        this.rentalService = rentalService;
    }

    @GetMapping
    public ApiResponse<List<Views.RentalView>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(rentalService.list(status));
    }

    /** 在途订单（骑行中） */
    @GetMapping("/active")
    public ApiResponse<List<Views.RentalView>> active() {
        return ApiResponse.success(rentalService.active());
    }

    /** 借车人下拉框用 */
    @GetMapping("/users")
    public ApiResponse<List<BikeUser>> users() {
        return ApiResponse.success(rentalService.users());
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.RentalView> detail(@PathVariable Long id) {
        return ApiResponse.success(rentalService.get(id));
    }

    /** 借车：从某个桩位上把车骑走 */
    @PostMapping("/borrow")
    public ApiResponse<Views.BorrowResult> borrow(@RequestBody RentalDtos.Borrow req) {
        return ApiResponse.success(rentalService.borrow(req));
    }

    /** 还车：把车还到某个桩位上，顺带结算 */
    @PostMapping("/{id}/return")
    public ApiResponse<Views.ReturnResult> returnBike(@PathVariable Long id,
                                                      @RequestBody RentalDtos.Return req) {
        return ApiResponse.success(rentalService.returnBike(id, req));
    }
}
