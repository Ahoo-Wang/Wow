# HTTP 开发实验

本目录不进入发布包。路径、响应格式、状态码映射和测试身份仍属实验，不构成公共 ViewHost 协议；用于验证服务与运行时边界及恢复能力。

### HTTP 适配器与协议

```tsx
import { HttpViewHost } from './http/index.js';

const host = new HttpViewHost({
  baseUrl: 'https://example.test/view-service/',
  definitionId: orderDefinition.id,
  headers: () => applicationAuthHeaders(),
  resolveSource: id => businessSources[id],
  timeoutMs: 10000,
});
// ViewPage: host + definitionId + an access-scoped scopeKey; no local definition/instances props.
```

以下路径相对于 `/view-service/definitions/{definitionId}`：

开发目录包含资源客户端：`HttpViewDefinitionService`、`HttpViewInstanceService`、`HttpViewPreferenceService`，可以直接使用，不依赖 ViewHost 或前端运行时。`HttpViewTransport` 共享认证、超时和错误处理，其配置不包含 `resolveSource`。共享的 `transport.permission` 客户端负责快照校验、版本控制与订阅，提供 `load(definitionId, signal?)` 和 `refresh(signal?)`。

```ts
import { HttpViewTransport, HttpViewInstanceService } from './http/index.js';

const transport = new HttpViewTransport({
  baseUrl: 'https://example.test/view-service/',
  definitionId: 'orders',
  headers: () => applicationAuthHeaders(),
});
const instance = new HttpViewInstanceService(transport);
const views = await instance.list('orders');
```

| 方法   | 路径                   | 输入／条件                                        |
| ------ | ---------------------- | ------------------------------------------------- |
| GET    | 定义根路径             | 返回 ViewDefinition                               |
| GET    | `/instances`           | 返回 ViewInstanceList                             |
| GET    | `/instances/{id}`      | 返回完整实例                                      |
| GET    | `/permissions`         | 返回权限快照                                      |
| POST   | `/instances`           | 不含 id/revision 的实例；必须提供 Idempotency-Key |
| PUT    | `/instances/{id}`      | 完整实例；If-Match 提供带引号的 revision          |
| PATCH  | `/instances/{id}/name` | `{title}` 与 If-Match                             |
| DELETE | `/instances/{id}`      | If-Match                                          |
| PUT    | `/order`               | `{instanceIds}`，必须完整且不重复                 |
| PUT    | `/default`             | `{instanceId}`，可见 ID；`null` 表示不自动选择    |

成功响应为 `{data, permissions}`，无返回内容的写入使用 `data: null`。读取实例、创建、保存和改名均返回完整权威实例及版本。错误响应为 `{data: null, error: {code, message}, permissions?}`；可识别身份的失败同时返回最新权限。响应和客户端请求均禁止缓存。

定义与实例 ID 必须为非空白的有效 Unicode 字符串，不能整体为 `.` 或 `..`。本地元数据与 HTTP 输入遵循相同规则；合法 ID 仅编码一次，保留 Unicode 和保留字符。`permission.load()` 返回已校验、当前已接受的权限快照副本；无效、不一致或过期的正文以 `UNAVAILABLE` 拒绝。`refresh()` 继续忽略旧快照，不向调用方暴露其原始正文。

权限快照为 `{revision, reorder, instances: {[id]: {save, rename, delete, saveAsPersonal, saveAsShared}}}`，所有授权字段均为显式 boolean。旧策略版本不能恢复已撤销权限；HTTP 401 在解析响应正文之前清空权限并阻止更早的响应恢复授权；即使正文为文本或无效 JSON，也返回 `UNAUTHENTICATED`。应用在权限变化事件中调用 `permission.refresh(signal?)`；这里不内置轮询或推送传输。用户或访问范围改变仍必须切换 ViewPage.scopeKey。

| 错误码                | HTTP | 含义                                                           |
| --------------------- | ---: | -------------------------------------------------------------- |
| INVALID_ARGUMENT      |  400 | 输入不合法                                                     |
| UNAUTHENTICATED       |  401 | 会话缺失或无效                                                 |
| FORBIDDEN             |  403 | 身份有效但不允许该操作                                         |
| NOT_FOUND             |  404 | 资源不存在或不可见                                             |
| CONFLICT              |  409 | 幂等键复用于不同内容，或排序的可见 ID 集合已变化               |
| REVISION_CONFLICT     |  412 | revision 不匹配                                                |
| PRECONDITION_REQUIRED |  428 | 写入缺少版本前提                                               |
| CORRUPT_STATE         |  500 | 服务存储文档损坏                                               |
| UNAVAILABLE           |  503 | 服务／存储不可用；客户端也用于读取失败或超时                   |
| UNKNOWN_OUTCOME       |  503 | 写入回执不明；客户端在发送后的超时、取消、响应丢失／无效时抛出 |

If-Match 版本不匹配使用 412，依据 [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-match)。身份从服务端会话解析，不从正文 scopeKey 或 owner 字段取得。测试服务器使用明确的假 bearer 会话；生产实现应替换身份提供方与存储实现，不应部署这些测试会话。

`ViewHost.instance.create(input, {requestId, signal?})` 要求每个逻辑创建保留同一个请求 ID。同一用户、同一键和同一规范化正文重放已存回执；正文变化返回 CONFLICT。实例与回执在同一个事务内提交。引擎在结果不明时保留 ID，阻止修改尚未确认请求的内容；原请求重试或显式重载可以核对已创建实例，不会把传输失败当成“肯定未写入”。直接使用客户端的调用者在重试、重建客户端后也必须保留原 ID。测试服务回执保留到管理重置为止。

个人排序采用完整替换，同一用户最后一次成功替换生效；可见 ID 集合必须仍然匹配，不修改其他用户顺序。实例写入使用 revision CAS，两者是明确不同的并发语义。

默认视图偏好仅属于当前认证用户。任一可见视图都可设为默认，无需编辑权限；`null` 表示不自动选择。该替换操作具有幂等性，写入结果未知时可用同一值重试，或重新加载确认结果。

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine build
pnpm storybook
# In another terminal:
node packages/view-engine/scripts/verify-http-view-host.mjs
# Manual Storybook service:
node packages/view-engine/scripts/verify-http-view-host.mjs --serve
```

夹具仅允许 `VIEW_ENGINE_E2E_BASE_URL` 的来源，默认 `http://127.0.0.1:6006`。使用其他 Storybook 地址（包括 `http://localhost:6006`）时需设置该变量；其他浏览器来源会在预检或写入前被拒绝。

`DELETE /instances/{id}` 的成功 envelope 包含 `ViewDeleteResult`：`{ defaultInstance: ViewInstance | null }`。回执来自删除事务，重复删除也返回当前用户的权威默认项；客户端不能丢弃该响应体。
