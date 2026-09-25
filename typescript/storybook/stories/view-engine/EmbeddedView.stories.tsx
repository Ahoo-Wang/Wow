/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useRef, useState, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type {
  ViewNavigation,
  DataViewDefinition,
  FilterTree,
  ViewEngine,
} from '@ahoo-wang/wow-view-engine';
import {
  EmbeddedView,
  useViewExpansion,
  type EmbeddedViewProps,
} from '@ahoo-wang/wow-view-engine/ui';
// View Engine's own primitives, so the mock host page is composed rather than
// hand-styled. They paint inside either of the theme's two style boundaries,
// and the page below takes the one that is not a surface — see `HostPage`.
import { Badge } from '@/ui/components/badge';
import { Button } from '@/ui/components/button';
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/ui/components/card';
import { Separator } from '@/ui/components/separator';
import { ToggleGroup, ToggleGroupItem } from '@/ui/components/toggle-group';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  ordersDefinition,
  overviewDefinition,
  savedViews,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 一个已经存好的视图，嵌在宿主自己的业务页面里——只有结果，没有别的。
 *
 * 工作台存在是为了让人**改变**自己怎么观察；`EmbeddedView` 存在是为了让一张
 * 业务页面**摆出**别人早就定好的那份观察。所以它没有视图列表、没有条件编辑
 * 器、没有保存，也**没有自己的「铺满屏幕」按钮**——一个悬在别人订单页上的按
 * 钮是那张页面没要过、也摆不进自己版面里的 chrome。它欠宿主的只是手段：
 * `ref` 交出自己那块 `.fve-root`，宿主把按钮放进**自己的** chrome 里，用
 * `useViewExpansion` 指向它。
 *
 * 所以每一条故事都套在一张假的客户详情页里，连面包屑、侧栏事实表和页脚一起
 * 画出来，这张页面又放在宿主应用（`AppShell`）的页面区里：视图铺满屏幕时盖
 * 住的是这些东西——连宿主的顶栏与导航一起——不套宿主就看不出「盖住」这件事。
 */

/** 左栏那几条事实，纯静态——它们在这里是为了被铺满屏幕的视图盖住。 */
const CUSTOMER_FACTS: [string, string][] = [
  ['客户编号', 'C-8812'],
  ['联系人', '周涛'],
  ['联系电话', '138 2025 0137'],
  ['信用额度', '¥ 200,000'],
  ['结算方式', '月结 30 天'],
];

/**
 * 同一份订单，外加一个搜索字段——搜订单号与备注。只有嵌入视图的故事用它：别
 * 的故事正数着筛选面板里有几个字段。
 */
const searchableOrders: DataViewDefinition = {
  ...ordersDefinition,
  fields: [
    ...ordersDefinition.fields,
    {
      name: 'q',
      label: '搜索订单',
      kind: 'search',
      searchFields: ['id', 'note'],
    },
  ],
};

/** 宿主替这张页面挑的范围。 */
export type ScopeChoice = 'none' | 'east' | 'unknown';

const SCOPE_LABELS: Record<ScopeChoice, string> = {
  none: '不收窄',
  east: '只看华东仓',
  unknown: '按客户收窄',
};

const SCOPE_ORDER: ScopeChoice[] = ['none', 'east', 'unknown'];

/**
 * An embed on a card paints the card's colour: the root paints
 * `--background`, which is the card's in light and a step darker in dark,
 * so an embed left alone sat in its card as a darker block. The host says
 * what it placed the embed on, through the tokens (the theme is the CSS
 * variables, and nothing else) — the colour, not `transparent`: the rows,
 * a pinned column and the hover mix are drawn in `--background`, and a
 * pinned column that is transparent shows the column scrolling under it.
 */
const ON_CARD = {
  '--fve-background': 'var(--card)',
  '--fve-dark-background': 'var(--card)',
} as CSSProperties;

interface HostPageProps {
  engine: ViewEngine;
  instanceId: string;
  scopeFilter?: FilterTree | null;
  /** 卡片标题下那一句，说清这一屏在看什么。 */
  caption: string;
  /** 给了就画一组范围开关——这是宿主自己的控件，不是视图的。 */
  scopeChoice?: ScopeChoice;
  onScope?(choice: ScopeChoice): void;
  /** 宿主替这一块打开的开关与交互档位（D22）。 */
  embed?: Partial<EmbeddedViewProps>;
}

/**
 * 宿主的业务页面，外加它自己的那颗「全屏查看」。
 *
 * 按钮画在卡片头上——宿主自己的 chrome 里——而 `ref` 指向嵌进去的那块
 * `.fve-root`。面一铺开，这颗按钮就**在面的下面**：`ViewSurface` 于是把自己
 * 那颗 `view-exit` 亮出来，Esc 也还管用。
 *
 * **这张页面为什么戴着 `fve-tokens`**：本包的样式表每一条规则都被
 * `scope-utilities.mjs` 钉在两个边界里（Storybook 跑的是同一个插件），所以
 * `Card`、`Button`、`Separator` 这些 vendored 组件——连 `grid`、`gap-4` 这样的
 * 排版 utility——**只有在一个边界里面才画得出来**。宿主要用本包的原语搭自己的
 * chrome，戴的就是 `fve-tokens`：它只给 token 与 utility 作用域，不是 surface
 * ——不钉 `data-theme`、也不涂底（底色是这张假页面自己用 `bg-background`
 * 涂的，真宿主当然用自己的），跟着祖先上的 `.dark` 走（D17-10）。
 *
 * 于是这里没有两层 `.fve-root`：面不嵌套，嵌进去的 `EmbeddedView` 是这一屏上
 * 唯一的那块根，哪怕它把自己钉成相反的模式也照样成立——`dark:` 工具类在边界
 * 里把面连同面里的一切原样交还给面自己。
 */
function HostPage({
  engine,
  instanceId,
  scopeFilter,
  caption,
  scopeChoice,
  onScope,
  embed,
}: HostPageProps) {
  const surface = useRef<HTMLDivElement>(null);
  // Where the host's route last went: 在工作台中打开 and a group's
  // follow-ups come here, and the page says so under the card.
  const [route, setRoute] = useState<ViewNavigation | null>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const expansion = useViewExpansion(surface, toggle);
  return (
    <div
      data-host-page
      // The page itself, not a card: the host's page area around it gives
      // the gutter, as a host's content region does.
      className="fve-tokens bg-background text-foreground flex min-h-0 flex-col gap-4"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="text-muted-foreground text-xs">
            订单中心 / 客户 / 明远商贸
          </p>
          <h3 className="truncate text-base font-semibold">
            明远商贸 · 客户详情
          </h3>
        </div>
        <Button size="sm" onClick={() => alert('新建工单')}>
          新建工单
        </Button>
      </div>
      <Separator />
      <div className="grid min-w-0 gap-4 md:grid-cols-[minmax(180px,220px)_minmax(0,1fr)]">
        <Card>
          <CardHeader>
            <CardTitle>客户资料</CardTitle>
            <CardDescription>宿主自己的那一栏。</CardDescription>
            <CardAction>
              <Badge variant="secondary">月结</Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {CUSTOMER_FACTS.map(([label, value]) => (
              <div key={label} className="flex flex-col gap-0.5">
                <span className="text-muted-foreground text-xs">{label}</span>
                <span className="text-sm">{value}</span>
              </div>
            ))}
          </CardContent>
        </Card>
        <Card className="min-w-0" style={ON_CARD}>
          <CardHeader>
            <CardTitle>最近运单</CardTitle>
            <CardDescription>{caption}</CardDescription>
            <CardAction>
              <Button
                ref={toggle}
                variant="outline"
                size="sm"
                aria-expanded={expansion.expanded}
                onClick={expansion.toggle}
              >
                {expansion.expanded ? '退出全屏' : '全屏查看'}
              </Button>
            </CardAction>
          </CardHeader>
          <CardContent className="flex min-w-0 flex-col gap-3">
            {onScope && (
              <ToggleGroup
                value={[scopeChoice ?? 'none']}
                onValueChange={value => {
                  const next = value[0];
                  if (SCOPE_ORDER.includes(next as ScopeChoice))
                    onScope(next as ScopeChoice);
                }}
                variant="outline"
                size="sm"
                aria-label="页面范围"
                className="flex-wrap"
              >
                {SCOPE_ORDER.map(choice => (
                  <ToggleGroupItem key={choice} value={choice}>
                    {SCOPE_LABELS[choice]}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            )}
            <EmbeddedView
              ref={surface}
              className="host-embed"
              engine={engine}
              instanceId={instanceId}
              scopeFilter={scopeFilter}
              messages={HOST_LANGUAGE.messages}
              locale={HOST_LANGUAGE.locale}
              onNavigate={setRoute}
              {...embed}
            />
          </CardContent>
        </Card>
      </div>
      <Separator />
      <p className="text-muted-foreground text-xs">
        数据来自运单中心 · 每 5 分钟同步一次
      </p>
      {route && (
        <p
          data-host-route
          className="text-muted-foreground font-mono text-xs break-all"
        >
          宿主路由：
          {/* The page's narrowing goes as the scope (D26 Q30): locked in
              the workbench as it is here. */}
          {route.kind === 'view'
            ? `打开视图 ${route.instanceId} · 作用域 ${JSON.stringify(route.scopeFilter)}`
            : route.kind === 'unsaved'
              ? `打开「${route.title}」 · 作用域 ${JSON.stringify(route.scopeFilter)} · 条件 ${JSON.stringify(route.config.filter)}`
              : route.kind === 'dashboard'
                ? `打开仪表盘 ${route.instanceId}`
                : route.url}
        </p>
      )}
    </div>
  );
}

/** 宿主把视图收到「只看华东仓」，用的是视图自己的字段名。 */
const EAST_ONLY: FilterTree = {
  op: 'and',
  children: [{ field: 'warehouse', operator: 'IN', value: ['CN-EAST'] }],
};

/** 宿主想按一个这份定义根本没有的字段收窄——于是收窄被拒。 */
const UNKNOWN_SCOPE: FilterTree = {
  op: 'and',
  children: [{ field: 'customerId', operator: 'EQ', value: 'C-8812' }],
};

const SCOPE_FILTERS: Record<ScopeChoice, FilterTree | null> = {
  none: null,
  east: EAST_ONLY,
  unknown: UNKNOWN_SCOPE,
};

function EmbeddedViewDemo({
  behaviour = 'data',
  instanceId,
  scope = 'none',
  scopePicker = false,
  caption = '这份共享视图筛的是待出库的单，按金额倒序。',
  embed,
}: {
  behaviour?: SourceBehaviour;
  instanceId?: string;
  /** 宿主替这张页面挑的范围，也是带选择器时的起点。 */
  scope?: ScopeChoice;
  /** 把范围做成宿主自己的一排按钮，可以当场换。 */
  scopePicker?: boolean;
  caption?: string;
  embed?: Partial<EmbeddedViewProps>;
}) {
  const [picked, setPicked] = useState<ScopeChoice>(scope);
  const choice = scopePicker ? picked : scope;
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          behaviour,
          definitions: [searchableOrders, overviewDefinition],
        })
      }
    >
      {engine => (
        <HostPage
          engine={engine}
          instanceId={instanceId ?? savedViews[0].id}
          scopeFilter={SCOPE_FILTERS[choice]}
          caption={caption}
          embed={embed}
          {...(scopePicker ? { scopeChoice: picked, onScope: setPicked } : {})}
        />
      )}
    </StoryEngine>
  );
}

/**
 * What the scenes answer from, said in the host's service line and below.
 * The customer page is not a service, so the line names only the data.
 */
const FIXTURE = '内存 ViewStore · 六条订单';

/**
 * What the scene is, on the docs page rather than above the page: the embed
 * is a customer page in the host application (`AppShell`), and the page has
 * the host's page area to itself.
 */
const description = `**数据视图 · 嵌入视图**

别人定好的一份观察，摆进一张业务页面里：只有结果。

- **数据源**：${FIXTURE}，嵌在一张假的客户详情页里。
- **准备**：每次挂载都新建引擎与存储；铺满屏幕的按钮由宿主画在自己的卡片头上。
- **操作**：打开任一场景。客户详情页放在宿主应用的页面区里——顶部导航与左侧应用导航是宿主的，页面也是宿主的，只有「最近运单」卡片里那一块是视图引擎——嵌入本来就是这样。
- **观察**：没有标题栏、没有工具栏、没有保存——变的只有结果和它自己的说明。`;

const meta = {
  parameters: {
    // The host's page fills its page area, as it would a screen.
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="embedded" service={{ fixture: FIXTURE }} padded>
        <Story />
      </AppShell>
    ),
  ],
  title: 'View Engine/数据视图/EmbeddedView',
  component: EmbeddedViewDemo,
  args: { behaviour: 'data' },
  argTypes: {
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'empty', 'slow', 'failing', 'no-aggregate'],
    },
    instanceId: { table: { disable: true } },
    scope: { table: { disable: true } },
    scopePicker: { table: { disable: true } },
    caption: { table: { disable: true } },
    embed: { table: { disable: true } },
  },
} satisfies Meta<typeof EmbeddedViewDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 默认：结果，以及结果是在什么条件下取来的。
 *
 * 屏幕上只有三样东西——已应用条件、表格、汇总行。条件条是**只读**的：没有 ✕，
 * 因为这里没有编辑器，而视图自己的条件是它作者存下来的；一个能撤条件的 ✕ 会
 * 让读者把「这个客户的单」悄悄变成「所有人的单」。
 *
 * 没有「铺满屏幕」按钮——那颗在宿主的卡片头上，写着「全屏查看」。
 */
export const Default: Story = { args: { behaviour: 'data' } };

/**
 * 宿主替这张页面把视图再收窄一档：`scopeFilter` 只留华东仓。
 *
 * 收窄和配置一起进引擎（`useOpenView` 把它交给 `engine.open`），所以**第一次
 * 查询就已经是收窄过的**；它按用户自己的条件那一套准入，宿主因此没法把一个视
 * 图放宽到它的定义不允许的范围，也永远不会写进保存的配置里。已应用条上读得出
 * 视图自己的条件加上这一条。
 */
export const ScopedByHost: Story = {
  args: { scope: 'east', caption: '宿主又收了一档：只看华东仓。' },
};

/**
 * 收窄被**拒**：宿主中途把范围换成一个这份定义没有的字段。
 *
 * 卡片头下面那一排是**宿主自己的**范围按钮（视图没有这种东西）。从「只看华东
 * 仓」换到「按客户收窄」：这个字段这份定义没有，收窄于是不被接受。
 *
 * 这是这一屏唯一不能默不作声的结局。被拒的收窄留下的是**更宽**的那个结果——
 * 页面要的是这一个客户的单，不说的话它就会安安静静地把所有人的单列出来。所以
 * 上面是一条 destructive 的告警，说清是哪一条没被接受，而行还照着上一次被接
 * 受的范围跑着。
 *
 * 手动可以在三档之间来回切：换回「只看华东仓」，告警消失，结果跟着收回去。
 */
export const ScopeRefused: Story = {
  args: {
    scope: 'east',
    scopePicker: true,
    caption: '按一下「按客户收窄」——这份定义没有 customerId。',
  },
};

/**
 * 同一件事发生在**第一次打开**的时候，说的是同一句话（D17-5）。
 *
 * 视图一开就带着一个不被接受的 `scopeFilter`。被拒的是**页面加上去的**那个条
 * 件，不是视图——视图好好的，宿主也改不了别人存的配置——所以这里说的是「页面
 * 的作用域条件对这个视图不适用」，和上面那条（先跑起来再换范围）一字不差。
 *
 * 结局也一样：那个条件根本没进准入，视图照它作者存的样子跑，**没收窄的那份结
 * 果留在屏幕上**，告警压在上面。页面要的范围没生效，不等于连宽的那份也不给。
 */
export const ScopeRefusedOnOpen: Story = {
  name: '收窄被拒（一开就拒）',
  args: {
    scope: 'unknown',
    caption: '一打开就带着一个这份定义不认的收窄条件。',
  },
};

/** 查询成功了，一条也没匹配上——这不是错误，所以画的也不是错误。 */
export const EmptyResult: Story = { args: { behaviour: 'empty' } };

/**
 * 查询失败：视图和它的条件都还在，没有的只是数据。
 *
 * 这里**没有「重试」**——嵌入的视图没有工具栏，一颗孤零零的按钮会让整块看起来
 * 像是一个控件；它按自己的节奏重跑。
 */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };

/** 第一次执行还没回来的样子：一块骨架，没有别的。 */
export const Loading: Story = { args: { behaviour: 'slow' } };

/**
 * 铺满屏幕，而按钮是**宿主的**。
 *
 * 按卡片头上的「全屏查看」：这块 `.fve-root` 就地撑满整页——不进 portal、什么
 * 都不重新挂载——把面包屑、左边那张事实表、页脚全盖住。`data-view-expanded`
 * 就在这块 surface 上。
 *
 * 一铺开，宿主那颗按钮自己就在面的**下面**了。所以 `ViewSurface` 会把一直藏
 * 着的那颗 `view-exit` 亮出来：桌面用户也许会猜 Esc，触屏根本没有 Esc 键，没
 * 有这颗按钮就真的出不来。收起时焦点回到宿主那颗按钮上，页面的滚动连
 * `!important` 一起原样还回去。
 */
export const FillTheScreen: Story = {
  args: { caption: '按右上角「全屏查看」——那颗按钮是宿主画的，不是视图的。' },
};

/**
 * 同一个壳，嵌的是一个分析视图：一张图，外加它是在什么条件下算出来的。
 *
 * 明细还是汇总由 `EmbeddedView` 自己分，宿主只给了一个 `instanceId`；仪表盘
 * 按资源分开，是 `EmbeddedDashboard` 的。
 */
export const AnalysisEmbed: Story = {
  name: '嵌一个分析视图',
  args: {
    instanceId: savedViews[1].id,
    caption: '同一个壳，存的是「仓库金额分布」。',
  },
};

/**
 * 汇总查询失败，明细照常：合计退回本页口径。
 *
 * 结果自己说得出的那些 warning，在嵌入的视图上比在工作台里更要紧——这里没有编
 * 辑器、没有工具栏，一个写着「总计」的数字如果是本页合计，屏幕上没有第二处能
 * 纠正它。
 */
export const TotalCoversThisPageOnly: Story = {
  args: { behaviour: 'no-aggregate' },
};

/**
 * 可交互一档（D22）：读者可以按表头排序、翻页、搜索、导出，也可以「在工作台中
 * 打开」——经宿主的路由，带着页面的收窄。没有保存、没有条件编辑器：改的都是这一
 * 次看的样子。
 *
 * 默认一档是 `static`：上面那几条故事里表头按不动、没有分页。页面还开了 `expandable`：首行最后一颗「铺满屏幕」。两档都不存任何东西（D36）。
 */
export const Interactive: Story = {
  name: '可交互',
  args: {
    scope: 'east',
    caption: '可交互：排序、翻页、搜索、导出、铺满屏幕、在工作台中打开。',
    embed: {
      interaction: 'interactive',
      withSearch: true,
      withExport: true,
      expandable: true,
    },
  },
};

/**
 * 分析视图，可交互一档：表格｜图表切换，按一组弹出追问菜单，每一项经宿主的路由
 * 在工作台打开。
 */
export const AnalysisInteractive: Story = {
  name: '分析视图（可交互）',
  args: {
    instanceId: savedViews[1].id,
    caption: '可交互：表格｜图表切换、按一组追问。',
    embed: { interaction: 'interactive' },
  },
};
