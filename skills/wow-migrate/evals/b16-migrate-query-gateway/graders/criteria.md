---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 固定下游旧版及目标 commit，区分 V8→V9 公共 API 迁移与同主版本实现 SPI 变动；QueryGateway/Backend 分责并在构造时绑定聚合与完整 Backend/Provider binding
2. 根据目标源码映射 Service/Factory/Backend/Registrar/NoOp/Store/索引及测试类型；删除旧代理、DynamicDocument、isDynamic 分流和 Mask registry，不把 starter 内部 Unavailable 当下游 API
3. 目标 Gateway 采用 prepare→scope→QueryPolicy→defaults→public validation→Backend→Mask/typed/observer 固定顺序，QueryContext 不携带 next、执行权或结果 Publisher
4. 两模型策略通过 QueryPolicy 装配，强制条件不能由普通 prepare 保证；ABAC 仅在 Snapshot 读标签，空或失败策略拒绝并阻止 Backend
5. Provider 加载刷新事实，Schema 只保存不可变值树与原生绑定，Backend 接收逻辑 query 和同一 schema；不保留 ResolvedQuery、validationMode 或旧 ErrorHandler 构造参数
6. 分别验证源码、二进制和 wire；实现 SPI 允许破坏时不补旧构造器或 override 桥。保留既有 Condition JVM 及适用 REST adapters 到 10.0.0；递归 Schema metadata 的变动不等于数据迁移
7. 验证自定义 SPI 编译、实际 Spring Bean 和 HTTP 路径、最终 Backend 约束、规范和兼容请求、Schema refresh 与所选后端；没有 mapping/writer/存储格式变化不发明数据转换，保持只读并报告证据缺口
