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
  OrderAnalysis as DisplayOrderAnalysis,
} from './RetailAnalysis.stories.js';

/**
 * 分析编辑区按依赖排行（D71）与图型选项从卡片右上角进（D72），在用户审查它们的
 * 那块屏上：零售「订单与商品」的「本月 GMV（较上月同期）」与「本月 GMV 较上月
 * 同期（分渠道）」。与 `RetailAnalysis.test.stories.tsx` 同一个标题，故事 id
 * 不变；那份太大，按关注点拆出这一份。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/分析工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const GMV_VIEW = '本月 GMV（较上月同期）';
const CHANNEL_VIEW = '本月 GMV 较上月同期（分渠道）';

/** A view's title as the start of a pattern: its parentheses mean themselves. */
const escaped = (title: string) => title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Opens a saved analysis from the list and waits for its answer. */
async function openView(canvasElement: HTMLElement, title: string) {
  const canvas = within(canvasElement);
  await userEvent.click(
    await canvas.findByRole(
      'button',
      { name: new RegExp(`^${escaped(title)}`) },
      { timeout: 10_000 },
    ),
  );
  await waitFor(() =>
    expect(
      canvasElement.querySelector('[data-slot="view-title"]'),
    ).toHaveTextContent(title),
  );
}

/** Presses the title bar's 「分析」 and answers the tray it opens. */
async function openTray(canvasElement: HTMLElement): Promise<HTMLElement> {
  await userEvent.click(
    within(
      canvasElement.querySelector<HTMLElement>('[data-slot="editor-toggle"]')!,
    ).getByRole('button'),
  );
  return waitFor(() => {
    const found = canvasElement.querySelector<HTMLElement>(
      '[data-slot="analysis-tray"]',
    );
    expect(found).not.toBeNull();
    return found!;
  });
}

/**
 * The result answering the question on screen, no longer faded: the axe
 * pass after the play reads its contrast, and a faded chart is the wait for
 * an answer, not the answer.
 */
async function answered(canvasElement: HTMLElement): Promise<void> {
  await waitFor(
    () =>
      expect(
        canvasElement.querySelector('[data-slot="analysis-result"]'),
      ).not.toHaveAttribute('data-stale'),
    { timeout: 5_000 },
  );
}

/**
 * The tooltip saying `text`, once it has opened — another may still be
 * fading out where the focus was before.
 */
const tooltip = (text: string) =>
  waitFor(() => {
    const found = [
      ...document.querySelectorAll<HTMLElement>(
        '[data-slot="tooltip-content"]',
      ),
    ].find(content => content.textContent === text);
    expect(found).toBeVisible();
    return found!;
  });

/** The tray's rows, top to bottom, by the term each is headed with. */
const rows = (tray: HTMLElement) =>
  [...tray.querySelectorAll('section[data-slot^="analysis-slot-"]')].map(row =>
    row.getAttribute('aria-label'),
  );

const TERMS = {
  elements: zhCN['label.analysis.slot.elements'],
  metrics: zhCN['label.analysis.slot.metrics'],
  dimensions: zhCN['label.analysis.slot.dimensions'],
  result: zhCN['label.analysis.slot.result'],
  range: zhCN['label.analysis.slot.range'],
};

/**
 * D71：行序即依赖序，行首是术语，每个术语一颗 ⓘ。
 *
 * 「本月 GMV（较上月同期）」没有维度：展开（这份定义声明了「商品」这条链）、
 * 指标、维度、范围，没有结果行；加一个维度，结果行出现在维度之后。每颗 ⓘ 用
 * Tab 走得到，聚焦就出那句说明，读屏读它的描述。
 */
export const TrayRowsInDependencyOrder: Story = {
  ...DisplayOrderAnalysis,
  name: '编辑区按依赖排行（D71）',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await openView(canvasElement, GMV_VIEW);
    const tray = await openTray(canvasElement);

    await expect(rows(tray)).toEqual([
      TERMS.elements,
      TERMS.metrics,
      TERMS.dimensions,
      TERMS.range,
    ]);
    // Each row is headed by its term alone — no 「看／按／在」, no hint.
    for (const term of Object.values(TERMS).filter(
      term => term !== TERMS.result,
    ))
      await expect(
        within(canvas.getByRole('region', { name: term })).getByRole(
          'heading',
          { level: 3 },
        ),
      ).toHaveTextContent(new RegExp(`^${term}$`));
    // The expansion row names the array, and says what is counted at its end.
    const elements = canvas.getByRole('region', { name: TERMS.elements });
    const expand = within(elements).getByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.expand-into', { name: '商品' }),
    });
    await expect(expand).toHaveTextContent(/^商品$/);
    await expect(
      elements.querySelector('[data-slot="counting-unit"]'),
    ).toBeVisible();
    // 「+ 添加」, named by what it adds.
    await expect(
      canvas.getByRole('button', { name: zhCN['label.analysis.add-metric'] }),
    ).toHaveTextContent(zhCN['label.analysis.add']);

    // The ⓘ beside 指标, reached from the keyboard: it opens on focus and
    // says the term; it is the term's sibling, not part of its heading.
    const metrics = canvas.getByRole('region', { name: TERMS.metrics });
    const tip = within(metrics).getByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.tip-of', {
        term: TERMS.metrics,
      }),
    });
    await expect(tip.closest('h3')).toBeNull();
    await expect(tip).toHaveAccessibleDescription(
      zhCN['label.analysis.tip.metrics'],
    );
    expand.focus();
    await userEvent.tab();
    await expect(tip).toHaveFocus();
    await expect(
      await tooltip(zhCN['label.analysis.tip.metrics']),
    ).toBeVisible();
    // Small to see, a full target to press (WCAG 2.5.8).
    const box = tip.getBoundingClientRect();
    await expect(box.width).toBeGreaterThanOrEqual(24);
    await expect(box.height).toBeGreaterThanOrEqual(24);

    // A dimension brings the result row, after the dimensions.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.add-group'] }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: '渠道' }),
    );
    await waitFor(() =>
      expect(rows(tray)).toEqual([
        TERMS.elements,
        TERMS.metrics,
        TERMS.dimensions,
        TERMS.result,
        TERMS.range,
      ]),
    );
    await answered(canvasElement);
  },
};

/**
 * D71：展开带走的指标，提示并可撤销。
 *
 * 两个 GMV 指标量的是订单的字段；展开到商品之后它们什么也不指，跟着这一步
 * 离开（D20 照旧），托盘底行上面出一句「展开 商品 后去掉了 2 个指标：本月至今
 * GMV、上月同期 GMV」，不弹确认框；「撤销」把问题恢复原样。
 */
export const ExpansionUndo: Story = {
  ...DisplayOrderAnalysis,
  name: '展开带走指标时可撤销（D71）',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await openView(canvasElement, GMV_VIEW);
    const tray = await openTray(canvasElement);
    const metricNames = () =>
      [
        ...tray.querySelectorAll<HTMLElement>(
          '[data-slot="metric-card"] [data-slot="card-name"]',
        ),
      ].map(name => name.textContent);
    const before = metricNames();
    await expect(before).toEqual(['本月至今 GMV', '上月同期 GMV']);

    await userEvent.click(
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.expand-into', {
          name: '商品',
        }),
      }),
    );

    const notice = await waitFor(() => {
      const found = tray.querySelector<HTMLElement>(
        '[data-slot="dropped-notice"]',
      );
      expect(found).toBeVisible();
      return found!;
    });
    const sentence = formatMessage(zhCN, 'label.analysis.dropped.expand', {
      name: '商品',
      what: formatMessage(zhCN, 'label.analysis.dropped.metrics', {
        count: 2,
        names: `本月至今 GMV${zhCN['label.filter.join']}上月同期 GMV`,
      }),
    });
    await expect(notice).toHaveTextContent(sentence);
    await expect(
      tray.querySelector('[data-slot="dropped-voice"]'),
    ).toHaveTextContent(sentence);
    // No confirmation: the step is made, and the counting unit says so.
    await expect(
      tray.querySelectorAll('[data-slot="element-card"]'),
    ).toHaveLength(1);
    await expect(
      tray.querySelector('[data-slot="counting-unit"]'),
    ).toHaveAttribute('data-emphasis', 'strong');
    await expect(metricNames()).not.toEqual(before);

    await userEvent.click(
      within(notice).getByRole('button', {
        name: zhCN['label.analysis.dropped.undo'],
      }),
    );
    await waitFor(() => expect(metricNames()).toEqual(before));
    await expect(
      tray.querySelectorAll('[data-slot="element-card"]'),
    ).toHaveLength(0);
    await expect(tray.querySelector('[data-slot="dropped-notice"]')).toBeNull();
    // Said to a reader, and still there after the notice's own render has
    // gone: the region is not emptied in the same breath (review round 1).
    const voice = tray.querySelector('[data-slot="dropped-voice"]');
    await waitFor(() =>
      expect(voice).toHaveTextContent(zhCN['label.analysis.dropped.undone']),
    );
    await answered(canvasElement);
    await expect(voice).toHaveTextContent(
      zhCN['label.analysis.dropped.undone'],
    );
  },
};

/**
 * D71：范围待应用时，提示与「应用」直接出现在底部。
 *
 * 自动运行开着，范围里加一个条件还没应用：底行直接写「范围里有未应用的条件
 * （含未填完的），点「应用」后才会一起运行。」，旁边「放弃范围修改」「清空
 * 范围」与强调的「应用」——不收进 ⓘ。平时那句自动运行说明在它自己的 ⓘ 里。
 */
export const RangeHeldInline: Story = {
  ...DisplayOrderAnalysis,
  name: '范围待应用时底部直说（D71）',
  play: async ({ canvasElement }) => {
    await openView(canvasElement, GMV_VIEW);
    const tray = await openTray(canvasElement);
    const actions = tray.querySelector<HTMLElement>(
      '[data-slot="analysis-tray-actions"]',
    )!;
    const apply = within(actions).getByRole('button', {
      name: zhCN['label.filter.apply'],
    });
    await expect(apply).toBeVisible();
    await expect(apply).toHaveAttribute('data-emphasis', 'quiet');
    await expect(
      actions.querySelector('[data-slot="auto-run-hint"]'),
    ).toBeNull();
    await expect(
      within(actions).getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.tip-of', {
          term: zhCN['label.analysis.auto-run'],
        }),
      }),
    ).toHaveAccessibleDescription(zhCN['label.analysis.auto-run-hint']);

    const range = within(tray).getByRole('region', { name: TERMS.range });
    await userEvent.click(
      within(range).getByRole('button', { name: zhCN['label.filter.add'] }),
    );
    const picker = await screen.findByRole('dialog', {
      name: zhCN['label.filter.pick-fields'],
    });
    await userEvent.click(within(picker).getAllByRole('checkbox')[0]!);
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );

    const held = await waitFor(() => {
      const found = actions.querySelector<HTMLElement>(
        '[data-slot="auto-run-hint"]',
      );
      expect(found).toBeVisible();
      return found!;
    });
    await expect(held).toHaveTextContent(zhCN['label.analysis.auto-run-held']);
    for (const name of [
      zhCN['label.filter.range.discard'],
      zhCN['label.filter.range.clear'],
    ])
      await expect(within(actions).getByRole('button', { name })).toBeVisible();
    await expect(apply).toHaveAttribute('data-emphasis', 'primary');
  },
};

/**
 * D71：指标可拖动排序，派生指标不越过它引用的指标。
 *
 * 「分渠道」那份有三个指标：本月至今、上月同期，和按两者计算的「GMV 变化」。
 * 在「GMV 变化」的手柄上按 ←：它只能排在它引用的指标之后，所以不动，下面一行
 * 说为什么；把「本月至今」按 → 挪到最后：停在「GMV 变化」之前。之后删掉
 * 「上月同期」，「GMV 变化」跟着走，托盘说一声并可撤销——任何编辑都一样。
 */
export const MetricReorder: Story = {
  ...DisplayOrderAnalysis,
  name: '指标拖动排序（D71）',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await openView(canvasElement, CHANNEL_VIEW);
    const tray = await openTray(canvasElement);
    const names = () =>
      [
        ...tray.querySelectorAll<HTMLElement>(
          '[data-slot="metric-card"] [data-slot="card-name"]',
        ),
      ].map(name => name.textContent);
    const handle = (name: string) =>
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.chart.reorder', { name }),
      });
    const note = () =>
      tray.querySelector<HTMLElement>('[data-slot="metric-order-note"]');
    await expect(names()).toEqual(['本月至今', '上月同期', 'GMV 变化']);

    handle('GMV 变化').focus();
    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() =>
      expect(note()).toHaveTextContent(
        formatMessage(zhCN, 'label.analysis.move-stop.after', {
          name: 'GMV 变化',
          other: '上月同期',
        }),
      ),
    );
    await expect(names()).toEqual(['本月至今', '上月同期', 'GMV 变化']);

    handle('本月至今').focus();
    await userEvent.keyboard('{End}');
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() =>
      expect(names()).toEqual(['上月同期', '本月至今', 'GMV 变化']),
    );
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() =>
      expect(note()).toHaveTextContent(
        formatMessage(zhCN, 'label.analysis.move-stop.before', {
          name: '本月至今',
          other: 'GMV 变化',
        }),
      ),
    );
    await expect(names()).toEqual(['上月同期', '本月至今', 'GMV 变化']);

    // A metric removed takes what reads it along, and says so with an undo.
    await userEvent.click(
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.remove-metric', {
          name: '上月同期',
        }),
      }),
    );
    const notice = await waitFor(() => {
      const found = tray.querySelector<HTMLElement>(
        '[data-slot="dropped-notice"]',
      );
      expect(found).toBeVisible();
      return found!;
    });
    await expect(notice).toHaveTextContent(
      formatMessage(zhCN, 'label.analysis.dropped.edit', {
        what: formatMessage(zhCN, 'label.analysis.dropped.metric', {
          names: 'GMV 变化',
        }),
      }),
    );
    await expect(names()).toEqual(['本月至今']);
    // The one left is the last: its ✕ is held, and says why.
    const last = canvas.getByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.remove-metric', {
        name: '本月至今',
      }),
    });
    await expect(last).toHaveAttribute('aria-disabled', 'true');
    await expect(last).toHaveAccessibleDescription(
      zhCN['label.analysis.remove-last'],
    );

    await userEvent.click(
      within(notice).getByRole('button', {
        name: zhCN['label.analysis.dropped.undo'],
      }),
    );
    await waitFor(() =>
      expect(names()).toEqual(['上月同期', '本月至今', 'GMV 变化']),
    );
    await answered(canvasElement);
  },
};

/**
 * D72：图型的选项从选中卡片的右上角进。
 *
 * 「本月 GMV（较上月同期）」画的是指标卡：「可视化」里选中的「指标卡」右上角
 * 一颗滑杆图标，名字与悬停都是「指标卡选项」，别的卡片没有；列表底下不再有
 * 「指标卡选项 ›」那一行。按它进选项页，「返回图型」回来焦点落回图标。
 */
export const ChartOptionsCorner: Story = {
  ...DisplayOrderAnalysis,
  name: '图型选项在卡片右上角（D72）',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await openView(canvasElement, GMV_VIEW);
    await userEvent.click(
      await canvas.findByRole('button', {
        name: zhCN['label.analysis.visualize'],
      }),
    );
    const picker = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart-picker"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    const icons = picker.querySelectorAll<HTMLElement>(
      '[data-slot="chart-options-open"]',
    );
    await expect(icons).toHaveLength(1);
    const icon = icons[0]!;
    const name = formatMessage(zhCN, 'label.chart.options', {
      name: zhCN['label.chart.type.metric'],
    });
    await expect(icon).toHaveAccessibleName(name);
    const tile = picker.querySelector<HTMLElement>(
      '[data-slot="chart-tile"][aria-checked="true"]',
    )!;
    await expect(tile).toHaveAttribute('data-chart-type', 'metric');
    await expect(tile.contains(icon)).toBe(false);
    const own = tile.getBoundingClientRect();
    const box = icon.getBoundingClientRect();
    await expect(own.right - box.right).toBeLessThan(6);
    await expect(box.top - own.top).toBeLessThan(6);
    await expect(box.top).toBeGreaterThanOrEqual(own.top - 0.5);
    // Nothing under the list leads there any more.
    await expect(
      [...picker.querySelectorAll('button')].filter(button =>
        button.textContent?.includes(name),
      ),
    ).toEqual([]);

    await userEvent.hover(icon);
    await expect(await tooltip(name)).toBeVisible();
    await userEvent.click(icon);
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="chart-options"]'),
      ).not.toBeNull(),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.chart.options-back'] }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="chart-options-open"]'),
      ).toHaveFocus(),
    );
  },
};
