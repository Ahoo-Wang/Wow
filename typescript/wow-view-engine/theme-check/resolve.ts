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

/**
 * The theme's tokens as a preset and a mode resolve them, read off the
 * stylesheet's token rules and the presets — no browser, no cascade engine,
 * just the rules those files keep (theme-architecture.md 3, S2): every token
 * is `var(--fve-[dark-]<name>, var(--fvp-[dark-]<name>, <built-in>))` — the
 * host's layer, then the preset's — a preset assigns the `--fvp-*` it
 * changes, and the reset rule (`@layer fve-reset`) empties the preset layer
 * on every element that names a preset before its block applies. The tokens
 * a brand colour derives (theme-architecture.md 2, S4) read one more layer
 * between the two, `var(--_fve-brand-<name>)`, declared in the stylesheet's
 * one `@supports` block from the host's `--fve-brand` and the preset's
 * bounds; with no brand colour it is invalid and the preset's layer is read.
 * A role a link draws in another token (theme-architecture.md 9.3) reads
 * `var(--_fve-link-<name>)` there too, declared by the links' rule from the
 * link's share — `--_fve-<name>-link`, the host's or the preset's — and left
 * as a `var()` of the token it names, which is resolved on the surface with
 * the rest; with no share it is invalid and the preset's layer is read.
 *
 * One resolver serves two readers (theme-architecture.md 5.2, S7): the
 * package's tests, over `src/styles.css` and the preset sources, and
 * theme-check, over what the build ships — `dist/theme-source.css` (the
 * token rules of `styles.css`, as written), `dist/themes.css` and
 * `dist/theme-tokens.json`. So a host's theme is measured by exactly the
 * arithmetic the built-in presets are held to.
 *
 * Tokens are keyed by the variable the blocks declare — `--primary`, and
 * the engine's own `--_fve-row-hover`; `tokenVariable` names it for a
 * registry entry.
 *
 * The WCAG arithmetic is the one Storybook's contrast matrix and the chart's
 * `inkOn` use: a half-transparent layer composed over what it lies on in
 * gamma-encoded sRGB, the way the browser paints it, channels clipped to the
 * gamut, then relative luminance and `(lighter + 0.05) / (darker + 0.05)` —
 * culori's `wcagContrast`.
 */

import { clampChroma, parse } from 'culori';
import postcss from 'postcss';
import {
  type ContrastPair,
  expandPairs,
  type Layer,
  type PairKind,
} from '../src/ui/theme/pairs';
import {
  at,
  contrast,
  type Gamut,
  mix,
  over,
  relativeOklch,
  rgba,
  type Rgba,
  splitArguments,
} from './colour';
import type { Registry } from './registry';

/**
 * The host's `--fve-*`, as a host would put them on `<html>`: read first by
 * every token, and by the brand's derivation — `--fve-brand`, and a bound a
 * host overrides (theme-architecture.md 2).
 */
export type HostVariables = Readonly<Record<string, string>>;

export type Mode = 'light' | 'dark';

/** The host's change convention, `data-fve-change-colors` (themes.md 2.6). */
export type Convention = 'semantic' | 'green-up' | 'red-up';

export const CONVENTIONS: readonly Convention[] = [
  'semantic',
  'green-up',
  'red-up',
];

/** Where a preset sits and what is around it. */
export interface Placement {
  /** The presets on the elements around it, outermost first. */
  outer?: readonly string[];
  /** Presets beyond the built-in ones, by name — a host's own. */
  extra?: ReadonlyMap<string, ReadonlyMap<string, string>>;
  /** `data-fve-brand-chart` on the surface or an ancestor. */
  brandChart?: boolean;
}

/** One measured pair: what it is, the line it owes and what it reads. */
export interface Measured {
  name: string;
  kind: PairKind;
  line: number;
  ratio: number;
}

/** What the resolver reads: the token rules, the presets, the registry. */
export interface ThemeSources {
  /** `styles.css`, or the token rules of it the build ships. */
  readonly styles: string;
  /** Every preset block, one after another (`themes.css`). */
  readonly presets: string;
  readonly registry: Pick<
    Registry,
    'tokens' | 'grounds' | 'lines' | 'presetLines'
  >;
}

/** A declaration's value on one line, with no padding inside its brackets. */
const tidy = (value: string) =>
  value
    .replace(/\s+/g, ' ')
    .replace(/\(\s+/g, '(')
    .replace(/\s+\)/g, ')')
    .trim();

const LIGHT_BLOCK = '.fve-root,\n.fve-tokens';
const DARK_BLOCK =
  ".dark .fve-root:not([data-theme='light']),\n.fve-root[data-theme='dark'],\n.dark .fve-tokens";

/** A selector list compared by its parts, whatever the indentation. */
const sameSelector = (one: string, other: string) =>
  one.replace(/\s+/g, ' ') === other.replace(/\s+/g, ' ');

/** Every `:where([data-fve-preset='<name>'])` block of a stylesheet. */
export function presetBlocks(css: string): Map<string, Map<string, string>> {
  const found = new Map<string, Map<string, string>>();
  postcss.parse(css).walkRules(rule => {
    const name = /data-fve-preset='([^']+)'/.exec(rule.selector)?.[1];
    if (!name) return;
    const assigned = found.get(name) ?? new Map<string, string>();
    rule.walkDecls(decl => {
      assigned.set(decl.prop, tidy(decl.value));
    });
    found.set(name, assigned);
  });
  return found;
}

/**
 * The rules of a stylesheet a resolver reads, as written: every rule that
 * declares a custom property (only those declarations kept), inside the
 * at-rules around it, and the reset layer. What the build ships as
 * `dist/theme-source.css`; resolving it is resolving the whole stylesheet.
 */
export function themeSource(styles: string): string {
  const root = postcss.parse(styles);
  root.walkComments(comment => {
    comment.remove();
  });
  root.walkDecls(decl => {
    if (!decl.prop.startsWith('--')) decl.remove();
  });
  const empty = (node: postcss.Container): boolean =>
    (node.nodes ?? []).every(
      child =>
        child.type !== 'decl' &&
        (!('nodes' in child) || empty(child as postcss.Container)),
    );
  root.walk(node => {
    if (node.type === 'rule' && empty(node)) node.remove();
  });
  root.walkAtRules(rule => {
    if (rule.nodes && empty(rule)) rule.remove();
  });
  root.walkAtRules(rule => {
    // `@layer fve-reset;` and the like: keep; bodiless Tailwind directives
    // (`@import`, `@plugin`, `@custom-variant`) read nothing here.
    if (!rule.nodes && rule.name !== 'layer') rule.remove();
  });
  root.each(node => {
    node.raws.before = '\n';
  });
  return `${root.toString().trim()}\n`;
}

/**
 * `var(--fve-x, fallback)`, `var(--_fve-brand-x, fallback)` (or `derived`),
 * `var(--_fve-link-x, fallback)` or `var(--fvp-x, fallback)`, split at its
 * first top-level comma — or with no fallback at all, a token with no
 * built-in value (the controls group), which is unset until a theme gives
 * it one.
 */
function layerReference(
  value: string,
): [string, string | undefined] | undefined {
  const match =
    /^var\((--fv[ep]-[\w-]+|--_fve-(?:brand|derived|link)-[\w-]+)(?:,\s*([\s\S]+))?\)$/.exec(
      value,
    );
  return match ? [match[1], match[2]?.trim()] : undefined;
}

/**
 * A value with every `var(--fve-*)`, `var(--fvp-*)` and bound the block
 * resolved (`var(--_fve-brand-*)`) in it replaced by what `given` has for
 * it, innermost first, a fallback taken where it has none; `undefined` when
 * one is left with nothing, as CSS makes the whole value invalid.
 */
function substitute(
  value: string,
  given: (variable: string) => string | undefined,
): string | undefined {
  const held: string[] = [];
  let text = value;
  const innermost = /var\((--(?:fv[ep]|_fve-brand)-[\w-]+)(?:,\s*([^()]*))?\)/;
  for (let match = innermost.exec(text); match; match = innermost.exec(text)) {
    const [whole, variable, fallback] = match;
    const set = given(variable);
    const found =
      set !== undefined && set !== 'initial'
        ? set
        : fallback?.replace(/§(\d+)/g, (_, at: string) => held[Number(at)]);
    if (found === undefined) return undefined;
    held.push(found);
    text = text.replace(whole, `§${held.length - 1}`);
  }
  return text.replace(/§(\d+)/g, (_, at: string) => held[Number(at)]);
}

interface BrandDeclaration {
  value: string;
  /** Declared only under `data-fve-brand-chart` (the first chart slot). */
  charted: boolean;
}

/** One stylesheet's token machinery, read once and resolved on demand. */
export interface Resolver {
  /** The variable the blocks declare a registry token as. */
  tokenVariable(name: string): string;
  /** The built-in presets, each as the preset variables it assigns. */
  presets(): ReadonlyMap<string, ReadonlyMap<string, string>>;
  /** Their names, in the order the presets are written. */
  readonly presetNames: readonly string[];
  /** What the reset rule empties on an element that names a preset. */
  resetVariables(): ReadonlySet<string>;
  /** The links' rule: each `--_fve-link-<role>` as written. */
  linkRule(): ReadonlyMap<string, string>;
  /** Each token as the CSS text the cascade hands on. */
  declared(
    preset: string,
    mode: Mode,
    convention?: Convention,
    host?: HostVariables,
    placement?: Placement,
  ): Map<string, string>;
  /** Each colour token, resolved. */
  resolveTokens(
    preset: string,
    mode: Mode,
    convention?: Convention,
    host?: HostVariables,
    gamut?: Gamut,
    placement?: Placement,
  ): Map<string, Rgba>;
  /** The lines one preset owes, kind by kind. */
  linesOf(preset: string): Record<PairKind, number>;
  /** Every pair of one mode, measured against that preset's lines. */
  measure(
    preset: string,
    mode: Mode,
    convention?: Convention,
    host?: HostVariables,
    gamut?: Gamut,
    placement?: Placement,
  ): Measured[];
}

/** A resolver over one stylesheet, its presets and the registry. */
export function createResolver({
  styles,
  presets: presetText,
  registry,
}: ThemeSources): Resolver {
  const root = postcss.parse(styles);
  const tokenVariable = (name: string) =>
    registry.tokens.find(token => token.name === name)?.declared ?? `--${name}`;

  /**
   * The variables the blocks declare a colour as — the registry's colour
   * tokens, and the engine's own derived ones every token reads through
   * (the change convention's pair).
   */
  const COLOR_VARIABLES: ReadonlySet<string> = new Set([
    ...registry.tokens
      .filter(entry => entry.kind === 'color' && entry.declared)
      .map(entry => entry.declared!),
    '--_fve-convention-rise',
    '--_fve-convention-fall',
  ]);

  /**
   * Every custom property one rule declares, as written — on a screen: what
   * the print rules put over it on paper is not a token block.
   */
  const blocks = new Map<string, Map<string, string>>();
  const block = (selector: string): Map<string, string> => {
    const known = blocks.get(selector);
    if (known) return known;
    const declared = new Map<string, string>();
    root.walkRules(rule => {
      if (!sameSelector(rule.selector, selector)) return;
      if (
        rule.parent?.type === 'atrule' &&
        /^print$/.test((rule.parent as postcss.AtRule).params)
      )
        return;
      rule.walkDecls(/^--/, decl => {
        declared.set(decl.prop, tidy(decl.value));
      });
    });
    if (declared.size === 0) throw new Error(`no ${selector} in styles.css`);
    blocks.set(selector, declared);
    return declared;
  };

  /**
   * The change convention's pair, as the stylesheet sets it on the
   * boundary: the default rule, and the one `red-up` crosses it with.
   * `green-up` colours a direction as `semantic` does, so it adds no rule
   * of its own.
   */
  const conventionBlock = (convention: Convention): Map<string, string> => {
    const declared = new Map<string, string>();
    root.walkRules(rule => {
      const crossed = rule.selector.includes("data-fve-change-colors='red-up'");
      if (
        !rule.some(
          node =>
            node.type === 'decl' && node.prop === '--_fve-convention-rise',
        )
      )
        return;
      if (crossed && convention !== 'red-up') return;
      rule.walkDecls(/^--/, decl => {
        declared.set(decl.prop, tidy(decl.value));
      });
    });
    return declared;
  };

  let presetCache: Map<string, Map<string, string>> | undefined;
  const presets = () => (presetCache ??= presetBlocks(presetText));

  let resetCache: ReadonlySet<string> | undefined;
  const resetVariables = (): ReadonlySet<string> => {
    if (resetCache) return resetCache;
    const cleared = new Set<string>();
    root.walkAtRules('layer', layer => {
      if (layer.params !== 'fve-reset') return;
      layer.walkRules(rule => {
        if (rule.selector !== ':where([data-fve-preset])') return;
        rule.walkDecls(decl => {
          if (decl.value === 'initial') cleared.add(decl.prop);
        });
      });
    });
    resetCache = cleared;
    return cleared;
  };

  /**
   * The preset layer on an element that names `preset`, inside elements
   * that named `outer` ones (outermost first): each outer preset's values
   * inherited down, the reset emptying what it names at each preset, and the
   * preset's own values over it — the cascade, as far as the preset layer
   * goes.
   */
  const presetLayer = (
    preset: string,
    outer: readonly string[],
    sources: ReadonlyMap<string, ReadonlyMap<string, string>>,
  ): Map<string, string> => {
    const layer = new Map<string, string>();
    for (const name of [...outer, preset]) {
      const assigned = sources.get(name);
      if (!assigned) throw new Error(`no preset ${name}`);
      for (const variable of resetVariables()) layer.delete(variable);
      for (const [variable, value] of assigned) layer.set(variable, value);
    }
    return layer;
  };

  let brandCache: Map<string, BrandDeclaration> | undefined;
  /**
   * What the stylesheet derives from a brand colour, as written: the one
   * `@supports` block's `--_fve-brand-*` (theme-architecture.md 2.2), each
   * with whether its rule asks for `data-fve-brand-chart`.
   */
  const brandBlock = (): ReadonlyMap<string, BrandDeclaration> => {
    if (brandCache) return brandCache;
    const declared = new Map<string, BrandDeclaration>();
    root.walkAtRules('supports', supports => {
      supports.walkDecls(/^--_fve-(brand|derived)-/, decl => {
        const rule = decl.parent as postcss.Rule;
        declared.set(decl.prop, {
          value: tidy(decl.value),
          charted: rule.selector.includes('data-fve-brand-chart'),
        });
      });
    });
    if (declared.size === 0) throw new Error('no brand block in styles.css');
    brandCache = declared;
    return declared;
  };

  let linkCache: Map<string, string> | undefined;
  const linkRule = (): ReadonlyMap<string, string> => {
    if (linkCache) return linkCache;
    const declared = new Map<string, string>();
    root.walkRules(rule => {
      if (!sameSelector(rule.selector, ':where(.fve-root, .fve-tokens)'))
        return;
      if (rule.parent?.type === 'atrule') return;
      rule.walkDecls(/^--_fve-link-/, decl => {
        declared.set(decl.prop, tidy(decl.value));
      });
    });
    if (declared.size === 0) throw new Error('no links rule in styles.css');
    linkCache = declared;
    return declared;
  };

  const declared: Resolver['declared'] = (
    preset,
    mode,
    convention = 'semantic',
    host = {},
    { outer = [], extra, brandChart = false } = {},
  ) => {
    const sources = extra ? new Map([...presets(), ...extra]) : presets();
    if (!sources.has(preset))
      throw new Error(`no preset ${preset} in themes.css`);
    const assigned = presetLayer(preset, outer, sources);
    const tokens = new Map<string, string>();
    const layers = (variable: string) =>
      variable.startsWith('--fve-') ? host[variable] : assigned.get(variable);
    // What the brand colour derives, on the boundary: invalid — left out —
    // where the host gave no brand colour, or the preset no bound it needs.
    const brand = new Map<string, string>();
    for (const [variable, { value, charted }] of brandBlock()) {
      if (charted && !brandChart) continue;
      const derived = substitute(value, v => layers(v) ?? brand.get(v));
      if (derived !== undefined) brand.set(variable, derived);
    }
    // The layers in the order a token reads them: the host's variable, the
    // brand's derivation, the preset's, the built-in value. A value that
    // reads a variable nobody set is invalid, and the next layer is read.
    const layered = (value: string): string | undefined => {
      const reference = layerReference(value);
      if (!reference) return value;
      const [variable, fallback] = reference;
      const given = /^--_fve-(brand|derived)-/.test(variable)
        ? brand.get(variable)
        : variable.startsWith('--_fve-link-')
          ? link(variable)
          : layers(variable);
      const substituted =
        given !== undefined && given !== 'initial'
          ? substitute(given, layers)
          : undefined;
      if (substituted !== undefined) return substituted;
      return fallback === undefined ? undefined : layered(fallback);
    };
    // A link, on the boundary: its share — the host's, then the preset's —
    // in place of `var(--_fve-<role>-link)`, the token it names left for the
    // surface to resolve; invalid, and left out, where nobody gave a share.
    const link = (variable: string): string | undefined => {
      const text = linkRule().get(variable);
      if (text === undefined) throw new Error(`no link ${variable}`);
      const share = /var\((--_fve-[\w-]+-link)\)/.exec(text);
      if (!share) throw new Error(`${variable} reads no share`);
      const declaration = block(LIGHT_BLOCK).get(share[1]);
      if (declaration === undefined) throw new Error(`no token ${share[1]}`);
      const given = layered(declaration);
      return given === undefined ? undefined : text.replace(share[0], given);
    };
    const read = (declarations: Map<string, string>) => {
      for (const [token, value] of declarations) {
        const resolved = layered(value);
        // No value and no fallback: the token is unset (guaranteed-invalid),
        // and what reads it falls back on its own.
        if (resolved === undefined) tokens.delete(token);
        else tokens.set(token, resolved);
      }
    };
    read(conventionBlock(convention));
    read(block(LIGHT_BLOCK));
    if (mode === 'dark') read(block(DARK_BLOCK));
    return tokens;
  };

  const resolveTokens: Resolver['resolveTokens'] = (
    preset,
    mode,
    convention = 'semantic',
    host = {},
    gamut = 'clip',
    placement = {},
  ) => {
    const text = declared(preset, mode, convention, host, placement);
    const resolved = new Map<string, Rgba>();

    const evaluate = (value: string, seen: string[]): Rgba => {
      const reference = /^var\((--[\w-]+)\)$/.exec(value);
      if (reference) return token(reference[1], seen);
      if (value === 'transparent') return { r: 0, g: 0, b: 0, alpha: 0 };
      const relative = relativeOklch(value, name => token(name, seen));
      if (relative)
        return rgba(
          gamut === 'clip' ? relative : clampChroma(relative, 'oklch'),
        );
      const call = /^color-mix\(in oklab,\s*([\s\S]+)\)$/.exec(value);
      if (call) {
        const [first, second] = splitArguments(call[1]);
        // A weight is a percentage, or a token that holds one: a state's
        // strength (`var(--_fve-state-hover)`), as this call declares it.
        const weighted = (part: string) => {
          const match = /^([\s\S]+?)\s+(?:([\d.]+)%|var\((--[\w-]+)\))$/.exec(
            part,
          );
          if (!match) return { color: evaluate(part, seen), weight: undefined };
          const found = text.get(match[3]);
          const share = match[2] ?? /^([\d.]+)%$/.exec(found ?? '')?.[1];
          if (share === undefined)
            throw new Error(
              `${match[3].replace(/^--(_fve-)?/, '')} is ${found}, not a percentage`,
            );
          return {
            color: evaluate(match[1], seen),
            weight: Number(share) / 100,
          };
        };
        const one = weighted(first);
        const other = weighted(second);
        const p = one.weight ?? 1 - (other.weight ?? 0.5);
        return mix(one.color, p, other.color);
      }
      const parsed = parse(value);
      if (!parsed) throw new Error(`unreadable colour ${value}`);
      return rgba(parsed);
    };

    const token = (name: string, seen: string[]): Rgba => {
      const known = resolved.get(name);
      if (known) return known;
      if (seen.includes(name))
        throw new Error(`${[...seen, name].join(' → ')} is a cycle`);
      const value = text.get(name);
      if (value === undefined) throw new Error(`${name} is not a token`);
      const color = evaluate(value, [...seen, name]);
      resolved.set(name, color);
      return color;
    };

    for (const name of text.keys()) {
      // Lengths (`radius`, `text-ui`), weights (`title-weight`), keywords
      // (`focus-style`) and shadows (`shadow-*`, `card-shadow`) are not
      // colours: the registry says which a token is.
      if (!COLOR_VARIABLES.has(name)) continue;
      token(name, []);
    }
    return resolved;
  };

  const linesOf = (preset: string): Record<PairKind, number> => ({
    ...registry.lines,
    ...registry.presetLines[preset],
  });

  const pairs = new Map<Mode, ContrastPair[]>();
  const measure: Resolver['measure'] = (
    preset,
    mode,
    convention = 'semantic',
    host,
    gamut,
    placement,
  ) => {
    const tokens = resolveTokens(
      preset,
      mode,
      convention,
      host,
      gamut,
      placement,
    );
    const paint = ({ token, alpha = 1 }: Layer): Rgba => {
      const color = tokens.get(tokenVariable(token));
      if (!color) throw new Error(`${tokenVariable(token)} did not resolve`);
      return at(color, alpha);
    };
    const lines = linesOf(preset);
    if (!pairs.has(mode)) pairs.set(mode, expandPairs(registry.grounds, mode));
    return pairs
      .get(mode)!
      .map(({ name, kind, ink, ground: [bottom, ...rest] }) => ({
        name,
        kind,
        line: lines[kind],
        ratio: contrast(
          paint(ink),
          rest.reduce(
            (under, layer) => over(paint(layer), under),
            paint(bottom),
          ),
        ),
      }));
  };

  return {
    tokenVariable,
    presets,
    get presetNames() {
      return [...presets().keys()];
    },
    resetVariables,
    linkRule,
    declared,
    resolveTokens,
    linesOf,
    measure,
  };
}
