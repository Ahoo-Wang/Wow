---
title: 视图引擎入门：从零接入一个业务对象
description: 用示例服务端的 Docker 镜像，把销售订单接进视图引擎：查询描述、defineView、引擎与资源、ViewHost、一个声明的操作。
---

# 视图引擎入门：从零接入一个业务对象

本页回答：**一个 React 应用从零开始，怎样把一个 Wow 业务对象接进视图引擎，得到能筛选、排序、保存视图、还能发命令的列表？**

下面用 Wow 示例服务端的销售订单（聚合 `sales-order`）走一遍：服务端跑在 Docker 里，应用是一个 Vite + React 项目。做完以后，`/orders` 是一整张订单列表——系统视图「待发货」与「全部订单」、筛选、列、排序、合计、另存为自己的视图，每行已付款的订单上有一个「发货」按钮。

```mermaid
flowchart LR
    Descriptor["查询描述<br>salesOrderDescriptor.json"] --> Definition["defineView<br>orders.ts"]
    Definition --> Engine["ViewEngine<br>engine.ts"]
    Source["wow-client 快照查询"] --> Engine
    Engine --> Host["ViewHost<br>App.tsx"]
    Actions["声明的操作<br>actions.ts"] --> Host
    Host --> Workbench["DataWorkbench"]
```

## 1. 前提

- Node.js **22.12** 或更高版本，TypeScript **6** 或更高版本，React **19.3** 或更高版本（视图引擎 `/react`、`/ui` 入口的 peer 范围，见[兼容性与版本](./compatibility.md#运行环境与-peer-依赖)）。
- 一个 Vite + React + TypeScript 项目。没有的话，用 Vite 的 `react-ts` 模板新建一个：`pnpm create vite orders-console --template react-ts`。
- Docker，用来运行示例服务端和它的 MongoDB。

## 2. 启动示例服务端

示例服务端的镜像随每个 Wow 版本发布（`ahoowang/wow-example-server`，GHCR 与阿里云上也有）。视图引擎发的是快照查询，快照要存在 MongoDB 里：镜像自带的配置把存储放在内存中，这时快照查询答 `QuerySchemaUnavailable`，所以下面用环境变量把事件与快照换到 MongoDB 上。

```bash
docker network create wow-example
docker run -d --name wow-example-mongo --network wow-example \
  -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=root \
  -e GLIBC_TUNABLES=glibc.pthread.rseq=1 \
  mongo:8.3.11
docker run -d --name wow-example-server --network wow-example -p 8080:8080 \
  -e SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration \
  -e 'SPRING_MONGODB_URI=mongodb://root:root@wow-example-mongo:27017/wow_example_db?authSource=admin' \
  -e WOW_EVENTSOURCING_STORE_STORAGE=mongo \
  -e WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo \
  ahoowang/wow-example-server:9.2.0
```

- `SPRING_AUTOCONFIGURE_EXCLUDE` 替换镜像配置里的整张排除清单：原清单连 MongoDB 的自动配置一起排除，这里只排除 Elasticsearch。
- `GLIBC_TUNABLES` 绕过 MongoDB 8 在部分 Linux 内核上启动即退出的问题（SERVER-121912）；Wow 的 CI 也这样启动它。

`curl http://localhost:8080/actuator/health/liveness` 答 `{"status":"UP"}` 时服务端就绪。再写入三张订单，前两张付清：

```bash
order() {
  curl -s http://localhost:8080/tenant/demo/owner/demo/sales-order \
    -H 'Content-Type: application/json' -H 'Command-Wait-Stage: SNAPSHOT' \
    -d "{\"items\":[{\"productId\":\"$1\",\"price\":10,\"quantity\":$2}],
         \"address\":{\"country\":\"China\",\"province\":\"Zhejiang\",
         \"city\":\"$3\",\"district\":\"$3\",\"detail\":\"No. 1\"},
         \"fromCart\":false}" \
    | sed -E 's/.*"aggregateId":"([^"]+)".*/\1/'
}
pay() {
  curl -s http://localhost:8080/tenant/demo/sales-order/$1/pay \
    -H 'Content-Type: application/json' -H 'Command-Wait-Stage: SNAPSHOT' \
    -d "{\"paymentId\":\"pay-$1\",\"amount\":$2}" > /dev/null
}
pay "$(order book 3 Hangzhou)" 30
pay "$(order pen 5 Ningbo)" 50
order cup 2 Wenzhou > /dev/null
```

订单写在租户 `demo` 下；示例给每件商品定价 10，别的价格会被拒绝。

## 3. 安装

Wow 包的次版本可能带有破坏性改动，所以先让 pnpm 用 `~` 范围保存它们，停在同一个次版本上（[版本范围](./compatibility.md#版本范围)）。在项目的 `pnpm-workspace.yaml` 里加上这一行，没有这个文件就新建一个：

```yaml
savePrefix: '~'
```

然后安装：

```bash
pnpm add @ahoo-wang/wow-view-engine @ahoo-wang/wow-client \
  @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator @ahoo-wang/fetcher-eventstream \
  react react-dom react-router
pnpm add -D vite @vitejs/plugin-react typescript @types/react @types/react-dom
```

三个 `fetcher` 包是 wow-client 的 peer；`react`、`react-dom` 是 `/react` 与 `/ui` 入口的 peer，`react-router` 只有 `/react-router` 入口要。Vite 模板已经装好的包，pnpm 只会把它们留在原处。

项目要能导入 JSON（第 4 步的查询描述），并读 Vite 为样式表导入声明的类型。CI 用下面这份 `tsconfig.json` 编译本页的代码，并检查上面的安装命令装齐了它导入的每个包：

<!-- typecheck: file=tsconfig.json -->

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2023", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "jsx": "react-jsx",
    "types": ["vite/client"],
    "resolveJsonModule": true,
    "strict": true,
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src"]
}
```

示例服务端不发 CORS 头，所以开发时让 Vite 把 `/api` 转给它，浏览器只和同源的 Vite 说话：

```ts
// vite.config.ts
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        rewrite: path => path.replace(/^\/api/, ''),
      },
    },
  },
});
```

## 4. 保存查询描述

服务端为每个聚合发布查询描述（`GET /sales-order/snapshot/schema`）：有哪些字段、各是什么类型、枚举有哪些值、每个字段能怎样筛选、排序与聚合。把它存进项目，和定义放在一起：

```bash
curl -o src/salesOrderDescriptor.json http://localhost:8080/sales-order/snapshot/schema
```

定义在模块加载时就从这份文件建好，测试也建同一份；运行时引擎还会再向服务端读一次，只提供这个部署当下接得住的能力。服务端升级后重新保存它。

::: details 文件里有什么（节选：下一步列出的五个字段）
<!-- typecheck: file=src/salesOrderDescriptor.json -->

```json
{
  "model": "SNAPSHOT",
  "version": "sha256:a30adffc8907fe76ad70e44161b1aaa2e5448d8db1717a22f25653f9dbed6ebc",
  "timeZone": "UTC",
  "record": {
    "identity": "aggregateId",
    "paging": ["LIST", "PAGED", "CURSOR"],
    "defaultScope": "ACTIVE",
    "rootOperators": ["ID", "IDS", "AGGREGATE_ID", "AGGREGATE_IDS", "TENANT_ID", "OWNER_ID", "SPACE_ID", "DELETION", "EXPRESSION"]
  },
  "limits": {
    "maxListSize": 1000,
    "defaultListSize": 100,
    "maxPageSize": 100,
    "maxPageWindow": 10000,
    "maxFilterNodes": 128,
    "maxFilterValues": 1000,
    "maxSortFields": 32,
    "aggregation": {
      "maxGroups": 32,
      "maxMetrics": 64,
      "maxElements": 5,
      "maxLimit": 1000,
      "maxExpressionDepth": 8,
      "maxExpressionNodes": 256
    }
  },
  "analysis": {
    "metrics": ["COUNT", "NUMERIC", "ANY", "DISTINCT_COUNT", "PERCENTILE", "DERIVED", "FIRST", "LAST"],
    "approximate": ["PERCENTILE"],
    "expressions": true,
    "having": {
      "metrics": ["COUNT", "NUMERIC", "DISTINCT_COUNT", "PERCENTILE", "DERIVED"]
    },
    "sort": {"groups": true, "metrics": true},
    "dense": true,
    "dateUnits": ["YEAR", "QUARTER", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE", "SECOND"],
    "dateParts": ["DAY_OF_WEEK", "DAY_OF_MONTH", "HOUR_OF_DAY", "MONTH_OF_YEAR"],
    "dateDiffUnits": ["SECOND", "MINUTE", "HOUR", "DAY"],
    "firstLastOrderBy": "eventTime"
  },
  "fields": [
    {
      "path": "aggregateId",
      "role": "AGGREGATE_ID",
      "types": ["STRING"],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "CONTAINS", "STARTS_WITH", "ENDS_WITH", "IN", "NOT_IN", "BETWEEN", "IS_EMPTY_STRING", "IS_NOT_EMPTY_STRING", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS"],
        "missingKey": true,
        "functions": [],
        "distinctCount": true,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "firstEventTime",
      "role": "FIRST_EVENT_TIME",
      "types": ["INTEGER"],
      "kind": "SCALAR",
      "nullable": false,
      "semantic": {"type": "TEMPORAL_EPOCH", "timeUnit": "MILLISECONDS"},
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "BETWEEN", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS", "TODAY", "BEFORE_TODAY", "TOMORROW", "THIS_WEEK", "NEXT_WEEK", "LAST_WEEK", "THIS_MONTH", "LAST_MONTH", "RECENT_DAYS", "EARLIER_DAYS", "YESTERDAY", "NEXT_MONTH", "LAST_YEAR", "THIS_YEAR", "NEXT_YEAR", "BEFORE_NOW", "AFTER_NOW"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS", "HISTOGRAM", "DATE_HISTOGRAM", "DATE_PART"],
        "missingKey": false,
        "functions": ["SUM", "AVG", "MIN", "MAX", "STDDEV", "VARIANCE"],
        "distinctCount": true,
        "percentile": true,
        "any": true,
        "firstLast": true,
        "expressionInput": true,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "state.address.city",
      "types": ["STRING"],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "CONTAINS", "STARTS_WITH", "ENDS_WITH", "IN", "NOT_IN", "BETWEEN", "IS_EMPTY_STRING", "IS_NOT_EMPTY_STRING", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS"],
        "missingKey": true,
        "functions": [],
        "distinctCount": true,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "state.status",
      "types": ["STRING"],
      "kind": "SCALAR",
      "nullable": false,
      "enum": [
        {"value": "CREATED"},
        {"value": "PAID"},
        {"value": "SHIPPED"},
        {"value": "RECEIVED"}
      ],
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "CONTAINS", "STARTS_WITH", "ENDS_WITH", "IN", "NOT_IN", "BETWEEN", "IS_EMPTY_STRING", "IS_NOT_EMPTY_STRING", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS"],
        "missingKey": true,
        "functions": [],
        "distinctCount": true,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "state.totalAmount",
      "types": ["DECIMAL"],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "BETWEEN", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS", "HISTOGRAM"],
        "missingKey": false,
        "functions": ["SUM", "AVG", "MIN", "MAX", "STDDEV", "VARIANCE"],
        "distinctCount": true,
        "percentile": true,
        "any": true,
        "firstLast": true,
        "expressionInput": true,
        "inMetricFilter": true
      },
      "aliases": []
    }
  ],
  "elements": [],
  "dynamic": [],
  "constraints": [
    {"type": "CURSOR_UNIQUE_SORT", "appended": "aggregateId"}
  ]
}
```
:::

## 5. 声明定义：`defineView`

定义说这份数据**能**怎样观察。事实——路径、类型、枚举值——从查询描述读；`defineView` 只写选择：列出哪些字段、按什么次序、叫什么、状态用什么语气，以及随定义发布的系统视图。没列出的字段不出现；列了描述里没有的路径或值，准入会指出来，而不是抛错。

<!-- typecheck: file=orders.ts -->

```ts
// src/orders.ts
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import { defineView, text } from '@ahoo-wang/wow-view-engine';
import descriptor from './salesOrderDescriptor.json';

export const ORDERS = 'sales-orders';

export const ordersDefinition = defineView(
  descriptor as unknown as QueryModelDescriptor,
  {
    id: ORDERS,
    // 引擎按这个键找数据源（第 6 步）。
    source: 'sales-order',
    // 文字写成键，由宿主的措辞表说出来（第 8 步）。
    title: text('orders.title'),
    // 只出现列出的字段，按这个次序。
    fields: {
      aggregateId: { label: text('orders.id'), cell: 'copyable' },
      'state.status': {
        label: text('orders.status'),
        cell: 'status',
        options: {
          CREATED: { label: text('orders.created'), tone: 'neutral' },
          PAID: { label: text('orders.paid'), tone: 'warning' },
          SHIPPED: { label: text('orders.shipped'), tone: 'success' },
          RECEIVED: { label: text('orders.received'), tone: 'success' },
        },
      },
      'state.address.city': text('orders.city'),
      'state.totalAmount': { label: text('orders.total'), summary: ['SUM'] },
      firstEventTime: text('orders.placedAt'),
    },
    // 第 7 步的「发货」读每一行的状态，列表里显示不显示它都一样：
    // 引擎只查询显示的列，行里要读的字段写在这里。
    record: { rowFields: ['state.status'] },
    // 系统视图：随定义部署，所有人只读，是读者另存自己视图的起点。
    views: [
      {
        id: 'to-ship',
        title: text('orders.toShip'),
        config: {
          kind: 'record',
          filter: {
            op: 'and',
            children: [
              { field: 'state.status', operator: 'IN', value: ['PAID'] },
            ],
          },
          filterMode: 'simple',
          refresh: { interval: null },
          sort: [{ field: 'firstEventTime', direction: 'ASC' }],
          pageSize: 20,
          summaries: [{ field: 'state.totalAmount', fn: 'SUM' }],
          layout: 'table',
          table: {
            columns: [
              { field: 'aggregateId' },
              { field: 'state.address.city' },
              { field: 'state.totalAmount' },
              { field: 'firstEventTime' },
            ],
          },
          card: { title: 'aggregateId', fields: ['state.totalAmount'] },
        },
      },
      {
        id: 'all',
        title: text('orders.all'),
        config: {
          kind: 'record',
          filter: { op: 'and', children: [] },
          filterMode: 'simple',
          refresh: { interval: null },
          sort: [{ field: 'firstEventTime', direction: 'DESC' }],
          pageSize: 20,
          summaries: [{ field: 'state.totalAmount', fn: 'SUM' }],
          layout: 'table',
          table: {
            columns: [
              { field: 'aggregateId' },
              { field: 'state.status' },
              { field: 'state.address.city' },
              { field: 'state.totalAmount' },
              { field: 'firstEventTime' },
            ],
          },
          card: { title: 'aggregateId', fields: ['state.status'] },
        },
      },
    ],
  },
);

/** 上面每个键的措辞，以及第 7 步操作的。多语言的宿主每种语言一张表。 */
export const ORDER_WORDS = {
  'orders.title': '销售订单',
  'orders.id': '订单号',
  'orders.status': '状态',
  'orders.created': '待付款',
  'orders.paid': '已付款',
  'orders.shipped': '已发货',
  'orders.received': '已签收',
  'orders.city': '城市',
  'orders.total': '金额',
  'orders.placedAt': '下单时间',
  'orders.toShip': '待发货',
  'orders.all': '全部订单',
  'orders.ship': '发货',
  'orders.shipTitle': '发出 {count} 张订单？',
  'orders.notPaid': '只有已付款的订单能发货',
};
```

## 6. 引擎与资源

引擎只发 Wow 的查询（分页、游标、聚合），所以 wow-client 的快照查询客户端原样就是数据源；`describe` 让引擎在第一次查询前读服务端当下的查询描述。**一个应用一个引擎**，在启动时建一次：每个**资源**把一份定义和它的数据源配成一对，所有页面共用查询缓存与描述。

<!-- typecheck: file=engine.ts -->

```ts
// src/engine.ts
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  QueryClientFactory,
  ResourceAttributionPathSpec,
} from '@ahoo-wang/wow-client';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { ordersDefinition } from './orders';

/** 第 2 步写入订单的租户。 */
export const TENANT = 'demo';

/** 示例服务端，经 Vite 的 `/api` 代理（第 3 步）。 */
export const fetcher = new Fetcher({ baseURL: '/api' });

function salesOrderSource(): ViewSource {
  const factory = new QueryClientFactory({
    aggregateName: 'sales-order',
    // 查询 `tenant/demo/sales-order/snapshot/...`：只看这个租户的订单。
    resourceAttribution: ResourceAttributionPathSpec.TENANT,
    urlParams: { path: { tenantId: TENANT } },
    fetcher,
  });
  const snapshots = factory.createSnapshotQueryClient();
  const descriptors = factory.createQueryDescriptorClient();
  return {
    paged: (query, attributes, abort) =>
      snapshots.paged(query, attributes, abort),
    cursor: (query, attributes, abort) =>
      snapshots.cursor(query, attributes, abort),
    aggregate: (query, attributes, abort) =>
      snapshots.aggregate(query, attributes, abort),
    describe: (previous, attributes, abort) =>
      descriptors.describeSnapshot(previous, attributes, abort),
  };
}

export const engine = new ViewEngine({
  resources: [{ definition: ordersDefinition, source: salesOrderSource() }],
  // 读者保存的视图放在这里；`MemoryViewStore` 刷新页面就忘了。
  store: new MemoryViewStore(),
});
```

`MemoryViewStore` 让本页不依赖存储。要让保存的视图留下来，换成 `@ahoo-wang/wow-view-store` 的 [`WowViewStore`](../../reference/typescript/wow-view-store/)，把视图存在 Wow 的视图存储服务端上——示例服务端已经内嵌了它。

## 7. 声明一个操作

记录上的命令是声明，不是画出来的：宿主说**做什么**——命令、何时可用、不可用时为什么、要不要先问；引擎负责**怎样做**——主操作是行里的按钮；多选时按钮写「发货 2/3 条」，并列出不能发的与理由；确认、进度、逐条的结果，跑完重读视图。

<!-- typecheck: file=actions.ts -->

```ts
// src/actions.ts
import {
  CommandClient,
  CommandStage,
  waitStrategy,
} from '@ahoo-wang/wow-client';
import { actions, text, type RecordRow } from '@ahoo-wang/wow-view-engine';
import { fetcher, TENANT } from './engine';

const commands = new CommandClient({
  fetcher,
  basePath: `tenant/${TENANT}/sales-order`,
});
// 快照写好才答：`run` 一结束引擎就重读视图，要读到发货后的状态。
const headers = waitStrategy({ stage: CommandStage.SNAPSHOT });

const paid = (row: RecordRow) =>
  (row.data.state as { status?: string } | undefined)?.status === 'PAID';

export const orderActions = actions([
  {
    id: 'ship',
    label: text('orders.ship'),
    // 行里的按钮；其余操作收在行的「⋯」菜单里。
    primary: true,
    // `true`，或者为什么不行：理由显示在停用的按钮上。
    available: row => (paid(row) ? true : text('orders.notPaid')),
    // 单张一按就发；多选时先数清楚再问。
    confirm: { title: text('orders.shipTitle'), ask: 'bulk' },
    // 被服务端拒绝时 reject，引擎把原因报在那张订单上。
    run: async row => {
      await commands.send({
        path: `${row.key}/package`,
        method: 'POST',
        headers,
        body: {},
      });
    },
  },
]);
```

发货是示例服务端的 `ShipOrder` 命令（`POST /tenant/{tenantId}/sales-order/{id}/package`）。用 wow-generator [生成的命令客户端](./quick-start.md)时，`run` 里调用它的方法，路由与请求体都有类型。

## 8. `ViewHost` 与页面

`ViewHost` 是宿主包在页面外面的唯一一层：数据（引擎）、路由、语言与措辞、主题，以及每个资源在这个宿主里做什么（`bind`：去哪个路由、带哪些操作）。工作台只要定义的 id。

<!-- typecheck: file=App.tsx -->

```tsx
// src/App.tsx
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
import { useReactRouter } from '@ahoo-wang/wow-view-engine/react-router';
import {
  bind,
  DataWorkbench,
  ViewHost,
  zhCN,
} from '@ahoo-wang/wow-view-engine/ui';
import { orderActions } from './actions';
import { engine } from './engine';
import { ORDER_WORDS, ORDERS } from './orders';

/** 引擎自己的中文措辞，加上定义的键。 */
const MESSAGES = { ...zhCN, ...ORDER_WORDS };

const BINDINGS = [
  bind(ORDERS, {
    // 去订单的每条路都经这个路由；打开的视图写在 `?view=`。
    route: view =>
      view === null ? '/orders' : `/orders?${new URLSearchParams({ view })}`,
    actions: orderActions,
  }),
];

export function App() {
  return (
    <ViewHost
      engine={engine}
      router={useReactRouter()}
      locale="zh-CN"
      messages={MESSAGES}
      bindings={BINDINGS}
      preset="porcelain"
    >
      <main style={{ height: '100vh' }}>
        <DataWorkbench definitionId={ORDERS} />
      </main>
    </ViewHost>
  );
}
```

<!-- typecheck: file=main.tsx -->

```tsx
// src/main.tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
```

- **路由**：`useReactRouter()` 要在 React Router 的路由组件里面调用。有了路由，引擎自己管地址：工作台打开的视图写在 `?view=`，记录详情跟着 `?id=`。用别的路由库时照 `ViewRouter` 的两个成员（`location` 与 `go`）自己写一个。
- **主题**：`preset="porcelain"` 用引擎的一套内置外观，它的样式表要导入；已有 shadcn 主题的宿主改写 `theme="host"` 并导入 `shadcn-bridge.css`。见[视图引擎的主题](./view-engine-theming.md)。
- **明暗**：`colorMode` 缺省 `system`，跟随系统，给 `<html>` 挂 `.dark`。
- **高度**：工作台填满它的容器，所以容器要有高度。

## 9. 运行

```bash
pnpm dev
```

打开 `http://localhost:5173/orders`：

- 打开的是第一个系统视图「待发货」，列着两张已付款的订单，表尾是金额合计。
- 按一行的「发货」：命令发出、快照写好后视图重读，那张订单离开「待发货」。再按一次另一张，或勾选几张后按「发货 N 条」，引擎先问。
- 切到「全部订单」，三张都在，第三张是「待付款」，它的「发货」按钮停用并说明原因。
- 加一个筛选（例如城市），标题会提醒还没保存；「另存为」存成你自己的视图，地址里的 `?view=` 随之改变。

## 完整的可运行版本

- Storybook 的[接入导览](/storybook/?path=/docs/view-engine-接入导览--docs)用同样的五步接一个订单对象，多了导航、多语言措辞、第二个操作与内存路由，页面下方就是它跑起来的样子。
- 它的源文件：[`ordersDefinition.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDefinition.ts)、[`wowSource.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/wowSource.ts)、[`ordersEngine.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersEngine.ts)、[`OrdersHost.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/OrdersHost.tsx)、[`orderActions.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/orderActions.ts)、[`wowCommands.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/wowCommands.ts)、[`OrdersPage.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/OrdersPage.tsx)，以及用 `/testing` 的 `admit` 与 `actionHarness` 核对它们的 [`integration.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/integration.test.ts)。
- 一个真实的宿主：补偿控制台的 [`src/views/`](https://github.com/Ahoo-Wang/Wow/tree/main/compensation/dashboard/src/views)。

## 下一步

| 接下来 | 阅读 |
|---|---|
| 视图引擎是什么、立足于哪些事实 | [视图引擎](./view-engine.md) |
| 换一套外观、用品牌色、接上 shadcn 主题 | [视图引擎的主题](./view-engine-theming.md) |
| 键盘、读屏与 WCAG 2.2 AA | [视图引擎的可访问性](./view-engine-accessibility.md) |
| 把保存的视图存在 Wow 服务端 | [wow-view-store 参考](../../reference/typescript/wow-view-store/) |
| 入口、持久化端口与扩展点 | [wow-view-engine 参考](../../reference/typescript/wow-view-engine/) |
