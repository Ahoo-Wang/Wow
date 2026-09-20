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
import type { Meta, StoryObj } from '@storybook/react-vite';
import { MemoryViewStore } from '@ahoo-wang/fetcher-view-engine';
import type { RecordActionSlots } from '@ahoo-wang/fetcher-view-engine/react';
import { RecordWorkbench, zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
// View Engine's own button, so the host's commands sit in its toolbar rather
// than beside it — exactly what an application does with the action slots.
import { Button } from '@/ui/components/button';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  createStoryEngine,
  recordConfig,
  savedViews,
  tableSettingsStore,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine, viewEngineScene } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The Record workbench in one state at a time. Every state below follows from
 * what the backend does or from what the saved config says, so a story sets
 * one of those two and changes nothing else.
 */
/**
 * Six orders two at a time, oldest first: the one scenario where the bar
 * under the rows has somewhere to go. The saved size is below the offered
 * ladder, so the control also shows a size folding into it.
 */
const pagedView = {
  ...savedViews[0],
  title: '逐页翻看',
  config: recordConfig({
    pageSize: 2,
    sort: [{ field: 'createdAt', direction: 'ASC' as const }],
  }),
};

/**
 * Every column the definition offers, with `金额` frozen beside the row key.
 *
 * The row key is pinned left by the projection whatever the config says, so a
 * view that only froze it would prove nothing about the config's own
 * `pinned` — and it is the config's pin that has to keep holding once the
 * view fills the screen and a different box is the tall one.
 */
const pinnedView = {
  ...savedViews[0],
  title: '冻结两列',
  config: recordConfig({
    table: {
      columns: [
        { field: 'id' as const, pinned: 'left' as const },
        { field: 'amount' as const, pinned: 'left' as const },
        { field: 'warehouse' as const },
        { field: 'status' as const },
        { field: 'createdAt' as const },
      ],
    },
  }),
};

/**
 * A view saved with an interval, so the refresh button opens already wearing
 * its credential — the thing a wall-mounted view exists for.
 */
const refreshingView = {
  ...savedViews[0],
  title: '每 30 秒自刷',
  config: recordConfig({ refresh: { interval: 30 } }),
};

/**
 * A view whose name is longer than any bar will ever be, for the one thing
 * in the title bar that is allowed to give way.
 */
const longTitledView = {
  ...savedViews[0],
  title: '全部订单 · 华东仓 · 待出库 · 按金额倒序 · 2026 年第三季度复核清单',
};

function RecordWorkbenchDemo({
  behaviour = 'data',
  instanceId,
  broken = false,
  paged = false,
  withActions = false,
  localized = false,
  keepStore = false,
  collapsed = false,
  transformedHost = false,
  scaledHost = false,
  raisedHost = false,
  pinnedColumn = false,
  refreshing = false,
  narrowHost = false,
}: {
  behaviour?: SourceBehaviour;
  instanceId?: string;
  /** Saves a config the definition no longer accepts, to show "needs fixing". */
  broken?: boolean;
  /** Saves a page size small enough that the result spans several pages. */
  paged?: boolean;
  /** Fills the three action slots, the way a business page would. */
  withActions?: boolean;
  /** Hands the workbench the shipped Chinese catalogue. */
  localized?: boolean;
  /** Publishes the store on `tableSettingsStore`, for a play to read. */
  keepStore?: boolean;
  /** Opens with the view list folded away, as a narrow page would. */
  collapsed?: boolean;
  /**
   * Puts the workbench inside a host container that owns its own containing
   * block, the way an animated panel or a GPU-hinted grid shell does.
   */
  transformedHost?: boolean;
  /**
   * The same, but with a host that *scales* rather than only moves. A pure
   * translate changes where a box is; a scale changes how big the browser
   * makes what we write, which is the other half of `transform`.
   */
  scaledHost?: boolean;
  /**
   * Stacks a layer of the host's own over the page, the way a scrim, a chat
   * widget or a drag ghost does: a positive `z-index`, and hit-testable.
   */
  raisedHost?: boolean;
  /**
   * Saves a config that freezes a column the projection would not freeze on
   * its own. The row key is pinned left whatever the config says, so it
   * proves nothing about `pinned` — this pins `金额` as well.
   */
  pinnedColumn?: boolean;
  /** Opens a view whose config already carries an auto-refresh interval. */
  refreshing?: boolean;
  /**
   * Opens a long-titled view in a column narrower than the title bar's own
   * controls, the way a phone or a split pane does. The width is on the host
   * rather than on the workbench, because that is where a host puts it.
   */
  narrowHost?: boolean;
}) {
  const workbench = (
    <StoryEngine
      create={() => {
        const store = keepStore
          ? new MemoryViewStore({ instances: savedViews })
          : undefined;
        if (store) tableSettingsStore.current = store;
        return createStoryEngine({
          behaviour,
          ...(store ? { store } : {}),
          instances: broken
            ? [
                {
                  ...savedViews[0],
                  title: '待修复视图',
                  config: recordConfig({
                    table: { columns: [{ field: 'removedColumn' }] },
                  }),
                },
              ]
            : paged
              ? [pagedView]
              : pinnedColumn
                ? [pinnedView]
                : refreshing
                  ? [refreshingView]
                  : narrowHost
                    ? [longTitledView]
                    : savedViews,
        });
      }}
    >
      {engine => (
        <RecordWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={instanceId ?? savedViews[0].id}
          actions={withActions ? businessActions : undefined}
          messages={localized ? zhCN : undefined}
          // A narrow column is a column with no room for a 224px sidebar
          // beside it, so it opens the way a phone does: the list folded
          // away and the whole width given to the view.
          defaultSidebarOpen={!collapsed && !narrowHost}
        />
      )}
    </StoryEngine>
  );
  // A column narrower than the title bar's own controls. The width is the
  // whole of the scenario, and the regression play walks it down by writing
  // to this element's `style.width`; `overflow: hidden` is the tell, since
  // anything the bar cannot fit into would otherwise be painted over the
  // page beside it rather than clipped where it can be measured.
  if (narrowHost)
    return (
      <div data-narrow-host style={{ width: 360, overflow: 'hidden' }}>
        {workbench}
      </div>
    );
  // `transform` makes this div the containing block for every `position:
  // fixed` inside it, so a surface that assumed the viewport would fill the
  // div instead. `data-transformed-host` is what the regression play looks
  // for; the style is the whole of the scenario.
  if (transformedHost)
    return (
      <div data-transformed-host style={{ transform: 'translateZ(0)' }}>
        {workbench}
      </div>
    );
  // A scaling host changes the *size* the browser makes of what we write, not
  // only where it lands: `getBoundingClientRect` already reports screen
  // pixels, while `--fve-expanded-*` are read in the element's own
  // coordinates. `translateZ(0)` above never exercised that half.
  if (scaledHost)
    return (
      <div
        data-transformed-host
        data-scaled-host
        style={{ transform: 'scale(0.75)', transformOrigin: 'top left' }}
      >
        {workbench}
      </div>
    );
  // Whatever a host stacks over its page — a scrim, a floating panel, a drag
  // ghost — is some element with a positive `z-index`, and every popup this
  // package opens has to come out in front of it. It is fixed to the viewport
  // so that a dialog, which is centred on the viewport rather than on this
  // box, is covered as well; and it is hit-testable on purpose, because the
  // regression asks the browser what is at the middle of each popup and a
  // layer the hit test walked straight through would answer nothing at all.
  if (raisedHost)
    return (
      <>
        {workbench}
        <div
          data-raised-host
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 10,
            display: 'grid',
            alignContent: 'end',
            justifyItems: 'center',
            padding: 16,
            background: 'rgb(23 37 84 / 24%)',
          }}
        >
          <span
            style={{
              borderRadius: 999,
              background: '#1d39c4',
              padding: '4px 12px',
              color: '#fff',
              font: '600 13px system-ui, sans-serif',
            }}
          >
            宿主抬到 z-index: 10 的一层
          </span>
        </div>
      </>
    );
  return workbench;
}

/**
 * What an application hangs on the workbench: one command over the view, one
 * over a selection, one per row. They are render functions rather than names
 * in a config, so they can do anything the page can do — and nothing about
 * them is saved with the view.
 */
const businessActions: RecordActionSlots = {
  global: () => (
    <Button size="sm" onClick={() => alert('新建订单')}>
      新建订单
    </Button>
  ),
  bulk: ({ rows, clearSelection }) => (
    <Button
      variant="outline"
      size="sm"
      onClick={() => {
        alert(`导出 ${rows.length} 单`);
        clearSelection();
      }}
    >
      导出所选
    </Button>
  ),
  row: ({ row, refresh }) => (
    <>
      <Button
        variant="ghost"
        size="xs"
        onClick={() => alert(`打开 ${row.key}`)}
      >
        打开
      </Button>
      <Button
        variant="ghost"
        size="xs"
        onClick={() => {
          alert(`取消 ${row.key}`);
          refresh();
        }}
      >
        取消
      </Button>
    </>
  ),
};

const scene = {
  ...viewEngineScene,
  domain: '数据视图',
  summary: '明细、汇总、筛选与保存，全部来自一份配置。',
  fixture: '内存 ViewStore · 六条订单 · 可切换的数据源行为',
  setup: '每次挂载都新建引擎与存储，场景之间不共享已保存的视图。',
  observe: '表格、汇总行与提示反映这一次执行的口径，而不是草稿。',
};

const meta = {
  decorators: [
    (Story, context) => (
      <ScenarioFrame title={context.name} {...scene}>
        <Story />
      </ScenarioFrame>
    ),
  ],
  title: 'View Engine/数据视图/Record 工作台',
  component: RecordWorkbenchDemo,
  args: { behaviour: 'data' },
  argTypes: {
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'empty', 'slow', 'failing', 'no-aggregate'],
    },
    broken: { table: { disable: true } },
    paged: { table: { disable: true } },
    instanceId: { table: { disable: true } },
    withActions: { table: { disable: true } },
    localized: { table: { disable: true } },
    keepStore: { table: { disable: true } },
    collapsed: { table: { disable: true } },
    transformedHost: { table: { disable: true } },
    refreshing: { table: { disable: true } },
    raisedHost: { table: { disable: true } },
    narrowHost: { table: { disable: true } },
  },
} satisfies Meta<typeof RecordWorkbenchDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Rows, the summary row and the saved conditions of a shared view. */
export const WithData: Story = { args: { behaviour: 'data' } };

/** A query that succeeded and matched nothing, which is not an error. */
export const EmptyResult: Story = { args: { behaviour: 'empty' } };

/** What the first execution looks like before the answer arrives. */
export const Loading: Story = { args: { behaviour: 'slow' } };

/** A failed query keeps the view and its conditions; only the data is gone. */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };

/**
 * 汇总查询失败，明细照常。合计因此退回本页合计——数字留着，因为本页合计本身
 * 有用——但行尾的口径标签改说「本页」，上方多一条 warning 说明为什么。默默
 * 顶替才是这里唯一的错误：读者看到「总计」，会当成全部命中记录的总计。
 */
export const TotalCoversThisPageOnly: Story = {
  args: { behaviour: 'no-aggregate' },
};

/** A saved config the definition outgrew: `apply` is refused until it is fixed. */
export const NeedsFixing: Story = { args: { broken: true } };

/** No saved view under this id, reported instead of an empty frame. */
export const CannotOpen: Story = { args: { instanceId: 'deleted' } };

/**
 * 结果不止一页时，表格下面那一行：左边说一共多少条，右边是每页几条、第几页，
 * 以及前后两步。翻页不写进配置，改每页条数则是一次编辑，会立刻应用。
 */
export const Paged: Story = { args: { paged: true } };

/**
 * The host's own commands in the three places they belong: over the view, over
 * a selection, and on one row. Pick rows to see the middle one appear.
 */
export const WithActions: Story = { args: { withActions: true } };

/**
 * Managing the list rather than looking at one view: rename, delete, reorder
 * and choose which view opens first. Open it from the gear beside the sidebar
 * heading — every button is there only where the store permits it.
 */
export const ManageViews: Story = { args: { behaviour: 'data' } };

/**
 * 中文文案。包里带了 `zhCN`，宿主把它交给 `messages` 就换掉整面的措辞；要改其
 * 中几句，铺开再覆盖：`{ ...zhCN, 'label.filter.apply': '确定' }`。
 *
 * 打开的是那个带条件的共享视图，所以结果上方的「正在显示」里就有一枚可操作的
 * 条件 badge：字段名来自定义，操作符与候选项标签分别来自目录与定义，按 ✕ 把它
 * 撤下会立刻重跑查询。展开筛选带还能看到相对日期的单位与时间段——`day`、
 * `thisWeek` 这些以前是原样的标识符，现在同样走目录。
 */
export const Localized: Story = { args: { localized: true } };

/**
 * 表格设置：工具栏右端的「列设置」与「排序」。
 *
 * 列设置里一行一列——拖动手柄、显隐、列名、汇总函数、固定开关。拖动只在同一区域
 * 内生效：主键「订单号」钉在左侧，宿主的操作列钉在右侧（这个故事没有行动作，所以
 * 右侧那一行不出现），中间几列随意排。手柄也可以用键盘：Tab 到手柄，方向键上下移
 * 一位，移完会播报落在第几位。旁边的排序按钮把当前排序读成话，点开可以逐条翻方
 * 向、删掉，或者添加一个还没用到的可排序字段。改完点「Save」，重开这个视图就是
 * 现在这副样子——列设置与排序改的都是视图本身，不是这一次打开。
 */
export const TableSettings: Story = { args: { keepStore: true } };

/**
 * 侧栏收起后的样子：标题栏最左边是展开按钮、定义标题与视图切换下拉，结果拿回
 * 侧栏占掉的那点宽度。下拉按受众分组、当前项打勾、系统视图带标签，末尾是「管理
 * 视图」——和侧栏齿轮开的是同一个对话框。切换照样先过离开守卫。
 */
export const CollapsedSidebar: Story = { args: { collapsed: true } };

/**
 * 自动刷新：工具栏「数据新鲜度」那一组里，刷新按钮右边多了一个 `▾`。
 *
 * 主键还是原来那一下——点一次，跑一次。`▾` 里是间隔：关闭，以及一梯档位。
 * 档位按 `runtime.limits` 的 `minRefreshInterval`／`maxRefreshInterval` 裁
 * 剪，**限制不允许的档位根本不出现**，而不是灰着让人点一下才知道不行（D4）。
 *
 * 选中即改视图自己的 `refresh.interval`——和排序、列设置一样，是一次 `edit`
 * 加 `apply`，因此标题旁立刻出现「已修改」，`Save` 才会把它存下来。开着的时
 * 候按钮上带着那一处凭据（「30s」），说的是「这个视图在自己刷新」，与三态凭
 * 据不是一回事。
 *
 * 什么时候停表由运行时说了算，界面不另立规矩：草稿有 error、编辑器正被输入、
 * 页面不可见、上一次请求还在途——四种情况下计时器暂停（见 runtime.md）。
 */
export const AutoRefresh: Story = { args: { refreshing: true } };

/**
 * 铺满屏幕：按标题栏右端那个方框按钮（「铺满屏幕」），视图就地撑满整页；再按
 * 一次、或按 Esc 回来。
 *
 * **就地**是这件事的全部要点——视图不被搬到 portal 里去，什么都不重新挂载，
 * 所以半句没写完的筛选、选中的行、开着的弹层全都还在原处。它**不是模态**：
 * 没有 `aria-modal`、不困住焦点、也不把页面其余部分设成 inert——什么也没在
 * 问，它还是刚才那些内容、还站在宿主页面里。唯一借来的是背景不滚：一次看不
 * 见效果的滚动就是一个悄悄丢掉的滚动位置。收起时连 `!important` 一起原样还
 * 回去，同一页上两个铺满的面各记各的数，谁先收都不会把另一个锁在那里。
 *
 * 表格的粘性在两种状态下都成立：表头粘在顶、两行汇总粘在底、`订单号` 冻结在
 * 左。铺满改变的是「哪个盒子高」，不是谁在滚。
 */
export const FillTheScreen: Story = { args: { behaviour: 'data' } };

/**
 * 同一件事，但工作台被放进一个自带 containing block 的宿主容器里（这里是
 * `transform: translateZ(0)`，动画面板与要 GPU 提示的栅格外壳天天这么写）。
 *
 * 这种祖先会接管 `position: fixed` 的坐标系，于是「铺满屏幕」本来只会铺满**那
 * 个容器**。`transform`、`filter`、`perspective`、`backdrop-filter`、
 * `will-change`、`contain`、`container-type` 都算，逐个列举是一份会过期的清
 * 单，所以 `useViewExpansion` 改为**量**浏览器实际给的那个盒子：不是视口，差
 * 值就是修正量，一次到位。按下按钮，面仍然落在视口上。
 */
export const FillTheScreenInTransformedHost: Story = {
  args: { transformedHost: true },
};

/**
 * 同一件事，但宿主容器把孩子**缩放**了（`transform: scale(.75)`，缩略预览、
 * 演示模式与自适应画布常见的一种写法）。
 *
 * 这一半和平移不同：`getBoundingClientRect()` 报的已经是屏幕像素，而
 * `--fve-expanded-*` 是按元素自己的坐标读的——一个本地像素等于 `scale` 个屏幕
 * 像素。把量到的差值原样写回去，面会照这个比例缩水，偏移也差同一个倍数。所以
 * 修正量本身也是**量**出来的：先写朴素值，再看浏览器把它变成了多大，要的和到
 * 手的之比就是那个 scale。按下按钮，面仍然正好落在视口上。
 */
export const FillTheScreenInScaledHost: Story = {
  args: { scaledHost: true },
};

/**
 * 铺满屏幕时，弹层仍然在面的**前面**——这正是「不进 top layer」当初要保住的东
 * 西，而列设置与排序这两个弹层是后来才有的。
 *
 * 本包所有弹层都 portal 到 `document.body`，`ui/popups.tsx` 给每个 positioner
 * 写上 `z-index: var(--fve-popup-z-index, 50)`，所以它们有自己的一层；铺满的面
 * 取 `z-index: 0`，管的只是它与**宿主页面**的高低。两件事分开之后，这条故事问
 * 的仍是同一句话：面铺开时，菜单打得开吗。
 *
 * 顺带把冻结列也换成配置自己指定的那一列：行键无论如何都会被投影钉在左边，只
 * 钉行键证明不了 `pinned` 还管不管用。
 */
export const FillTheScreenWithPopups: Story = {
  args: { pinnedColumn: true },
};

/**
 * 宿主在自己页面上抬起了一层（这里是 `z-index: 10` 的一块浮层，可命中、盖住整
 * 个视口），弹层仍然在它**前面**。
 *
 * 这是弹层层级的底线。每个弹层都 portal 到 `document.body`，外面那层
 * positioner 由布局引擎写上 `transform: translate(...)`，于是它自己就是一个
 * stacking context——弹层内容里的 `z-50` 出不去；而 positioner 上那句
 * `isolate z-50` 是 Tailwind utility，构建又把本样式表每条规则都钉在
 * `:where(.fve-root, .fve-root *)` 里，positioner 不带 `fve-root`，那句话谁也没
 * 匹配上。所以层级只能写在 positioner **自己**身上：`ui/popups.tsx` 把
 * `z-index: var(--fve-popup-z-index, 50)` 作为 style 写上去，样式表在不在都成
 * 立；宿主自己的 chrome 比 50 还高时，在 `:root` 上改这一个变量即可。
 *
 * 浮层是可命中的，所以这一屏**看而不点**：要手动操作请看上面的故事。
 */
export const PopupsOverRaisedHostLayer: Story = {
  args: { raisedHost: true, paged: true },
};

/**
 * 窄栏里的标题栏：一根 360px 宽的柱子——手机、分屏、宿主的侧边面板都是这个
 * 宽度——装一个名字长得放不下的视图。
 *
 * 标题栏读作两组：左边「这是哪个视图」（种类、受众、名字、保存命令），右边
 * 「我在怎么看它」（筛选带的开合、铺满屏幕、宿主自己的按钮）。窄到两组并排
 * 放不下时，**先让名字缩，缩到不能再缩就换行**——右边那组整组挪到第二行，而
 * 不是压在左边那组上面。
 *
 * 以前是压上去的：左边那组带着 `min-w-0`，于是它可以被压到比自己内容还窄
 * （实测 96.8px 装 206.9px 的内容），父级因此以为一行放得下、`flex-wrap` 永远
 * 不触发，`Save` 被画在筛选开关**底下** 102px。在 375px 的视口上，`Save` 左
 * 缘、正中、右缘三点 `elementFromPoint` 全部答的是筛选开关：键盘还够得着，指
 * 针和手指一下也点不到——一个手机宽度的用户存不了盘。
 *
 * 现在左边那组按自己的内容定尺寸，名字是组里唯一的弹簧（`w-0 grow`）：
 * `truncate` 自己做不到这件事，一行不折的标题照样按整串文字要宽度，组会跟着
 * 名字一起长出去，溢出只是换了条路。
 */
export const NarrowTitleBar: Story = { args: { narrowHost: true } };
