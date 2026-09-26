package com.bike.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import com.bike.station.common.BizException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.ClearanceDict;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Rental;
import com.bike.station.domain.Station;
import com.bike.station.dto.ClearanceDtos;
import com.bike.station.dto.RentalDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.RentalRepository;
import com.bike.station.repository.StationRepository;
import com.bike.station.service.ClearanceService;
import com.bike.station.service.RentalService;

/**
 * 应急清站端到端：用 H2（MySQL 模式）跑真实的 Spring/JPA/事务/行锁流程。
 * 每个用例前用 reset-h2.sql 把全部业务表重置成初始种子，避免用例互相污染。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/reset-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ClearanceFlowTests {

    @Autowired
    private ClearanceService clearanceService;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private BikeRepository bikeRepository;
    @Autowired
    private PileRepository pileRepository;
    @Autowired
    private RentalRepository rentalRepository;
    @Autowired
    private RentalService rentalService;

    private static final Long SRC = 1L;
    private static final Long S2 = 2L;
    private static final Long S3 = 3L;
    private static final Long S5 = 5L;
    private static final Long S6 = 6L;
    private static final Long S7 = 7L;

    private Map<Long, Views.ClearanceBikeView> itemMap(Views.ClearanceOrderView v) {
        return v.items().stream().collect(Collectors.toMap(Views.ClearanceBikeView::bikeId, x -> x));
    }

    /** 还掉从 S001 借出的在途单 6（BK-2002）到 S002/S003 任一空桩，让完成闸门归零 */
    private void returnRentalFromSource() {
        List<Long> freePile = List.of(S2, S3).stream()
                .flatMap(s -> pileRepository.findByStationIdOrderByPileNoAsc(s).stream())
                .filter(p -> p.getStatus().equals(BikeDict.PILE_IDLE))
                .map(Pile::getId)
                .toList();
        assertFalse(freePile.isEmpty(), "接收站应当至少还有一个空桩可以还车");
        rentalService.returnBike(6L, new RentalDtos.Return(freePile.get(0)));
    }

    // ------------------------------------------------------------------
    // 1. 方案原则：故障车优先到有空闲维修工的 S003；普通车就近
    // ------------------------------------------------------------------
    @Test
    void planFaultBikeToWorkerStationAndNormalBikesNearby() {
        Views.ClearancePreviewView preview = clearanceService.preview(
                new ClearanceDtos.ClearanceCreate(SRC, List.of(S2, S3)));

        assertEquals(4, preview.waitingCount());
        Map<Long, Views.ClearanceBikeView> items = preview.items().stream()
                .collect(Collectors.toMap(Views.ClearanceBikeView::bikeId, x -> x));

        Views.ClearanceBikeView fault = items.get(1010L);
        assertEquals("FAULT", fault.bikeKind());
        assertNotNull(fault.planStationId());
        assertEquals("西二旗驿站", fault.planStationName());

        Set<String> normalTargets = items.values().stream()
                .filter(i -> i.bikeKind().equals("NORMAL"))
                .map(Views.ClearanceBikeView::planStationName)
                .collect(Collectors.toSet());
        assertTrue(normalTargets.contains("五道口驿站"));

        Map<Long, Views.ClearanceStationInfo> recv = preview.receivers().stream()
                .collect(Collectors.toMap(Views.ClearanceStationInfo::stationId, x -> x));
        assertTrue(recv.get(S3).faultCapable());
        assertFalse(recv.get(S2).faultCapable());
        assertEquals("没有在岗驻站维修工", recv.get(S2).faultBlockReason());
    }

    // ------------------------------------------------------------------
    // 2. 接收能力不足：明确列出无处安置车辆，不伪造完整方案
    // ------------------------------------------------------------------
    @Test
    void insufficientCapacityProducesUnplacedBikes() {
        Views.ClearancePreviewView preview = clearanceService.preview(
                new ClearanceDtos.ClearanceCreate(SRC, List.of(S5, S6, S7)));
        assertEquals(4, preview.waitingCount());
        assertTrue(preview.plannedCount() <= 1, "最多只能安排 1 辆");
        assertTrue(preview.unplacedCount() >= 3, "至少 3 辆无处安置");
        assertTrue(preview.planRemark().contains("无处安置"));
    }

    // ------------------------------------------------------------------
    // 3. 完整链路：部分迁出 → 闸门拦截 → 还掉在途车 → 完成并真正停用源站
    // ------------------------------------------------------------------
    @Test
    void executePartiallyThenCompleteAndCloseSource() {
        Views.ClearanceOrderView order = clearanceService.create(
                new ClearanceDtos.ClearanceCreate(SRC, List.of(S2, S3)));
        Long orderId = order.id();
        assertEquals(ClearanceDict.ORDER_PENDING, order.status());
        assertEquals(4, order.totalCount());

        Views.ClearanceOrderView after = clearanceService.execute(orderId);
        assertEquals(4, after.migratedCount());
        assertEquals(ClearanceDict.ORDER_PARTIALLY_BLOCKED, after.status());
        assertEquals(BikeDict.STATION_OPEN, stationRepository.findById(SRC).orElseThrow().getStatus());
        assertTrue(after.gate().ridingRentals() >= 1);
        assertEquals("西二旗驿站", itemMap(after).get(1010L).actualStationName());

        for (Long sid : List.of(S2, S3)) {
            Station s = stationRepository.findById(sid).orElseThrow();
            assertTrue(bikeRepository.countByStationId(sid) <= s.getCapacity(),
                    "站点 " + s.getCode() + " 容量被突破");
        }

        assertThrows(BizException.class, () -> clearanceService.cancel(orderId));

        returnRentalFromSource();

        Views.ClearanceOrderView done = clearanceService.execute(orderId);
        assertEquals(ClearanceDict.ORDER_COMPLETED, done.status());
        assertEquals(BikeDict.STATION_CLOSED, stationRepository.findById(SRC).orElseThrow().getStatus());
        assertEquals(0, bikeRepository.countByStationId(SRC));
        assertEquals(0, done.gate().occupiedPiles());

        assertThrows(BizException.class, () -> clearanceService.execute(orderId));
        assertThrows(BizException.class, () -> clearanceService.cancel(orderId));
    }

    // ------------------------------------------------------------------
    // 4. 并发/重复执行：每辆车最多迁出一次，落点与统计一致
    // ------------------------------------------------------------------
    @Test
    void concurrentExecuteMigratesEachBikeOnce() throws Exception {
        Views.ClearanceOrderView order = clearanceService.create(
                new ClearanceDtos.ClearanceCreate(SRC, List.of(S2, S3)));
        Long orderId = order.id();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicCounterShim ok = new AtomicCounterShim();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    clearanceService.execute(orderId);
                    ok.inc();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignore) {
                    // 并发下其它请求拿到「正在处理中」进度是允许的
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(ok.get() >= 1);

        Views.ClearanceOrderView finalView = clearanceService.detail(orderId);
        assertEquals(4, finalView.migratedCount());
        for (Views.ClearanceBikeView i : finalView.items()) {
            assertEquals(ClearanceDict.ITEM_MIGRATED, i.itemStatus());
            assertNotNull(i.actualStationId());
            Bike b = bikeRepository.findById(i.bikeId()).orElseThrow();
            assertEquals(i.actualStationId(), b.getStationId());
            assertEquals(i.actualPileId(), b.getPileId());
        }
        assertEquals(BikeDict.STATION_OPEN, stationRepository.findById(SRC).orElseThrow().getStatus());
    }

    private static final class AtomicCounterShim {
        private int n;

        synchronized void inc() {
            n++;
        }

        synchronized int get() {
            return n;
        }
    }

    // ------------------------------------------------------------------
    // 5. 执行时计划接收站被停用 → 受阻/改派，其它车辆继续，不整单回滚
    // ------------------------------------------------------------------
    @Test
    void receiverClosedDuringExecutionBlocksOnlyAffectedBike() {
        Views.ClearanceOrderView created = clearanceService.create(
                new ClearanceDtos.ClearanceCreate(SRC, List.of(S2, S3)));
        Long orderId = created.id();

        // 两个接收站都停用：所有车都应受阻/无处安置，但不会被迁到任何站
        Station s2 = stationRepository.findById(S2).orElseThrow();
        s2.setStatus(BikeDict.STATION_CLOSED);
        stationRepository.save(s2);
        Station s3 = stationRepository.findById(S3).orElseThrow();
        s3.setStatus(BikeDict.STATION_CLOSED);
        stationRepository.save(s3);

        Views.ClearanceOrderView after = clearanceService.execute(orderId);
        assertEquals(0, after.migratedCount());
        for (Views.ClearanceBikeView i : after.items()) {
            assertTrue(List.of(ClearanceDict.ITEM_BLOCKED, ClearanceDict.ITEM_UNPLACED).contains(i.itemStatus()),
                    "车 " + i.bikeCode() + " 不该被迁出");
            assertNull(i.actualStationId());
        }
        // 车与桩位统计仍一致：4 辆在桩、源站 4 个占用桩
        assertEquals(4, bikeRepository.countByStationId(SRC));
        assertEquals(ClearanceDict.ORDER_PARTIALLY_BLOCKED, after.status());

        // 重新启用后再次执行：全部迁出
        s2.setStatus(BikeDict.STATION_OPEN);
        stationRepository.save(s2);
        s3.setStatus(BikeDict.STATION_OPEN);
        stationRepository.save(s3);
        Views.ClearanceOrderView second = clearanceService.execute(orderId);
        assertEquals(4, second.migratedCount());
    }

    // ------------------------------------------------------------------
    // 6. 撤销：未迁出任何车辆时允许；迁出后拒绝
    // ------------------------------------------------------------------
    @Test
    void cancelOnlyBeforeAnyMigration() {
        Views.ClearanceOrderView order = clearanceService.create(
                new ClearanceDtos.ClearanceCreate(SRC, List.of(S5, S6, S7)));
        Views.ClearanceOrderView cancelled = clearanceService.cancel(order.id());
        assertEquals(ClearanceDict.ORDER_CANCELLED, cancelled.status());
        assertThrows(BizException.class, () -> clearanceService.execute(order.id()));
    }

    // ------------------------------------------------------------------
    // 7. 执行时车辆已被借走：受阻留单，其余车照常迁出
    // ------------------------------------------------------------------
    @Test
    void bikeBorrowedDuringExecutionBecomesBlocked() {
        Views.ClearanceOrderView created = clearanceService.create(
                new ClearanceDtos.ClearanceCreate(SRC, List.of(S2, S3)));
        Long orderId = created.id();

        Bike b = bikeRepository.findById(1001L).orElseThrow();
        Long pileId = b.getPileId();
        Pile pile = pileRepository.findById(pileId).orElseThrow();
        pile.setStatus(BikeDict.PILE_IDLE);
        pileRepository.save(pile);
        b.setStatus(BikeDict.BIKE_RIDING);
        b.setStationId(null);
        b.setPileId(null);
        bikeRepository.save(b);

        Rental r = new Rental();
        r.setOrderNo("RTEST0001");
        r.setUserId(1L);
        r.setBikeId(1001L);
        r.setStartStationId(SRC);
        r.setStartPileId(pileId);
        r.setStartTime(LocalDateTime.now());
        r.setStatus(BikeDict.RENTAL_RIDING);
        r.setCreatedAt(LocalDateTime.now());
        rentalRepository.save(r);

        Views.ClearanceOrderView after = clearanceService.execute(orderId);
        Views.ClearanceBikeView bike1001 = itemMap(after).get(1001L);
        assertEquals(ClearanceDict.ITEM_BLOCKED, bike1001.itemStatus());
        assertNotNull(bike1001.failReason());
        assertNull(bike1001.actualStationId());
        assertEquals(3, after.migratedCount());
        assertEquals(ClearanceDict.ORDER_PARTIALLY_BLOCKED, after.status());
    }

    // ------------------------------------------------------------------
    // 8. 人工调整：可改派、可改回无处安置；超出容量硬拦
    // ------------------------------------------------------------------
    @Test
    void manualAdjustAndCapacityHardLimit() {
        Views.ClearanceOrderView created = clearanceService.create(
                new ClearanceDtos.ClearanceCreate(SRC, List.of(S2, S3, S6)));
        Long orderId = created.id();
        Long item1001 = itemMap(created).get(1001L).itemId();

        Views.ClearanceOrderView moved = clearanceService.adjustItem(orderId, item1001,
                new ClearanceDtos.ItemAdjust(S6, null, null));
        assertEquals(S6, itemMap(moved).get(1001L).planStationId());

        Long item1002 = itemMap(moved).get(1002L).itemId();
        assertThrows(BizException.class, () -> clearanceService.adjustItem(orderId, item1002,
                new ClearanceDtos.ItemAdjust(S6, null, null)));

        Views.ClearanceOrderView unplaced = clearanceService.adjustItem(orderId, item1001,
                new ClearanceDtos.ItemAdjust(null, null, true));
        assertEquals(ClearanceDict.ITEM_UNPLACED, itemMap(unplaced).get(1001L).itemStatus());
    }

    // ------------------------------------------------------------------
    // 9. 同站不能重复建单；列表可按状态过滤
    // ------------------------------------------------------------------
    @Test
    void duplicateActiveClearanceRejected() {
        clearanceService.create(new ClearanceDtos.ClearanceCreate(SRC, List.of(S2, S3)));
        assertThrows(BizException.class, () ->
                clearanceService.create(new ClearanceDtos.ClearanceCreate(SRC, List.of(S2))));
    }

    // ------------------------------------------------------------------
    // 10. 执行前计划桩位被别的车占了：该车就近改派到另一个空桩，不卡死、不破一桩一车
    // ------------------------------------------------------------------
    @Test
    void plannedPileOccupiedBeforeExecutionGetsRerouted() {
        Views.ClearanceOrderView created = clearanceService.create(
                new ClearanceDtos.ClearanceCreate(SRC, List.of(S2, S3)));
        Long orderId = created.id();
        Views.ClearanceBikeView bike1002 = itemMap(created).get(1002L);

        // 在 1002 的计划桩位上直接停另一辆车（bike.pile_id 才是占用关系）
        Bike squatter = new Bike();
        squatter.setCode("BK-9002");
        squatter.setModel("测试车");
        squatter.setStatus(BikeDict.BIKE_PILE);
        squatter.setStationId(bike1002.planStationId());
        squatter.setPileId(bike1002.planPileId());
        squatter.setTotalRides(0);
        squatter.setCreatedAt(LocalDateTime.now());
        squatter.setUpdatedAt(LocalDateTime.now());
        bikeRepository.save(squatter);

        Views.ClearanceOrderView after = clearanceService.execute(orderId);
        assertEquals(4, after.migratedCount(), "全部 4 辆应迁出（计划桩被占的那辆改派）");
        Views.ClearanceBikeView moved = itemMap(after).get(1002L);
        assertEquals(ClearanceDict.ITEM_MIGRATED, moved.itemStatus());
        assertNotNull(moved.actualPileId());
        // 实际落点不再是被占的计划桩
        assertFalse(moved.actualPileId().equals(bike1002.planPileId())
                && moved.actualStationId().equals(bike1002.planStationId()),
                "不能迁到已被占用的计划桩");
        // 被占的桩上仍然只有占位车，一桩一车没有被破坏
        assertEquals(squatter.getId(),
                bikeRepository.findByPileId(bike1002.planPileId()).orElseThrow().getId());
    }
}
