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

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cleanup, render, screen } from '@testing-library/react';
import postcss, { type Rule } from 'postcss';
import { afterEach, describe, expect, it } from 'vitest';
import { CHART_COLOR_SLOTS } from '../src/index.js';
import { ViewSurface, useSurfaceTheme } from '../src/ui/index.js';

afterEach(cleanup);

/**
 * The two style boundaries, read off the stylesheet that defines them.
 *
 * jsdom applies no stylesheet, so nothing here measures a painted pixel. What
 * it does instead is take the three selector lists the theme is decided by —
 * the light tokens, the dark tokens and the list `dark:` utilities compile
 * against — and ask the DOM which elements each one matches, which is the same
 * question a browser asks. That makes the two shapes below say their actual
 * values out loud: two nested roots, which is unsupported and wrong, and a
 * host's chrome on `.fve-tokens` with a pinned view inside it, which is the
 * supported way round (D17-10).
 */
const STYLESHEET = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../src/styles.css'),
  'utf8',
);

/** Every rule of the stylesheet, as postcss reads it. */
function rules(): Rule[] {
  const found: Rule[] = [];
  postcss.parse(STYLESHEET).walkRules(rule => {
    found.push(rule);
  });
  return found;
}

/** The token block of one mode, found by the `color-scheme` it declares. */
function tokenSelector(mode: 'light' | 'dark'): string {
  const rule = rules().find(candidate =>
    candidate.some(
      node =>
        node.type === 'decl' &&
        node.prop === 'color-scheme' &&
        node.value === mode,
    ),
  );
  if (!rule) throw new Error(`No ${mode} token block in the stylesheet`);
  return rule.selector;
}

/**
 * The selector a `dark:` utility ends up carrying — the `@custom-variant`
 * without the `&` it is attached to.
 */
function darkVariant(): string {
  let params: string | undefined;
  postcss.parse(STYLESHEET).walkAtRules('custom-variant', rule => {
    if (rule.params.startsWith('dark ')) params = rule.params;
  });
  if (!params) throw new Error('No dark variant in the stylesheet');
  const scope = params.slice(params.indexOf('(') + 1, params.lastIndexOf(')'));
  return scope.replace(/^&/, '');
}

const LIGHT_TOKENS = tokenSelector('light');
const DARK_TOKENS = tokenSelector('dark');
const DARK_UTILITIES = darkVariant();

/** What an element is actually served, by the selectors that decide it. */
function modeOf(testId: string) {
  const element = screen.getByTestId(testId);
  return {
    tokens: element.matches(DARK_TOKENS)
      ? 'dark'
      : element.matches(LIGHT_TOKENS)
        ? 'light'
        : 'inherited',
    utilities: element.matches(DARK_UTILITIES) ? 'dark' : 'light',
  };
}

/** Says what the nearest surface reports as its mode. */
function ThemeProbe() {
  return <span data-testid="probe">{useSurfaceTheme() ?? 'none'}</span>;
}

describe('the theme has two boundaries and only one of them is a surface', () => {
  it('gives the tokens boundary the same tokens as the surface', () => {
    expect(LIGHT_TOKENS.split(',').map(part => part.trim())).toContain(
      '.fve-tokens',
    );
    expect(DARK_TOKENS.split(',').map(part => part.trim())).toContain(
      '.dark .fve-tokens',
    );
  });

  /**
   * It follows a `.dark` ancestor and reads nothing else — pinning a mode is
   * what a surface is for, and this class is not one.
   */
  it('never lets the tokens boundary carry a mode of its own', () => {
    const mentions = [LIGHT_TOKENS, DARK_TOKENS, DARK_UTILITIES]
      .flatMap(selector => selector.split(','))
      .map(part => part.trim())
      .filter(part => part.includes('.fve-tokens'));

    expect(mentions.length).toBeGreaterThan(0);
    for (const part of mentions) expect(part).not.toContain('data-theme');
  });

  /**
   * The surface paints a page; the tokens boundary hands over values and
   * paints nothing, so the host's own background and text colour survive it.
   */
  it('paints the page on the surface alone', () => {
    const painted: string[] = [];
    postcss.parse(STYLESHEET).walkAtRules('apply', rule => {
      if (rule.params.includes('bg-background'))
        painted.push((rule.parent as Rule).selector);
    });

    expect(painted).toEqual(['.fve-root']);
  });
});

/**
 * Why roots do not nest, in the values it produces.
 *
 * CSS has no nearest-ancestor selector, so the inner root's own mode reaches
 * its tokens — it redeclares them on itself — but not the `dark:` utilities,
 * which the outer root claimed for everything inside it. The result is the one
 * combination nothing can render: light tokens under dark utilities.
 */
describe('a surface pinned inside another surface', () => {
  function renderNested() {
    render(
      <ViewSurface theme="dark" data-testid="outer">
        <ViewSurface theme="light" data-testid="inner">
          <p data-testid="inside">rows</p>
          <ThemeProbe />
        </ViewSurface>
      </ViewSurface>,
    );
  }

  it('reports the mode it pinned', () => {
    renderNested();

    expect(screen.getByTestId('probe').textContent).toBe('light');
  });

  it('is served light tokens under dark utilities, which is why it is unsupported', () => {
    renderNested();

    expect(modeOf('outer')).toEqual({ tokens: 'dark', utilities: 'dark' });
    expect(modeOf('inner')).toEqual({ tokens: 'light', utilities: 'dark' });
    expect(modeOf('inside')).toEqual({
      tokens: 'inherited',
      utilities: 'dark',
    });
  });
});

/**
 * And the same page written the supported way.
 *
 * The host's chrome takes `.fve-tokens`, the view keeps its own root, and the
 * two do not fight: the dark variant hands every element a surface answers for
 * back to that surface, so a view pinned to light inside a dark page is light
 * all the way through.
 */
describe("a host's chrome on the tokens boundary", () => {
  function renderHost(theme?: 'light' | 'dark') {
    render(
      <div className="dark">
        <div className="fve-tokens" data-testid="chrome">
          <span data-testid="chrome-text">Customer</span>
          <ViewSurface theme={theme} data-testid="view">
            <p data-testid="inside">rows</p>
            <ThemeProbe />
          </ViewSurface>
        </div>
      </div>,
    );
  }

  it('follows the dark page, chrome and all', () => {
    renderHost();

    expect(modeOf('chrome')).toEqual({ tokens: 'dark', utilities: 'dark' });
    expect(modeOf('chrome-text')).toEqual({
      tokens: 'inherited',
      utilities: 'dark',
    });
    expect(modeOf('view')).toEqual({ tokens: 'dark', utilities: 'dark' });
    expect(modeOf('inside')).toEqual({
      tokens: 'inherited',
      utilities: 'dark',
    });
  });

  it('leaves a view pinned to the other mode alone, tokens and utilities together', () => {
    renderHost('light');

    expect(screen.getByTestId('probe').textContent).toBe('light');
    expect(modeOf('chrome')).toEqual({ tokens: 'dark', utilities: 'dark' });
    expect(modeOf('view')).toEqual({ tokens: 'light', utilities: 'light' });
    expect(modeOf('inside')).toEqual({
      tokens: 'inherited',
      utilities: 'light',
    });
  });
});

/**
 * The kernel folds a pie at `CHART_COLOR_SLOTS` and the palette hands out
 * that many `var(--chart-N)`, so the theme has to declare exactly that many:
 * a slot the stylesheet never declared paints nothing, and one it declares
 * past the count is a colour no chart ever reaches.
 */
describe('the chart palette the theme declares', () => {
  const slots = Array.from(
    { length: CHART_COLOR_SLOTS },
    (_, index) => `--chart-${index + 1}`,
  );

  /** The `--chart-N` declarations of one mode's token block, in order. */
  function declared(mode: 'light' | 'dark') {
    const selector = tokenSelector(mode);
    const rule = rules().find(candidate => candidate.selector === selector)!;
    return rule.nodes.flatMap(node =>
      node.type === 'decl' && /^--chart-\d+$/.test(node.prop)
        ? [{ prop: node.prop, value: node.value }]
        : [],
    );
  }

  it('holds as many slots as the kernel folds at, in both modes', () => {
    for (const [mode, host] of [
      ['light', '--fve-chart-'],
      ['dark', '--fve-dark-chart-'],
    ] as const) {
      const found = declared(mode);
      expect(found.map(entry => entry.prop)).toEqual(slots);
      // Each one a host can restyle, under its own mode's name.
      found.forEach((entry, index) =>
        expect(entry.value.startsWith(`var(${host}${index + 1},`)).toBe(true),
      );
    }
  });

  it('registers every slot as a utility colour', () => {
    const registered: string[] = [];
    postcss.parse(STYLESHEET).walkAtRules('theme', rule => {
      rule.walkDecls(/^--color-chart-\d+$/, decl => {
        registered.push(`${decl.prop}: ${decl.value}`);
      });
    });
    expect(registered.sort()).toEqual(
      slots.map(slot => `--color-${slot.slice(2)}: var(${slot})`).sort(),
    );
  });
});
