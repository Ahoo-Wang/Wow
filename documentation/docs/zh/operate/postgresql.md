---
title: PostgreSQL
description: "在 PostgreSQL 上运行服务：DataSource、Wow 创建的表、它需要的服务端设置、故障切换与同步复制、用于追加和查询的连接池、把聚合放在多个数据库上，以及 schema 管理。"
outline: deep
---

# PostgreSQL

在 PostgreSQL 上，一个数据库保存服务存储的一切：它的日志、快照、定时器，以及它的检查点、租约、失败台账和预留。日志每次提交一条自动提交的语句；负载高时，节点把一个分区的追加合成一条语句。本页连接一个服务、介绍这些表，并确定连接池的大小。

## 连接服务 {#connect-a-service}

把 `wow-postgres` 和 HikariCP 放到类路径上，并设置 `spring.datasource.url` 以及 `spring.datasource.username`、`spring.datasource.password`：宿主用它们建一个带连接池的 `DataSource`，并选择 PostgreSQL。HikariCP 自己的设置放在 `spring.datasource.hikari.*` 下，例如 `maximum-pool-size`。连接池在第一次取连接时才打开。没有设置 URL 时，宿主不连接任何数据库。

自己定义了 `DataSource` Bean 的应用保留它；引入了 Spring Boot JDBC 模块（`spring-boot-starter-jdbc`）的应用，使用 Spring Boot 用同样的属性建的那个。eShop 的服务只在它们的 `postgres` profile 中设置这些属性：

<!-- sample: examples/eshop/ordering/ordering-server/src/main/resources/application.yaml#postgres -->

```yaml
spring:
  config:
    activate:
      on-profile: postgres
  datasource:
    url: jdbc:postgresql://localhost:5432/eshop_ordering
    username: postgres
    password: postgres
```

`wow.backend: postgres` 直接指明后端，而不是交给应用拥有的 Bean 来决定。每个服务有自己的数据库；两个服务从不共用一个。

## 表 {#the-tables}

存储在第一次使用时创建它们的表。表名由上下文和聚合类型推出，所以人或查询服务可以直接读取一个聚合的数据：

| 表 | 保存 |
| --- | --- |
| `wow_log__<context>__<aggregate>` | 一个聚合类型的日志：每条已提交的流一行，它的事实放在列里（版本、命令、请求、作用域、因果、操作人，访问属性为 `jsonb`，未盖章时为 `null`），以及它的事件 |
| `wow_log__<context>__partition` | 上下文日志的每个分区一行计数器 |
| `wow_log_aggregate` | 每个上下文的日志有哪些聚合表 |
| `wow_snapshot__<context>__<aggregate>` | 每个聚合一行：它最新的快照，状态和访问属性为 `jsonb`，直接在原处查询 |
| `wow_snapshot_table` | 每张快照表保存的状态修订号 |
| `wow_timer__<context>__<aggregate>` | 一种聚合类型的定时命令，每个定时器一行，位于其属主的分区 |
| `wow_timer_aggregate` | 每个上下文有哪些定时器表；到期读取在一条语句中合并它们 |
| `wow_checkpoint`、`wow_lease`、`wow_lease_member` | 每个订阅进行到哪里，以及哪个节点拥有哪个分区 |
| `wow_failure_ledger` | 停放的聚合 |
| `wow_reservation__<context>__<aggregate>` | 一种类型的聚合预留的唯一键：每个（租户、命名空间、键）一行，带着它的所有者 |
| `wow_format` | 每个结构的格式版本 |

日志表的键是位置（`partition`、`sequence`）、版本（`tenant_id`、`aggregate_id`、`version`）和请求（`tenant_id`、`request_id`）：数据库本身就会拒绝同一版本或同一请求的第二次提交。

**格式。** 每个结构在创建之前先在 `wow_format` 中声明它的格式版本。以另一个版本记录的结构，或者存在却没有记录的结构，都会被拒绝：一个 Wow 构建从不读写它不认识的布局。

## 管理 schema {#manage-the-schema}

`wow.ddl` 决定存储怎样对待它们的表：

| `wow.ddl` | 作用 |
| --- | --- |
| `auto`（默认） | 创建缺失的东西 |
| `validate` | 什么都不创建；缺少表或表是另一种格式时拒绝启动 |
| `none` | 什么都不创建，也不检查 |

数据库账户不能建表的部署，先用一个能建表的账户运行一次，或者自己应用 schema，然后以 `validate` 运行。`wow.stores.<name>.ddl` 为单个存储设置它。

聚合声明的查询索引（`@QueryIndex`）来自一次 `auto` 下的启动：它用 `CREATE INDEX CONCURRENTLY` 建立每个索引，并把规划定义的指纹记为索引的注释。`validate` 检查这条注释，所以手工建立的同名索引（没有这条注释）或早先定义的索引会被拒绝，下一次 `auto` 下的启动会重建它。改变索引规划方式的版本会把声明的索引重建一次。

## 服务端设置 {#server-settings}

**TCP keepalive。** 节点不关连接就死掉时，它的连接对应的后端进程仍然开着，其中未结束的事务还持有它的锁。设置服务端的 keepalive，让服务器在一分钟左右察觉并结束它们：

```sql
ALTER SYSTEM SET tcp_keepalives_idle = 30;
ALTER SYSTEM SET tcp_keepalives_interval = 10;
ALTER SYSTEM SET tcp_keepalives_count = 3;
SELECT pg_reload_conf();
```

默认值跟随操作系统，通常要静默两小时才发第一次探测。

**Wow 在事务内设置的上限。** 存储开启的每个事务，开头都有一批 `SET LOCAL`，与事务的第一条语句在同一次往返中发送（第一条语句若是批量写入，则先单独发送），由连接的超时（`wow.postgres.operation-timeout`，默认 30 秒）推导，没有单独的配置项：

| 设置 | 取值 | 作用 |
| --- | --- | --- |
| `statement_timeout`、`lock_timeout` | 超时减 1 秒，至少 1 秒：默认 29 秒 | 服务器在客户端放弃之前取消语句（`57014`），连接得以保留 |
| `idle_in_transaction_session_timeout` | 超时的两倍：默认 60 秒 | 服务器结束节点留下未结束的事务，包括崩溃节点的事务 |

查询的事务改为使用自己的预算，并以只读方式开启。追加每次是一条自动提交的语句，不设服务端上限。`SET LOCAL` 随事务结束，所以应用的连接池上什么也不会留下。

**建表步骤。** 存储在创建任何东西之前先读系统目录，所以 schema 已是最新时不取表锁。必须执行的语句等锁最多 5 秒，最多尝试 5 次（含第一次），退避从半秒开始；之后启动失败，并指出表和持有锁的会话。同时启动的节点一次只有一个执行同一张表的步骤。

**快照保存与 `fillfactor`。** 一次快照保存会重写其聚合所在的行。只有当没有被索引的列发生变化、且该行所在的页还有空间容纳新版本时，PostgreSQL 才会让这次更新不触及索引（HOT 更新）。

- **在 `state` 字段上声明的索引**（`@QueryIndex`）索引的是 `state`，而每次保存都会改变它，所以该聚合的每次保存都是完整更新，与 `fillfactor` 无关。
- **没有这样的索引时，** 若一张表的状态能在一页中放下好几个，可以让每页为新版本留出空间：

  ```sql
  ALTER TABLE wow_snapshot__<context>__<aggregate> SET (fillfactor = 80);
  ```

  这样每页保留其 8 kB 的五分之一，所以 80 适合大约 1.6 kB 以内的状态。

在 20,000 个约 1.2 kB 的快照上保存 100,000 次的实测：

| `fillfactor` | HOT 保存 | 堆 |
| --- | --- | --- |
| 100（默认） | 75% | 加载时 24 MB，保存后 46 MB |
| 80 | 100% | 加载时大 37%，保存后不变 |

该设置只作用于其后写入的页；`VACUUM FULL` 会在排他锁下重写已有的页。Wow 自己不设置 `fillfactor`。

## 故障切换 {#failover}

在单个 PostgreSQL 服务器上，一行只有在它的事务提交之后才可见，日志的追加在 WAL 落盘后提交（`synchronous_commit` 取默认值 `on`）。订阅读取的是已提交的行，所以服务器崩溃不会丢掉任何订阅已据以行动的东西。

切换到备库时，只有同步复制才能保住这一点。PostgreSQL 默认异步复制，被提升的异步备库缺少它尚未收到的提交。旧主库已经确认过这些提交，订阅也可能已经据以行动。Wow 从它的连接上看不到复制配置：这由部署方决定。

**异步故障切换可能留下什么。** 新主库保有数据库一个较早的、一致的时点：它的日志、检查点和计数器停在同一处。订阅对丢失的提交做过的事，留在它去往的地方：

- saga 的派生命令，已在另一个上下文中提交；
- Kafka 上的消息，已被其他上下文读到；
- 投影对另一个系统的写入，例如视图或索引；
- 保存在另一个存储中的快照。

之后日志会把丢失的位置和版本分给新的提交，所以同一个聚合版本可能在服务之外出现两次，带着不同的事件。

**找出它们。** 数据库里的检查点随日志一起回退，所以看不出缺口。拿日志的头部位置与保存在它之外的东西比较：

- 检查点保存在另一个存储中的订阅，会站在其分区头部位置之后（`GET /wow/ops/subscriptions` 给出每个分区的位置与头部位置，见[运维服务与控制台](./ops-console#without-the-ops-service)）；
- 服务的 Kafka 主题，以及订阅写入的读模型和系统，保有日志已经没有的提交。

然后按恢复到较早时点之后的做法处理：重建保存在数据库之外的读模型，并告知读取该服务事件流的那些上下文的负责人（[恢复数据库](./backup#restore-a-database)）。

**消除这个缺口。** 让故障切换的目标是同步备库。在主库的 `postgresql.conf` 中：

```ini
synchronous_standby_names = 'ANY 1 (standby_a, standby_b)'
synchronous_commit = on
```

- **`synchronous_standby_names`** 按备库的 `application_name` 列出提交要等待的备库：
  - `FIRST n (…)` 等待列在最前、已连接的 `n` 个；
  - `ANY n (…)` 等待其中任意 `n` 个。

  它只能在配置文件或服务器命令行中设置。
- **`synchronous_commit = on`**（默认值）让提交等到这些备库把它刷到持久存储。`remote_apply` 还要等它们重放完、在备库上对查询可见，延迟更大。
  - `remote_write` 只等数据到达备库的操作系统，这个操作系统崩溃就会丢失。
  - `local` 和 `off` 不等任何备库。
- **对 Wow 的账号生效。** `synchronous_commit` 也可以按角色、数据库或事务设置，在那里设成 `local` 或 `off` 就会让 Wow 的提交失去这个保证。Wow 自己只在被覆盖的快照保存上降低它（见下）。
- **尾随读读到什么。** 提交在等待备库期间，PostgreSQL 让它对其他会话不可见，所以尾随读读不到同步备库还没有的东西。等待中的提交只在两种情况下变成仅在主库上可见：它的等待被取消，或主库在等待期间重启。不要取消正在等待复制的后端。
- **提升同步备库。** 用 `ANY` 时，提升进度最靠前的那个。

**它让提交付出什么。** 每次提交至少等待一次到同步备库的往返，用 `remote_apply` 时更久，期间一直持有它的锁。让备库靠近主库。备库缺席多久，提交就等待多久，所以只指定一个备库时，它一停，所有提交都会停住。指定的备库要比提交等待的多一个（两个中的 `ANY 1`，或两个中的 `FIRST 1`）。节点的调用最多等待 `wow.postgres.operation-timeout`，然后失败（[确定连接池的大小](#size-the-pools)）。

**被覆盖的快照。** 默认情况下，上下文的日志、它的快照订阅的检查点和它的快照在同一个存储上，这时快照保存以 `synchronous_commit = off` 提交：既不等本地落盘，也不等备库。这不会留下缺口。覆盖这些快照的检查点在它们之后提交，并等待备库；WAL 按顺序到达备库，所以有了这个检查点的备库也有这些快照。备库缺少的快照位于它最后一个检查点之后，订阅会从日志重新写出它。

**在托管服务上。**

- Amazon RDS 的 Multi-AZ 数据库实例同步复制到它的备用实例；Multi-AZ 数据库集群在至少一个读取实例确认后提交。RDS 的只读副本是异步的。
- Amazon Aurora 把写入实例和副本共享的集群卷同步写到跨可用区的六个存储节点。全球数据库向其他区域的复制是异步的。

切换到异步副本，就是上面说的情况。

## 确定连接池的大小 {#size-the-pools}

每个节点为它的追加、订阅、定时器和租约持有几个连接，为它的查询持有更多。连接池耗尽时追加就会等待，所以给查询一个单独的连接池：

- 定义第二个 `DataSource` Bean，并把它指定为存储的 `query-data-source`：快照查询和 `events` 查询就从它取连接，绝不会占用追加正在等待的连接。它可以指向只读副本。
- 没有单独的连接池时，查询与追加共用连接池，节点把查询限制在它的四分之一，至少 2 个。追加变慢或等待连接时降低这个上限，恢复后再提高，最多到连接池的一半（[配置项](../reference/configuration#wow-query-adaptive)）。

按数据库计算连接数：连接池大小乘以服务的节点数，再加一个连接池，留给替换崩溃节点的新节点，因为崩溃节点的连接在上面的 keepalive 结束它们之前仍然开着，再加上迁移和运维人员使用的连接，要在 PostgreSQL 的 `max_connections` 之内。

存储借出的每个连接等待回应最多 `wow.postgres.operation-timeout`（默认 30 秒，单个存储的 `operation-timeout`（`wow.stores.<name>.operation-timeout`）可覆盖），建表类语句最多 5 分钟：在故障切换中或代理之后丢失的回应会让调用以暂时性错误失败并换掉这个连接，而不是一直挂住。连接池的驱动已设了更短的 `socketTimeout` 时保留它。服务器按同一个超时为 Wow 的每个事务设上限（[服务端设置](#server-settings)）。

## 多个数据库 {#several-databases}

各聚合类型把数据放在不同数据库里的服务，命名它的存储实例，并把每个类型放到其中一个上：

```yaml
wow:
  stores:
    orders:
      type: postgres
      data-source: ordersDataSource
    archive:
      type: postgres
      data-source: archiveDataSource
  log:
    store: orders
  snapshot:
    store: orders
  aggregates:
    "[shop.invoice]":
      log:
        store: archive
```

每个存储运行在它指定的 `DataSource` Bean 上，两个日志存储不能共用一个数据库。放到另一个存储上的聚合类型，把它的日志、它的订阅的检查点、它的失败台账、定时器和预留都放在那里；租约仍留在默认的日志存储上。

## 备份 {#backups}

日志是唯一的事实：快照、读模型和数仓都从它重建。把日志表与检查点、租约、台账、预留和定时器一起，作为一次一致的数据库备份来备份。
