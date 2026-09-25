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
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import postcss, {
  type AtRule,
  type Declaration,
  type Node,
  type Rule,
} from 'postcss';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { themesSource } from '../scripts/themes.mjs';
import { CHART_TOKENS } from '../src/ui/charts/theme.js';
import { CHART_COLOR_SLOTS } from '../src/index.js';
import { Dialog, DialogTitle } from '../src/ui/components/dialog.js';
import { DialogContent } from '../src/ui/popups.js';
import {
  BUILT_IN_PRESETS,
  ViewSurface,
  type ViewSurfaceProps,
  useSurfaceTheme,
} from '../src/ui/index.js';

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
 * The `@custom-variant dark` block, and the selector a `dark:` utility ends
 * up carrying — the variant's rule without the `&` it is attached to. The
 * variant is written as a block, because it holds only off paper (T5).
 */
function darkVariantRule() {
  let found: Rule | undefined;
  postcss.parse(STYLESHEET).walkAtRules('custom-variant', variant => {
    if (variant.params.trim() !== 'dark') return;
    variant.walkRules(rule => {
      if (rule.selector.startsWith('&')) found = rule;
    });
  });
  if (!found) throw new Error('No dark variant in the stylesheet');
  return found;
}

function darkVariant(): string {
  return darkVariantRule().selector.replace(/^&/, '');
}

/** The media a rule of the stylesheet holds under, outermost last. */
function mediaOf(node: Node): string[] {
  const media: string[] = [];
  for (let at = node.parent; at; at = at.parent)
    if (at.type === 'atrule' && (at as AtRule).name === 'media')
      media.push((at as AtRule).params);
  return media;
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
  it('prints without a lift and with the patterns on, colour kept where it is the reading (T5)', () => {
    const paper = rules().filter(rule => mediaOf(rule).includes('print'));
    const tokens = paper.find(rule =>
      /^\.fve-root,\s*\.fve-tokens$/.test(rule.selector),
    );
    const value = (prop: string) =>
      tokens?.nodes.find(
        (node): node is Declaration =>
          node.type === 'decl' && node.prop === prop,
      )?.value;
    // A transparent shadow, never `none` (T2): the utilities compose it into
    // one list with the ring, which a `none` would void.
    for (const shadow of [
      '--shadow-sm',
      '--shadow-md',
      '--shadow-lg',
      '--_fve-card-shadow',
    ])
      expect(value(shadow)).toBe('0 0 0 0 oklch(0 0 0deg / 0%)');
    expect(value('--_fve-pin-shadow')).toBe('oklch(0 0 0deg / 0%)');
    expect(value('--fve-chart-patterns')).toBe('on');
    const exact = paper.filter(rule =>
      rule.some(
        node =>
          node.type === 'decl' &&
          node.prop === 'print-color-adjust' &&
          node.value === 'exact',
      ),
    );
    expect(exact.map(rule => rule.selector).join(' ')).toMatch(
      /data-slot='chart'.*data-slot='badge'/s,
    );
  });

  it('keeps focus and selection in forced colours, in system colours (T5)', () => {
    const forced = rules().filter(rule =>
      mediaOf(rule).includes('(forced-colors: active)'),
    );
    const outline = (selector: RegExp) =>
      forced
        .filter(rule => selector.test(rule.selector))
        .flatMap(rule =>
          rule.nodes.filter(
            (node): node is Declaration =>
              node.type === 'decl' && node.prop.startsWith('outline'),
          ),
        )
        .map(decl => `${decl.prop}: ${decl.value}`);
    expect(outline(/:focus-visible/)).toContain(
      'outline: 2px solid CanvasText',
    );
    expect(outline(/\[data-state='selected'\]/)).toContain(
      'outline: 2px solid Highlight',
    );
  });

  it('prints in the light half: the dark tokens and utilities hold off paper only (T5)', () => {
    expect(mediaOf(darkVariantRule())).toEqual(['not print']);
    const darkBlock = rules().find(candidate =>
      candidate.some(
        node =>
          node.type === 'decl' &&
          node.prop === 'color-scheme' &&
          node.value === 'dark',
      ),
    )!;
    expect(mediaOf(darkBlock)).toEqual(['not print']);
    // And the light block holds everywhere.
    const lightBlock = rules().find(candidate =>
      candidate.some(
        node =>
          node.type === 'decl' &&
          node.prop === 'color-scheme' &&
          node.value === 'light',
      ),
    )!;
    expect(mediaOf(lightBlock)).toEqual([]);
  });

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
      // Each one a host can restyle, under its own mode's name, and a
      // preset after it (theme-architecture.md 3, S2) — the first behind the
      // brand's derivation, which the host switches on (2, S4).
      const preset = host.replace('--fve-', '--fvp-');
      found.forEach((entry, index) =>
        expect(entry.value.replace(/\s+/g, ' ')).toMatch(
          new RegExp(
            `^var\\(\\s?${host}${index + 1}, (var\\(\\s?--_fve-brand-[\\w-]+, )?var\\(${preset}${index + 1},`,
          ),
        ),
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

/**
 * What the token blocks declare, each token held to a reader and to the line
 * it owes (phase 5, 5A).
 */
describe('the tokens the theme declares', () => {
  /** One mode's token block, declaration by declaration. */
  function block(mode: 'light' | 'dark') {
    const selector = tokenSelector(mode);
    const rule = rules().find(candidate => candidate.selector === selector)!;
    const declared = new Map<string, string>();
    rule.walkDecls(/^--/, decl => {
      declared.set(decl.prop, decl.value);
    });
    return declared;
  }

  /** Every source file of the package but the stylesheet, as one text. */
  const SOURCES = readdirSync(
    resolve(dirname(fileURLToPath(import.meta.url)), '../src'),
    { recursive: true, withFileTypes: true },
  )
    .filter(entry => entry.isFile() && /\.tsx?$/.test(entry.name))
    .map(entry => readFileSync(join(entry.parentPath, entry.name), 'utf8'))
    .join('\n');

  /** Every declaration of the stylesheet, as the property and its value. */
  const DECLARATIONS: [string, string][] = [];
  postcss.parse(STYLESHEET).walkDecls(decl => {
    DECLARATIONS.push([decl.prop, decl.value]);
  });

  /**
   * Whether anything reads a token: a declaration other than its own and
   * its registration as a colour, a `var()` in the code, or — registered —
   * a utility naming it (`bg-row-hover`, `text-quiet-foreground`,
   * `ring-ring/50`, `**:data-[x]:border-input`).
   */
  function isRead(token: string): boolean {
    const name = token.slice(2);
    const byVar = new RegExp(`var\\(${token}[,)]`);
    if (
      DECLARATIONS.some(
        ([prop, value]) =>
          prop !== token && prop !== `--color-${name}` && byVar.test(value),
      )
    )
      return true;
    if (byVar.test(SOURCES)) return true;
    // Tailwind's shorthand for a variable: `shadow-(--_fve-card-shadow)`, and
    // with a type hint `ring-(color:--_fve-card-edge)`.
    if (new RegExp(`\\((?:[a-z-]+:)?${token}\\)`).test(SOURCES)) return true;
    // A shadow is registered under Tailwind's own namespace, so its utility
    // is its name: `shadow-md` reads `--shadow-md`.
    if (name.startsWith('shadow-'))
      return new RegExp(`(?<![\\w-])${name}(?![\\w-])`).test(SOURCES);
    return new RegExp(`[a-z]-${name}(?:/\\d+)?(?![\\w-])`).test(SOURCES);
  }

  it('declares no token nothing reads — `info` is gone (Q45)', () => {
    for (const mode of ['light', 'dark'] as const) {
      const unread = [...block(mode).keys()].filter(
        // The chart slots are read by index (`charts/palette.ts`), and held
        // to their count by the palette's own suite above; the chart's
        // roles are read back off its element (`CHART_TOKENS`, held to what
        // `readChartTheme` reads by `test/chartTheme.test.tsx`).
        token =>
          !/^--chart-\d+$/.test(token) &&
          !CHART_TOKENS.includes(token) &&
          !isRead(token),
      );
      expect(unread, `${mode} tokens nothing reads`).toEqual([]);
      expect(block(mode).has('--info')).toBe(false);
    }
    expect(DECLARATIONS.some(([prop]) => prop === '--color-info')).toBe(false);
  });

  // The four groups D43 added draw, unset, exactly what was drawn before
  // them — so `neutral` and a host that sets none of them do not move.
  it('defaults the layering and control groups to what was drawn before', () => {
    for (const mode of ['light', 'dark'] as const) {
      const token = (name: string) =>
        block(mode)
          .get(name)
          ?.replace(/\s+/g, ' ')
          .replace(/\( /g, '(')
          .replace(/ \)/g, ')');
      expect(token('--_fve-canvas'), mode).toMatch(
        /^var\(--fve-(dark-)?canvas, var\(--fvp-(dark-)?canvas, var\(--background\)\)\)$/,
      );
      // The registry card's `ring-foreground/10`, and no shadow.
      expect(token('--_fve-card-edge'), mode).toMatch(
        /, color-mix\(in oklab, var\(--foreground\) 10%, transparent\)\)\)$/,
      );
      expect(token('--_fve-card-shadow'), mode).toMatch(/, 0 0 #0000\)\)$/);
      // No built-in value: each control keeps its own fallback.
      for (const name of [
        '--_fve-control',
        '--_fve-control-edge',
        '--_fve-control-thumb',
      ])
        expect(token(name), `${mode} ${name}`).toMatch(
          /^var\(--fve-(dark-)?control(-edge|-thumb)?, var\(--fvp-(dark-)?control(-edge|-thumb)?\)\)$/,
        );
    }
    // The registry's `font-medium`; a weight has no dark half.
    expect(block('light').get('--_fve-title-weight')).toBe(
      'var(--fve-title-weight, var(--fvp-title-weight, 500))',
    );
    expect(block('dark').has('--_fve-title-weight')).toBe(false);
  });

  it('derives the quiet ink from the foreground, in both modes', () => {
    // A host that sets `--fve-foreground` moves the summary rows' quiet half
    // with it; a copy of the value stayed behind (5A).
    for (const mode of ['light', 'dark'] as const) {
      const value = block(mode).get('--_fve-quiet-foreground') ?? '';
      expect(value).toMatch(/var\(--foreground\)/);
      expect(value).not.toMatch(/oklch\(/);
    }
  });

  it('keeps the focus ring and the control edge off the brand tokens', () => {
    // The shadcn habit — `--ring: var(--primary)`, `--input: var(--border)`
    // — hands the 3:1 a focus mark and a control's edge owe (WCAG 1.4.11) to
    // a brand colour and a divider grey that owe nothing of the kind. Here
    // both stay values of their own, so a host restyling `--fve-primary` or
    // `--fve-border` cannot take them under the line; one that re-points
    // `--fve-ring` / `--fve-input` owes its theme the measurement itself
    // (the READMEs' token table; `docs/design/ui/README.md`). The ring
    // follows a host's brand colour only through the bounds a preset gives
    // it, which the brand sweep holds to the preset's lines
    // (`test/brandInput.test.ts`); unbounded, it stays the tuned grey.
    for (const mode of ['light', 'dark'] as const) {
      for (const token of ['--ring', '--input']) {
        const value = (block(mode).get(token) ?? '').replace(/\s+/g, ' ');
        expect(value, `${mode} ${token}`).toMatch(
          /^var\( ?--fve-[\w-]+, (var\(--_fve-brand-[\w-]+, )?var\(--fvp-[\w-]+, oklch\(/,
        );
        expect(value, `${mode} ${token}`).not.toMatch(
          /var\(--(primary|border|accent|secondary|muted)\b/,
        );
      }
    }
  });
});

/**
 * The presets (phase 5, 5B): `themes.css` assigns the preset layer,
 * `--fvp-*`, keyed by `data-fve-preset`, and the surface carries the
 * attribute to the popups it portals out of the tree.
 *
 * jsdom inherits custom properties and matches `:where()`, but substitutes no
 * `var()`, so this reads the preset variables a preset assigns — the level
 * the mechanism works at. What the tokens then resolve to is a browser's to
 * measure (`ThemeTokens.test.stories.tsx`, `ThemeLayers.test.stories.tsx`).
 */
describe('a preset reaches the surface and its popups', () => {
  const THEMES = themesSource();
  /** A preset for these tests only, written the way `themes.css` writes one. */
  const PROBE = `:where([data-fve-preset='probe']) { --fvp-primary: rgb(1, 2, 3); --fvp-dark-primary: rgb(4, 5, 6); }`;
  const html = document.documentElement;

  afterEach(() => {
    document.head.querySelectorAll('style[data-presets]').forEach(style => {
      style.remove();
    });
    html.removeAttribute('data-fve-preset');
  });

  function sheet(css: string) {
    const style = document.createElement('style');
    style.dataset.presets = '';
    style.textContent = css;
    document.head.append(style);
  }

  function primaryOf(element: Element | null) {
    if (!element) throw new Error('No element to read');
    return getComputedStyle(element).getPropertyValue('--fvp-primary');
  }

  function surface() {
    return document.querySelector('[data-slot="view-surface"]');
  }

  /** A surface with an open dialog, the popup that portals the furthest. */
  function withDialog(props: Pick<ViewSurfaceProps, 'preset' | 'tokens'> = {}) {
    return (
      <ViewSurface {...props}>
        <Dialog defaultOpen>
          <DialogContent>
            <DialogTitle>Probe</DialogTitle>
          </DialogContent>
        </Dialog>
      </ViewSurface>
    );
  }

  it('is only values for the preset layer, and neutral says nothing', () => {
    const presets = postcss.parse(THEMES);
    const selectors: string[] = [];
    presets.walkRules(rule => {
      selectors.push(rule.selector);
      if (rule.selector.includes("'neutral'")) expect(rule.nodes).toEqual([]);
      rule.walkDecls(decl => {
        expect(decl.prop).toMatch(/^--fvp-/);
        // The mode's, the host's typography and the host's convention.
        expect(decl.prop).not.toMatch(/pin-shadow|text-ui|rise|fall/);
        expect(decl.value).not.toBe('initial');
      });
    });
    expect(selectors).toEqual(
      BUILT_IN_PRESETS.map(name => `:where([data-fve-preset='${name}'])`),
    );
    // No at-rule: the brand's derivation is the stylesheet's, and its one
    // feature query sits there (theme-architecture.md 2.6).
    const atRules: string[] = [];
    presets.walkAtRules(rule => {
      atRules.push(`@${rule.name} ${rule.params}`);
    });
    expect(atRules).toEqual([]);
  });

  it('follows a preset on <html>, popups included', async () => {
    sheet(`${THEMES}\n${PROBE}`);
    html.setAttribute('data-fve-preset', 'probe');
    render(withDialog());

    const dialog = await screen.findByRole('dialog');
    expect(primaryOf(surface())).toBe('rgb(1,2,3)');
    expect(primaryOf(dialog)).toBe('rgb(1,2,3)');
    await waitFor(() =>
      expect(dialog.getAttribute('data-fve-preset')).toBe('probe'),
    );
  });

  it('carries a preset set on a part of the page out to the popups', async () => {
    // The limit the design named — a subtree's `data-fve-preset` does not
    // reach what is portalled to <body> — is what the surface lifts: it
    // resolves the nearest preset and the popups copy it, as `data-theme`.
    sheet(`${THEMES}\n${PROBE}`);
    render(<div data-fve-preset="probe">{withDialog()}</div>);

    const dialog = await screen.findByRole('dialog');
    await waitFor(() =>
      expect(dialog.getAttribute('data-fve-preset')).toBe('probe'),
    );
    expect(dialog.closest('[data-slot="view-surface"]')).toBeNull();
    expect(primaryOf(dialog)).toBe('rgb(1,2,3)');
    const backdrop = document.querySelector('[data-slot="dialog-overlay"]');
    expect(backdrop?.getAttribute('data-fve-preset')).toBe('probe');
    // Nothing outside the surface's own popups moves.
    expect(primaryOf(document.body)).toBe('');
  });

  it('pins a preset on the surface and on each popup it opens', async () => {
    sheet(`${THEMES}\n${PROBE}`);
    render(withDialog({ preset: 'probe' }));

    const dialog = await screen.findByRole('dialog');
    expect(surface()?.getAttribute('data-fve-preset')).toBe('probe');
    expect(primaryOf(surface())).toBe('rgb(1,2,3)');
    expect(dialog.getAttribute('data-fve-preset')).toBe('probe');
    expect(primaryOf(dialog)).toBe('rgb(1,2,3)');
  });

  it('puts a view pinned to neutral back on the built-in values', async () => {
    // The page is on another preset; the reset empties the preset layer on
    // every element that names one, and `neutral` puts nothing back, so the
    // tokens fall back to the stylesheet's own values, popups too.
    sheet(`${THEMES}\n${PROBE}`);
    html.setAttribute('data-fve-preset', 'probe');
    render(withDialog({ preset: 'neutral' }));

    //
    // jsdom resolves `initial` on an inherited custom property by inheriting
    // anyway, and reads no `@layer`, so this asserts which preset each element
    // is keyed to and that the reset matches it; `PresetPinnedReachesPopups`
    // in `ThemeTokens.test.stories.tsx` and `ThemeLayers.test.stories.tsx`
    // measure the colours in a browser, and `test/themeLayers.test.ts` the
    // cascade.
    const dialog = await screen.findByRole('dialog');
    const backdrop = document.querySelector('[data-slot="dialog-overlay"]');
    let reset: Rule | undefined;
    postcss
      .parse(
        readFileSync(
          resolve(dirname(fileURLToPath(import.meta.url)), '../src/styles.css'),
          'utf8',
        ),
      )
      .walkAtRules('layer', layer => {
        if (layer.params === 'fve-reset')
          layer.walkRules(rule => {
            reset = rule;
          });
      });
    for (const element of [surface(), dialog, backdrop]) {
      expect(element?.getAttribute('data-fve-preset')).toBe('neutral');
      expect(element?.matches(reset!.selector)).toBe(true);
    }
    const emptied: string[] = [];
    reset!.walkDecls(decl => {
      if (decl.value === 'initial') emptied.push(decl.prop);
    });
    expect(emptied).toEqual(
      expect.arrayContaining(['--fvp-primary', '--fvp-dark-primary']),
    );
  });

  it('keeps the host’s own variables apart from a preset’s, on a pinned surface too', () => {
    // The host and the preset write different variables (theme-architecture.md
    // 3, S2), so a preset pinned on the surface declares its own there and
    // leaves the host's `:root` value inherited as it was; every token reads
    // the host's first (`test/themeLayers.test.ts`).
    sheet(`:root { --fve-primary: rgb(7, 7, 7); }`);
    sheet(`${THEMES}\n${PROBE}`);
    render(<ViewSurface preset="probe" />);

    const style = getComputedStyle(surface()!);
    expect(style.getPropertyValue('--fve-primary')).toBe('rgb(7,7,7)');
    expect(primaryOf(surface())).toBe('rgb(1,2,3)');
  });

  it('writes the host’s tokens on the surface and on every popup it opens', async () => {
    // A popup is portalled out from under any wrapper a host could set a
    // variable on, so a surface's own values travel with it
    // (theme-architecture.md 7, S2) — the backdrop too, whose dim is a token.
    render(
      withDialog({
        preset: 'probe',
        tokens: { '--fve-primary': 'rgb(9, 9, 9)', '--fve-radius': '0px' },
      }),
    );

    const dialog = await screen.findByRole('dialog');
    const backdrop = document.querySelector('[data-slot="dialog-overlay"]');
    for (const element of [surface(), dialog, backdrop]) {
      const style = (element as HTMLElement).style;
      expect(style.getPropertyValue('--fve-primary')).toBe('rgb(9, 9, 9)');
      expect(style.getPropertyValue('--fve-radius')).toBe('0px');
    }
  });

  it('writes no attribute when nothing asks for a preset', async () => {
    render(withDialog());

    const dialog = await screen.findByRole('dialog');
    expect(surface()?.hasAttribute('data-fve-preset')).toBe(false);
    expect(dialog.hasAttribute('data-fve-preset')).toBe(false);
    expect(dialog.hasAttribute('data-fve-change-colors')).toBe(false);
    expect(dialog.hasAttribute('data-fve-brand-chart')).toBe(false);
  });

  it('carries the brand chart attribute out to the popups (theme S4)', async () => {
    // Present on a part of the page, the first chart slot follows the
    // brand there; a popup portalled to <body> copies the attribute, bare,
    // so a chart in a dialog keeps the page's first colour.
    render(<div data-fve-brand-chart="">{withDialog()}</div>);

    const dialog = await screen.findByRole('dialog');
    await waitFor(() =>
      expect(dialog.getAttribute('data-fve-brand-chart')).toBe(''),
    );
    const backdrop = document.querySelector('[data-slot="dialog-overlay"]');
    expect(backdrop?.hasAttribute('data-fve-brand-chart')).toBe(true);
    expect(surface()?.hasAttribute('data-fve-brand-chart')).toBe(false);
  });

  it('carries the change convention out to the popups, as a preset', async () => {
    // A convention set on a part of the page (themes.md 2.6): the popup
    // portalled to <body> copies it, so a change it draws is coloured the
    // page's way. It is followed, not pinned — there is no prop for it.
    render(<div data-fve-change-colors="red-up">{withDialog()}</div>);

    const dialog = await screen.findByRole('dialog');
    await waitFor(() =>
      expect(dialog.getAttribute('data-fve-change-colors')).toBe('red-up'),
    );
    expect(dialog.closest('[data-fve-change-colors]')).toBe(dialog);
    const backdrop = document.querySelector('[data-slot="dialog-overlay"]');
    expect(backdrop?.getAttribute('data-fve-change-colors')).toBe('red-up');
    expect(surface()?.hasAttribute('data-fve-change-colors')).toBe(false);
  });

  it('pins a density on the surface and its popups (themes.md 2.4)', async () => {
    render(
      <ViewSurface density="compact">
        <Dialog defaultOpen>
          <DialogContent>
            <DialogTitle>Probe</DialogTitle>
          </DialogContent>
        </Dialog>
      </ViewSurface>,
    );

    const dialog = await screen.findByRole('dialog');
    expect(surface()?.getAttribute('data-fve-density')).toBe('compact');
    expect(dialog.getAttribute('data-fve-density')).toBe('compact');
  });

  it('carries a density set around the surface out to its popups', async () => {
    // The host's density on <html> or any ancestor reaches the popups
    // portalled to <body>, which are no longer under it; the surface itself
    // writes no attribute, so the ancestor's stays in force there.
    render(<div data-fve-density="comfortable">{withDialog()}</div>);

    const dialog = await screen.findByRole('dialog');
    await waitFor(() =>
      expect(dialog.getAttribute('data-fve-density')).toBe('comfortable'),
    );
    expect(surface()?.hasAttribute('data-fve-density')).toBe(false);
  });
});

/**
 * The density axis (themes.md 2.4, D35 Q63), read off `styles.css`: one
 * step drives four lengths through `default + a·step + b·step²`, and at the
 * default step every length is exactly the registry class it replaces, so
 * `neutral` at the default density paints the same pixels as before. The
 * browser story `DensityAxis` measures the laid-out rows.
 */
describe('the density axis', () => {
  const STYLES = readFileSync(
    resolve(dirname(fileURLToPath(import.meta.url)), '../src/styles.css'),
    'utf8',
  );
  const lengths = new Map<string, string>();
  postcss.parse(STYLES).walkRules(':where(.fve-root, .fve-tokens)', rule => {
    rule.walkDecls(/^--/, decl => {
      lengths.set(decl.prop, decl.value);
    });
  });

  /** A length at one step, in px (1rem = 16px), the calc worked out. */
  function px(token: string, step: number): number {
    const text = lengths.get(token);
    if (!text) throw new Error(`${token} is not declared`);
    const expression = text
      .replace(/^calc\(/, '(')
      .replace(/var\(--_fve-density\)/g, `(${step})`)
      .replace(/([\d.]+)rem/g, '($1*16)')
      .replace(/\s+/g, ' ');
    expect(expression).toMatch(/^[\d\s.()*+-]+$/);
    return (
      Math.round(Number(new Function(`return ${expression}`)()) * 100) / 100
    );
  }

  it.each([
    // compact, default, comfortable — the default the registry's own class.
    ['--_fve-table-head-height', [32, 40, 44]], // h-10
    ['--_fve-table-cell-padding-block', [4, 8, 10]], // p-2
    ['--_fve-table-cell-padding-inline', [6, 8, 12]], // px-2 / p-2
    ['--_fve-sidebar-item-height', [24, 28, 32]], // the `sm` button, h-7
    ['--_fve-panel-padding', [8, 12, 16]], // p-3
  ] as const)('%s is %j', (token, expected) => {
    expect([-1, 0, 1].map(step => px(token, step))).toEqual(expected);
  });

  // The board's row is 80px at every density (D34), so what is above and
  // below a panel's content stops at the default's 12px: a tile one row
  // tall keeps room for a metric's number.
  it('holds --_fve-panel-padding-block to [8, 12, 12]', () => {
    expect(lengths.get('--_fve-panel-padding-block')).toBe(
      'min(var(--_fve-panel-padding), 0.75rem)',
    );
    expect(
      [-1, 0, 1].map(step => Math.min(px('--_fve-panel-padding', step), 12)),
    ).toEqual([8, 12, 12]);
  });

  it('takes the preset’s recommendation, and 0 without one', () => {
    expect(lengths.get('--_fve-density')).toBe(
      'var(--fve-preset-density, var(--fvp-preset-density, 0))',
    );
  });

  it('lets the surface’s own attribute outweigh an ancestor’s', () => {
    const steps = new Map<string, string>();
    postcss.parse(STYLES).walkRules(/data-fve-density/, rule => {
      rule.walkDecls('--_fve-density', decl => {
        steps.set(rule.selector, decl.value);
      });
    });
    expect(Object.fromEntries(steps)).toEqual({
      ":where(.fve-root, .fve-tokens):where([data-fve-density='compact'] *)":
        '-1',
      ":where(.fve-root, .fve-tokens):where([data-fve-density='default'] *)":
        '0',
      ":where(.fve-root, .fve-tokens):where([data-fve-density='comfortable'] *)":
        '1',
      ":where(.fve-root, .fve-tokens)[data-fve-density='compact']": '-1',
      ":where(.fve-root, .fve-tokens)[data-fve-density='default']": '0',
      ":where(.fve-root, .fve-tokens)[data-fve-density='comfortable']": '1',
    });
  });
});

/**
 * `theme="system"` (Q43): the surface follows `prefers-color-scheme`, live,
 * by writing the answer as the `data-theme` a pinned mode writes — so the
 * stylesheet keeps one set of dark selectors.
 */
describe('a surface that follows the system', () => {
  /** A `matchMedia` whose dark-scheme answer the test can change. */
  function system(dark: boolean) {
    const listeners = new Set<() => void>();
    const list = {
      matches: dark,
      addEventListener: (_: string, listener: () => void) =>
        listeners.add(listener),
      removeEventListener: (_: string, listener: () => void) =>
        listeners.delete(listener),
    };
    vi.stubGlobal('matchMedia', (query: string) =>
      query === '(prefers-color-scheme: dark)'
        ? list
        : { matches: false, addEventListener() {}, removeEventListener() {} },
    );
    return {
      set(next: boolean) {
        list.matches = next;
        listeners.forEach(listener => listener());
      },
      listeners,
    };
  }

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function open() {
    return render(
      <ViewSurface theme="system">
        <ThemeProbe />
        <Dialog defaultOpen>
          <DialogContent>
            <DialogTitle>Probe</DialogTitle>
          </DialogContent>
        </Dialog>
      </ViewSurface>,
    );
  }

  function root() {
    const found = document.querySelector('[data-slot="view-surface"]');
    if (!found) throw new Error('No surface');
    return found;
  }

  it('takes the mode the system is in, popups included', async () => {
    system(true);
    open();

    expect(root().getAttribute('data-theme')).toBe('dark');
    expect(root().matches(DARK_TOKENS)).toBe(true);
    expect(screen.getByTestId('probe').textContent).toBe('dark');
    const dialog = await screen.findByRole('dialog');
    expect(dialog.getAttribute('data-theme')).toBe('dark');
  });

  it('follows the system when it changes while the view is open', async () => {
    const media = system(false);
    const { unmount } = open();
    const dialog = await screen.findByRole('dialog');
    expect(root().getAttribute('data-theme')).toBe('light');

    act(() => media.set(true));
    expect(root().getAttribute('data-theme')).toBe('dark');
    expect(dialog.getAttribute('data-theme')).toBe('dark');
    expect(screen.getByTestId('probe').textContent).toBe('dark');

    act(() => media.set(false));
    expect(root().getAttribute('data-theme')).toBe('light');
    expect(root().matches(DARK_TOKENS)).toBe(false);
    unmount();
    expect(media.listeners.size).toBe(0);
  });

  it('holds to the system under a dark host, because that is what was asked', () => {
    system(false);
    document.documentElement.classList.add('dark');
    try {
      open();
      expect(root().matches(DARK_TOKENS)).toBe(false);
      expect(root().matches(DARK_UTILITIES)).toBe(false);
      expect(root().matches(LIGHT_TOKENS)).toBe(true);
    } finally {
      document.documentElement.classList.remove('dark');
    }
  });

  it('is light where the platform cannot say', () => {
    vi.stubGlobal('matchMedia', undefined);
    open();
    expect(root().getAttribute('data-theme')).toBe('light');
  });
});
