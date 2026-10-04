---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 逐项对照两个源码版本，候选版本属性不能证明目标制品已发布
2. 删除旧 validation-mode 配置而不是改成 strict/compatible 或关闭 Schema，迁移旧 Gateway 构造参数和 ResolvedQuery Backend 方法
3. 普通准备改为 QueryFilter.prepare，必须保留的规则用 QueryPolicy，结果处理和终止观察保持固定职责；两种 Gateway 都接入策略
4. Schema 构造迁为递归逻辑值树与原生绑定，Provider 只负责事实加载刷新，不把请求校验执行权放入 Schema
5. 按授权范围保留 QueryGateway API/既有 Condition 至 10.0.0，不为实现类构造器或未发布中间钩子补兼容桥，并单独核实源码/二进制/wire
6. 列出 SPI 编译、启动、实际路由装配、HTTP 最终谓词/拒绝、旧新请求和后端验证；只在已证明存储或 writer 变化时规划数据转换
