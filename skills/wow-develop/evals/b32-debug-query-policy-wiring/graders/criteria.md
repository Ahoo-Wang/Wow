---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 核实目标是明确提交而不是仅依赖版本名称；将配置迁移异常与查询范围异常分别定位，不猜成同一故障
2. 从目标源码及原始异常证明旧配置键无论 strict/compatible 均不支持，不推广到 8.13 等历史版本，也不建议关闭 Schema 或加兼容模式
3. 比较同一身份输入的 JVM/HTTP 最终 Backend filter，追踪 HTTP scope 提取、Reactor Context、实际 Gateway 实例来源、Policy Beans 和适用性
4. 证明手工装配是否遗漏 policies 或 scope 后再归因，不把框架存在正确 Registrar 当成应用实际使用它的证据
5. 复现 prepare 覆盖及空/失败 Policy 的零 Backend 调用；HTTP 记录型 Backend 只能证明链路装配，数据隔离和存储执行另需授权环境证据
6. 保留只读，明确缺少的下游源码/日志/运行证据，不将策略搬到 Schema、补客户端条件或创建新的执行层掩盖旁路
