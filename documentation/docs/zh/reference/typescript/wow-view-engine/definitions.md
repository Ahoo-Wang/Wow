---
title: '定义与字段类型'
description: 'defineView 与它的规格、text 键、FieldKind 与字段类型登记表——@ahoo-wang/wow-view-engine'
---

# 定义与字段类型

定义是代码：它随应用发布，说清一个业务对象能被怎样观察——哪些字段、各是什么类型、能怎样筛选、排序和汇总，以及随定义一起发布的系统视图。用户保存的每个视图都按它校验。`defineView` 从来源的查询描述构建一份数据定义：描述给出事实，规格在其中挑选、命名、收窄。

相关指南：[写好一份定义](../../../guide/typescript/view-engine-definitions.md)（从描述符出发逐项选择、措辞、系统视图与看板）、[视图引擎的核心概念](../../../guide/typescript/view-engine-concepts.md)（定义在模型里的位置）、[视图引擎入门](../../../guide/typescript/view-engine-getting-started.md)（第 5 步的一份完整定义）。

## defineView {#api-defineView}

`descriptor` 是与定义一起提交的查询描述快照（`GET /<aggregate>/snapshot/schema` 的回答）：模块加载时定义就建好，测试建出来的也是同一份。运行时来源答出的描述还会再收窄它一次，和任何定义一样。

- **事实归描述**：路径、类型、取值、敏感性、数组的条目。没列出的字段不出现。描述里没有的路径或取值是准入报告的错误（`DataViewDefinition.described`），从不抛出：一处写错的定义照样加载，并说出错在哪里。
- **能力不写死**：宿主不收窄的地方，定义取来源授予的全部；收窄的地方，取它的子集。超出快照的能力要求是警告（`definition.field.sort-wider` 等）——路径按什么排序与聚合是存储说了算，换个存储可能就授予了。
- 结果是一份普通定义：之后没有任何东西知道它是怎么写的，完全手写的定义也照样是定义。

```ts
export declare function defineView(descriptor: QueryModelDescriptor, spec: DefineViewSpec, options?: DefineViewOptions): DataViewDefinition;
```

<!-- typecheck-context
declare const ordersDescriptor: import('@ahoo-wang/wow-client').QueryModelDescriptor
-->

```ts
import { defineView, text } from '@ahoo-wang/wow-view-engine';

export const ordersDefinition = defineView(ordersDescriptor, {
  id: 'orders',
  // 宿主解析这份定义的来源时用的键（ViewEngine 的 resources）。
  source: 'order',
  title: text('orders.title'),
  timeField: 'firstEventTime',
  // 只出现列出的字段，按列出的顺序；字段是什么、能排序筛选聚合什么，由描述说。
  fields: {
    aggregateId: { label: text('orders.id'), cell: 'copyable' },
    'state.status': {
      label: text('orders.status'),
      cell: 'status',
      options: {
        PAID: { label: text('orders.paid'), tone: 'warning' },
        SHIPPED: { label: text('orders.shipped'), tone: 'success' },
      },
    },
    'state.amount': { label: text('orders.amount'), summary: ['SUM', 'AVG'] },
    firstEventTime: text('orders.placedAt'),
  },
  // 操作要读的字段即使视图不显示也要取回，见下面的 rowFields。
  record: { layouts: ['table', 'card'], rowFields: ['state.status'] },
});
```

### DefineViewSpec {#api-DefineViewSpec}

| 成员 | 作用 |
|---|---|
| `id`、`title`、`recordNoun` | 定义的标识与标题；标题可以是 `text(key)` |
| `source` | 宿主解析这份定义的数据来源时用的键 |
| `fields` | 读者看到的字段，按根路径，按列出的顺序。描述里有而这里没列的不出现。值写成字符串就只是标签 |
| `fieldGroups` | 字段选择器列出字段时用的分组，按此顺序 |
| `record` | 记录能力：行键与分页缺省取描述的（它的标识；能分页处分页，否则用游标），以表格显示。`false` 不提供记录 |
| `analysis` | 分析能力，按描述提供的；`false` 不提供 |
| `timeField` | 看板的时间筛选通过它到达面板；必须是 `fields` 里列出的日期字段 |
| `views` | 随定义发布的系统视图：人人可见、无人可覆盖、谁都能另存一份 |

```ts
export interface DefineViewSpec {
  analysis?: AnalysisSpec | false;
  fieldGroups?: FieldGroupDefinition[];
  fields: Readonly<Record<string, FieldSpec | string>>;
  id: string;
  record?: Partial<RecordCapability> | false;
  recordNoun?: string;
  source: string;
  timeField?: string;
  title: string;
  views?: SystemView[];
}
```

### FieldSpec {#api-FieldSpec}

一个字段：宿主在它的路径之上说了什么。

| 成员 | 作用 |
|---|---|
| `label` | 读者认识它的那个词。不写时取描述里的说明，再没有就是路径——准入会说出来（`definition.field.unlabelled`，一条 note） |
| `kind` | 描述留有选择余地时的类型：可复制的引用 id、当作类别读的字符串。不写时由描述的值类型决定 |
| `cell` | 单元格的读法：`string`、`number`、`boolean`、`date`、`datetime`、`enum`、`status`、`tags`、`link`、`text`、`copyable` |
| `operators` | 提供的比较，必须是该路径比较的子集，超出是警告 `definition.field.operator-wider` |
| `sortable` | `false` 不在能排序的路径上提供排序；`true` 而路径不能排序是警告 `definition.field.sort-wider` |
| `summary` | 表尾提供的汇总，在路径能喂的函数之内，超出是警告 `definition.field.summary-wider` |
| `options` | 类别的取值，按列出的顺序，各带措辞与色调，`false` 隐藏一个。描述不给取值的路径上，这就是宿主自己的封闭列表。数字取值的类别写成 `[值, 措辞]` 列表，因为对象的整数键会被 JavaScript 排到前面 |
| `elements`、`elementTitle` | 数组的条目：它们的字段按条目内的路径写，路径必须是描述列出的元素 |
| `search` | 一个搜索框而不是一条路径：键只是个把手，`fields` 是它搜的路径 |
| `analysis` | 它怎样参与分析，收窄后的；`false` 不参与 |
| `deprecated` | 描述把它标成废弃时仍保留它的原因 |
| `timePrecision` | 表格单元格写到多细：`'second'` 用在秒才是重点的地方（事件流的时间）；缺省到分钟 |
| `more` | 手写字段时字段还能说的其他东西 |

```ts
export interface FieldSpec {
  analysis?: FieldAnalysisSpec | false;
  cell?: FieldCellId;
  deprecated?: { message?: string };
  elements?: Readonly<Record<string, FieldSpec | string>>;
  elementTitle?: string;
  kind?: FieldKindId;
  label?: string;
  more?: Partial<Pick<FieldDefinition, 'numberFormat' | 'numeric' | 'temporal' | 'remote' | 'stringComparison'>>;
  operators?: FilterOperatorName[];
  options?: Readonly<Record<string, OptionSpec>> | readonly (readonly [FieldOption['value'], OptionSpec])[];
  search?: { fields: string[]; mode?: SearchModeName };
  sortable?: boolean;
  summary?: SummaryFunction[];
  timePrecision?: TimePrecision;
}
```

### 行里取回哪些字段：rowFields {#api-RecordCapability}

一页记录只向来源要视图显示的字段（行键、可见列、卡片字段、排序字段），不要整份文档。宿主自己的代码要读、而视图不一定显示的字段——行操作的 `available` 判断、批量操作、自定义单元格——写进 `record.rowFields`，每个都必须是行里有的已声明字段，准入会检查。

这是接入时最常见的坑：一个读 `state.status` 判断能否发货的操作，在不显示状态列的视图里会一直禁用，直到定义写上 `rowFields: ['state.status']`。没有「有操作就取整份文档」这回事——整份文档正是一页失败执行曾经变成 808 KB 的原因。

```ts
export interface RecordCapability {
  defaults?: Partial<RecordViewConfig>;
  layouts: RecordLayout[];
  maxSortFields?: number;
  maxWindow?: number;
  paging: PagingMode;
  parallelArrays?: string[][];
  requiresFilter?: boolean;
  rowFields?: string[];
  rowKey: string;
}
```

### DefineViewOptions {#api-DefineViewOptions}

宿主登记的字段类型（与 `ViewEngineOptions.kinds` 相同）。定义在任何引擎之前就建好，所以宿主自定义类型的字段，只有类型在这里已知时才会写上快照的比较。不写时用内置类型。

```ts
export interface DefineViewOptions {
  kinds?: FieldKindRegistry;
}
```

### SystemView {#api-SystemView}

随定义发布的基线视图。`id` 在定义内唯一，且不含 `:`。`timeField` 是这个视图读记录时的时刻（不同于定义的 `timeField` 时）；`null` 表示整体读取的视图，看板的时间筛选够不到它。

```ts
export interface SystemView {
  config: ViewConfig;
  id: string;
  timeField?: string | null;
  title: string;
}
```

## text 与键 {#api-text}

定义写 `text('orders.title')`，而不是某种语言里的「订单」，这样一份定义服务所有语言，由宿主的措辞目录去说它。

- 键以字符串旅行：定义、系统视图与看板的每个标签位都是 `string`，键夹在两个任何措辞都不用的字符之间（私用区 U+E000 与 U+E001）。所以键也能出现在代码拼出的长字符串里，在那里同样被说出来。字面字符串仍是标签，给只有一种语言的宿主。
- **键在叶子处才说出**：定义、保存的配置、每个运行时的状态和快照都保持键；只有在显示或离开引擎的地方——渲染出的标签、图表选项、导出、可访问名称、标题——才按当时生效的 Provider 的措辞说出，没有 Provider 就用引擎构建时的（`ViewEngineOptions.text`）。所以一个引擎服务所有语言，换语言只是重画。
- 缺措辞的键读作键本身，并被报告（`definition.text.unknown`、`definition.text.fallback`，见 [Issue code](./issues)）。测试里用 [`admit`](./testing#api-admit) 连同措辞一起检查。

```ts
export declare function text(key: string): Text;
```

```ts
export type Text = string & {
  readonly __text: unique symbol;
};
```

## 字段类型 {#api-FieldKind}

`FieldKind` 是引擎的主要扩展点：一种字段类型的全部知识——支持的运算符、值的形状与校验、编译成 Wow 的 `FilterExpression`、编辑器描述。类型拥有它的值的形状，所以应用加一种类型时内核什么都不用学。

- `editor()` 返回的是**数据**，不是组件名：`EditorDescriptor.input` 是封闭联合，成员列在 `EDITOR_INPUTS` 里，`/ui` 的值编辑器正是对它分支。没有渲染器登记表，所以自定义类型从已有的输入里挑一个；要一个引擎没有的输入，`validateFilter` 以 `filter.kind.unknown-editor` 拒绝，未登记的类型是 `filter.kind.unregistered`，应用按钮被挡住。
- `emptyValue()` 是叶子的起始值，通常是「还没有」：选了字段却没填值是正常的编辑状态，不校验也不编译。拿 `0` 当数字的起始值会在行一出现时悄悄套上 `amount = 0`。
- `compile()` 把一个已准入的叶子映射到 Wow 协议；`compiledOperators()` 说明某运算符实际以哪些 Wow 运算符发送，描述必须全部允许它们，该运算符才会提供。
- `readLeaf()` 把字段还不是这个类型时存下的叶子读成这个类型提供的形状；每一遍过树都先经它读每片叶子。内置的 `enum` 把字段还是字符串时存下的 `EQ` 读成一个值的 `IN`，条件因此照常准入与编译。
- `scalar`、`singleString`、`fieldless`、`nested` 告诉引擎这个类型的值在 Wow 那边是什么形状，以免引擎认为可用的条件被服务端拒绝。

内置类型：`string`、`number`、`boolean`、`date`、`datetime`、`enum`、`reference`、`array`、`elementMatch`、`search`，以及 Wow 元数据筛选背后的 `documentId`、`aggregateId`、`tenantId`、`ownerId`、`spaceId`、`deletion`。

```ts
export interface FieldKind {
  compile(context: FieldKindCompileContext): FilterExpression;
  compiledOperators?(operator: FilterOperatorName): readonly FilterOperatorName[];
  defaultOperator: FilterOperatorName;
  describe(context: FieldKindDescribeContext): FieldKindDescription;
  editor(operator: FilterOperatorName, field: FieldDefinition, value?: unknown): EditorDescriptor;
  emptyValue(operator: FilterOperatorName, field: FieldDefinition): unknown;
  fieldless?: true;
  id: FieldKindId;
  isBlank?(context: FieldKindBlankContext): boolean;
  nested?(value: unknown, field: FieldDefinition, operator: FilterOperatorName): NestedTree | null;
  operators: FilterOperatorName[];
  readLeaf?(leaf: FilterLeaf, field: FieldDefinition): FilterLeaf;
  relations?: Partial<Record<FilterOperatorName, FilterSummaryRelation>>;
  scalar?: boolean;
  singleString?: boolean;
  validate(context: FieldKindValidateContext): Issue[];
}
```

### 登记表 {#api-FieldKindRegistry}

登记表是按 id 索引的只读 Map。`builtinFieldKinds` 是现成的登记表；`withFieldKinds` 在它之上添加或替换类型而不改动它，结果交给 `ViewEngineOptions.kinds`，也交给 `defineView` 的 `options.kinds`。

<!-- typecheck-context
declare const moneyKind: import('@ahoo-wang/wow-view-engine').FieldKind
-->

```ts
import { builtinFieldKinds, withFieldKinds } from '@ahoo-wang/wow-view-engine';

export const kinds = withFieldKinds(builtinFieldKinds, [moneyKind]);
```

```ts
export type FieldKindRegistry = ReadonlyMap<FieldKindId, FieldKind>;
export declare const builtinFieldKinds: FieldKindRegistry;
export declare const BUILTIN_FIELD_KINDS: readonly FieldKind[];
export declare function withFieldKinds(registry: FieldKindRegistry, kinds: readonly FieldKind[]): FieldKindRegistry;
export declare function createFieldKindRegistry(kinds: readonly FieldKind[]): FieldKindRegistry;
```

### 编辑器描述 {#api-EditorDescriptor}

```ts
export interface EditorDescriptor {
  input: EditorInput;
  multiple?: boolean;
  options?: FieldOption[];
  range?: boolean;
  remote?: string;
  withTime?: boolean;
}
export declare const EDITOR_INPUTS: readonly ['none', 'text', 'number', 'boolean', 'deletion', 'select', 'remote', 'date', 'dateRange', 'relativeDate', 'predicate', 'duration'];
```

## 完整可运行版本

- Storybook 的[接入导览](/storybook/?path=/docs/view-engine-接入导览--docs)从一份提交的查询描述声明订单定义。
- 源文件：[`ordersDefinition.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDefinition.ts) 与 [`ordersDescriptor.json`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDescriptor.json)；`defineView` 在 [`src/runtime/define/defineView.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/runtime/define/defineView.ts)，`FieldKind` 在 [`src/filter/fieldKind.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/filter/fieldKind.ts)。
