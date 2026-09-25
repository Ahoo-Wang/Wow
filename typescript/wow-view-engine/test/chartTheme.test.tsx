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

import { readdirSync, readFileSync } from 'node:fs';
import ts from 'typescript';
import { join } from 'node:path';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ChartData, ChartSpec, NumberFormat } from '../src/index.js';
import {
  AnalysisChart,
  useSurfaceTokens,
  ViewSurface,
} from '../src/ui/index.js';
import {
  CHART_FALLBACK,
  CHART_TOKENS,
  type ChartTheme,
  concreteColor,
  readChartTheme,
} from '../src/ui/charts/theme.js';
import { CHART_COLOR_SLOTS } from '../src/model/index.js';
import { compactFormat, formatNumber } from '../src/ui/display.js';
import { measureText } from '../src/ui/charts/measure.js';
import { declared } from './fixtures/themeTokens';
import { merged } from '../src/ui/charts/optionMerge.js';
import { ChartLegend } from '../src/ui/charts/ChartLegend.js';

const STYLESHEET = readFileSync(
  join(import.meta.dirname, '..', 'src', 'styles.css'),
  'utf8',
);

afterEach(() => {
  cleanup();
  document.head.querySelectorAll('style[data-test]').forEach(s => s.remove());
});

/** A stylesheet for one test, as a host would ship its theme. */
function sheet(css: string) {
  const style = document.createElement('style');
  style.dataset.test = '';
  style.textContent = css;
  document.head.append(style);
}

describe('readChartTheme: the stylesheet read back as colours', () => {
  it('converts every token to rgb, whatever space it was written in', () => {
    const ground = document.createElement('div');
    ground.style.backgroundColor = 'rgb(20, 20, 20)';
    ground.style.setProperty('--chart-1', 'oklch(0.6 0.15 250)');
    ground.style.setProperty('--foreground', '#fafafa');
    ground.style.setProperty('--_fve-chart-grid', 'oklch(1 0 0 / 20%)');
    ground.style.setProperty('--brand', 'hsl(0 100% 50%)');
    const chart = document.createElement('div');
    ground.append(chart);
    document.body.append(ground);

    const theme = readChartTheme(chart);
    expect(theme.palette[0]).toMatch(/^rgb\(/);
    // A slot the stylesheet does not set falls back to the light theme's.
    expect(theme.palette[1]).toBe('rgb(235, 104, 52)');
    expect(theme.foreground).toBe('rgb(250, 250, 250)');
    expect(theme.grid.color).toBe('rgba(255, 255, 255, 0.2)');
    // What the chart stands on is the first ancestor with paint.
    expect(theme.ground).toBe('rgb(20, 20, 20)');

    // A pinned colour: a slot, another token, or any CSS colour.
    expect(theme.resolve('var(--chart-1)')).toBe(theme.palette[0]);
    expect(theme.resolve('var(--chart-10)')).toBe(theme.palette[1]);
    expect(theme.resolve('var(--brand)')).toBe('rgb(255, 0, 0)');
    expect(theme.resolve('#0f766e')).toBe('rgb(15, 118, 110)');
    // Read once and kept.
    expect(theme.resolve('#0f766e')).toBe('rgb(15, 118, 110)');
    // Not a colour: the first slot rather than nothing.
    expect(theme.resolve('var(--unset)')).toBe(theme.palette[0]);
    expect(theme.resolve('banana')).toBe(theme.palette[0]);

    expect(readChartTheme(chart).key).toBe(theme.key);
    ground.remove();
  });

  it('stands on white where nothing under it is painted', () => {
    const chart = document.createElement('div');
    document.body.append(chart);
    expect(readChartTheme(chart).ground).toBe('rgb(255, 255, 255)');
    chart.remove();
  });

  it('reads exactly the tokens the registry says a chart reads', () => {
    // `ViewSurface` watches `CHART_TOKENS` — the registry's chart set — to
    // redraw a chart when the theme moves, so a token the chart reads and
    // the registry leaves out would change with no redraw. What is read is
    // recorded off the computed style itself, then held to the list.
    const chart = document.createElement('div');
    document.body.append(chart);
    const read = new Set<string>();
    const original = CSSStyleDeclaration.prototype.getPropertyValue;
    const spy = vi
      .spyOn(CSSStyleDeclaration.prototype, 'getPropertyValue')
      .mockImplementation(function (this: CSSStyleDeclaration, name) {
        if (name.startsWith('--')) read.add(name);
        return original.call(this, name);
      });
    readChartTheme(chart).resolve('var(--chart-2)');
    spy.mockRestore();
    chart.remove();
    expect([...read].sort()).toEqual([...CHART_TOKENS].sort());
    expect(CHART_TOKENS.slice(0, CHART_COLOR_SLOTS)).toEqual(
      Array.from({ length: CHART_COLOR_SLOTS }, (_, i) => `--chart-${i + 1}`),
    );
  });

  it('falls back to the stylesheet’s own light tokens, not a copy of its own (5A)', () => {
    // The fallback is a second spelling of the light token block, so it is
    // read against that block: a slot retuned in `styles.css` and not here
    // turns this red instead of drawing jsdom's charts in last year's blue.
    const start = STYLESHEET.indexOf('\n.fve-root,\n.fve-tokens {');
    // One declaration a line, whatever the formatter wrapped.
    const lightBlock = STYLESHEET.slice(
      start,
      STYLESHEET.indexOf('\n}\n', start),
    )
      .replace(/\(\s+/g, '(')
      .replace(/,\s+/g, ', ')
      .replace(/\s+\)/g, ')');
    const light = (token: string) => {
      const name = token.slice(2);
      const found = new RegExp(
        `\\n  ${token}: var\\(--fve-${name}, (?:var\\(--_fve-brand-${name}, )?var\\(--fvp-${name}, (oklch\\([^)]*\\))\\)\\)`,
      ).exec(lightBlock)?.[1];
      if (!found) throw new Error(`no light ${token} in styles.css`);
      return concreteColor(found);
    };
    expect(CHART_FALLBACK.palette).toEqual(
      CHART_TOKENS.slice(0, CHART_COLOR_SLOTS).map(light),
    );
    expect(CHART_FALLBACK.foreground).toBe(light('--foreground'));
    expect(CHART_FALLBACK.muted).toBe(light('--muted-foreground'));
    expect(CHART_FALLBACK.ground).toBe(light('--background'));
    // The two colour roles fall back to those tokens (`test/themeRoles`).
    expect(CHART_FALLBACK.grid.color).toBe(light('--border'));
    expect(CHART_FALLBACK.axis.color).toBe(light('--muted-foreground'));
  });

  it('holds the built-in look to the chart roles’ own values in styles.css (S5)', () => {
    // Every length and number a chart is drawn with, as the light block
    // declares it: worked out here as the browser would, at a 16px root.
    const block = declared('neutral', 'light', 'semantic', {});
    const role = (name: string) => evaluate(`var(--_fve-chart-${name})`);
    function evaluate(text: string): number | undefined {
      const value = text.trim();
      const variable = /^var\((--[\w-]+)\)$/.exec(value)?.[1];
      if (variable) {
        const found = block.get(variable);
        return found === undefined ? undefined : evaluate(found);
      }
      const call = /^(calc|min)\((.*)\)$/.exec(value);
      if (call) {
        const parts = split(call[2]);
        if (call[1] === 'min')
          return Math.min(...parts.map(part => evaluate(part)!));
        const [left, op, right] = parts[0].split(/ ([-+*]) /);
        const a = evaluate(left)!;
        const b = evaluate(right)!;
        return op === '-' ? a - b : op === '+' ? a + b : a * b;
      }
      const unit = /^(-?[\d.]+)(px|rem)?$/.exec(value);
      if (!unit) throw new Error(`cannot work out ${value}`);
      return Number(unit[1]) * (unit[2] === 'rem' ? 16 : 1);
    }
    function split(args: string): string[] {
      const parts: string[] = [];
      let depth = 0;
      let start = 0;
      for (let at = 0; at < args.length; at++) {
        if (args[at] === '(') depth++;
        else if (args[at] === ')') depth--;
        else if (args[at] === ',' && depth === 0) {
          parts.push(args.slice(start, at).trim());
          start = at + 1;
        }
      }
      return [...parts, args.slice(start).trim()];
    }
    expect({
      grid: role('grid-width'),
      text: [role('text-size'), role('label-size')],
      line: [role('line-width'), role('area-opacity')],
      bar: [role('bar-radius'), role('bar-min-width'), role('bar-max-width')],
      slice: role('slice-border'),
    }).toEqual({
      grid: CHART_FALLBACK.grid.width,
      text: [CHART_FALLBACK.text.size, CHART_FALLBACK.text.labelSize],
      line: [CHART_FALLBACK.line.width, CHART_FALLBACK.line.areaOpacity],
      // No lower bound: the role has no built-in value.
      bar: [CHART_FALLBACK.bar.radius, undefined, CHART_FALLBACK.bar.maxWidth],
      slice: CHART_FALLBACK.slice.border,
    });
    // The corner follows the style's `radius`: a square style (`0`) has
    // square bars, with no word from it.
    block.set('--radius', '0rem');
    expect(role('bar-radius')).toBe(0);
  });

  it('asks the browser for a colour it cannot read itself (T1)', () => {
    // A preset may derive a token — `color-mix()`, relative colour syntax —
    // and a custom property reads back as that expression, which the parser
    // cannot read (themes.md 2.5). A hidden probe under the chart takes it as
    // a real colour property and reads the computed value. jsdom computes no
    // colour, so the browser's answer is stood in for here;
    // `DerivedTokensDrawn` in `ThemeTokens.test.stories.tsx` asks a real one.
    const chart = document.createElement('div');
    chart.style.setProperty('--chart-1', 'color-mix(in oklab, red 50%, blue)');
    chart.style.setProperty('--_fve-rise', 'oklch(from red l c h)');
    chart.style.setProperty(
      '--_fve-fall',
      'color-mix(in oklab, nothing 50%, red)',
    );
    document.body.append(chart);
    const computed = window.getComputedStyle;
    const asked: string[] = [];
    const answers: Record<string, string> = {
      'var(--chart-1)': 'oklab(0.54 0.1 -0.1)',
      'var(--_fve-rise)': 'rgb(255, 0, 0)',
      // What a probe whose token computes to no colour at all shows.
      'var(--_fve-fall)': 'rgba(0, 0, 0, 0)',
    };
    vi.spyOn(window, 'getComputedStyle').mockImplementation(element => {
      const probe = (element as HTMLElement).style?.backgroundColor ?? '';
      if (element.parentElement === chart && probe in answers) {
        asked.push(probe);
        expect((element as HTMLElement).hidden).toBe(true);
        return { backgroundColor: answers[probe] } as CSSStyleDeclaration;
      }
      return computed(element);
    });

    const theme = readChartTheme(chart);
    expect(theme.palette[0]).toMatch(/^rgb\(/);
    expect(theme.palette[0]).not.toBe(CHART_FALLBACK.palette[0]);
    expect(theme.resolve('var(--_fve-rise)')).toBe('rgb(255, 0, 0)');
    // Nothing a colour: the first slot, as for any colour that is none.
    expect(theme.resolve('var(--_fve-fall)')).toBe(theme.palette[0]);
    // A token the parser reads is never probed, and every probe is gone.
    expect(theme.foreground).toBe(CHART_FALLBACK.foreground);
    expect(asked).toEqual(expect.arrayContaining(Object.keys(answers)));
    expect(chart.children).toHaveLength(0);
    chart.remove();
  });

  describe('the chart roles, each read as the browser computes it (S5)', () => {
    /**
     * A chart under a host that set its roles, with the browser's answer
     * for each probe stood in (jsdom computes no length or colour): what the
     * probe was asked is recorded, keyed by the property it went through.
     */
    function chartWith(
      roles: Record<string, string>,
      answers: Record<string, string>,
    ) {
      const chart = document.createElement('div');
      for (const [name, value] of Object.entries(roles))
        chart.style.setProperty(`--_fve-chart-${name}`, value);
      document.body.append(chart);
      const asked: string[] = [];
      const computed = window.getComputedStyle;
      const spy = vi
        .spyOn(window, 'getComputedStyle')
        .mockImplementation(element => {
          const probe = element as HTMLElement;
          if (element.parentElement === chart && probe.hidden) {
            const question =
              probe.style.getPropertyValue('width') ||
              probe.style.getPropertyValue('background-color');
            asked.push(question);
            return {
              width: answers[question] ?? 'auto',
              backgroundColor: answers[question] ?? 'rgba(0, 0, 0, 0)',
            } as CSSStyleDeclaration;
          }
          return computed(element);
        });
      const theme = readChartTheme(chart);
      spy.mockRestore();
      expect(chart.children).toHaveLength(0);
      chart.remove();
      return { theme, asked };
    }

    it('reads each colour role, deriving ones through the probe', () => {
      const { theme, asked } = chartWith(
        {
          grid: 'color-mix(in oklab, red 50%, blue)',
          axis: 'rgb(1, 2, 3)',
        },
        { 'var(--_fve-chart-grid)': 'rgb(128, 0, 128)' },
      );
      expect(theme.grid.color).toBe('rgb(128, 0, 128)');
      // Written plainly, read as written: never probed.
      expect(theme.axis.color).toBe('rgb(1, 2, 3)');
      expect(asked).toEqual(['var(--_fve-chart-grid)']);
    });

    it.each([
      ['grid-width', (t: ChartTheme) => t.grid.width],
      ['text-size', (t: ChartTheme) => t.text.size],
      ['label-size', (t: ChartTheme) => t.text.labelSize],
      ['line-width', (t: ChartTheme) => t.line.width],
      ['bar-radius', (t: ChartTheme) => t.bar.radius],
      ['bar-min-width', (t: ChartTheme) => t.bar.minWidth],
      ['bar-max-width', (t: ChartTheme) => t.bar.maxWidth],
      ['slice-border', (t: ChartTheme) => t.slice.border],
    ])(
      'reads the length %s: plain as written, worked out as a width',
      (name, of) => {
        expect(of(chartWith({ [name]: '3px' }, {}).theme)).toBe(3);
        const variable = `var(--_fve-chart-${name})`;
        const { theme, asked } = chartWith(
          { [name]: 'calc(0.5rem - 1px)' },
          { [variable]: '7px' },
        );
        expect(of(theme)).toBe(7);
        expect(asked).toEqual([variable]);
      },
    );

    it('reads a number as a width of that many pixels, never as an opacity', () => {
      expect(
        chartWith({ 'area-opacity': '0.35' }, {}).theme.line.areaOpacity,
      ).toBe(0.35);
      const variable = 'calc(var(--_fve-chart-area-opacity) * 1px)';
      const { theme, asked } = chartWith(
        { 'area-opacity': 'calc(0.1 + 0.2)' },
        { [variable]: '0.3px' },
      );
      expect(theme.line.areaOpacity).toBe(0.3);
      expect(asked).toEqual([variable]);
    });

    it('falls back to the built-in look for a role that is no such value', () => {
      const { theme } = chartWith(
        {
          grid: 'banana',
          'grid-width': 'thick',
          'text-size': '0px',
          'line-width': '-2px',
          'area-opacity': '3',
          'bar-min-width': '0px',
          'bar-max-width': '50%',
        },
        {},
      );
      expect(theme.grid).toEqual(CHART_FALLBACK.grid);
      // A size of nothing would draw no text.
      expect(theme.text.size).toBe(CHART_FALLBACK.text.size);
      expect(theme.line).toEqual(CHART_FALLBACK.line);
      // No lower bound, and a bound the probe cannot make pixels of.
      expect(theme.bar).toEqual(CHART_FALLBACK.bar);
      expect(theme.bar).not.toHaveProperty('minWidth');
    });

    it('is a new theme when any role moves, so the chart redraws', () => {
      const plain = chartWith({}, {}).theme.key;
      for (const [name, value] of [
        ['grid', 'rgb(1, 1, 1)'],
        ['line-width', '3px'],
        ['bar-radius', '0px'],
        ['area-opacity', '0.5'],
      ])
        expect(chartWith({ [name]: value }, {}).theme.key, name).not.toBe(
          plain,
        );
    });
  });

  it('draws with no size, width or corner of its own: every one is the theme’s (S5)', () => {
    // theme-architecture.md 6.6: an option builder reads its look from the
    // theme. A number written in place as a font size, a corner, or a line's
    // width would be a look no theme reaches — walked on the source, as
    // `test/architecture.test.ts` walks imports. A named constant is a
    // decision about the chart's geometry, said where it is made.
    const directory = join(import.meta.dirname, '..', 'src', 'ui', 'charts');
    const builders = readdirSync(directory).filter(name =>
      /(?:Option|Marks|Zoom|Tooltip)\.ts$/.test(name),
    );
    expect(builders.length).toBeGreaterThan(15);
    const found: string[] = [];
    for (const file of builders) {
      const text = readFileSync(join(directory, file), 'utf8');
      const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest);
      const literal = (node: ts.Node): boolean =>
        ts.isNumericLiteral(node) ||
        (ts.isArrayLiteralExpression(node) && node.elements.some(literal));
      const visit = (node: ts.Node, lineStyle: boolean) => {
        if (ts.isPropertyAssignment(node) && ts.isIdentifier(node.name)) {
          const name = node.name.text;
          const measured =
            name === 'fontSize' ||
            name === 'borderRadius' ||
            name === 'barMaxWidth' ||
            name === 'barMinWidth' ||
            (lineStyle && name === 'width');
          if (measured && literal(node.initializer))
            found.push(
              `${file}:${source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1} ${name}`,
            );
          ts.forEachChild(node, child => visit(child, name === 'lineStyle'));
          return;
        }
        ts.forEachChild(node, child => visit(child, lineStyle));
      };
      visit(source, false);
    }
    expect(found).toEqual([]);
  });

  it('reads nothing into a colour that is none', () => {
    expect(concreteColor(undefined)).toBeUndefined();
    expect(concreteColor('  ')).toBeUndefined();
    expect(concreteColor('nope')).toBeUndefined();
    expect(concreteColor('oklch(0.5753 0.1626 255.532deg)')).toBe(
      'rgb(42, 120, 214)',
    );
  });
});

describe('a number written short', () => {
  const short = (value: number, locale: string, currency?: boolean) =>
    formatNumber(
      value,
      compactFormat(
        currency
          ? {
              style: 'currency',
              currency: 'CNY',
              minimumFractionDigits: 2,
              maximumFractionDigits: 2,
            }
          : undefined,
      ),
      locale,
    );

  it('follows the language: 万 and 亿 in Chinese, K and M in English', () => {
    expect(short(11_100_000, 'zh-CN')).toBe('1,110万');
    expect(short(123_456_789, 'zh-CN')).toBe('1.23亿');
    expect(short(11_100_000, 'en')).toBe('11.1M');
    expect(short(4_200, 'en')).toBe('4.2K');
    // The column's currency stays; the decimals it asked of a whole number
    // do not.
    expect(short(11_100_000, 'zh-CN', true)).toBe('¥1,110万');
    expect(short(950, 'zh-CN', true)).toBe('¥950');
  });

  it('keeps three significant digits, adding no zero and inventing none (audit P1-4)', () => {
    // A donut's ¥10,230 is not 「¥1万」, and a week of 49,993 / 49,818 /
    // 49,077 is not three 「4.9万」s.
    expect(short(10_230, 'zh-CN', true)).toBe('¥1.02万');
    expect(short(10_230, 'en', true)).toBe('CN¥10.2K');
    expect(
      [49_993, 49_818, 49_077, 659_000, 1_831_229].map(value =>
        short(value, 'zh-CN'),
      ),
    ).toEqual(['5万', '4.98万', '4.91万', '65.9万', '183万']);
    expect(
      [49_993, 49_818, 49_077, 659_000, 1_831_229].map(value =>
        short(value, 'en'),
      ),
    ).toEqual(['50K', '49.8K', '49.1K', '659K', '1.83M']);
    // No zero added to make up three (「5万」, not 「5.00万」), and none
    // invented where the whole part is longer: 「1,235万」, not 「1,230万」.
    expect(short(50_000, 'zh-CN')).toBe('5万');
    expect(short(12_345_678, 'zh-CN')).toBe('1,235万');
    // Small and fractional numbers keep their digits.
    expect(short(12.345, 'en')).toBe('12.3');
    expect(short(0.5, 'zh-CN', true)).toBe('¥0.5');
  });

  it('groups a Chinese figure under 万 as the table does (audit P2-3)', () => {
    const chinese = (value: number, format?: NumberFormat) =>
      formatNumber(value, compactFormat(format), 'zh-CN');
    expect(short(4_880, 'zh-CN', true)).toBe('¥4,880');
    // Written whole, not rounded to three digits: there is no shorter word.
    expect(chinese(4_885)).toBe('4,885');
    // A field whose numbers are names keeps them ungrouped.
    expect(chinese(2_026, { useGrouping: false })).toBe('2026');
  });
});

describe('measureText', () => {
  it('estimates where there is no canvas: a CJK character wider', () => {
    expect(measureText('订单')).toBeGreaterThan(measureText('ab'));
  });
});

const data: ChartData = {
  type: 'cartesian',
  chart: 'bar',
  points: [
    { x: 'CN', values: { orders: 2 } },
    { x: 'JP', values: { orders: 1 } },
  ],
  series: [{ key: 'orders', label: 'orders', metric: 'orders' }],
};
const spec: ChartSpec = {
  type: 'bar',
  cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
};

const fills = (container: HTMLElement) =>
  [
    ...container.querySelectorAll('[data-slot="chart-plot"] svg path[fill]'),
  ].map(path => path.getAttribute('fill'));

describe('EChart: the drawing bound to its element', () => {
  it('redraws in the other mode’s colours, in place', () => {
    sheet(`
      .fve-root[data-theme='light'] { --chart-1: rgb(1, 2, 3); }
      .fve-root[data-theme='dark'] { --chart-1: rgb(200, 201, 202); }
    `);
    const { container, rerender } = render(
      <ViewSurface theme="light">
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    const svg = container.querySelector('[data-slot="chart-plot"] svg');
    expect(fills(container)).toContain('rgb(1, 2, 3)');

    rerender(
      <ViewSurface theme="dark">
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    expect(fills(container)).toContain('rgb(200, 201, 202)');
    expect(fills(container)).not.toContain('rgb(1, 2, 3)');
    // The same drawing, not a new one.
    expect(container.querySelector('[data-slot="chart-plot"] svg')).toBe(svg);
  });

  it('redraws when the host swaps its tokens and the mode stays (5A)', async () => {
    // A host's preset, keyed by an attribute on <html>: the tokens move, the
    // mode does not — the case a chart listening for the mode missed. (jsdom
    // resolves no `var()` inside a custom property, so the rules name the
    // token itself where the stylesheet goes through `--fve-*`.)
    sheet(`
      .fve-root { --chart-1: rgb(1, 2, 3); }
      html[data-fve-preset='brand'] .fve-root { --chart-1: rgb(9, 8, 7); }
      html.brand-by-class .fve-root { --chart-1: rgb(4, 5, 6); }
    `);
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    const svg = container.querySelector('[data-slot="chart-plot"] svg');
    expect(fills(container)).toContain('rgb(1, 2, 3)');
    const html = document.documentElement;
    try {
      html.dataset.fvePreset = 'brand';
      await act(async () => {});
      expect(fills(container)).toContain('rgb(9, 8, 7)');
      expect(fills(container)).not.toContain('rgb(1, 2, 3)');

      html.classList.add('brand-by-class');
      delete html.dataset.fvePreset;
      await act(async () => {});
      expect(fills(container)).toContain('rgb(4, 5, 6)');

      // Inline, as `style.setProperty('--fve-…')` on the page writes it;
      // jsdom stands in for the `var()` with a rule keyed on the attribute.
      sheet(
        `html[style*='--fve-chart-1'] .fve-root { --chart-1: rgb(7, 7, 7); }`,
      );
      html.style.setProperty('--fve-chart-1', 'rgb(7, 7, 7)');
      await act(async () => {});
      expect(fills(container)).toContain('rgb(7, 7, 7)');
      html.style.removeProperty('--fve-chart-1');
      await act(async () => {});
      expect(fills(container)).toContain('rgb(4, 5, 6)');

      // A stylesheet swapped with no attribute moving is not observed: the
      // design says a preset is switched by an attribute (5A).
      sheet(`html.brand-by-class { --fve-chart-1: rgb(3, 3, 3); }`);
      await act(async () => {});
      expect(fills(container)).toContain('rgb(4, 5, 6)');
      expect(fills(container)).not.toContain('rgb(3, 3, 3)');
      // The same drawing throughout.
      expect(container.querySelector('[data-slot="chart-plot"] svg')).toBe(svg);
    } finally {
      html.classList.remove('brand-by-class');
      delete html.dataset.fvePreset;
      html.style.removeProperty('--fve-chart-1');
    }
  });

  it('reads the theme again when the host names another change convention', async () => {
    // `data-fve-change-colors` moves `--_fve-rise` / `--_fve-fall` and nothing else a
    // bar reads (themes.md 2.6), so it is watched on its own, as a preset is.
    sheet(`
      .fve-root { --_fve-rise: rgb(1, 1, 1); }
      html[data-fve-change-colors='red-up'] .fve-root { --_fve-rise: rgb(2, 2, 2); }
    `);
    let seen: string | undefined;
    function Tokens() {
      seen = useSurfaceTokens()?.replace(/\s/g, '');
      return null;
    }
    render(
      <ViewSurface>
        <Tokens />
      </ViewSurface>,
    );
    await act(async () => {});
    expect(seen).toContain('rgb(1,1,1)');
    const html = document.documentElement;
    try {
      html.setAttribute('data-fve-change-colors', 'red-up');
      await act(async () => {});
      expect(seen).toContain('rgb(2,2,2)');
    } finally {
      html.removeAttribute('data-fve-change-colors');
    }
  });

  it('redraws when the preset pinned on its surface changes (5B)', async () => {
    // jsdom resolves no `var()` inside a custom property, so the rule names
    // the token where `themes.css` goes through `--fve-*`.
    sheet(`
      .fve-root { --chart-1: rgb(1, 2, 3); }
      .fve-root[data-fve-preset='probe'] { --chart-1: rgb(9, 8, 7); }
    `);
    const { container, rerender } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    const svg = container.querySelector('[data-slot="chart-plot"] svg');
    expect(fills(container)).toContain('rgb(1, 2, 3)');

    rerender(
      <ViewSurface preset="probe">
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    await act(async () => {});
    expect(fills(container)).toContain('rgb(9, 8, 7)');
    expect(fills(container)).not.toContain('rgb(1, 2, 3)');

    rerender(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    await act(async () => {});
    expect(fills(container)).toContain('rgb(1, 2, 3)');
    expect(container.querySelector('[data-slot="chart-plot"] svg')).toBe(svg);
  });

  it('redraws for paper when printing starts, and back when it ends (T5)', async () => {
    const listeners = new Set<() => void>();
    const print = {
      matches: false,
      addEventListener: (_: string, listener: () => void) =>
        listeners.add(listener),
      removeEventListener: (_: string, listener: () => void) =>
        listeners.delete(listener),
    };
    vi.stubGlobal('matchMedia', (query: string) =>
      query === 'print'
        ? print
        : { matches: true, addEventListener() {}, removeEventListener() {} },
    );
    try {
      sheet(
        `.fve-root { --chart-1: rgb(200, 201, 202); --_fve-chart-grid: rgb(60, 61, 62); --_fve-chart-grid-width: 1px; }`,
      );
      const strokes = () =>
        [
          ...container.querySelectorAll(
            '[data-slot="chart-plot"] svg path[stroke]',
          ),
        ].map(
          path =>
            `${path.getAttribute('stroke')} ${path.getAttribute('stroke-width') ?? ''}`,
        );
      const { container } = render(
        <ViewSurface theme="dark">
          <AnalysisChart data={data} spec={spec} />
        </ViewSurface>,
      );
      const svg = container.querySelector('[data-slot="chart-plot"] svg');
      expect(fills(container)).toContain('rgb(200, 201, 202)');
      expect(strokes()).toContain('rgb(60, 61, 62) ');

      // The browser lays the page out for paper: the print rules apply
      // (jsdom reads no `@media print`, so the rule stands in for them),
      // and the query answers — nothing on the surface itself moved.
      const paper = document.createElement('style');
      paper.dataset.test = '';
      // The chart's roles are read again too: paper's gridlines, here in
      // another grey and twice as wide.
      paper.textContent = `.fve-root.fve-root { --chart-1: rgb(1, 2, 3); --_fve-chart-grid: rgb(4, 5, 6); --_fve-chart-grid-width: 2px; }`;
      document.head.append(paper);
      print.matches = true;
      await act(async () => {
        listeners.forEach(listener => listener());
      });
      expect(fills(container)).toContain('rgb(1, 2, 3)');
      expect(fills(container)).not.toContain('rgb(200, 201, 202)');
      expect(strokes()).toContain('rgb(4, 5, 6) 2');
      expect(strokes()).not.toContain('rgb(60, 61, 62) ');

      paper.remove();
      print.matches = false;
      await act(async () => {
        listeners.forEach(listener => listener());
      });
      expect(fills(container)).toContain('rgb(200, 201, 202)');
      expect(strokes()).toContain('rgb(60, 61, 62) ');
      expect(container.querySelector('[data-slot="chart-plot"] svg')).toBe(svg);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('redraws when the system it follows turns dark (5B)', async () => {
    const listeners = new Set<() => void>();
    const list = {
      matches: false,
      addEventListener: (_: string, listener: () => void) =>
        listeners.add(listener),
      removeEventListener: (_: string, listener: () => void) =>
        listeners.delete(listener),
    };
    vi.stubGlobal('matchMedia', (query: string) =>
      query === '(prefers-color-scheme: dark)'
        ? list
        : { matches: true, addEventListener() {}, removeEventListener() {} },
    );
    try {
      sheet(`
        .fve-root[data-theme='light'] { --chart-1: rgb(1, 2, 3); }
        .fve-root[data-theme='dark'] { --chart-1: rgb(200, 201, 202); }
      `);
      const { container } = render(
        <ViewSurface theme="system">
          <AnalysisChart data={data} spec={spec} />
        </ViewSurface>,
      );
      expect(fills(container)).toContain('rgb(1, 2, 3)');

      list.matches = true;
      await act(async () => {
        listeners.forEach(listener => listener());
      });
      expect(fills(container)).toContain('rgb(200, 201, 202)');
      expect(fills(container)).not.toContain('rgb(1, 2, 3)');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('waits for a size, and follows every new one', () => {
    const observed: [ResizeObserverCallback, Element][] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(private readonly callback: ResizeObserverCallback) {}
        observe(target: Element) {
          observed.push([this.callback, target]);
        }
        unobserve() {}
        disconnect() {}
      },
    );
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    const size = (width: number, height: number) => {
      const [callback, target] = observed[observed.length - 1];
      callback(
        [{ target, contentRect: { width, height } } as ResizeObserverEntry],
        {} as ResizeObserver,
      );
    };

    // Folded away, a chart has no size and nothing is drawn yet.
    size(0, 0);
    expect(container.querySelector('[data-slot="chart-plot"] svg')).toBeNull();

    size(400, 200);
    const svg = container.querySelector('[data-slot="chart-plot"] svg')!;
    expect(svg.getAttribute('width')).toBe('400');

    size(700, 300);
    expect(svg.getAttribute('width')).toBe('700');
    vi.unstubAllGlobals();
  });

  it('creates every chart sized in one frame before drawing any', () => {
    // Creating a chart adds its listeners, and drawing one reads the
    // layout: in Linux WebKit each read after a new wheel listener walked
    // the whole page, once per chart (the theme gallery, 5 s).
    const observers: {
      callback: ResizeObserverCallback;
      targets: Element[];
    }[] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class {
        private readonly watching: {
          callback: ResizeObserverCallback;
          targets: Element[];
        };
        constructor(callback: ResizeObserverCallback) {
          this.watching = { callback, targets: [] };
          observers.push(this.watching);
        }
        observe(target: Element) {
          this.watching.targets.push(target);
        }
        unobserve() {}
        disconnect() {}
      },
    );
    try {
      const { container } = render(
        <ViewSurface>
          <AnalysisChart data={data} spec={spec} />
          <AnalysisChart data={data} spec={spec} />
        </ViewSurface>,
      );
      // One observer watches both plots, so one frame sizes them together.
      const [{ callback, targets }] = observers.filter(
        observer => observer.targets.length === 2,
      );
      const log = new MutationObserver(() => {});
      log.observe(container, { childList: true, subtree: true });
      callback(
        targets.map(
          target =>
            ({
              target,
              contentRect: { width: 400, height: 200 },
            }) as unknown as ResizeObserverEntry,
        ),
        {} as ResizeObserver,
      );
      // Where the library put its element (created), and where it first
      // put a mark into the drawing (drawn), in the order it happened.
      const records = log.takeRecords();
      log.disconnect();
      const [first, second] = targets;
      const created = (plot: Element) =>
        records.findIndex(record => record.target === plot);
      const drawn = (plot: Element) =>
        records.findIndex(
          record =>
            plot.contains(record.target) &&
            record.target instanceof SVGElement &&
            record.addedNodes.length > 0,
        );
      expect(created(second)).toBeGreaterThan(-1);
      expect(drawn(first)).toBeGreaterThan(created(second));
      expect(drawn(second)).toBeGreaterThan(-1);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('hands a pressed bar’s group to the follow-up, where it was pressed', () => {
    const onPick = vi.fn();
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} onPick={onPick} />
      </ViewSurface>,
    );
    const bar = [
      ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
    ].find(path => path.getAttribute('fill') === 'rgb(38, 117, 211)')!;
    // The bar's corners are the points its outline moves and lines to; the
    // rounded ones add arcs, whose numbers are not points.
    const corners = [
      ...(bar.getAttribute('d') ?? '').matchAll(
        /[ML]\s*(-?[\d.]+)[\s,](-?[\d.]+)/g,
      ),
    ].map(([, x, y]) => [Number(x), Number(y)]);
    const xs = corners.map(([x]) => x);
    const ys = corners.map(([, y]) => y);
    const at = {
      clientX: (Math.min(...xs) + Math.max(...xs)) / 2,
      clientY: (Math.min(...ys) + Math.max(...ys)) / 2,
    };
    const surface = container.querySelector(
      '[data-slot="chart-plot"] > div > div',
    )!;
    // The library reads where a press landed off the event's own offset,
    // which jsdom leaves at nothing, so it is given the point.
    const press = (type: string) => {
      const event = new MouseEvent(type, { bubbles: true, ...at });
      Object.defineProperties(event, {
        offsetX: { value: at.clientX },
        offsetY: { value: at.clientY },
      });
      fireEvent(surface, event);
    };
    for (const type of ['mousemove', 'mousedown', 'mouseup', 'click'])
      press(type);

    expect(onPick).toHaveBeenCalledTimes(1);
    const [row, anchor] = onPick.mock.calls[0];
    expect(row).toEqual({ warehouse: 'CN' });
    expect(anchor.getBoundingClientRect()).toMatchObject({
      x: at.clientX,
      y: at.clientY,
    });
  });

  it('says on its frame when the marks have landed', async () => {
    const { container, rerender } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    const frame = container.querySelector('[data-slot="chart"]')!;
    await waitFor(() => expect(frame.hasAttribute('data-drawn')).toBe(true));

    // A new drawing takes it back until that one has landed too.
    rerender(
      <ViewSurface>
        <AnalysisChart data={data} spec={{ ...spec, labels: true }} />
      </ViewSurface>,
    );
    await waitFor(() => expect(frame.hasAttribute('data-drawn')).toBe(true));
  });

  it('says what it draws on its frame', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={{ ...spec, labels: true }} />
      </ViewSurface>,
    );
    const frame = container.querySelector('[data-slot="chart"]')!;
    expect(frame.getAttribute('data-marks')).toBe('2');
    expect(frame.getAttribute('data-labels')).toBe('on');
    expect(frame.getAttribute('data-legend')).toBe('none');
    expect(screen.getByRole('img', { name: /bar/ })).toBeDefined();
  });
});

describe('merged: the width’s adjustment laid onto a drawing', () => {
  it('merges object into object and replaces anything else', () => {
    expect(
      merged(
        {
          xAxis: { type: 'category', axisLabel: { rotate: 0, color: 'a' } },
          series: [1],
        },
        { xAxis: { axisLabel: { rotate: 45 } }, series: [2], grid: { top: 1 } },
      ),
    ).toEqual({
      xAxis: { type: 'category', axisLabel: { rotate: 45, color: 'a' } },
      series: [2],
      grid: { top: 1 },
    });
  });

  it('merges a list of objects item by item, as the library does', () => {
    expect(
      merged(
        {
          series: [
            { type: 'pie', radius: ['50%', '72%'], label: { color: 'a' } },
            { type: 'pie' },
          ],
        },
        { series: [{ radius: [50, 72], label: { show: false } }] },
      ),
    ).toEqual({
      series: [
        // A list of numbers replaces.
        { type: 'pie', radius: [50, 72], label: { color: 'a', show: false } },
        { type: 'pie' },
      ],
    });
  });
});

describe('EChart: a legend beside the plot, where there is room', () => {
  const pie: ChartData = {
    type: 'pie',
    slices: [
      { category: 'CN', value: 3 },
      { category: 'JP', value: 1 },
    ],
  };
  const pieSpec: ChartSpec = {
    type: 'pie',
    pie: { category: 'country', value: 'orders' },
  };
  const framed = (width: number) => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(private readonly callback: ResizeObserverCallback) {}
        observe(target: Element) {
          this.callback(
            [{ target, contentRect: { width, height: 300 } } as never],
            this as never,
          );
        }
        unobserve() {}
        disconnect() {}
      },
    );
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={pie} spec={pieSpec} />
      </ViewSurface>,
    );
    vi.unstubAllGlobals();
    return container.querySelector<HTMLElement>('[data-slot="chart"]')!;
  };

  it('stands beside a pie on a wide frame', () => {
    const frame = framed(800);
    expect(frame.getAttribute('data-legend')).toBe('right');
    expect(frame.className).toContain('flex-row');
  });

  it('goes under the pie on a phone, and folds to a line there (audit P0-6)', () => {
    const frame = framed(382);
    expect(frame.getAttribute('data-legend')).toBe('bottom');
    expect(frame.className).toContain('flex-col');
    // Under the plot, after it.
    expect(frame.lastElementChild?.getAttribute('data-slot')).toBe(
      'chart-legend',
    );
    // Every share still in it, one decimal each.
    expect(
      [...frame.querySelectorAll('[data-slot="chart-legend-item"]')].map(
        item => item.textContent,
      ),
    ).toEqual(['CN75.0%', 'JP25.0%']);
  });
});

describe('ChartLegend: one line, and the rest counted', () => {
  it('counts the entries past the first line, and opens the whole list', async () => {
    // jsdom lays nothing out: every entry past the third stands a line lower.
    vi.spyOn(HTMLElement.prototype, 'offsetTop', 'get').mockImplementation(
      function (this: HTMLElement) {
        const siblings = [...(this.parentElement?.children ?? [])];
        return this.dataset.slot === 'chart-legend-item' &&
          siblings.indexOf(this) >= 3
          ? 20
          : 0;
      },
    );
    const entries = Array.from({ length: 7 }, (_, i) => ({
      key: `k${i}`,
      label: `Series ${i}`,
      color: 'red',
    }));
    render(
      <ViewSurface>
        <ChartLegend entries={entries} at="top" />
      </ViewSurface>,
    );
    const more = screen.getByRole('button', { name: '4 more' });
    expect(more.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(more);
    const less = screen.getByRole('button', { name: 'Show less' });
    expect(less.getAttribute('aria-expanded')).toBe('true');
    expect(
      document
        .querySelector('[data-slot="chart-legend"]')!
        .hasAttribute('data-open'),
    ).toBe(true);
  });

  it('scrolls beside the plot rather than folding', () => {
    render(
      <ViewSurface>
        <ChartLegend
          entries={[{ key: 'a', label: 'A', color: 'red' }]}
          at="right"
        />
      </ViewSurface>,
    );
    expect(screen.queryByRole('button')).toBeNull();
  });
});
