---
title: 在 CI 中重新生成
description: 提交 OpenAPI 文档，并在重新生成改变了输出时让 CI 失败，使生成的 Wow 客户端与所调用的服务保持一致。
---

# 在 CI 中重新生成

本页回答：**怎样保证提交的生成客户端与服务契约一致，而不必靠开发者记得重新生成？**

像对待锁文件一样对待生成代码：它被提交，由工具从输入产生，提交的输出与工具现在产生的不一致时 CI 失败。Wow 仓库就这样检查自己的生成客户端：每次改到服务端或生成器，都拿示例服务重新生成并比对。

## 1. 确定文档来源

| 来源 | 适合 | 取舍 |
|---|---|---|
| 提交在应用仓库里的文档（`openapi/orders.json`） | 大多数项目 | 构建不需要运行中的服务。服务变化时要有人更新这个文件——手工、定时任务，或由服务的流水线发来 PR |
| 运行中的服务（`-i https://orders.internal/v3/api-docs`） | 预发环境总是运行着你要对接的那个版本 | CI 依赖该环境可用且版本正确；用 `-H` 传凭据，并设置 `--timeout` |

无论哪种，每个服务只从一份文档生成，并让输出目录只归生成器所有。

## 2. 一个脚本

```json
{
  "scripts": {
    "generate": "wow-generator generate -i openapi/orders.json -o src/generated/orders -t tsconfig.json --strict",
    "generate:check": "pnpm generate && git diff --exit-code -- src/generated"
  }
}
```

- `--strict` 在本次运行有警告时以退出码 4 结束，例如某个操作因为缺少 operationId 被跳过；这样生成得比应有的少的契约也会失败。
- 本次运行把写出的文件记录在 `src/generated/orders/.wow-generator.json`。请提交它：下一次运行靠它删除不再生成的文件。
- `git diff --exit-code` 能看到被删除的文件。要发现新增的文件，先暂存目录，或者像下面那样检查 `git status --porcelain`。

多个服务就写多个输出目录，每个一条命令；各自的清单把它们的文件分开。

## 3. CI 任务

```yaml
name: Generated clients
on:
  pull_request:
  push:
    branches: [main]
jobs:
  generated-clients:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: pnpm/action-setup@v4
      - uses: actions/setup-node@v5
        with:
          node-version: 22
          cache: pnpm
      - run: pnpm install --frozen-lockfile
      - name: Regenerate
        run: pnpm generate
      - name: Require the committed clients to match
        run: |
          if [ -n "$(git status --porcelain --untracked-files=all -- src/generated)" ]; then
            git --no-pager diff -- src/generated
            echo "::error::src/generated is out of date. Run 'pnpm generate' and commit the result."
            exit 1
          fi
      - name: Type-check
        run: pnpm exec tsc --noEmit
```

把各个 action 固定到贵组织使用的版本或提交 SHA。改为从运行中的服务生成时，把输入换成 URL，并从 secret 传入令牌：`-i "$SPEC_URL" -H "Authorization: Bearer $SPEC_TOKEN" --timeout 60000`。

## 4. 看退出码

| 退出码 | 含义 | 怎么办 |
|---|---|---|
| 0 | 生成成功 | 按上文比对输出 |
| 1 | 内部错误 | 加 `--verbose` 重跑查看堆栈，并报告问题 |
| 2 | 输入：文档读不到或取不到、不是 JSON 或 YAML、不是 OpenAPI 3.x（Swagger 2.0 会被拒绝），或选项值无效 | 检查路径或 URL、网络和凭据 |
| 3 | 配置：`wow-generator.config.json`（或 `-c` 指定的文件）读不到、解析不了或校验不通过 | 修正该文件；默认位置没有文件不算错误 |
| 4 | 规范：文档描述了生成器无法生成的代码，或开启 `--strict` 且本次运行有警告 | 读那一行报错，它会点名操作或 schema |
| 130 | 被中断 | — |

完整情况见 [CLI 参考](../../reference/typescript/wow-generator/cli.md#失败与退出码)。

## 5. 审查一次重新生成

检查因服务变化而失败时，在本地重新生成，并像审查契约变更一样阅读 diff：删掉的命令方法或改名的字段会让调用方在编译期出错，这正是目的所在。升级 `@ahoo-wang/wow-generator` 本身也可能改变输出；把它放在单独的 PR 里，连同重新生成的代码一起提交，让 diff 只显示生成器带来的变化。永远不要手改生成的文件：下一次运行会替换它们。

## 延伸阅读

- [快速开始](./quick-start.md)：第一次生成。
- [生成器 CLI](../../reference/typescript/wow-generator/cli.md)、[配置](../../reference/typescript/wow-generator/configuration.md)与[生成结果与重新生成](../../reference/typescript/wow-generator/generated-output.md)。
- [兼容性与版本](./compatibility.md)：哪个生成器版本对应哪个服务。
