# 集成测试

这个私有包把 `@ahoo-wang/wow-view-store` 和建在它上面的视图引擎对着
视图存储服务（`platform/view-store`）跑。它与各包确定性的单元测试分开。

## 跑什么

`test/view-store/` 拿服务检验存储：视图引擎的端口一致性套件
（`../wow-view-engine/test/conformance/viewStoreConformance.ts`，按工作区路径导入，
所以 `tsconfig.json` 把 `rootDir` 设为 `..`）、租户与应用隔离、存储的与配置的系统视图、
服务的写入角色、租户的数据集措辞、错误、查询路由上的 `viewSourceOf`，以及引擎端到端
（保存、冲突、结果未知后的重试、共享与认领、偏好、经 查询路由的记录视图和分析）。

## 运行

全局准备（`test/view-store/globalSetup.ts`）在一个 JVM 里以内存后端启动服务，
所有端口绑定到 0，结束时停掉。内存后端上，服务自己的配置从内存回答快照查询
（`wow.query.memory.enabled`）。先用 JDK 21 构建：

```bash
./gradlew :view-store-server:installDist :view-store-server:testClasses :query-tck:jar
pnpm --filter @ahoo-wang/wow-view-store... build
pnpm --dir typescript/integration-test test
```

`JAVA_HOME` 选择 JVM（否则用路径上的 `java`）。设了 `WOW_VIEW_STORE_URL` 时，
套件改用一个已在运行的服务；它必须回答快照查询，并提供
`test/view-store/globalSetup.ts` 配置的 `conformance-system` 系统视图。

## CI

`.github/workflows/typescript-contract.yml` 的 `v10-contract` 作业对着同一提交构建的服务
跑 `test`。

`typecheck` 检查 `test`，不需要服务。`typescript.yml` 的 Quality 作业通过根目录的
`pnpm typecheck` 在每个涉及 TypeScript 的拉取请求上运行它，所以 `wow-client`、
`wow-view-store` 或 `wow-view-engine` 的类型改动破坏这些测试时，在那里就失败，
不必等契约作业。

[English](./README.md)
