# 集成测试

该工作区使用真实 HTTP 服务和生成的 Wow 客户端验证已构建 Fetcher 包。它与确定性的包
单元测试明确分离。

## 测试范围

- 针对 JSONPlaceholder 的核心 Fetcher 与 Decorator 请求。
- 针对 Wow 示例服务的命令、快照、状态加载与事件流。
- `src/generated/` 下生成的 Wow 客户端。
- 提供 LLM 测试变量时，对 OpenAI 兼容服务执行流式与非流式调用。

## 前置条件

- 安装根目录依赖。
- 测试前构建全部包。
- JSONPlaceholder 测试需要外网访问。
- 生成和 Wow 测试需要 MongoDB，以及在 8080 端口运行的
  `ghcr.io/ahoo-wang/wow-example-server:8.13.0`。CI 工作流是规范服务配置。
- LLM 测试变量只通过环境提供，禁止提交具体值。

## 生成并测试

在仓库根目录执行：

```bash
pnpm install --frozen-lockfile
pnpm build
pnpm --dir integration-test generate
pnpm test:it
```

`generate` 读取 `http://localhost:8080/v3/api-docs` 并替换
`integration-test/src/generated`。提交前检查生成差异。

诊断时运行单个测试：

```bash
pnpm --dir integration-test vitest run \
  test/wow/cart/cartSnapshotQueryClient.test.ts
```

## 可选 LLM 环境变量

- `FETCHER_LLM_BASE_URL`
- `FETCHER_LLM_API_KEY`
- `FETCHER_LLM_MODEL`

这些测试会联系配置的供应商，并可能产生成本。除非需要实时集成证据，否则不要放入普通
本地测试。

## 失败诊断

1. 先构建；工作区导入无法解析通常意味着包产物缺失或过期。
2. 生成或运行 Wow 测试前检查 `http://localhost:8080/actuator/health`。
3. 服务端契约变更后重新生成。
4. 区分网络/供应商失败与包单元回归。
5. 完成后删除临时容器，并取消设置凭据环境变量。

[English](./README.md)
