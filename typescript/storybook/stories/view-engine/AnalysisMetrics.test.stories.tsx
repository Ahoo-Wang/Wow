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
import type { StoryObj } from '@storybook/react-vite';
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { formatMessage, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  LatestPerWarehouse as DisplayLatestPerWarehouse,
  TableWithTotals as DisplayTableWithTotals,
} from './AnalysisWorkbench.stories.js';
import { findDataTable, readColumn, readHeaders } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/分析工作台/指标与保留/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * 写出来的指标、只保留的分组、按两个别名排的顺序，在真浏览器里走一遍
 * （D20 屏 B）。
 *
 * 这三件事在 jsdom 里钉的是「按下去写了什么」
 * （`typescript/wow-view-engine/test/havingRows.test.tsx` 与
 * `test/formulaCard.test.tsx`）；这里钉的是走完一遍之后**屏幕上的那张表
 * 变了**——少了两行、多了一列、行序换了。一份假答案会让前两件事照样通过，
 * 所以故事的数据源真的按查询分组、筛选、排序（`rowSource.ts`）。
 */

/** The tray's handle in the title bar; the one button of its group. */
function trayToggle(canvasElement: HTMLElement): HTMLElement {
  return within(
    canvasElement.querySelector<HTMLElement>('[data-slot="editor-toggle"]')!,
  ).getByRole('button');
}

const tray = (canvasElement: HTMLElement) =>
  canvasElement.querySelector<HTMLElement>('[data-slot="analysis-tray"]');

async function openTray(canvasElement: HTMLElement): Promise<HTMLElement> {
  await userEvent.click(trayToggle(canvasElement));
  await waitFor(() => expect(tray(canvasElement)).not.toBeNull());
  return tray(canvasElement)!;
}

/** The one button that runs the draft. */
function applyButton(canvasElement: HTMLElement): HTMLElement {
  return within(
    canvasElement.querySelector<HTMLElement>(
      '[data-slot="analysis-tray-actions"]',
    )!,
  ).getByRole('button', { name: zhCN['label.filter.apply'] });
}

/** How many groups the result drew. */
const groupRows = (table: HTMLElement) =>
  (table as HTMLTableElement).tBodies[0]?.rows.length ?? 0;

/**
 * 指标卡片上写的是它测量的那个字段——「金额」——汇总方式由旁边那个控件说；
 * 一旦在别处**提到**这个指标（「只保留」、排序、派生指标的操作数），旁边
 * 没有那个控件，于是提到它的地方和结果表的列头说同一句话：「金额的总和」
 * （`columnTitle` / `metricReference`）。
 */
const AMOUNT_METRIC = formatMessage(zhCN, 'label.summary.of', {
  field: '金额',
  fn: zhCN['label.summary.fn.SUM'],
});

/**
 * 「(金额 − 成本)的总和」: a formula's own words, bracketed because a summary
 * is put around them (2026-09-23 audit), then how it was summarised.
 */
const MARGIN_HEADER = formatMessage(zhCN, 'label.summary.of', {
  field: '(金额 − 成本)',
  fn: zhCN['label.summary.fn.SUM'],
});

/** The result's first row: what the numbers below are. */
const reading = (canvasElement: HTMLElement) =>
  canvasElement.querySelector<HTMLElement>('[data-slot="analysis-reading"]');

/** 「只保留 金额的总和 大于 ¥2,000.00」, in the words the tray's row uses. */
const KEPT_READING = formatMessage(zhCN, 'label.analysis.reading-kept', {
  reading: formatMessage(zhCN, 'label.analysis.reading', {
    dimensions: '仓库',
    metrics: `${zhCN['label.analysis.row-count']}${zhCN['label.filter.join']}${AMOUNT_METRIC}`,
  }),
  conditions: formatMessage(zhCN, 'label.analysis.reading-kept-row', {
    metric: AMOUNT_METRIC,
    operator: zhCN['label.having.op.GT'],
    value: new Intl.NumberFormat('zh-CN', {
      style: 'currency',
      currency: 'CNY',
    }).format(2000),
  }),
});

/**
 * 「只保留」：一行一条比较，跑完之后表上真的少了两组。
 *
 * 四个仓库的金额总和是 1920／2450／4880／980，「金额的总和 大于 2000」
 * 之后只剩华北与华南。它是聚合之后、排序与截断之前的一道筛选，所以它减少
 * 的是**组**，不是记录——一条画在条件面板里的筛选做不到这件事，这也是它
 * 为什么不在范围里。
 */
export const KeepOnly: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));

    const opened = await openTray(canvasElement);
    await userEvent.click(
      opened.querySelector<HTMLElement>('[data-slot="add-having"]')!,
    );
    const row = await waitFor(() => {
      const found = opened.querySelector<HTMLElement>(
        '[data-slot="having-row"]',
      );
      if (!found) throw new Error('「只保留」那一行没有出来');
      return found;
    });

    // Which metric keeps a group: the sample values are not offered, because
    // Wow refuses a having over one.
    await userEvent.click(
      within(row).getByLabelText(zhCN['label.analysis.having-metric']),
    );
    await userEvent.click(
      await screen.findByRole('option', { name: AMOUNT_METRIC }),
    );

    await userEvent.type(
      within(row).getByLabelText(zhCN['label.analysis.having-value']),
      '2000',
    );
    await userEvent.click(applyButton(canvasElement));

    const after = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(after)).toBe(2));
    await expect(readColumn(after, '仓库')).toEqual(['华北', '华南']);

    // 托盘收起之后，被筛掉的两组仍由结果第一行说出来（2026-09-23 审查 P0-2）。
    await userEvent.click(trayToggle(canvasElement));
    await waitFor(() => expect(tray(canvasElement)).toBeNull());
    await expect(reading(canvasElement)).toBeVisible();
    await expect(reading(canvasElement)?.textContent).toBe(KEPT_READING);
  },
};

/**
 * 存着「只保留」的视图，打开时托盘收着（P0-2）。
 *
 * 表上只有华北与华南，合计行却数着全部记录；从前屏幕上没有一个字说华东与
 * 西南去了哪里，读的人只能当它们没有数据。现在结果第一行在指标后面说出
 * 「只保留 金额的总和 大于 ¥2,000.00」——不打开托盘也看得见。
 */
export const KeepOnlySaved: Story = {
  ...DisplayTableWithTotals,
  args: { ...DisplayTableWithTotals.args, kept: 2000 },
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(2));
    await expect(readColumn(table, '仓库')).toEqual(['华北', '华南']);

    // A saved view opens folded: the tray is not there to say it.
    await expect(tray(canvasElement)).toBeNull();
    await expect(reading(canvasElement)).toBeVisible();
    await expect(reading(canvasElement)?.textContent).toBe(KEPT_READING);
  },
};

/**
 * 公式：两个字段一次运算，逐条算完再汇总，屏幕上多出一列。
 *
 * 「金额 − 成本」在**每一条记录上**算一次、再在组里合计，这与「金额总和
 * 减 成本合计」在合计上碰巧相等、在平均上并不相等——数据源真的按表达式
 * 算，所以这一列的数是查询答的，不是故事写死的。
 */
export const Formula: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    await expect(readHeaders(table)).not.toContain(MARGIN_HEADER);

    await openTray(canvasElement);
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.add-metric'] }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: zhCN['label.analysis.add-formula'],
      }),
    );

    // The card is named by what it says, because no field stands behind it.
    await waitFor(() =>
      expect(
        [...canvasElement.querySelectorAll('[data-slot="card-name"]')].map(
          name => name.textContent,
        ),
      ).toContain('金额 − 成本'),
    );

    await userEvent.click(applyButton(canvasElement));

    const after = await findDataTable(canvasElement);
    await waitFor(() => expect(readHeaders(after)).toContain(MARGIN_HEADER));
    // 华东 1920 − 1400 = 520；这一列的数由数据源逐条算出来。
    await expect(readColumn(after, MARGIN_HEADER)[0]).toContain('520');
    // Money minus money is money: the column reads in the ¥ its operands
    // are in, as the amount column beside it does (2026-09-23 audit).
    await expect(readColumn(after, MARGIN_HEADER)[0]).toBe('¥520.00');
  },
};

/**
 * 排序：与记录视图同一个控件，所以「先按哪个、再按哪个」说得出来。
 *
 * 先按记录数、再按金额：记录数 1 的两组（华北 2450、西南 980）排在前面，
 * 组内按金额升序，于是西南在华北之前。一个只装得下一条排序的控件说不出
 * 这句话——它只能在两组并列时听天由命。
 */
export const SortedByTwo: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));

    const opened = await openTray(canvasElement);
    await userEvent.click(
      opened.querySelector<HTMLElement>(
        '[data-slot="analysis-sort"] [data-control="sort"]',
      )!,
    );
    const editor = await screen.findByRole('dialog');

    for (const name of [zhCN['label.analysis.row-count'], AMOUNT_METRIC]) {
      await userEvent.click(
        within(editor).getByRole('button', {
          name: zhCN['label.sort.groups.add'],
        }),
      );
      // The menu opens a frame after the press, and the second press lands
      // while the first pick's menu is still fading out — in the document,
      // closed, its items there but taking no pointer. So its item is picked
      // once the menu is open again, not the moment it can be found.
      const menu = await waitFor(() => {
        const found = screen.getByRole('menu');
        expect(found).toHaveAttribute('data-open');
        return found;
      });
      await userEvent.click(within(menu).getByRole('menuitem', { name }));
    }

    await expect(
      [...editor.querySelectorAll('[data-slot="sort-entry"]')].map(entry =>
        entry.getAttribute('data-field'),
      ),
    ).toEqual(['orders', 'amount']);

    await userEvent.keyboard('{Escape}');
    await userEvent.click(applyButton(canvasElement));

    const after = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(after, '仓库')).toEqual([
        '西南',
        '华北',
        '华东',
        '华南',
      ]),
    );
  },
};

/**
 * 「前 N 组」的框（2026-09-23 审查）：草稿里只放 Wow 收得下的 N。
 *
 * 从前清空之后弹回刚才的 -3；2.5 被说成「必须是正数」；删掉最后一个维度把这一
 * 行带走之后，那个 -3 还留在草稿里拦着应用。这里在真浏览器里走一遍：出界的字
 * 照原样留在框里，框下面一句「须为 1～1,000 的整数」（一台保持缺省配置的
 * Wow 服务端收的，D42），读得见、在框下面；清空就是空着（占位字写着起步的
 * 100）；带着一个出界的字删掉维度，框与那句话一起
 * 走，应用照跑，表上只剩一行。
 */
export const TopNField: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    const opened = await openTray(canvasElement);
    const box = () =>
      within(opened).getByLabelText<HTMLInputElement>(
        zhCN['label.analysis.row-limit'],
      );
    const refusal = formatMessage(zhCN, 'label.analysis.row-limit-invalid', {
      max: 1_000,
    });

    for (const typed of ['-3', '2.5']) {
      await userEvent.clear(box());
      await userEvent.type(box(), typed);
      await expect(box()).toHaveValue(typed);
      await expect(box()).toHaveAttribute('aria-invalid', 'true');
      // A fraction is told the range, not that it must be positive.
      const said = await within(opened).findByText(refusal);
      await expect(said).toBeVisible();
      // Beside the note that the source picks the groups (nothing sorts
      // them here), so the description holds both.
      await expect(box()).toHaveAccessibleDescription(
        expect.stringContaining(refusal),
      );
      // Under its box, and inside the tray rather than cut off at its edge.
      const where = said.getBoundingClientRect();
      await expect(where.top).toBeGreaterThanOrEqual(
        box().getBoundingClientRect().bottom,
      );
      await expect(where.right).toBeLessThanOrEqual(
        opened.getBoundingClientRect().right,
      );
    }

    // Emptied, it stays empty: the N a view starts at, said as the placeholder.
    await userEvent.clear(box());
    await userEvent.tab();
    await expect(box()).toHaveValue('');
    await expect(box()).toHaveAttribute('placeholder', '100');
    await expect(box()).not.toHaveAttribute('aria-invalid', 'true');
    await expect(within(opened).queryByText(refusal)).toBeNull();

    // A refused N left in the box goes with the box when the last dimension
    // takes the row away — and nothing is left behind to refuse Apply.
    await userEvent.type(box(), '-3');
    await within(opened).findByText(refusal);
    await userEvent.click(
      within(opened).getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.remove-group', {
          name: '仓库',
        }),
      }),
    );
    await waitFor(() =>
      expect(opened.querySelector('[data-slot="analysis-limit"]')).toBeNull(),
    );
    await expect(within(canvasElement).queryByText(refusal)).toBeNull();
    await expect(applyButton(canvasElement)).toBeEnabled();
    await userEvent.click(applyButton(canvasElement));
    await waitFor(async () =>
      expect(groupRows(await findDataTable(canvasElement))).toBe(1),
    );
    // Nor does the status line say anything about the N.
    await expect(canvasElement.textContent ?? '').not.toContain(
      formatMessage(zhCN, 'analysis.limit.out-of-range', { max: 1_000 }),
    );
  },
};

/**
 * 「改了就跑」（D20，todo 批 7）：托盘里改一下问题，没人按应用，表自己重画。
 *
 * jsdom 那边钉的是**什么时候**跑——一次编辑之后那 300 毫秒、一串编辑并成一次
 * 查询、范围改了就一直等着（`typescript/wow-view-engine/test/autoRun.test.tsx` 的
 * 「改了就跑: an analysis runs as it is edited」，走的是测试时钟）。这里钉的是
 * 走完一遍之后**屏幕上真的变了**：多了一列状态，中途那一下结果是**淡着**的而
 * 不是空的，跑完点也没了；把开关关掉，同样一次编辑就停在那儿等应用。
 *
 * 故事的存储每次挂载新建（`StoryEngine`），所以这里写下的 `autoRun: false`
 * 不会漏给下一个故事，末尾不必再收拾一遍。
 */

/** 「成本的总和」：关掉开关之后那次编辑要带出来的那一列。 */
const COST_HEADER = formatMessage(zhCN, 'label.summary.of', {
  field: '成本',
  fn: zhCN['label.summary.fn.SUM'],
});

/** The tray's auto-run checkbox, wherever the actions footer puts it. */
function autoRunSwitch(canvasElement: HTMLElement): HTMLElement {
  return within(
    canvasElement.querySelector<HTMLElement>('[data-slot="auto-run"]')!,
  ).getByRole('checkbox');
}

/** 结果淡着的那一下，被真的画出来了没有。 */
interface Fade {
  /** 那个时刻的属性在不在。 */
  marked: boolean;
  /** 那个时刻这块东西画得有多淡；1 就是根本没淡。 */
  opacity: number;
  /** 这块东西有没有自己的盒子——没有盒子，`opacity` 就只是个计算值。 */
  painted: boolean;
}

/**
 * Watches for the result being drawn faded from this moment on.
 *
 * The fade lasts as long as one auto-run takes, so it is sampled every frame
 * rather than looked at twice. What it records is not the attribute but **what the
 * browser did with it**: the element's own box, and the least opacity it was
 * ever painted at — the fade eases in, so the frame the attribute lands on
 * still reads 1. Only a real browser answers this, and it is the one thing
 * jsdom cannot: a wrapper that generates no box (`display: contents`)
 * computes the very same 0.6 and paints nothing at all.
 */
function watchFading(canvasElement: HTMLElement): {
  read(): Fade;
  stop(): void;
} {
  const fade: Fade = { marked: false, opacity: 1, painted: false };
  let running = true;
  const look = () => {
    const stale = canvasElement.querySelector<HTMLElement>(
      '[data-slot="analysis-result"][data-stale]',
    );
    if (stale) {
      fade.marked = true;
      fade.opacity = Math.min(
        fade.opacity,
        Number(getComputedStyle(stale).opacity),
      );
      if (stale.getBoundingClientRect().height > 0) fade.painted = true;
    }
    if (running) requestAnimationFrame(look);
  };
  look();
  return {
    read: () => ({ ...fade }),
    stop: () => {
      running = false;
    },
  };
}

export const RunsAsEdited: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    await expect(readHeaders(table)).not.toContain('状态');

    const opened = await openTray(canvasElement);
    await expect(autoRunSwitch(canvasElement)).toBeChecked();

    // 加一个维度，然后什么也不按。
    const faded = watchFading(canvasElement);
    await userEvent.click(
      within(opened).getByRole('button', {
        name: zhCN['label.analysis.add-group'],
      }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: '状态' }),
    );

    await waitFor(async () =>
      expect(readHeaders(await findDataTable(canvasElement))).toContain('状态'),
    );
    // 四个仓库按状态再切一刀，组比原来多。
    await expect(groupRows(await findDataTable(canvasElement))).toBeGreaterThan(
      4,
    );
    // 等的那一下，上一份答案留在屏幕上、**真的**淡着——不只是带了个属性，
    // 而是浏览器为它画了一个盒子、并把那个盒子画淡了；跑完就不淡了，点也没了。
    faded.stop();
    await expect(faded.read().marked).toBe(true);
    await expect(faded.read().painted).toBe(true);
    await expect(faded.read().opacity).toBeLessThan(1);
    await waitFor(() =>
      expect(
        canvasElement.querySelector(
          '[data-slot="analysis-result"][data-stale]',
        ),
      ).toBeNull(),
    );
    await expect(
      applyButton(canvasElement).querySelector('[data-slot="pending-dot"]'),
    ).toBeNull();

    // 关掉开关：这是这个用户对这个定义的偏好，写完列表重读，勾自然落下。
    await userEvent.click(autoRunSwitch(canvasElement));
    await waitFor(() => expect(autoRunSwitch(canvasElement)).not.toBeChecked());

    const stillFading = watchFading(canvasElement);
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.add-metric'] }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: '成本' }),
    );

    // 编辑落进了托盘——卡片在那儿——而结果一次也没淡过：没有哪一次自动运行
    // 在路上，那一列因此也还没有。
    await waitFor(() =>
      expect(
        [...canvasElement.querySelectorAll('[data-slot="card-name"]')].map(
          name => name.textContent,
        ),
      ).toContain('成本'),
    );
    stillFading.stop();
    await expect(stillFading.read().marked).toBe(false);
    await expect(readHeaders(await findDataTable(canvasElement))).not.toContain(
      COST_HEADER,
    );
    await expect(
      applyButton(canvasElement).querySelector('[data-slot="pending-dot"]'),
    ).not.toBeNull();

    // 按下去才跑。
    await userEvent.click(applyButton(canvasElement));
    await waitFor(async () =>
      expect(readHeaders(await findDataTable(canvasElement))).toContain(
        COST_HEADER,
      ),
    );
  },
};

/** 「创建时间的最晚」: a moment's own word, not 「最大」. */
const LATEST_HEADER = formatMessage(zhCN, 'label.summary.of', {
  field: '创建时间',
  fn: zhCN['label.summary.fn.date.MAX'],
});

/**
 * 每个仓库最晚的一单（生产审查：真实 Wow 服务上 `MAX(eventTime)` 在表里、
 * 图上与读屏表里都是一串十三位毫秒）。
 *
 * 数据源真的按组取创建时间的最大值、再按它降序排（`rowSource.ts`），所以
 * 这一列的时刻与行序都是查询答的：西南最近，华东最早（它最晚的那一单已被
 * 软删除，不在答复里）。每一格按界面语言与引擎时区读，与记录视图的单元格
 * 同一套读法；表头说「最晚」。切到可视化，散点灰着——它要两个数量，而
 * 最晚是一个时刻，不是数量。
 */
export const LatestPerWarehouse: Story = {
  ...DisplayLatestPerWarehouse,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    await expect(readHeaders(table)).toContain(LATEST_HEADER);

    const shown = (iso: string) =>
      new Intl.DateTimeFormat('zh-CN', {
        dateStyle: 'medium',
        timeStyle: 'medium',
      }).format(new Date(iso));
    await expect(readColumn(table, '仓库')).toEqual([
      '西南',
      '华南',
      '华北',
      '华东',
    ]);
    await expect(readColumn(table, LATEST_HEADER)).toEqual([
      shown('2026-09-17T08:45:00.000Z'),
      shown('2026-09-17T02:20:00.000Z'),
      shown('2026-09-16T01:05:00.000Z'),
      shown('2026-09-15T06:40:00.000Z'),
    ]);
    // Neither the stored value nor the number it was compared as.
    await expect(table.textContent).not.toContain('2026-09-17T08:45');
    await expect(table.textContent).not.toMatch(/[0-9]{13}/);

    await userEvent.click(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.analysis.visualize'],
      }),
    );
    const scatter = await waitFor(() => {
      const tile = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart-picker"] [data-chart-type="scatter"]',
      );
      if (!tile) throw new Error('可视化面板没有出来');
      return tile;
    });
    await expect(scatter.getAttribute('aria-disabled')).toBe('true');
    await expect(scatter.textContent).toContain(
      zhCN['chart.fit.needs-quantity'],
    );
  },
};
