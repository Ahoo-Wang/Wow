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
import postcss, { type Rule } from 'postcss';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CHART_COLOR_SLOTS } from '../src/index.js';
import { Dialog, DialogTitle } from '../src/ui/components/dialog.js';
import { DialogContent } from '../src/ui/popups.js';
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
    return new RegExp(`[a-z]-${name}(?:/\\d+)?(?![\\w-])`).test(SOURCES);
  }

  it('declares no token nothing reads — `info` is gone (Q45)', () => {
    for (const mode of ['light', 'dark'] as const) {
      const unread = [...block(mode).keys()].filter(
        // The chart slots are read by index (`charts/palette.ts`), and held
        // to their count by the palette's own suite above.
        token => !/^--chart-\d+$/.test(token) && !isRead(token),
      );
      expect(unread, `${mode} tokens nothing reads`).toEqual([]);
      expect(block(mode).has('--info')).toBe(false);
    }
    expect(DECLARATIONS.some(([prop]) => prop === '--color-info')).toBe(false);
  });

  it('derives the quiet ink from the foreground, in both modes', () => {
    // A host that sets `--fve-foreground` moves the summary rows' quiet half
    // with it; a copy of the value stayed behind (5A).
    for (const mode of ['light', 'dark'] as const) {
      const value = block(mode).get('--quiet-foreground') ?? '';
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
    // (the READMEs' token table; `docs/design/ui/README.md`).
    for (const mode of ['light', 'dark'] as const) {
      for (const token of ['--ring', '--input']) {
        const value = block(mode).get(token) ?? '';
        expect(value, `${mode} ${token}`).toMatch(
          /^var\(--fve-[\w-]+, oklch\(/,
        );
        expect(value, `${mode} ${token}`).not.toMatch(
          /var\(--(primary|border|accent|secondary|muted)\b/,
        );
      }
    }
  });
});

/**
 * The presets (phase 5, 5B): `themes.css` assigns `--fve-*` keyed by
 * `data-fve-preset`, and the surface carries the attribute to the popups it
 * portals out of the tree.
 *
 * jsdom inherits custom properties and matches `:where()`, but substitutes no
 * `var()`, so this reads the host variables a preset assigns — the level the
 * mechanism works at. What the tokens then resolve to is a browser's to
 * measure (`ThemeTokens.test.stories.tsx`).
 */
describe('a preset reaches the surface and its popups', () => {
  const THEMES = readFileSync(
    resolve(dirname(fileURLToPath(import.meta.url)), '../src/themes.css'),
    'utf8',
  );
  /** A preset for these tests only, written the way `themes.css` writes one. */
  const PROBE = `:where([data-fve-preset='probe']) { --fve-primary: rgb(1, 2, 3); --fve-dark-primary: rgb(4, 5, 6); }`;
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
    return getComputedStyle(element).getPropertyValue('--fve-primary');
  }

  function surface() {
    return document.querySelector('[data-slot="view-surface"]');
  }

  /** A surface with an open dialog, the popup that portals the furthest. */
  function withDialog(props: { preset?: string } = {}) {
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

  it('is only values for the host variables, and neutral only unsets them', () => {
    const presets = postcss.parse(THEMES);
    const selectors: string[] = [];
    presets.walkRules(rule => {
      selectors.push(rule.selector);
      rule.walkDecls(decl => {
        expect(decl.prop).toMatch(/^--fve-/);
        expect(decl.prop).not.toMatch(/chart/);
        expect(decl.value).toBe('initial');
      });
    });
    expect(selectors).toEqual([":where([data-fve-preset='neutral'])"]);
    let atRules = 0;
    presets.walkAtRules(() => {
      atRules += 1;
    });
    expect(atRules).toBe(0);
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
    // The page is on another preset; `neutral` unsets every variable, so the
    // tokens fall back to the stylesheet's own values, popups too.
    sheet(`${THEMES}\n${PROBE}`);
    html.setAttribute('data-fve-preset', 'probe');
    render(withDialog({ preset: 'neutral' }));

    //
    // jsdom resolves `initial` on an inherited custom property by inheriting
    // anyway, so this asserts which preset each element is keyed to and what
    // that preset declares; `PresetPinnedReachesPopups` in
    // `ThemeTokens.test.stories.tsx` measures the colours in a browser.
    const dialog = await screen.findByRole('dialog');
    const backdrop = document.querySelector('[data-slot="dialog-overlay"]');
    const neutral = postcss
      .parse(THEMES)
      .nodes.find((node): node is Rule => node.type === 'rule');
    for (const element of [surface(), dialog, backdrop]) {
      expect(element?.getAttribute('data-fve-preset')).toBe('neutral');
      expect(element?.matches(neutral!.selector)).toBe(true);
    }
    const reset: string[] = [];
    neutral!.walkDecls(decl => {
      if (decl.value === 'initial') reset.push(decl.prop);
    });
    expect(reset).toEqual(
      expect.arrayContaining(['--fve-primary', '--fve-dark-primary']),
    );
  });

  it('lets the host’s own variables on the same element win over a preset', () => {
    // `:where()` weighs nothing: a host that chose a preset and set one
    // variable of its own on `<html>` keeps its variable, whichever loads
    // first.
    sheet(`:root { --fve-primary: rgb(7, 7, 7); }`);
    sheet(`${THEMES}\n${PROBE}`);
    html.setAttribute('data-fve-preset', 'probe');
    render(<ViewSurface />);

    expect(primaryOf(surface())).toBe('rgb(7,7,7)');
  });

  it('writes no attribute when nothing asks for a preset', async () => {
    render(withDialog());

    const dialog = await screen.findByRole('dialog');
    expect(surface()?.hasAttribute('data-fve-preset')).toBe(false);
    expect(dialog.hasAttribute('data-fve-preset')).toBe(false);
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
