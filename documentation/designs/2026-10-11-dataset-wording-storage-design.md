# 数据集措辞覆盖的存储

日期：2026-10-11。状态：草稿，待用户确认。

## 1. 来由

view-engine D86 定下了措辞覆盖的形状与套用：`DatasetCatalog.overrides(entry)` 给出标题、名词与字段覆盖（`DefineDatasetSpec.fields`：每项是口径、`FieldSpec` 收窄或 `false` 隐藏），只能收窄与改词。**存在哪里**没有定，宿主今天只能写在代码或配置里，改一个字就是一次发版。数据集平台设计（[2026-10-11-dataset-platform-design.md](2026-10-11-dataset-platform-design.md)）说覆盖「存法与系统视图相同」，由管理员编辑。view-engine 的 todo 记着这一条：

> 判据：视图存储里存一份数据集的覆盖、由管理员编辑，宿主的 `overrides` 读它。

## 2. 范围

- **在内**：view-store 服务端存一份「数据集措辞」（W1）；`@ahoo-wang/wow-view-store` 读写它，并给宿主一个现成的 `overrides`（W2）；view-engine 里管理员编辑它的界面（W3）。
- **不在内**：覆盖的形状与套用（D86 已有，不改）；平台级（跨租户）的覆盖——今天没有需要，以后照系统视图的 `tenant/(platform)/owner/(system)` 再加；按 space 区分（与视图同理不隔离，view-store-backend.md 4.4）。

## 3. 服务端（W1）

### 3.1 聚合根：**DatasetWording**

与视图偏好（ViewPreferences）同一种做法：一个租户、一个应用下每个数据集一份，id 由服务端按「租户 × 应用 × 数据集」算出，不开 `spaced`。

```kotlin
data class DatasetWordingState(
    val id: String,          // 服务端按 租户 × 应用 × 数据集 算出
    val dataset: String,     // 目录里的名字，{context}.{dataset}
    val appId: String,       // 创建时的 CoSec-App-Id
    /** D86 的 DatasetOverrides：title、recordNoun、fields，整份 JSON，服务端不解释其语义。 */
    val wording: ObjectNode,
)
```

| 命令                                                         | 事件                     | 规则                                                                                                                                                                                         |
| ------------------------------------------------------------ | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **SetDatasetWording** `{dataset, wording}`（允许创建）        | **DatasetWordingSet**    | 期望版本（0 即从没写过）；`wording` 只做形状与大小的检查：是对象，只有 `title`（≤ 120 字）、`recordNoun`（≤ 40 字）、`fields`（对象）三个键，整份不超过 64 KB；应用与状态不符读作不存在 |
| **ClearDatasetWording**                                      | **DatasetWordingCleared** | 期望版本；删除聚合，之后读作没有覆盖                                                                                                                                                         |

服务端不校验字段路径：数据集删了列，覆盖照旧存着，引擎套用时忽略没有的路径（D86）。

### 3.2 路径

前缀是 `/view-store/tenant/{tenantId}/owner/(shared)`——措辞是整个租户的，所有者段固定为共享。

| 方法与路径                           | 说明                                                                                     |
| ------------------------------------ | ---------------------------------------------------------------------------------------- |
| `GET /dataset-wordings`              | 本租户、本应用（按请求的 `CoSec-App-Id` 过滤）所有数据集的措辞与版本，一次读完           |
| `PUT /datasets/{dataset}/wording`    | SetDatasetWording，带 `Command-Aggregate-Version` 与 `Command-Request-Id`               |
| `DELETE /datasets/{dataset}/wording` | ClearDatasetWording，同上                                                                |

**谁能写由 CoSec 决定**，与写共享视图分开：`PUT`／`DELETE …/wording` 要「管理数据集措辞」的角色；`GET` 与读视图同样放行。服务端照旧不做鉴权（view-store-backend.md 4.1）。

## 4. 客户端（W2）

`@ahoo-wang/wow-view-store` 加两件，不动 `ViewStore` 端口：

```ts
interface DatasetWordings {
  /** GET /dataset-wordings：每个数据集的覆盖与版本。 */
  list(): Promise<ReadonlyMap<string, { wording: DatasetOverrides; revision: string }>>;
  set(dataset: string, wording: DatasetOverrides, revision: string): Promise<string>;
  clear(dataset: string, revision: string): Promise<void>;
}
```

- `DatasetCatalog.overrides` 是同步的（`DatasetResources.refresh` 逐个登记时读）。所以宿主在 `refresh` 之前读一次 `list()`，`overrides` 从读到的表里取：

  ```ts
  const wordings = wowDatasetWordings(fetcher);
  const resources = new DatasetResources(engine, {
    list: async () => { await wordings.load(); return directory.list(); },
    source: entry => directory.source(entry),
    overrides: entry => wordings.of(entry.name),
  });
  ```

  `load()` 失败时（服务挂了、没有这个路由的旧服务端）读作没有覆盖、经 `onError` 报一次，数据集照常登记——措辞是锦上添花，不该让分析打不开。
- 覆盖变了，`DatasetResources` 已经会按 JSON 比较后重新登记（D86），所以编辑后再 `refresh` 一次即生效。

## 5. 编辑界面（W3）

管理员在哪里改？推荐放在**视图管理器**里：数据集定义（`defineDataset` 出来的）的管理器多一个「措辞」页签，列出描述符的每个字段——名字、当前口径、显示／隐藏、上下移动——外加标题与名词，保存即 `set`。只有宿主给了 `editDatasetWording` 许可（缺省 false，与 `editSystem` 同理）才出现。

W3 是这三批里最大的一块，可以晚于 W1、W2：在它之前，管理员可以直接调 `PUT` 或由宿主自己的后台写。

## 6. 批次

| 批 | 内容                                                                                                 | 验证                                                                                       |
| -- | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| W1 | 聚合、命令、路由、`GET /dataset-wordings`、OpenAPI；生成的客户端更新                                 | 领域单测（形状、大小、版本、应用隔离）；契约测试跑真服务端                                 |
| W2 | `@ahoo-wang/wow-view-store` 的 `DatasetWordings` 与 `load`／`of`；文档（指南 en/zh、host-integration 3.2） | 单测；集成测试：写一份措辞、`refresh` 后定义的标题与字段口径变了                           |
| W3 | 管理器的「措辞」页签、`editDatasetWording` 许可                                                      | 组件测试与浏览器故事                                                                       |

## 7. 待确认

1. 存在 view-store（推荐）还是数据集注册表（dataset-registry）？view-store 已有租户、应用、所有者的隔离和「共享」的写法，措辞又是给读者看的，与视图同类；注册表管的是数据集本身的发布。
2. 措辞按租户一份（推荐），还是也要平台级的一份给所有租户？
3. W3 放进视图管理器（推荐），还是由宿主自己的后台编辑、引擎不出界面？
