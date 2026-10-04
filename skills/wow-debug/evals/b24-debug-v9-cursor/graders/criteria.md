---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 确认下游实际 Wow 版本并捕获第一、第二页完整 filter、projection、sort、size、cursor、路由、异常与所选 Backend
2. 用未修改的 nextCursor 和不变 filter/sort 建立最小对照，定位首个错误阶段而不是列出猜测
3. 把应用解析重编码不透明 token 且改变 sort 识别为 Backend token 解码、值数量或有效排序合同被破坏，不把第一页成功当作第二页兼容证据
4. 检查 Gateway request rewrite、Query Schema 有效排序、唯一 tie-breaker 与 Backend codec 边界，但不声称 token 携带授权或跨 Backend 可用
5. 保持只读，不通过重试、回到第一页、退化 PagedQuery 或自定义 token 适配器隐藏失败，并报告未取得的真实 Backend/HTTP 证据
