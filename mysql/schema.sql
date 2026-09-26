-- ============================================================================
-- 单车驿站（共享单车 / 公共自行车运营）管理系统 —— 建表 + 种子数据
-- 库：bike_station    字符集：utf8mb4 / utf8mb4_unicode_ci
--
-- 执行时机：mysql 镜像【构建期】执行（见 mysql/Dockerfile 里 COPY 到
--           /docker-entrypoint-initdb.d/01-schema.sql）。
--           后端 spring.jpa.hibernate.ddl-auto=none，表结构以本文件为准。
--
-- 种子数据的口径：4 个业务模块里的【每一条】业务规则，都能在下面这份数据上
-- 被真的触发一次（可行分支 + 拒绝分支都留了样本），改规则时不用另造数据。
-- ============================================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS evacuation_item;
DROP TABLE IF EXISTS evacuation_target;
DROP TABLE IF EXISTS evacuation_order;
DROP TABLE IF EXISTS work_order;
DROP TABLE IF EXISTS rental;
DROP TABLE IF EXISTS bike;
DROP TABLE IF EXISTS pile;
DROP TABLE IF EXISTS station;
DROP TABLE IF EXISTS worker;
DROP TABLE IF EXISTS bike_user;

-- ---------------------------------------------------------------------------
-- 骑行人（会员）
--   balance 用来触发「余额不足拦借车 / 拦扣费」
-- ---------------------------------------------------------------------------
CREATE TABLE bike_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    phone      VARCHAR(20)  NOT NULL COMMENT '手机号，唯一',
    nickname   VARCHAR(50)  NOT NULL COMMENT '昵称',
    balance    DECIMAL(10, 2) NOT NULL COMMENT '账户余额（元）',
    status     VARCHAR(16)  NOT NULL COMMENT '账号状态：NORMAL 正常 / FROZEN 冻结',
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '骑行人';

-- ---------------------------------------------------------------------------
-- 站点（驿站）
--   capacity 触发「站点容量上限」；status=CLOSED 触发「站点停用禁借还」
-- ---------------------------------------------------------------------------
CREATE TABLE station (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32)  NOT NULL COMMENT '站点编号，唯一，形如 S001',
    name       VARCHAR(64)  NOT NULL COMMENT '站点名称',
    district   VARCHAR(32)  NOT NULL COMMENT '所在行政区',
    capacity   INT          NOT NULL COMMENT '可停放车辆数上限',
    status     VARCHAR(16)  NOT NULL COMMENT '站点状态：OPEN 运营中 / CLOSED 已停用',
    longitude  DECIMAL(10, 6) NULL,
    latitude   DECIMAL(10, 6) NULL,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_station_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '站点';

-- ---------------------------------------------------------------------------
-- 桩位
--   一桩一车的落点在 bike.pile_id 上的唯一索引（MySQL 唯一索引允许多个 NULL，
--   所以「空桩」可以无限多，「占用」只允许一辆车）。pile.status 只是快照，
--   真正的占用关系以 bike.pile_id 为准，两者在同一个事务里一起改。
-- ---------------------------------------------------------------------------
CREATE TABLE pile (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    station_id BIGINT      NOT NULL COMMENT '所属站点',
    pile_no    VARCHAR(16) NOT NULL COMMENT '站内桩位号，形如 P1',
    status     VARCHAR(16) NOT NULL COMMENT '桩位状态：IDLE 空闲 / OCCUPIED 占用 / DISABLED 停用',
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pile_station_no (station_id, pile_no),
    KEY idx_pile_station (station_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '桩位';

-- ---------------------------------------------------------------------------
-- 车辆台账
--   code 唯一；status 是状态机（在桩/骑行中/维修中/报废）；
--   pile_id 唯一 → 一桩至多一车；station_id 用于站点容量计数。
-- ---------------------------------------------------------------------------
CREATE TABLE bike (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    code         VARCHAR(32) NOT NULL COMMENT '车辆编号，唯一，形如 BK-1001',
    model        VARCHAR(32) NOT NULL COMMENT '车型',
    status       VARCHAR(16) NOT NULL COMMENT 'PILE 在桩 / RIDING 骑行中 / REPAIR 维修中 / SCRAPPED 报废',
    station_id   BIGINT      NULL COMMENT '在桩/维修时所在站点；骑行中与报废后为 NULL',
    pile_id      BIGINT      NULL COMMENT '在桩时占用的桩位；骑行中/维修中/报废为 NULL',
    total_rides  INT         NOT NULL COMMENT '累计骑行次数',
    scrap_reason VARCHAR(128) NULL COMMENT '报废原因（仅报废车有值）',
    created_at   DATETIME    NOT NULL,
    updated_at   DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bike_code (code),
    UNIQUE KEY uk_bike_pile (pile_id),
    KEY idx_bike_station (station_id),
    KEY idx_bike_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车辆台账';

-- ---------------------------------------------------------------------------
-- 租借订单
--   active_bike_id 是生成列：只在 status='RIDING' 时有值，配唯一索引
--   → 数据库层面硬保证「同一辆车同时只能有一张在途单」。
-- ---------------------------------------------------------------------------
CREATE TABLE rental (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    order_no         VARCHAR(32) NOT NULL COMMENT '订单号，唯一',
    user_id          BIGINT      NOT NULL COMMENT '借车人',
    bike_id          BIGINT      NOT NULL COMMENT '车辆',
    start_station_id BIGINT      NOT NULL COMMENT '借车站点',
    start_pile_id    BIGINT      NOT NULL COMMENT '借车桩位',
    end_station_id   BIGINT      NULL COMMENT '还车站点（未还车为 NULL）',
    end_pile_id      BIGINT      NULL COMMENT '还车桩位（未还车为 NULL）',
    start_time       DATETIME    NOT NULL,
    end_time         DATETIME    NULL,
    duration_minutes INT         NULL COMMENT '骑行时长（分钟）',
    amount           DECIMAL(10, 2) NULL COMMENT '结算金额（元）',
    status           VARCHAR(16) NOT NULL COMMENT 'RIDING 骑行中 / SETTLED 已结算',
    created_at       DATETIME    NOT NULL,
    active_bike_id   BIGINT GENERATED ALWAYS AS (IF(status = 'RIDING', bike_id, NULL)) STORED
                     COMMENT '生成列：仅骑行中订单有值，配合唯一索引实现「一车一在途单」',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rental_order_no (order_no),
    UNIQUE KEY uk_rental_active_bike (active_bike_id),
    KEY idx_rental_bike (bike_id),
    KEY idx_rental_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '租借订单';

-- ---------------------------------------------------------------------------
-- 维修工
--   station_id 是维修工常驻站点：决定一个站点是否「具备在岗维修工」，
--   清站分配故障车时只送往有【在岗且名下没有未完工工单冲突】维修工的站点。
-- ---------------------------------------------------------------------------
CREATE TABLE worker (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(32) NOT NULL COMMENT '姓名',
    phone      VARCHAR(20) NOT NULL COMMENT '手机号，唯一',
    skill      VARCHAR(32) NOT NULL COMMENT '技能方向',
    status     VARCHAR(16) NOT NULL COMMENT 'ON_DUTY 在岗 / OFF_DUTY 休息',
    station_id BIGINT      NULL COMMENT '常驻站点（可在站点间调配，可为空）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_worker_phone (phone),
    KEY idx_worker_station (station_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '维修工';

-- ---------------------------------------------------------------------------
-- 运维工单
--   状态机 REPORTED → ASSIGNED → FIXED → CLOSED；
--   (worker_id, plan_start, plan_end) 支撑「同一维修工时段不重叠」。
-- ---------------------------------------------------------------------------
CREATE TABLE work_order (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(32) NOT NULL COMMENT '工单号，唯一',
    bike_id     BIGINT      NOT NULL COMMENT '故障车辆',
    fault_type  VARCHAR(32) NOT NULL COMMENT '故障类型',
    level       VARCHAR(16) NOT NULL COMMENT 'LOW 低 / MID 中 / HIGH 高',
    status      VARCHAR(16) NOT NULL COMMENT 'REPORTED 已上报 / ASSIGNED 已派单 / FIXED 已修复 / CLOSED 已关闭',
    worker_id   BIGINT      NULL COMMENT '承修维修工（未派单为 NULL）',
    plan_start  DATETIME    NULL COMMENT '计划开始时间（未派单为 NULL）',
    plan_end    DATETIME    NULL COMMENT '计划结束时间（未派单为 NULL）',
    report_time DATETIME    NOT NULL COMMENT '上报时间',
    assign_time DATETIME    NULL COMMENT '派单时间',
    fix_time    DATETIME    NULL COMMENT '修复时间',
    fix_note    VARCHAR(255) NULL COMMENT '维修说明',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_bike (bike_id),
    KEY idx_order_worker_plan (worker_id, plan_start, plan_end),
    KEY idx_order_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运维工单';

-- ---------------------------------------------------------------------------
-- 应急清站单
--   状态机：PENDING 待执行 → RUNNING 执行中 → DONE 已完成 / PARTIAL 部分受阻
--           PENDING → CANCELLED 已撤销（只允许在尚未迁出任何车辆时撤销）。
--   active_source_id 是生成列：只有单还没走完（PENDING/RUNNING/PARTIAL）时等于
--   源站 id，配唯一索引 → 数据库层面硬保证「同一个站点同时只能有一张未完结清站单」。
--   finish_blocker 记录最后一次执行后仍然不能停用源站的原因（有车未迁出时另有
--   明细行，这里只记骑行未还 / 未关闭工单 / 源站桩位仍被占等站级原因）。
-- ---------------------------------------------------------------------------
CREATE TABLE evacuation_order (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    order_no           VARCHAR(32)  NOT NULL COMMENT '清站单号，唯一，形如 CL20260926...',
    source_station_id  BIGINT       NOT NULL COMMENT '待停用的源站点',
    status             VARCHAR(16)  NOT NULL COMMENT 'PENDING 待执行 / RUNNING 执行中 / PARTIAL 部分受阻 / DONE 已完成 / CANCELLED 已撤销',
    remark             VARCHAR(255) NULL COMMENT '运营人员备注',
    finish_blocker     VARCHAR(255) NULL COMMENT '尚不能停用源站的站级原因（无未迁车时用）',
    created_at         DATETIME     NOT NULL,
    started_at         DATETIME     NULL COMMENT '首次执行时间',
    executed_at        DATETIME     NULL COMMENT '最近一次执行时间',
    finished_at        DATETIME     NULL COMMENT '全部迁出并停用源站的时间',
    cancelled_at       DATETIME     NULL COMMENT '撤销时间',
    active_source_id   BIGINT GENERATED ALWAYS AS
                       (IF(status IN ('PENDING', 'RUNNING', 'PARTIAL'), source_station_id, NULL)) STORED
                       COMMENT '生成列：仅未完结的清站单有值，配唯一索引保证同源站同时只有一张活单',
    PRIMARY KEY (id),
    UNIQUE KEY uk_evac_no (order_no),
    UNIQUE KEY uk_evac_active_source (active_source_id),
    KEY idx_evac_source (source_station_id),
    KEY idx_evac_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '应急清站单';

-- ---------------------------------------------------------------------------
-- 清站单接收站（一张单可以选多个仍在运营的接收站）
--   创建时快照距离（公里）与「故障车可接收」标记；执行时以实时状态重新判定，
--   快照只用于页面回显当初为什么这样分。
-- ---------------------------------------------------------------------------
CREATE TABLE evacuation_target (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    evacuation_id    BIGINT       NOT NULL COMMENT '所属清站单',
    station_id       BIGINT       NOT NULL COMMENT '接收站点',
    distance_km      DECIMAL(8, 2) NULL COMMENT '创建时源站到该站直线距离（公里）',
    fault_capable    TINYINT(1)   NOT NULL COMMENT '创建时快照：是否具备在岗且无未完工工单冲突的维修工',
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_evac_target (evacuation_id, station_id),
    KEY idx_et_station (station_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '清站单接收站';

-- ---------------------------------------------------------------------------
-- 清站单车辆明细（一辆待迁车一行，随源站在桩车辆在创建时固化）
--   item_status：
--     PLANNED 已计划（还没迁出）/ MOVED 已迁出 / BLOCKED 受阻
--   fault_flag：是否按故障车分配（创建时由「有未关闭工单」判定，可人工改）。
--   计划去向（plan_*）允许人工调整；实际去向（actual_*）只在迁出成功的那个
--   事务里写一次，任何重复执行都不能改它；受阻时把失败原因写进 fail_reason。
-- ---------------------------------------------------------------------------
CREATE TABLE evacuation_item (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    evacuation_id          BIGINT       NOT NULL COMMENT '所属清站单',
    bike_id                BIGINT       NOT NULL COMMENT '待迁车辆',
    bike_code              VARCHAR(32)  NOT NULL COMMENT '车辆编号快照',
    fault_flag             TINYINT(1)   NOT NULL COMMENT '是否故障车（故障车优先送维修能力站）',
    plan_station_id        BIGINT       NULL COMMENT '计划接收站点（NULL=暂无处安置）',
    plan_pile_id           BIGINT       NULL COMMENT '计划桩位（NULL=到站自动选空桩）',
    actual_station_id      BIGINT       NULL COMMENT '实际接收站点（成功迁出后写一次）',
    actual_pile_id         BIGINT       NULL COMMENT '实际落点桩位',
    item_status            VARCHAR(16)  NOT NULL COMMENT 'PLANNED 已计划 / MOVED 已迁出 / BLOCKED 受阻',
    manual_flag            TINYINT(1)   NOT NULL COMMENT '计划去向是否被人工改过：人工指定的只校验硬约束，自动方案执行时重新校验维修能力',
    fail_reason            VARCHAR(255) NULL COMMENT '受阻原因（车辆被借走/桩位被占/接收站停用/容量满等）',
    moved_at               DATETIME     NULL COMMENT '迁出成功时间',
    created_at             DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_evac_bike (evacuation_id, bike_id),
    KEY idx_ei_status (evacuation_id, item_status),
    KEY idx_ei_bike (bike_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '清站单车辆明细';


-- ===========================================================================
-- 种子数据
-- ===========================================================================

-- ---- 骑行人 ---------------------------------------------------------------
-- 1 号余额充足（可走通借车→还车）；2 号余额 0.50 元，低于起步价 1.50 元
-- → 触发「余额不足拦借车」；3 号余额充足。
INSERT INTO bike_user (id, phone, nickname, balance, status, created_at) VALUES
(1, '13800000001', '阿明', 30.00, 'NORMAL', '2026-08-01 09:00:00'),
(2, '13800000002', '阿红',  0.50, 'NORMAL', '2026-08-02 10:00:00'),
(3, '13800000003', '阿刚', 12.00, 'NORMAL', '2026-08-03 11:00:00');

-- ---- 站点 -----------------------------------------------------------------
-- S004 停用 → 触发「站点停用禁借还」；
-- S005 容量只有 2，站里已经停了 2 辆（BK-1008 / BK-1009），但还留着两个空桩位
--   → 只有这种「桩位空着、车位数却满了」的站点才能真的触发「站点容量超限」，
--     否则会先被「一桩一车」拦掉，容量这条规则就永远试不出来。
INSERT INTO station (id, code, name, district, capacity, status, longitude, latitude, created_at) VALUES
(1, 'S001', '中关村驿站',   '海淀区', 6, 'OPEN',   116.316000, 39.983000, '2026-07-01 08:00:00'),
(2, 'S002', '五道口驿站',   '海淀区', 4, 'OPEN',   116.337000, 39.992000, '2026-07-01 08:00:00'),
(3, 'S003', '西二旗驿站',   '昌平区', 5, 'OPEN',   116.308000, 40.056000, '2026-07-01 08:00:00'),
(4, 'S004', '国贸驿站',     '朝阳区', 3, 'CLOSED', 116.461000, 39.908000, '2026-07-01 08:00:00'),
(5, 'S005', '望京驿站',     '朝阳区', 2, 'OPEN',   116.470000, 39.996000, '2026-07-01 08:00:00');

-- ---- 桩位 -----------------------------------------------------------------
-- 106 号桩位停用 → 触发「停用桩位不能借车/还车/调拨」。
INSERT INTO pile (id, station_id, pile_no, status, created_at) VALUES
(101, 1, 'P1', 'OCCUPIED', '2026-07-01 08:00:00'),
(102, 1, 'P2', 'OCCUPIED', '2026-07-01 08:00:00'),
(103, 1, 'P3', 'OCCUPIED', '2026-07-01 08:00:00'),
(104, 1, 'P4', 'IDLE',     '2026-07-01 08:00:00'),
(105, 1, 'P5', 'IDLE',     '2026-07-01 08:00:00'),
(106, 1, 'P6', 'DISABLED', '2026-07-01 08:00:00'),
(107, 2, 'P1', 'OCCUPIED', '2026-07-01 08:00:00'),
(108, 2, 'P2', 'OCCUPIED', '2026-07-01 08:00:00'),
(109, 2, 'P3', 'IDLE',     '2026-07-01 08:00:00'),
(110, 2, 'P4', 'IDLE',     '2026-07-01 08:00:00'),
(111, 3, 'P1', 'OCCUPIED', '2026-07-01 08:00:00'),
(112, 3, 'P2', 'IDLE',     '2026-07-01 08:00:00'),
(113, 3, 'P3', 'IDLE',     '2026-07-01 08:00:00'),
(114, 3, 'P4', 'IDLE',     '2026-07-01 08:00:00'),
(115, 3, 'P5', 'IDLE',     '2026-07-01 08:00:00'),
(116, 4, 'P1', 'OCCUPIED', '2026-07-01 08:00:00'),
(117, 4, 'P2', 'IDLE',     '2026-07-01 08:00:00'),
(118, 4, 'P3', 'IDLE',     '2026-07-01 08:00:00'),
(119, 5, 'P1', 'OCCUPIED', '2026-07-01 08:00:00'),
(120, 5, 'P2', 'OCCUPIED', '2026-07-01 08:00:00'),
(121, 5, 'P3', 'IDLE',     '2026-07-01 08:00:00'),
(122, 5, 'P4', 'IDLE',     '2026-07-01 08:00:00');

-- ---- 车辆 -----------------------------------------------------------------
-- 在桩 9 辆（其中 1007 停在已停用的 S004、1008/1009 停满 S005）
-- 骑行中 2 辆（2001/2002，各有一张在途单）
-- 维修中 1 辆（3001，挂 S003 但不占桩）
-- 报废 1 辆（4001，终态，无站点无桩位）
INSERT INTO bike (id, code, model, status, station_id, pile_id, total_rides, scrap_reason, created_at, updated_at) VALUES
(1001, 'BK-1001', '青桔经典款',  'PILE',     1, 101,  42, NULL, '2026-06-01 08:00:00', '2026-09-20 18:00:00'),
(1002, 'BK-1002', '青桔经典款',  'PILE',     1, 102,  37, NULL, '2026-06-01 08:00:00', '2026-09-20 18:00:00'),
(1003, 'BK-1003', '青桔轻骑款',  'PILE',     1, 103,  55, NULL, '2026-06-01 08:00:00', '2026-09-20 18:00:00'),
(1004, 'BK-1004', '哈啰电助力',  'PILE',     2, 107,  88, NULL, '2026-06-02 08:00:00', '2026-09-20 18:00:00'),
(1005, 'BK-1005', '哈啰电助力',  'PILE',     2, 108,  61, NULL, '2026-06-02 08:00:00', '2026-09-20 18:00:00'),
(1006, 'BK-1006', '美团小黄车',  'PILE',     3, 111,  73, NULL, '2026-06-03 08:00:00', '2026-09-20 18:00:00'),
(1007, 'BK-1007', '美团小黄车',  'PILE',     4, 116,  19, NULL, '2026-06-03 08:00:00', '2026-09-20 18:00:00'),
(1008, 'BK-1008', '摩拜经典款',  'PILE',     5, 119,  26, NULL, '2026-06-04 08:00:00', '2026-09-20 18:00:00'),
(1009, 'BK-1009', '摩拜经典款',  'PILE',     5, 120,  31, NULL, '2026-06-04 08:00:00', '2026-09-20 18:00:00'),
(2001, 'BK-2001', '青桔经典款',  'RIDING',   NULL, NULL, 64, NULL, '2026-06-05 08:00:00', '2026-09-21 19:00:00'),
(2002, 'BK-2002', '哈啰电助力',  'RIDING',   NULL, NULL, 47, NULL, '2026-06-05 08:00:00', '2026-09-21 20:30:00'),
(3001, 'BK-3001', '美团小黄车',  'REPAIR',   3, NULL,   58, NULL, '2026-06-06 08:00:00', '2026-09-18 15:10:00'),
(4001, 'BK-4001', '摩拜经典款',  'SCRAPPED', NULL, NULL, 132, '车架断裂，判定报废', '2026-05-20 08:00:00', '2026-08-15 10:00:00');

-- ---- 租借订单 -------------------------------------------------------------
-- 4 张已结算单，覆盖计费四档：25 分钟 1.50 元 / 95 分钟 3.50 元 /
-- 200 分钟 6.50 元 / 700 分钟封顶 20.00 元。
-- 2 张在途单（RIDING），用于触发「同一辆车已有在途订单」。
INSERT INTO rental (id, order_no, user_id, bike_id, start_station_id, start_pile_id,
                    end_station_id, end_pile_id, start_time, end_time,
                    duration_minutes, amount, status, created_at) VALUES
(1, 'R20260901001', 1, 1002, 1, 102, 2, 109, '2026-09-01 08:10:00', '2026-09-01 08:35:00',  25,  1.50, 'SETTLED', '2026-09-01 08:10:00'),
(2, 'R20260901002', 1, 1003, 1, 103, 3, 112, '2026-09-01 09:00:00', '2026-09-01 10:35:00',  95,  3.50, 'SETTLED', '2026-09-01 09:00:00'),
(3, 'R20260901003', 3, 1004, 2, 107, 1, 104, '2026-09-10 18:00:00', '2026-09-10 21:20:00', 200,  6.50, 'SETTLED', '2026-09-10 18:00:00'),
(4, 'R20260901004', 3, 1006, 3, 111, 2, 110, '2026-09-15 07:00:00', '2026-09-15 18:40:00', 700, 20.00, 'SETTLED', '2026-09-15 07:00:00'),
(5, 'R20260901005', 1, 2001, 3, 112, NULL, NULL, '2026-09-21 19:00:00', NULL, NULL, NULL, 'RIDING', '2026-09-21 19:00:00'),
(6, 'R20260901006', 3, 2002, 1, 105, NULL, NULL, '2026-09-21 20:30:00', NULL, NULL, NULL, 'RIDING', '2026-09-21 20:30:00'),
-- 7 号已结算：1003 借走后又还回 S001 的 P3（下面 5 号工单就是这次骑行途中报的故障，
--    还车不拦截带未关闭工单的车 → 1003 现在「在桩且挂未完工工单」，是清站时的故障车样本）
(7, 'R20260901007', 1, 1003, 1, 103, 1, 103, '2026-09-22 08:00:00', '2026-09-22 09:10:00',  70,  2.50, 'SETTLED', '2026-09-22 08:00:00');

-- ---- 维修工 ---------------------------------------------------------------
-- 1/2 号在岗，3 号休息中 → 触发「休息的维修工不能派单」。
-- station_id 是常驻站点，供清站判定「该站有没有在岗维修工」：
--   张师傅驻 S002（但下面 1 号工单已派给他 → 名下有未完工工单，S002 暂不具备故障车接收能力）；
--   李师傅驻 S003（名下工单都已修复 → S003 是种子里唯一能接故障车的站）；
--   王师傅驻 S005 且休息。
INSERT INTO worker (id, name, phone, skill, status, station_id) VALUES
(1, '张师傅', '13800001001', '车锁与传动', 'ON_DUTY',  2),
(2, '李师傅', '13800001002', '电控与电池', 'ON_DUTY',  3),
(3, '王师傅', '13800001003', '轮胎与刹车', 'OFF_DUTY', 5);

-- ---- 运维工单 -------------------------------------------------------------
-- 1 号已派单给张师傅 09:00~11:00 → 触发「同维修工时段不重叠」，也让张师傅常驻的 S002
--   暂时「有维修工但有未完工工单冲突」，清站分配故障车时不应把 S002 算作具备维修能力；
-- 2 号待派单 → 可走通派单→修复→关闭；
-- 3 号已修复 / 4 号已关闭 → 已完成的工单不再占用维修工时段的样本；
-- 5 号挂在【在桩车 1003】名下且未派单（还车不拦截带工单的车）→ 清 S001 时
--   1003 会被识别成故障车，且因为工单没关闭，S001 也不能完成停用。
INSERT INTO work_order (id, order_no, bike_id, fault_type, level, status, worker_id,
                        plan_start, plan_end, report_time, assign_time, fix_time, fix_note) VALUES
(1, 'WO20260901001', 2001, '智能锁无法上锁', 'HIGH', 'ASSIGNED', 1,
    '2026-09-22 09:00:00', '2026-09-22 11:00:00', '2026-09-21 19:20:00', '2026-09-21 19:40:00', NULL, NULL),
(2, 'WO20260901002', 2002, '后刹车异响',     'MID',  'REPORTED', NULL,
    NULL, NULL, '2026-09-21 20:45:00', NULL, NULL, NULL),
(3, 'WO20260901003', 3001, '车架焊点开裂',   'HIGH', 'FIXED',    2,
    '2026-09-18 14:00:00', '2026-09-18 15:30:00', '2026-09-17 09:00:00', '2026-09-17 10:00:00', '2026-09-18 15:10:00', '补焊并做防锈处理'),
(4, 'WO20260901004', 1009, '脚踏板松动',     'LOW',  'CLOSED',   1,
    '2026-09-05 09:00:00', '2026-09-05 10:00:00', '2026-09-04 16:00:00', '2026-09-04 16:30:00', '2026-09-05 09:50:00', '紧固并更换销钉'),
(5, 'WO20260901005', 1003, '车把转向异响',   'MID',  'REPORTED', NULL,
    NULL, NULL, '2026-09-22 08:40:00', NULL, NULL, NULL);
