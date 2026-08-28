# Java 银行转账 Saga 示例

该示例用 Java 聚合实现账户业务，用 Wow 无状态 Saga 协调跨账户转账。成功路径是 `Prepared -> Entry -> AmountEntered -> Confirm`；目标账户冻结时走 `EntryFailed -> UnlockAmount`，将源账户的已锁金额退回。这是事件驱动补偿，不是跨聚合数据库事务。

## 模块

| 模块 | 职责 |
| --- | --- |
| [`example-transfer-api`](example-transfer-api/) | `account` 的命令、事件与发布语言 |
| [`example-transfer-domain`](example-transfer-domain/) | `Account` / `AccountState` 的决策与溯源，以及 `TransferSaga` |
| [`example-transfer-server`](example-transfer-server/) | Spring Boot 入口，组装 WebFlux 与 OpenAPI |

## 先验证领域

```shell
./gradlew :example-transfer-domain:check
```

该检查覆盖账户冻结、余额不足、锁款/解锁，以及 `Prepared` / `AmountEntered` / `EntryFailed` 到 Saga 命令的三条映射。

## 启动真实 Java 入口

当前 `example-transfer-server` 的 Gradle `application.mainClass` 仍指向不存在的 `me.ahoo.wow.example.transfer.server.ExampleServer`，因此不要用 `./gradlew :example-transfer-server:run` 掩盖该已知构建配置缺陷。先生成 distribution，再直接启动实际主类：

```shell
./gradlew :example-transfer-server:installDist

java \
  -Dserver.address=127.0.0.1 \
  -Dserver.port=8080 \
  -Dspring.config.location=file:example/transfer/example-transfer-server/src/main/resources/application.yaml \
  -cp 'example/transfer/example-transfer-server/build/install/example-transfer-server/lib/*' \
  me.ahoo.wow.example.transfer.server.TransferExampleServer
```

这条路径不执行 distribution 中的启动脚本，因此不继承当前 application 插件里未认证的 JMX 5555 参数。已验证的进程只在 `127.0.0.1:8080` 监听；应用使用内存 command/event bus、EventStore 和 SnapshotStore，进程退出后账户数据消失。

在另一个终端检查：

```shell
curl -fsS http://127.0.0.1:8080/actuator/health/liveness
curl -fsS http://127.0.0.1:8080/v3/api-docs | \
  jq -r '.paths["/account/{id}/prepare"].post.operationId'
```

预期分别得到 `{"status":"UP"}` 和 `transfer.account.prepare`。核心路由是：

| 操作 | 方法与路径 |
| --- | --- |
| 创建账户 | `POST /account/create_account` |
| 准备转账 | `POST /account/{id}/prepare` |
| 读取账户状态 | `GET /account/{id}/state` |

可用 [`Transfer.http`](Transfer.http) 执行两个账户的成功路径。从 100 转出 10 后，预期源账户 `balanceAmount=90, lockedAmount=0`，目标账户 `balanceAmount=10`。

完整的命令/事件/状态对照、失败路径和源码索引见 [Java 转账参考案例](../../documentation/docs/zh/reference/example/transfer.md)；Saga 机制与补偿边界见[分布式事务（Saga）](../../documentation/docs/zh/guide/saga.md)。
