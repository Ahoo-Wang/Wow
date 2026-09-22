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
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  type RecordKey,
  type ViewInstance,
} from '@ahoo-wang/fetcher-view-engine';
import {
  useBulkCommand,
  type BulkCommand,
  type BulkOutcome,
  type RecordActionSlots,
} from '@ahoo-wang/fetcher-view-engine/react';
import {
  BulkOutcomeStrip,
  DataWorkbench,
  ViewSurface,
} from '@ahoo-wang/fetcher-view-engine/ui';
// View Engine's own button, so the host's commands sit in its toolbar rather
// than beside it — exactly what an application does with the action slots.
import { Button } from '@/ui/components/button';
import { Spinner } from '@/ui/components/spinner';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  savedViews,
  savedWaybillViews,
  storyExportSource,
  tableSettingsStore,
  waybillSource,
  waybillsDefinition,
  type SourceBehaviour,
} from './fixtures.js';
import {
  OutcomeViewStore,
  outcomesStore,
  type StagedOutcome,
} from './outcomesStore.js';
import { StoryEngine, viewEngineScene } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The Record workbench in one state at a time. Every state below follows from
 * what the backend does or from what the saved config says, so a story sets
 * one of those two and changes nothing else.
 *
 * The wording is one of neither: the orders are Chinese, so every story runs
 * on the shipped `zhCN` catalogue and a `zh-CN` locale (`HOST_LANGUAGE`).
 * `English` is the one story that opts out, and it is what keeps the English
 * catalogue on screen somewhere.
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
        { field: 'id' as const, pinned: true },
        { field: 'amount' as const, pinned: true },
        { field: 'warehouse' as const },
        { field: 'status' as const },
        { field: 'createdAt' as const },
      ],
    },
  }),
};

/**
 * Every reading a cell can have, on one screen.
 *
 * The definition decides how a column reads. Here `状态` is a toned badge,
 * `标记` is one badge per entry, `运单` is an external link, `备注` is a
 * clamped paragraph and `订单号` is copyable — the same text it always was,
 * with a button beside it. No condition, so all six orders are on screen and
 * the three tones are too.
 */
const cellFamilyView = {
  ...savedViews[0],
  title: '单元格读法',
  config: recordConfig({
    summaries: [],
    table: {
      columns: [
        { field: 'id' as const, pinned: true },
        { field: 'status' as const },
        { field: 'tags' as const },
        { field: 'trackingUrl' as const },
        { field: 'note' as const },
      ],
    },
    card: { title: 'id', fields: ['status', 'tags', 'trackingUrl', 'note'] },
  }),
};

/**
 * 一列时刻的最早与最晚（2026-09-22 用户裁定）。
 *
 * 汇总不只是数字：一列时刻有最早、有最晚。页脚把这两格按**这一列画单元格的
 * 读法**画出来——宿主的语言与时区里的那个时刻，而不是十三位的毫秒数——词也换
 * 成时刻的词（「最早」「最晚」，不是「最小」「最大」）。金额那一格照旧合计，
 * 于是同一行里两种读法并排，谁也没有被对方带歪。
 */
const datedView = {
  ...savedViews[0],
  title: '最早与最晚下单',
  config: recordConfig({
    summaries: [
      { field: 'amount' as const, fn: 'SUM' as const },
      { field: 'createdAt' as const, fn: 'MIN' as const },
      { field: 'createdAt' as const, fn: 'MAX' as const },
    ],
    table: {
      columns: [
        { field: 'id' as const, pinned: true },
        { field: 'warehouse' as const },
        { field: 'status' as const },
        { field: 'amount' as const },
        { field: 'createdAt' as const },
      ],
    },
    card: {
      title: 'id',
      fields: ['warehouse', 'status', 'amount', 'createdAt'],
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

/**
 * A store that never answers `get`, so the view never finishes opening.
 *
 * It is the one screen the opening skeleton is on (P-13), and a real one:
 * a cold service, a slow link, a store behind a gateway that is thinking.
 */
class NeverOpensStore extends MemoryViewStore {
  override get(): Promise<ViewInstance> {
    return new Promise(() => {});
  }
}

function RecordWorkbenchDemo({
  behaviour = 'data',
  instanceId,
  broken = false,
  paged = false,
  withActions = false,
  english = false,
  keepStore = false,
  collapsed = false,
  transformedHost = false,
  scaledHost = false,
  raisedHost = false,
  pinnedColumn = false,
  refreshing = false,
  narrowHost = false,
  narrowWidth = 375,
  writeOutcome,
  theme,
  breakable = false,
  cellFamily = false,
  dated = false,
  exporting,
  wide = false,
  noViews = false,
  opening = false,
}: {
  behaviour?: SourceBehaviour;
  /** Holds the view open-but-not-opened, to show the opening skeleton. */
  opening?: boolean;
  /**
   * A definition with no view yet — no system view declared, nothing in the
   * store — which is the screen a host meets on its first day. The only
   * thing to do on it is to make one.
   */
  noViews?: boolean;
  instanceId?: string;
  /** Saves a config the definition no longer accepts, to show "needs fixing". */
  broken?: boolean;
  /** Saves a page size small enough that the result spans several pages. */
  paged?: boolean;
  /** Fills the three action slots, the way a business page would. */
  withActions?: boolean;
  /**
   * Opts this story out of `HOST_LANGUAGE` and back onto the English
   * catalogue the package ships as its default.
   */
  english?: boolean;
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
  /**
   * How narrow that host is. 375 is the phone the title bar was written
   * against; 420 is the column the pinned group's cap was measured in
   * (D17-4), where the result area comes out 286px wide.
   */
  narrowWidth?: number;
  /**
   * What the store makes of the next write that can carry it: a conflict, a
   * result that never comes back, or a flat refusal. One shot — the write
   * after it behaves normally, which is what lets the way *out* of the
   * outcome be pressed. Re-mount the story to stage it again.
   */
  writeOutcome?: StagedOutcome;
  /**
   * Pins the surface to one mode, the way a host does when its page is not
   * the one deciding. Left unset the view follows the toolbar's `.dark`.
   */
  theme?: 'light' | 'dark';
  /** Fills the row slot with an action that throws once it is pressed. */
  breakable?: boolean;
  /** Opens a view whose columns cover all four declared cell readings. */
  cellFamily?: boolean;
  /**
   * Opens a view that summarises a date column, so the footer carries the
   * earliest and the latest order beside a sum of money.
   */
  dated?: boolean;
  /**
   * What the export window has to report: pages slow enough to watch the
   * bar fill, a ceiling below the result, or a backend that refuses the
   * export while the view itself keeps answering.
   */
  exporting?: 'slow' | 'capped' | 'failing';
  /**
   * Opens the 20-column, 50-row definition instead of the six orders: the
   * one scenario where the table has to scroll in both directions at once.
   */
  wide?: boolean;
}) {
  // The whole of a host's bulk action that is not its own command: in
  // flight, what it came to, and what that does to the selection.
  const exportSelected = useBulkCommand(exportOrders);
  const workbench = (
    <>
      {/* Where a bulk outcome goes: beside the workbench, not in the toolbar
          slot that raised it. The run clears the selection, the toolbar's
          bulk slot lives only while there is one, and an outcome that went
          down with the selection it reported on would never be read. Its own
          `ViewSurface` is what carries this package's wording and theme to a
          component mounted outside the workbench. */}
      {exportSelected.outcome && (
        <ViewSurface
          {...(english ? {} : HOST_LANGUAGE)}
          className="px-3 pt-3"
          theme={theme}
        >
          <BulkOutcomeStrip
            outcome={exportSelected.outcome}
            onDismiss={exportSelected.dismiss}
            title={EXPORT_SELECTED}
          />
        </ViewSurface>
      )}
      <StoryEngine
        create={() => {
          if (opening)
            return createStoryEngine({
              store: new NeverOpensStore({ instances: savedViews }),
            });
          if (noViews)
            return createStoryEngine({
              definitions: [
                { ...ordersDefinition, views: [] },
                overviewDefinition,
              ],
              instances: [],
              behaviour,
            });
          if (wide)
            return createStoryEngine({
              definitions: [waybillsDefinition],
              instances: savedWaybillViews,
              source: waybillSource(behaviour),
            });
          if (writeOutcome) {
            const staged = new OutcomeViewStore({ instances: savedViews });
            staged.stage(writeOutcome);
            outcomesStore.current = staged;
            return createStoryEngine({ behaviour, store: staged });
          }
          const store = keepStore
            ? new MemoryViewStore({ instances: savedViews })
            : undefined;
          if (store) tableSettingsStore.current = store;
          return createStoryEngine({
            behaviour,
            ...(store ? { store } : {}),
            // The export's own pages, where the story is about them: `slow`
            // and `failing` answer the view normally and treat the export's
            // whole-page request differently, and `capped` only lowers the
            // ceiling — six orders against a ceiling of two.
            ...(exporting === 'capped' ? { limits: { exportMax: 2 } } : {}),
            ...(exporting === 'slow' || exporting === 'failing'
              ? {
                  source: storyExportSource(
                    exporting,
                    DEFAULT_RUNTIME_LIMITS.maxPageSize,
                  ),
                }
              : {}),
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
                      : cellFamily
                        ? [cellFamilyView]
                        : dated
                          ? [datedView]
                          : savedViews,
          });
        }}
      >
        {engine => (
          <DataWorkbench
            engine={engine}
            definitionId={wide ? waybillsDefinition.id : 'orders'}
            instanceId={
              noViews
                ? undefined
                : (instanceId ??
                  (wide ? savedWaybillViews[0].id : savedViews[0].id))
            }
            // zh-CN by default, because the fixtures are Chinese; the one
            // story that says `english` gets the shipped defaults instead.
            messages={english ? undefined : HOST_LANGUAGE.messages}
            locale={english ? 'en-US' : HOST_LANGUAGE.locale}
            theme={theme}
            // Left to the shell everywhere but the one story that is *about*
            // the fold: a column narrower than `md` opens folded on its own
            // now, so the narrow host proves that rule rather than being
            // handed the answer.
            defaultSidebarOpen={collapsed ? false : undefined}
            record={{
              actions: breakable
                ? breakableActions
                : withActions
                  ? businessActions(exportSelected)
                  : undefined,
            }}
          />
        )}
      </StoryEngine>
    </>
  );
  // A column narrower than the title bar's own controls. The width is the
  // whole of the scenario, and the regression play walks it down by writing
  // to this element's `style.width`; `overflow: hidden` is the tell, since
  // anything the bar cannot fit into would otherwise be painted over the
  // page beside it rather than clipped where it can be measured.
  if (narrowHost)
    return (
      <div data-narrow-host style={{ width: narrowWidth, overflow: 'hidden' }}>
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
/**
 * A row action that can be made to throw: the host's code runs inside the
 * workbench's tree, and this is what it looks like when it fails there.
 */
function BreakableAction({ row }: { row: { key: unknown } }) {
  const [broken, setBroken] = useState(false);
  if (broken) throw new Error(`Row action for ${String(row.key)} threw`);
  return (
    <Button variant="ghost" size="xs" onClick={() => setBroken(true)}>
      弄坏
    </Button>
  );
}

/** The business slots, with a row action that breaks on request. */
const breakableActions: RecordActionSlots = {
  global: () => (
    <Button size="sm" onClick={() => alert('新建订单')}>
      新建订单
    </Button>
  ),
  row: ({ row }) => <BreakableAction row={row} />,
};

/** The name of the one bulk command these stories carry, said in two places. */
const EXPORT_SELECTED = '导出所选';

/**
 * The host's own command, which is the only part `useBulkCommand` leaves to
 * it: the orders that can be exported, and the ones that cannot.
 *
 * A cancelled order is not exportable, so selecting the whole of 「全部」
 * gives the partial reading rather than the tidy one. That is on purpose —
 * a bulk command over real records is partly refused more often than not.
 */
function exportOrders(keys: readonly RecordKey[]): Promise<BulkOutcome> {
  const failed = keys.filter(key => CANCELLED_ORDERS.includes(String(key)));
  return new Promise(resolve =>
    setTimeout(
      () =>
        resolve({
          succeeded: keys.filter(key => !failed.includes(key)),
          failed,
          reason: failed.length > 0 ? '已取消的订单不能导出。' : undefined,
        }),
      400,
    ),
  );
}

const CANCELLED_ORDERS = ['SO-1002'];

function businessActions(exportSelected: BulkCommand): RecordActionSlots {
  return {
    // `outline`, not the default: the one primary on a screen is the Apply
    // that runs the query (D12 Ⅰ), and a host that put its own button in that
    // weight would be the second primary the moment the editor band is open.
    global: () => (
      <Button variant="outline" size="sm" onClick={() => alert('新建订单')}>
        新建订单
      </Button>
    ),
    // The selection goes to `run` as it came from the slot: the keys it is
    // over, and the two ways of showing what it did to them. The button's
    // name does not change while it runs — a control that renames itself
    // mid-press is one a screen reader has lost — so the spinner is drawn
    // and `aria-busy` is what says so. The registry's `Spinner` carries its
    // own `role="status"` and `aria-label`, for a spinner standing on its
    // own; inside a button those join the button's name, so this one is
    // hidden and the state is announced on the control itself.
    bulk: selection => (
      <Button
        variant="outline"
        size="sm"
        aria-busy={exportSelected.pending}
        disabled={exportSelected.pending}
        onClick={() => exportSelected.run(selection)}
      >
        {exportSelected.pending && (
          <Spinner
            data-icon="inline-start"
            role={undefined}
            aria-label={undefined}
            aria-hidden="true"
          />
        )}
        {EXPORT_SELECTED}
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
}

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
    opening: { table: { disable: true } },
    paged: { table: { disable: true } },
    instanceId: { table: { disable: true } },
    withActions: { table: { disable: true } },
    english: { table: { disable: true } },
    keepStore: { table: { disable: true } },
    collapsed: { table: { disable: true } },
    transformedHost: { table: { disable: true } },
    refreshing: { table: { disable: true } },
    raisedHost: { table: { disable: true } },
    narrowHost: { table: { disable: true } },
    narrowWidth: { table: { disable: true } },
    writeOutcome: { table: { disable: true } },
    theme: { table: { disable: true } },
    cellFamily: { table: { disable: true } },
    exporting: { table: { disable: true } },
    wide: { table: { disable: true } },
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

/**
 * 一列怎么读，由定义说了算。
 *
 * 「状态」是一枚带语气的徽章（待出库=warning、已发运=success、已取消=danger，
 * 颜色取主题 token，定义不能写任意色值）；「标记」一个数组一枚一枚地画，
 * 选项没命名过的码原样画出来；「运单」是外链，`target="_blank"` 且
 * `rel="noopener noreferrer"`，读不出的 scheme（SO-1005 那条）落回纯文本，
 * 绝不画成能点的链接；「备注」截到三行，整段留在 title 里，换行照留。
 * 「订单号」是 `copyable`：字一个没变，旁边多一颗复制按钮——指针划到这一行
 * 才现身，键盘 Tab 到它则始终现身，按下去图标翻成对勾、名字改说「已复制」，
 * 约 1.5 秒后复原。切到卡片，同一份读法。
 */
export const CellFamily: Story = { args: { cellFamily: true } };

/**
 * 一列时刻的最早与最晚。
 *
 * 页脚同一行里两种读法并排：「金额」是合计，一个带货币格式的数；「创建时间」
 * 是「最早」与「最晚」，两个按这一列画单元格的读法画出来的时刻——宿主的语言
 * 与时区里的那个日子，而不是十三位毫秒，也不是原样的 ISO 串。函数名也跟着
 * 换：一列时刻没有「最小」，它有「最早」。两份口径都在——「全部」那一行来自
 * 它自己那一次聚合，「本页」那一行是屏幕上这几行自己比出来的；这份视图不带
 * 条件，六单都在一页上，于是两行说的是同一件事，而这正是那两个标签的用处。
 */
export const EarliestAndLatest: Story = { args: { dated: true } };

/** A saved config the definition outgrew: `apply` is refused until it is fixed. */
export const NeedsFixing: Story = { args: { broken: true } };

/**
 * 打开视图的那一刻（P-13）：标题栏一块、结果块一块——工具栏一行、几行行，
 * 该有边的地方有边。从前这里只有一条 `h-8` 的灰条，它说的是"有东西在加载"，
 * 而不是"正在来的那一页长这样"，于是视图一到就是整页换一个形状。
 */
export const Opening: Story = { args: { opening: true } };

/** No saved view under this id, reported instead of an empty frame. */
export const CannotOpen: Story = { args: { instanceId: 'deleted' } };

/**
 * 一个还没有任何视图的定义：定义不声明系统视图，store 里也没有。
 *
 * 工作区说「还没有视图」，底下一句为什么、一颗「新建视图」——这一状态只在这里
 * 说全；侧栏在视图本该出现的地方只有一行安静的「还没有视图」，头上那颗 `+` 是它
 * 唯一的入口，列表折起时切换器菜单里还有同一项——三处通向 `useWorkbench.create`
 * 这一条命令（用户 2026-09-22）。按下去
 * 视图立刻以「新视图」打开、标着「尚未保存」、编辑带默认展开；第一次保存问
 * 名字和给谁看（与另存同一张表），存下的那一个随即列进侧栏并打开。没改过
 * 的新视图切走时不问；改过才问。
 */
export const NoViews: Story = { args: { noViews: true } };

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
 * 英文目录。这些故事的数据是中文的（订单号、待出库、华东仓），所以整册默认把
 * 包里带的 `zhCN` 交给 `messages`、把 `locale` 设成 `zh-CN`；这一条是唯一反过
 * 来的——什么都不传，于是用的是包自己的 `defaultMessages` 与 `en-US`。
 *
 * 它存在是为了让英文目录仍然有人看着：`messages` 是本地化的入口，两本目录里
 * 任何一本掉了键，都应该有一屏能看出来。要改其中几句，铺开再覆盖：
 * `{ ...zhCN, 'label.filter.apply': '确定' }`。
 *
 * 打开的是那个带条件的共享视图，所以结果上方的 Showing 里就有一枚可操作的条件
 * badge：字段名与候选项标签来自定义（它们是数据，仍然是中文），操作符来自目
 * 录，按 ✕ 把它撤下会立刻重跑查询。
 */
export const English: Story = {
  name: '英文目录',
  args: { english: true },
};

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
 * 导出：工具栏右端那颗下载图标，点开是一个窗口，整件事都在这个窗口里（D14）。
 *
 * **第一步先摆清楚**：有勾选时上面是一组单选——「选中（N）」（默认选它）与
 * 「所有（N，按当前筛选）」；没勾选就没有单选，只有「所有」。底下四行说的是
 * 文件里会有什么：多少条、按什么条件（和结果条件带同一套读法）、哪几列（列设
 * 置里可见的那几列，按表上的顺序）、文件叫什么名字。条数超过 `limits.exportMax`
 * 时这里多一行警告——**按下「导出」就是同意**，所以同意的是什么得先摆在眼前，
 * 而不是下载完才说。
 *
 * **第二步是同一个窗口**：一条进度条加「已拉取 {fetched} / {total} 条」，只有一
 * 个「取消」。在途时 Esc 与点遮罩**就是取消**——不是被拒掉：取消是用户自己的
 * 答复，它可以关窗。「选中」那一路的行本来就在手上，从确认直接到结果。
 *
 * **第三步还是同一个窗口**：「已导出 N 条」加文件名，被上限截断时多一句「文件只
 * 含前 N 条（共 M 条匹配）」；失败则是失败那句话加「重试」。所以结果区上方的状态
 * 行里不再有导出的事——一次旅程一个壳。
 *
 * 拿到的是一个 UTF-8 带 BOM 的 CSV：列与顺序就是窗口里列出的那几列，每个值按单元
 * 格的读法写（枚举用标签、时间按这个界面的时区与语言、数字按 `numberFormat`），
 * 文件名就是窗口里报的那一个。「所有」在后台按已应用条件分页拉，屏幕上的行、翻页
 * 与勾选都不受影响。宿主想留痕的，`DataWorkbench` 的 `record.onExported` 会把文件原样
 * 交出来。
 */
export const ExportResult: Story = {
  name: '导出',
  args: { behaviour: 'data' },
};

/**
 * 导出跑起来的样子：后台那一页故意拖慢，进度条与「已拉取 0 / 4 条」停在屏幕
 * 上。这时候 Esc、遮罩和「取消」是同一个答复——停下来，什么也不说。
 */
export const ExportRunning: Story = {
  name: '导出/进行中',
  args: { exporting: 'slow' },
};

/**
 * 超过上限：把 `limits.exportMax` 压到 2，这个视图匹配的四条就超了。警告在按钮
 * 之前，导出完窗口再说一遍文件里实际有几条、总共匹配几条。
 */
export const ExportCapped: Story = {
  name: '导出/超上限',
  args: { exporting: 'capped' },
};

/**
 * 导出失败：视图照常出数，只有导出那一次请求被拒。失败那句话就在窗口里，旁边
 * 是「重试」——不用关掉窗口再从工具栏开一遍。
 */
export const ExportFailed: Story = {
  name: '导出/失败',
  args: { exporting: 'failing' },
};

/**
 * 侧栏收起后的样子：标题栏最左边是展开按钮、定义标题与视图切换下拉，结果拿回
 * 侧栏占掉的那点宽度。下拉按受众分组、当前项打勾、系统视图带标签，末尾是「管理
 * 视图」——和侧栏齿轮开的是同一个对话框。切换照样先过离开守卫。
 */
export const CollapsedSidebar: Story = { args: { collapsed: true } };

/**
 * 自动刷新：工具栏「数据新鲜度」那一组里，刷新按钮右边多了一个 `▾`。
 *
 * 主键还是原来那一下——点一次，跑一次。`▾` 里是间隔：关闭，以及三档（30 秒、
 * 1 分钟、5 分钟）。档位再按 `runtime.limits` 的 `minRefreshInterval`／
 * `maxRefreshInterval` 裁剪，**限制不允许的档位根本不出现**，而不是灰着让人
 * 点一下才知道不行（D4）。
 *
 * 选中即改视图自己的 `refresh.interval`——和排序、列设置一样，是一次 `edit`
 * 加 `apply`，因此标题旁立刻出现「已修改」，`Save` 才会把它存下来。
 *
 * **这个故事看的是按钮上那处凭据在走**：开着的时候它写的不是节奏而是**还剩
 * 多久**（30 秒 → 29 秒 → … → 1 秒），数到头就刷新（图标换成 spinner），落
 * 地后从头再数。数的是运行时公布的下一次到期时刻，界面不另起一只钟；请求在
 * 途时写「0 秒」，而运行时因另外三条理由停表时退回那一档本身。那一格的宽度
 * 按该档位最宽的读数预留，所以数字变短时旁边的 `▾` 不会跟着挪。倒计时不念给
 * 屏幕阅读器——朗读的是 `label.refresh.on` 那一句节奏。
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
 * 冻结列的边只在有行滑到它下面时才出现。
 *
 * 左边冻着行键与 `金额`，右边冻着宿主的操作列；把结果区拉窄到中间那几列不得不
 * 滚，然后看两侧的边：滚动条在起点时左边没有边（没有东西在它下面），在终点时
 * 右边没有；两个冻结列之间也没有边——那里从来没有东西经过。
 */
export const PinnedEdges: Story = {
  args: { pinnedColumn: true, withActions: true },
};

/**
 * 宿主的行动作在渲染时抛错，只毁掉结果块。
 *
 * 按任一行的「弄坏」，那个动作从此在渲染时抛错：结果块换成一句说明加「重试」，
 * 标题栏、编辑带、保存都还在。按「重试」重新绘制这一块，行回来了。
 */
export const RenderFailure: Story = {
  args: { breakable: true },
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
 * 窄栏里的标题栏：一根 375px 宽的柱子——手机、分屏、宿主的侧边面板都是这个
 * 宽度——装一个名字长得放不下的视图。侧栏没人交代过收起，是外壳自己量出来
 * 的：窄于 `md` 的一栏里，列表不在视图旁边而是堆在它上面（D12／L1）。
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

/**
 * 保存撞上了别人：**冲突**。
 *
 * 手动走一遍：点一下 `订单号` 的表头（排序会立刻应用，标题旁出现「已修改」），
 * 再按 `Save`。存储在这一次写入之前替别人先落了一份配置——整份五列、每页 50
 * 条、不排序——于是乐观版本对不上，引擎把结果记成 `kind: 'conflict'`，标题栏
 * 下面那条带边框的行就是它。
 *
 * 三条出路都画在那一行上，**按代价从小到大**：**Take theirs** 丢掉草稿改用服
 * 务端那份，**Save my copy** 另存一份、两边都留着，**Keep mine** 把自己这份盖
 * 上去。三颗都是 `outline`——哪一条损失最小取决于两份配置里各有什么，屏幕不替
 * 谁作主；这一屏唯一的 primary 始终是跑查询的那个 Apply（D12 Ⅰ）。从前最危险
 * 的那一条（盖上去）是实心 primary，也就是整屏唯一被强调的东西。
 *
 * 取服务端那份与盖上去都会先把同一个选择再问一遍（`ConflictConfirm`），因为两
 * 边都有从按钮上看不见的损失——对话框里用 `describeConfig` 把两份配置各说成一
 * 句话摆在一起，这里正好三处不同：每页多少条、几列、几条排序。
 *
 * 冲突只安排了一次：答完之后的写入照常落库，所以「盖上去」是真的盖上去了。
 */
export const SaveConflicted: Story = { args: { writeOutcome: 'conflict' } };

/**
 * 保存发出去了，结果没回来：**未知**。
 *
 * 同样先点一下表头再按 `Save`。存储抛 `UNAVAILABLE`——按 management.md 的分类
 * 这不是失败也不是成功：请求已经出门，服务端可能写了也可能没写。所以那一行只
 * 给两个按钮：**Retry** 用**同一个** `requestId` 与同一份正文再发一次，交给服
 * 务端去重；**Abandon** 放下它，草稿仍在，下次保存是一次新的意图。
 *
 * 未结清的未知会挡住这个视图上的新写入（`view.write.unknown-pending`），所以
 * 这两个按钮不是装饰——不答它，`Save` 就一直不听话。
 */
export const SaveResultUnknown: Story = { args: { writeOutcome: 'unknown' } };

/**
 * 服务端看过了，不收：**拒绝**。
 *
 * 点表头，按 `Save`。存储抛 `INVALID`，那一行说的是目录里的句子
 * （`view.write.invalid`），后面跟着存储自己的原话——已打开的视图有地方放这句
 * 话，管理器的行里只放前半句。
 *
 * 拒绝是一个明确答案：什么也没写进去，也就没有可重试、可覆盖的东西，只有
 * **Dismiss** 把这行拿掉。拿掉不是装饰——引擎还替这次写入留着位置，不结清它，
 * 这行会在屏幕上待一整个会话。改完再按一次 `Save`，那是一次新的意图，会落库。
 */
export const SaveRefused: Story = { args: { writeOutcome: 'rejected' } };

/**
 * 没打开的那一行也会撞上别人：**管理器行内的结局**。
 *
 * 从侧栏齿轮打开「管理视图」，给任意一行按铅笔改个名、按 ✓ 确认。改名同样撞上
 * 先落的那份配置，于是冲突画在**它自己那一行下面**——一行小字加两个小按钮，而
 * 不是把整张列表推下去：下面那些行还是真的，一条横幅把它们挤走就是用一个问题
 * 报告另一个问题。
 *
 * 行里的措辞与打开的视图不同：取服务端那份在这里叫 **Reload list**（这一行讲
 * 的是它所在的那张列表，列表会按存储的版本重新读回来），盖上去仍叫 **Keep
 * mine**。行里没有「另存」——管理器没有地方放一份副本。
 */
export const RenameConflicted: Story = { args: { writeOutcome: 'conflict' } };

/**
 * 删除撞上冲突，要问第二遍（management.md「冲突与未知结果」）。
 *
 * 这里打开的是个人视图「我盯的大额单」，要删的是另一行、共享的「待出库订
 * 单」——删的不是眼前这个，正是管理器存在的理由。（打开的那个视图筛的是五千以
 * 上的单子，这份数据里一条也没有，所以下面空着；这一屏看的是对话框。）打开
 * 「管理视图」，给那一行按垃圾桶，第一个对话框说清代价（共享视图：所有人都会
 * 失去它），按 `Delete`。
 *
 * 删除这时撞上别人刚落的那份：第一次确认说的是**当时那个视图**，而冲突报回来
 * 的是一个此后变过的视图——它可能已经变成共享的，删掉就连带别人一起。所以行里
 * 按 **Keep mine** 不会直接删，而是拿着服务端刚回读的那份摘要**再问一遍**，第
 * 二个对话框按 `Delete` 才真的删。
 */
export const DeleteConflicted: Story = {
  args: { writeOutcome: 'conflict', instanceId: savedViews[2].id },
};

/**
 * 一张真的很宽的表：20 列、50 行，外加宿主的操作列。
 *
 * 订单那套定义只有八个字段，故事里最多摆五列，于是每一条与「宽」有关的规矩都
 * 只在没什么可滚的桌面上看过。这一条把它们摆在一起看：
 *
 * - **横滚只发生在中间**。左边冻着运单号（配置自己钉的，也正好是行键），右边
 *   冻着宿主的操作列（D13），中间 19 列随滚动条走。两侧的边只在真有东西滑到
 *   它下面时才出现。
 * - **表头粘在顶、两行汇总粘在底**。50 行一页放不下，纵向滚起来之后列名和
 *   「件数／重量／运费」三个合计都还在原地——一张 20 列的表，滚到第 40 行还认
 *   得出哪一列是哪一列，靠的就是这个。
 * - **一列一种读法**。枚举四套（发货仓／承运商／运输方式／时效）、带语气的状
 *   态徽章、一列标记数组、三种数字格式（整数件数、公斤、人民币）、两列布尔、
 *   一个日期与一个时刻、一个外链、一段截到三行的备注。
 * - **列设置、排序、导出这三个弹层在 20 列上才有分量**：列设置里 20 行可拖加
 *   一行还没加进来的「联系电话」，排序弹层里已经堆了三个字段（发运日期、运
 *   费、运单号），导出窗口的列清单要一口气念完这 20 列。
 *
 * 窄到 420px（手机、分屏、宿主的侧边面板）时这张表不会变窄，它**就是**要横
 * 滚：把 20 列挤进一柱宽度只会把每一列都挤成看不懂的样子。窄屏要验的是两条冻
 * 结列没有把中间吃光，以及滚动条到得了两头。
 */
export const WideTable: Story = {
  name: '宽表 · 20 列 50 行',
  args: { wide: true, withActions: true },
};

/**
 * 同一张宽表，装进一根 420px 的柱子——手机、分屏、宿主的侧边面板都是这个宽
 * 度。这是 **D17-4 那条封顶**唯一看得见的地方。
 *
 * 封顶之前：钉住的三列（勾选 42 + `运单号` 86 + 宿主操作列 104）合计 232px。
 * 这张故事里结果区量到 371px，232 就占掉 **63%**；用户 2026-09-21 在真机
 * 420×860 上量到的结果区只有 286px，占 **81%**，留给其余 19 列的只有 54px——
 * 中间最窄的一列 44px、最宽的 247px，横滚到哪儿都在看半列。冻结列的宽度是固
 * 定的，视口越窄它占的比例越大，而此前没有任何一处封顶。
 *
 * 封顶之后：钉住的一组不得超过结果区可视宽的**一半**，超出就从**最外侧**开始
 * 放掉冻结——这里放掉的是宿主的操作列，剩下 128px（34%），中间拿回 243px
 * （66%）。放掉的只是这一次的渲染，配置里那一条 `pinned` 一个字没动：把这根
 * 柱子拉宽，操作列自己就回到右边钉住。**主键永远不放**，一行滚到哪儿都还说得
 * 出自己是哪一单。
 *
 * **放得下就一个都不放**：中间要滚，冻结列才吃得掉东西；一张本来就放得下的表
 * 上放掉冻结，一个像素也换不回来，只是把 D13 的框拆了。
 *
 * 代价写在这里：操作列一旦放掉冻结，右边那道 D13 的边就跟着走（除非配置自己
 * 在右边钉了一列）。中间读不出来的时候，框的那一头没有它值钱。
 */
export const PinnedGroupCapped: Story = {
  name: '宽表 · 420 窄栏里的封顶',
  args: { wide: true, withActions: true, narrowHost: true, narrowWidth: 420 },
};
