---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 从异常字段沿 QuerySchemaSource、声明合并和后端 adapter 定位第一个冲突阶段
2. 对照目标版本源码与下游 JSON Schema、classpath、working-directory 和 Bean 声明，找出同一 leaf 的不一致值
3. 区分声明合并冲突、运行时 validation mode 与后端 mapping 能力，不通过关闭 Schema 或改成 STRICT 规避冲突
4. 给出统一或显式覆盖该字段声明的最小修复方向及启动和运行时 Schema 复验门禁
5. 保持只读并标记未验证的后端 mapping、请求兼容和部署状态
