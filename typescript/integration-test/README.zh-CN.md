# 集成测试

这个私有包拿 Wow TypeScript 客户端和 `wow-generator` 生成的代码，对着运行中的 Wow
示例服务端做检查。它与各个包确定性的单元测试分开。

## 测什么

- 通过 `@ahoo-wang/wow-client` 发命令、查快照、加载状态、订阅事件流（`test/wow/cart/`）。
- `src/generated/` 下的生成代码：用 `tsc` 检查，并由 `generatedCartCommandClient.test.ts` 调用。
- `test/wow/wowOpenApi.test.ts`：`wow-client` 发给服务端的每个枚举值都要出现在服务端的
  OpenAPI 文档里。

## 前置条件

8080 端口上有一个连着 MongoDB 的 Wow 示例服务端。可以从本仓构建：

```bash
./gradlew :example-server:installDist
```

也可以运行已发布的镜像 `ghcr.io/ahoo-wang/wow-example-server:<version>`。用
`SPRING_MONGODB_URI` 指向 MongoDB，并设置
`WOW_EVENTSOURCING_STORE_STORAGE=mongo`、`WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo`。

## 生成与测试

在仓库根目录执行：

```bash
pnpm install --frozen-lockfile
pnpm --filter wow-integration-test... build
pnpm --filter wow-integration-test generate
pnpm --filter wow-integration-test exec tsc --noEmit
pnpm --filter wow-integration-test test
```

`generate` 读取 `http://localhost:8080/v3/api-docs` 并替换 `src/generated`。提交前先检查生成结果的改动。

## 排查失败

1. 先构建；工作区导入解析不到，通常是包的产物过期或缺失。
2. 生成或测试前先访问 `http://localhost:8080/actuator/health`。
3. 服务端契约变了就重新生成。

[English](./README.md)
