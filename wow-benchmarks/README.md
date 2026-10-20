# Wow Benchmarks

| 路径 | 内容 |
| --- | --- |
| [`e2e/`](e2e/README.md) | V10 与 9.x 的端到端架构基准（决策 D224）：V10 的孪生基准领域与服务 `:e2e-bench-v10`、负载生成器 `:e2e-bench-load` 与运行脚本 |
| `results/` | 已提交的性能证据：每份报告带复现它的提交、命令、环境与原始结果 |

9.x 的 JMH 基准（`:wow-benchmarks`、`build-logic` 的基准插件、`benchmark-smoke.yml` 与 `benchmark-ab.yml`）随 9.x 代码一起提前离开了 `v10` 分支（决策 D244、D262）。它们留在 main 与旁路分支 `v10-9x-baseline` 上：9.x 的对照（JMH 或端到端的 `bench-9x`）从该分支的工作树运行，见 [`e2e/README.md`](e2e/README.md)。`results/` 里 9.x 时期的报告保留为其记录条件下的证据，复现它们也从 `v10-9x-baseline` 或 main 进行。
