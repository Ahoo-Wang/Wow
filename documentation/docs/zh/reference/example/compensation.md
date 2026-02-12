# 事件补偿

_[事件补偿](https://github.com/Ahoo-Wang/Wow/tree/main/compensation)_ 是一个基于 _Wow_ 框架开发的真实应用案例，用于处理和恢复因事件处理失败而导致的数据不一致性。

## 模块划分

| 模块                      | 说明                                                                                       |
|-------------------------|------------------------------------------------------------------------------------------|
| wow-compensation-api    | API 层，定义聚合命令（Command）、领域事件（Domain Event）以及查询视图模型（Query View Model）。                       |
| wow-compensation-core   | 核心层，包含补偿机制的核心实现。                                                                         |
| wow-compensation-domain | 领域层，包含聚合根和业务约束的实现。                                                                       |
| wow-compensation-server | 宿主服务，应用程序的启动点。负责整合其他模块，并提供应用程序的入口。                                                       |
| dashboard               | 前端控制台，基于 React + TypeScript + Vite 开发，提供可视化的事件补偿管理界面。                                           |

## 功能特性

- **分布式自动补偿**：智能解决系统数据最终一致性问题
- **可视化控制台**：直观监控和管理补偿事件
- **企业微信通知**：及时接收执行失败通知
- **OpenAPI 接口**：方便集成和调用

## 控制台截图

![Event-Compensation-Dashboard](../../../public/images/compensation/dashboard.png)

## 详细文档

关于事件补偿的详细使用说明，请参阅 [事件补偿指南](../../guide/event-compensation)。
