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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  LogRefused as DisplayLogRefused,
  LogScale as DisplayLogScale,
  PieOfAnAverage as DisplayPieOfAnAverage,
  ScatterAxes as DisplayScatterAxes,
  SplitCrowded as DisplaySplitCrowded,
  SplitOther as DisplaySplitOther,
} from './AnalysisDisplay.stories.js';
import { chartsDrawn, legendNames } from './chartDom.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/显示收口/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see AnalysisReferences.test).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const frameOf = (root: HTMLElement) =>
  root.querySelector<HTMLElement>('[data-slot="chart"]')!;

const plotOf = (root: HTMLElement) =>
  root.querySelector<HTMLElement>('[data-slot="chart-plot"]')!;

/** Every piece of text the library drew in the plot. */
const plotTexts = (root: HTMLElement) =>
  [...plotOf(root).querySelectorAll('svg text')].map(text =>
    (text.textContent ?? '').trim(),
  );

/** Opens the chart's options on one of their pages. */
async function optionsPage(canvasElement: HTMLElement, tab: string) {
  const canvas = within(canvasElement);
  await userEvent.click(
    canvas.getByRole('button', { name: zhCN['label.analysis.visualize'] }),
  );
  await userEvent.click(
    await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart-options-open"]',
      );
      if (!found) throw new Error('没有选项按钮');
      return found;
    }),
  );
  const options = await waitFor(() => {
    const found = canvasElement.querySelector<HTMLElement>(
      '[data-slot="chart-options"]',
    );
    if (!found) throw new Error('选项没有打开');
    return found;
  });
  await userEvent.click(within(options).getByRole('tab', { name: tab }));
  return options;
}

/**
 * Every file the page hands the browser, caught before it is saved: the
 * blob and the name it goes under. A PNG's SVG passes through
 * `createObjectURL` too, on its way onto the canvas; that one is kept only
 * as a URL the image can load.
 */
function catchDownloads() {
  const files: { blob: Blob; name: string }[] = [];
  const create = URL.createObjectURL.bind(URL);
  let last: Blob | undefined;
  const originalCreate = URL.createObjectURL;
  const originalClick = HTMLAnchorElement.prototype.click;
  URL.createObjectURL = (blob: Blob | MediaSource) => {
    if (blob instanceof Blob) last = blob;
    return create(blob);
  };
  HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement) {
    if (last && this.download) files.push({ blob: last, name: this.download });
  };
  return {
    files,
    restore() {
      URL.createObjectURL = originalCreate;
      HTMLAnchorElement.prototype.click = originalClick;
    },
  };
}

/**
 * 对数刻度：十二个城市、从拉萨的 ¥640 到上海的 ¥52 万，左轴按 10 的幂排；
 * 图名之后的一句话说共几组、最高、最低（读屏摘要）。
 */
export const LogScaleAndSentence: Story = {
  ...DisplayLogScale,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await expect(frameOf(canvasElement)).toHaveAttribute('data-log', 'left');
    const plot = plotOf(canvasElement);
    const sentence = document.getElementById(
      plot.getAttribute('aria-describedby') ?? '',
    );
    await expect(sentence?.textContent).toMatch(
      /^共 12 组，最高 上海 .+，最低 拉萨 .+。$/,
    );
  },
};

/**
 * 拉萨一退一进合计为 0：已存的对数刻度按线性画，图上方写原因；坐标轴页里
 * 「对数」下面写同一句为什么。
 */
export const LogRefusedSaysWhy: Story = {
  ...DisplayLogRefused,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await expect(frameOf(canvasElement)).not.toHaveAttribute('data-log');
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="chart-gap-note"]'),
      ).toHaveTextContent(
        '左轴按线性刻度画：轴上有 0 或负数，对数刻度放不下。',
      ),
    );
    const options = await optionsPage(
      canvasElement,
      zhCN['label.chart.tab.axes'],
    );
    await expect(options).toHaveTextContent(
      zhCN['label.chart.axis-scale.not-positive'],
    );
  },
};

/**
 * 散点：纵轴写分析师起的标题、按对数排；指针在绘图区里，两根轴上各写出
 * 指针所在处的数（十字准星）。
 */
export const ScatterCrosshair: Story = {
  ...DisplayScatterAxes,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await expect(frameOf(canvasElement)).toHaveAttribute('data-log', 'x y');
    await expect(plotTexts(canvasElement)).toContain('销售额（对数）');
    const before = plotTexts(canvasElement).length;
    const plot = plotOf(canvasElement);
    const svg = plot.querySelector('svg')!;
    const box = plot.getBoundingClientRect();
    svg.dispatchEvent(
      new MouseEvent('mousemove', {
        bubbles: true,
        clientX: box.left + box.width / 2,
        clientY: box.top + box.height / 2,
      }),
    );
    // One label on each axis where the pointer stands.
    await waitFor(() =>
      expect(plotTexts(canvasElement).length).toBeGreaterThanOrEqual(
        before + 2,
      ),
    );
  },
};

/**
 * 十二个城市的拆分：画销售额最大的七个与一条「其他」（Q56）；读屏表也有
 * 「其他」这一列。
 */
export const SplitFoldsIntoOther: Story = {
  ...DisplaySplitOther,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => {
      const names = legendNames(canvasElement);
      expect(names).toHaveLength(8);
      expect(names.at(-1)).toBe(zhCN['label.chart.other']);
    });
    await expect(legendNames(canvasElement).slice(0, 3)).toEqual([
      '上海',
      '北京',
      '深圳',
    ]);
    const headers = [
      ...canvasElement.querySelectorAll('[data-slot="chart-reading"] thead th'),
    ].map(cell => cell.textContent);
    await expect(headers).toContain(zhCN['label.chart.other']);
  },
};

/** 平均额拆成十二条：不折叠，显示页说颜色会重复、建议改用热力图或表格。 */
export const SplitCrowdedSaysSo: Story = {
  ...DisplaySplitCrowded,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const options = await optionsPage(
      canvasElement,
      zhCN['label.chart.tab.display'],
    );
    await expect(
      options.querySelector('[data-slot="chart-crowded"]'),
    ).toHaveTextContent('超过 8 条，颜色会重复，建议改用热力图或表格。');
  },
};

/** 只有平均额时饼图置灰，磁贴下写「占比只对可加的指标成立」（Q9）。 */
export const PieOfAnAverageIsGreyed: Story = {
  ...DisplayPieOfAnAverage,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await userEvent.click(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.analysis.visualize'],
      }),
    );
    const pie = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-chart-type="pie"]',
      );
      if (!found) throw new Error('没有饼图磁贴');
      return found;
    });
    await expect(pie).toHaveAttribute('aria-disabled', 'true');
    await expect(pie).toHaveTextContent(zhCN['chart.fit.needs-share']);
  },
};

/**
 * 导出图片（Q58）：「导出」是一个菜单，「导出数据…」旁边是 PNG 与 SVG。SVG
 * 带标题、图例与一行条件；PNG 是它画到两倍大小的画布上。控制台一句不说。
 */
export const ExportsThePicture: Story = {
  ...DisplaySplitOther,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const errors: unknown[] = [];
    const error = console.error;
    const warn = console.warn;
    console.error = (...args: unknown[]) => errors.push(args);
    console.warn = (...args: unknown[]) => errors.push(args);
    const caught = catchDownloads();
    try {
      const canvas = within(canvasElement);
      const open = async () => {
        // The menu a pick closed has gone before the next is opened.
        await waitFor(() =>
          expect(document.querySelector('[role="menu"]')).toBeNull(),
        );
        await userEvent.click(
          canvas.getByRole('button', { name: zhCN['label.export.title'] }),
        );
        return waitFor(() => {
          const menu = document.querySelector<HTMLElement>('[role="menu"]');
          if (!menu) throw new Error('菜单没有打开');
          return menu;
        });
      };
      let menu = await open();
      await expect(
        within(menu)
          .getAllByRole('menuitem')
          .map(item => item.textContent),
      ).toEqual([
        zhCN['label.export.data'],
        zhCN['label.export.image-png'],
        zhCN['label.export.image-svg'],
      ]);
      await userEvent.click(
        within(menu).getByRole('menuitem', {
          name: zhCN['label.export.image-svg'],
        }),
      );
      await waitFor(() => expect(caught.files).toHaveLength(1));
      const svg = caught.files[0]!;
      await expect(svg.name).toMatch(/^各城市销售额-\d{4}-\d{2}-\d{2}\.svg$/);
      const text = await svg.blob.text();
      for (const part of ['各城市销售额', '条件：', '上海', '其他'])
        await expect(text).toContain(part);

      menu = await open();
      await userEvent.click(
        within(menu).getByRole('menuitem', {
          name: zhCN['label.export.image-png'],
        }),
      );
      await waitFor(() => expect(caught.files).toHaveLength(2));
      const png = caught.files[1]!;
      await expect(png.name).toMatch(/\.png$/);
      await expect(png.blob.type).toBe('image/png');
      const bitmap = await createImageBitmap(png.blob);
      const picture = new DOMParser()
        .parseFromString(text, 'image/svg+xml')
        .documentElement.getAttribute('width');
      await expect(bitmap.width).toBe(Number(picture) * 2);
      await expect(errors).toEqual([]);
    } finally {
      caught.restore();
      console.error = error;
      console.warn = warn;
    }
  },
};
