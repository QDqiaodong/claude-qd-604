package com.bike.station.dto;

/**
 * 租还与计费模块的入参。
 *
 * 注意：userId / pileId 这类「外键」刻意不加 @NotNull，
 * 而是由 RentalService 先判 null 再查库，抛 BizException 返回中文提示，
 * 避免用户看到 JPA 抛出来的非人话报错。
 */
public final class RentalDtos {

    private RentalDtos() {
    }

    /** 借车：站在 pileId 这个桩位前，用 userId 这个账号把车借走 */
    public record Borrow(
            Long userId,
            Long pileId) {
    }

    /** 还车：把车还到 pileId 这个桩位 */
    public record Return(
            Long pileId) {
    }
}
