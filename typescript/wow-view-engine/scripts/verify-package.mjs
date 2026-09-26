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

// Run after building: pnpm --filter @ahoo-wang/wow-view-engine test:package
//
// Six properties of the built package, which no unit test can see because
// each one is about the artifact rather than the source (docs/design/README.md):
//
// 1. Every declared entry resolves and imports, and a code entry exports at
//    run time exactly the values its list under `test/surface/` names.
// 2. The root entry's types need no DOM lib, so a Node or worker consumer can
//    use the kernels and the runtime.
// 3. No JavaScript entry pulls in the stylesheet, so importing the package
//    never puts CSS in a host page that did not ask for it.
// 4. The stylesheet holds no rule outside the two style boundaries at all, so
//    a host that does import it keeps its own page and its own variables, and
//    every scope it does carry names both of them — bar the preset reset, one
//    rule in the lowest layer that only empties the preset layer. Every other
//    rule weighs one class more than its source wrote it, so a host's
//    Tailwind, imported before or after, never outweighs it on a surface.
// 5. The `dark:` utilities and the dark tokens turn on the same roots, so no
//    host can end up with the utilities of one mode over the other's tokens.
// 6. Every token reads a host-level `--fve-*` variable first and a preset's
//    `--fvp-*` next, so a host customises the theme from `:root` without
//    reaching inside the root, and beats any preset.
// 9. The presets (`/themes.css`, and `/themes/<name>.css` one by one) only
//    assign the preset layer, only what they change, the chart colours and
//    the shadows whole or not at all.
// 10. The shadcn bridge (`/shadcn-bridge.css`) only points the preset layer
//    at a host's shadcn tokens, bar input, ring and the status colours, and
//    only while no preset is named.
// 11. The stylesheets' gzipped sizes, the presets' under their budget,
//    one by one (`/themes/<name>.css`) and together.
// 12. No entry grows by accident: the three code entries, the chart chunk
//    (still loaded lazily) and the two stylesheets, gzipped, each under a
//    regression ceiling in `scripts/size-budget.json` (not a size target).
import assert from 'node:assert/strict';
import {
  readdirSync,
  readFileSync,
  statSync,
  mkdtempSync,
  writeFileSync,
  rmSync,
} from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import {
  checkSizes,
  gzippedSize,
  lazyChunks,
  staticClosure,
} from '../../../.github/scripts/size-budget.mjs';
import postcss from 'postcss';
import ts from 'typescript';

const packageRoot = new URL('../', import.meta.url);
const manifest = JSON.parse(
  readFileSync(new URL('package.json', packageRoot), 'utf8'),
);
const name = manifest.name;

/** Every file `exports` promises, by the specifier that reaches it. */
const targets = new Map();
// A pattern entry (`./themes/*.css`) promises a directory of files, not one;
// the presets' check below reads that directory.
const patterns = new Map();
for (const [specifier, entry] of Object.entries(manifest.exports)) {
  if (specifier.includes('*')) {
    patterns.set(specifier, entry);
    continue;
  }
  const paths = typeof entry === 'string' ? { default: entry } : entry;
  for (const path of Object.values(paths)) {
    assert.ok(
      statSync(new URL(path, packageRoot)).size > 0,
      `${path} is missing or empty; run the build first`,
    );
  }
  targets.set(specifier, paths);
}

// 1. Entries resolve where they promise to.
const jsEntries = [];
for (const [specifier, paths] of targets) {
  const subpath = specifier === '.' ? '' : specifier.slice(1);
  if (!paths.import) continue;
  const resolved = import.meta.resolve(name + subpath);
  assert.equal(
    resolved,
    new URL(paths.import, packageRoot).href,
    `${name}${subpath} does not resolve to its declared target`,
  );
  jsEntries.push({ specifier: name + subpath, resolved });
}
assert.ok(jsEntries.length >= 3, 'Expected the root, /react and /ui entries');

// The stylesheet ships as its own entry, which a host imports deliberately.
const styles = manifest.exports['./styles.css'];
assert.equal(typeof styles, 'string', './styles.css must be a single target');
const stylesheet = readFileSync(new URL(styles, packageRoot), 'utf8');

/**
 * The two style boundaries, spelled out here rather than imported from
 * `scripts/scope-utilities.mjs`: this file is what fails when one of them goes
 * missing, and a check that reads its expectations from the thing it checks
 * would pass a stylesheet that had quietly lost a boundary. `.fve-root` is the
 * surface `ViewSurface` renders; `.fve-tokens` is the boundary a host puts on
 * its own chrome, which carries the tokens and the utilities and nothing of a
 * surface — no paint, no `data-theme` (D17-10).
 */
const BOUNDARIES = ['.fve-root', '.fve-tokens'];

/**
 * The weight `scripts/scope-utilities.mjs` puts on every rule, as the
 * minifier writes it: an `:is()` of both boundaries and their contents.
 */
const SCOPE_PARTS = BOUNDARIES.flatMap(boundary => [boundary, `${boundary} *`]);
for (const boundary of BOUNDARIES)
  assert.ok(
    stylesheet.includes(boundary),
    `The theme must hang off the ${boundary} boundary`,
  );

// 4. No rule sits outside the boundaries — custom properties included.
//
// Tailwind's preflight would reset `*`, `html`, headings, lists and buttons on
// the whole host page, and its utilities are bare classes a host may share;
// `scripts/scope-utilities.mjs` pins every rule to the root at build time, and
// this is where that is checked. A rule that only sets custom properties is no
// exception, because a host reads custom properties: a `:root` variable is as
// much a leak as a painted pixel. Tailwind's theme variables (`--spacing`,
// `--radius-md`, `--font-sans`, …) would otherwise overwrite a host Tailwind's
// values for the same names, or be overwritten by them, and its `--tw-*`
// defaults on `*` are scoped to the root like everything else. Only
// `@property` registrations stay global, and they have no selector at all.
const RESET_LAYER = 'fve-reset';
const scopedRules = styleRules(stylesheet).filter(
  ({ layer }) => layer !== RESET_LAYER,
);
const leaks = scopedRules.filter(
  ({ selector }) => !BOUNDARIES.some(boundary => selector.includes(boundary)),
);
assert.deepEqual(
  leaks.map(({ selector }) => selector),
  [],
  'The stylesheet has rules outside the style boundaries',
);
const globalRoots = styleRules(stylesheet).flatMap(({ selector }) =>
  selectorList(selector).filter(part => part === ':root' || part === ':host'),
);
assert.deepEqual(
  globalRoots,
  [],
  "The stylesheet still carries a :root or :host selector; Tailwind's theme variables must live on the boundaries",
);

// 4b. And every scope it pins a rule to names both boundaries.
//
// `scripts/scope-utilities.mjs` gives each rule the same subject —
// `:where(.fve-root, .fve-root *, .fve-tokens, .fve-tokens *)` — and the dark
// variant names both in its own way. Lose one of them at either end and a
// whole boundary silently stops painting: a host's chrome with no utilities,
// or a surface with no theme. A scope that names one boundary must name the
// other, whatever else it says.
const partial = scopedRules.filter(({ selector }) =>
  scopeArguments(selector).some(scope => {
    const parts = selectorList(scope);
    const named = BOUNDARIES.filter(boundary =>
      parts.some(part => part.includes(boundary)),
    );
    return named.length > 0 && named.length < BOUNDARIES.length;
  }),
);
assert.deepEqual(
  partial.map(({ selector }) => selector),
  [],
  `The stylesheet scopes rules to one boundary and not the other; every scope must name ${BOUNDARIES.join(' and ')}`,
);
const fullyScoped = scopedRules.filter(({ selector }) =>
  scopeArguments(selector).some(scope => {
    const parts = selectorList(scope);
    return BOUNDARIES.every(
      boundary => parts.includes(boundary) && parts.includes(`${boundary} *`),
    );
  }),
);
assert.ok(
  fullyScoped.length > 0,
  'No rule carries the scope the build pins them to; scope-utilities did not run',
);

// 4c. And every rule weighs one class more than its source wrote it (G16).
//
// A host's Tailwind fills the same `utilities` layer, where a tie in weight
// goes to whichever stylesheet came later: imported before the host's own,
// our `md:w-64` lost to the host's `.w-full` on our own surface. The scope is
// an `:is()` of both boundaries and what is inside them — one class — on
// every part of every selector, the ones that already named a boundary too,
// so the cascade among our own rules is unchanged and the host's order no
// longer matters.
const unweighed = scopedRules.flatMap(({ selector }) =>
  selectorList(selector).filter(part => unscoped(part) === part),
);
assert.deepEqual(
  unweighed,
  [],
  `Every selector must carry :is(${SCOPE_PARTS.join(', ')}), the one class that keeps a host's utilities from outweighing ours`,
);

// 4d. The preset reset: one rule in the lowest layer, and nothing else there.
//
// `:where([data-fve-preset])` empties the preset layer (`--fvp-*`) on every
// element that names a preset, `<html>` included, so a preset pinned inside
// another replaces it whole (theme-architecture.md 3.2). It is the one rule
// outside the boundaries, and it may say nothing but `initial` to a preset
// variable; the list is checked against the registry below (9). Its layer
// comes first, so the layers Tailwind declares — and any preset block — win.
const resetRules = styleRules(stylesheet).filter(
  ({ layer }) => layer === RESET_LAYER,
);
assert.deepEqual(
  resetRules.map(({ selector }) => unquoted(selector)),
  [':where([data-fve-preset])'],
  `@layer ${RESET_LAYER} must hold one :where([data-fve-preset]) rule`,
);
const [reset] = resetRules;
assert.deepEqual(
  reset.declarations.filter(
    property =>
      !property.startsWith('--fvp-') ||
      reset.values.get(property) !== 'initial',
  ),
  [],
  `The reset may only set --fvp-* variables to initial`,
);
// Tailwind's own `properties` layer, the `--tw-*` defaults of a browser
// without `@property`, may come before it; it sets no preset variable.
const layerOrder = [
  ...new Set([...stylesheet.matchAll(/@layer\s+([\w-]+)/g)].map(m => m[1])),
].filter(layer => layer !== 'properties');
assert.equal(
  layerOrder[0],
  RESET_LAYER,
  `@layer ${RESET_LAYER} must come before every layer of Tailwind's (${layerOrder.join(', ')})`,
);

// 5. Light and dark are one decision, spelled the same way twice.
//
// `src/styles.css` names the dark roots once for the `@custom-variant dark`
// the vendored components' `dark:` utilities compile against, and once for the
// token block. Let the two drift and a host is served the utilities of one
// mode over the tokens of the other — dark `data-theme` with light colours, or
// a surface pinned light inside a `.dark` page painted half dark.
const darkTokens = styleRules(stylesheet).find(
  ({ selector, declarations }) =>
    !selector.includes('dark\\:') &&
    selector.includes('data-theme') &&
    declarations.includes('color-scheme') &&
    declarations.includes('--background'),
);
assert.ok(darkTokens, 'The stylesheet sets no dark tokens');
const tokenSelectors = selectorList(darkTokens.selector).map(unscoped);
assert.equal(
  tokenSelectors.length,
  3,
  'The dark tokens should name a pinned root, a root following a .dark host, and the tokens boundary under one',
);

// A compiled utility carries the variant on its subject, as in
// `.dark\:bg-input\/30:where(<the variant>)`. `scripts/scope-utilities.mjs`
// would append a second `:where(.fve-root, .fve-root *)`, and does so as soon
// as the variant stops naming the root itself, so read the first one.
const darkUtility = styleRules(stylesheet).find(({ selector }) =>
  selector.startsWith('.dark\\:'),
);
assert.ok(darkUtility, 'The stylesheet compiled no dark: utility to check');
const variantSelectors = selectorList(whereArguments(darkUtility.selector)[0]);
assert.deepEqual(
  variantSelectors.map(unquoted).sort(),
  tokenSelectors
    .flatMap(selector => [selector, `${selector} ${descendants(selector)}`])
    .map(unquoted)
    .sort(),
  'The dark: variant and the dark tokens must name the same roots',
);

/**
 * What a dark root hands its `dark:` utilities to: everything inside it —
 * except, under the tokens boundary, an element a surface answers for. The
 * two surface entries already cover a surface and its contents, each by its
 * own mode, so a host's chrome that also claimed them would paint a view
 * pinned to light with dark utilities. That is the failure nested roots have,
 * and not having it is why `.fve-tokens` exists (D17-10).
 */
function descendants(selector) {
  return selector.includes('.fve-tokens')
    ? ':not(.fve-root, .fve-root *)'
    : '*';
}

// 6. Every token is an indirection through a host-level variable.
//
// A host customises the theme by setting `--fve-<token>` for light and
// `--fve-dark-<token>` for dark on its own `:root`, and every token here reads
// that variable with the built-in value as its fallback. Because the host sets
// them above everything, the override reaches the root and the popups
// portalled out of it alike, in follow-the-host and pinned modes alike, with
// no selector to scope and no load order to win. A token left as a literal
// would quietly ignore the host, so both blocks are read token by token.
// The navigation column's ground, under shadcn's own names. They are named
// here rather than counted, because the point is not "some sidebar tokens
// exist" but that both modes carry *the same* five: a column themed in light
// and unthemed in dark is the one failure a host cannot see from one screen.
const SIDEBAR_TOKENS = [
  '--sidebar',
  '--sidebar-foreground',
  '--sidebar-accent',
  '--sidebar-accent-foreground',
  '--sidebar-border',
];

const lightTokens = styleRules(stylesheet).find(
  ({ selector, declarations }) =>
    !selector.includes('data-theme') &&
    !selector.includes('.dark ') &&
    declarations.includes('color-scheme') &&
    declarations.includes('--background'),
);
assert.deepEqual(
  lightTokens && selectorList(lightTokens.selector).map(unscoped),
  BOUNDARIES,
  'The light tokens must be declared on both boundaries, in one block',
);
assert.ok(lightTokens, 'The stylesheet sets no light tokens');
for (const [mode, rule, prefix] of [
  ['light', lightTokens, '--fve-'],
  ['dark', darkTokens, '--fve-dark-'],
]) {
  // The minifier may drop the space after the comma; the fallback is the
  // rest — the preset's layer, then the built-in value. A token under the
  // engine's own name (`--_fve-row-hover`) reads the host variable of its
  // registry name.
  const literal = rule.tokens.filter(([property, value]) => {
    const token = property.replace(/^--(_fve-)?/, '');
    return !new RegExp(`^var\\(${prefix}${token},\\s*.+\\)$`).test(value);
  });
  assert.deepEqual(
    literal.map(([property]) => property),
    [],
    `The ${mode} tokens must each read ${prefix}<token> first, with the preset layer and the built-in value as the fallback`,
  );
  assert.deepEqual(
    rule.tokens
      .map(([property]) => property)
      .filter(property => property.startsWith('--sidebar')),
    SIDEBAR_TOKENS,
    `The ${mode} tokens must carry exactly the sidebar group the navigation column paints with, in one order`,
  );
}

// 9. The presets are values for the preset layer, and only that.
//
// `/themes.css` is an optional entry a host imports beside the theme. It may
// sit on the host's `<html>`, outside every boundary, so what makes it safe is
// what it may say rather than where: every rule is
// `:where([data-fve-preset=<name>])`, outside any layer; every declaration
// assigns a `--fvp-` variable (theme-architecture.md 3, S2), which nothing of
// the host's reads and which every token reads only after the host's own
// `--fve-*`; and there is no at-rule, so nothing is painted, registered or
// imported. A brand colour is no preset's: the stylesheet derives it, in its
// own feature query (theme-architecture.md 2), and a preset gives only the
// numbers it is held to.
//
// A preset writes only what it changes, never `initial`: the reset rule (4d)
// empties the preset layer on every element that names a preset, so a preset
// pinned inside another replaces it whole, and what it leaves out is the
// built-in value. Two groups are one design each and go whole or not at all —
// the eight chart colours of both modes and the three shadows of both modes —
// and the registry says which (`whole`). Never a preset's: `pin-shadow` (the
// mode's), `text-ui` (the host's typography) and `rise` / `fall` (the host's
// change convention, which a preset would undo).
//
// Which variable is which is not written here: it is the theme's registry
// (`src/ui/theme/tokens.ts`), which the build writes out beside the
// stylesheets as `theme-tokens.json` (theme-architecture.md 5.2). What is
// checked here is that the built stylesheets and the registry agree — every
// host variable the stylesheet reads is registered, every token the registry
// puts in the blocks is there, the reset empties exactly the preset layer —
// and then the presets and the bridge by it.
const themesPath = manifest.exports['./themes.css'];
assert.equal(
  typeof themesPath,
  'string',
  './themes.css must be a single target',
);
const themesText = readFileSync(new URL(themesPath, packageRoot), 'utf8');
const themes = postcss.parse(themesText);
const themeAtRules = [];
themes.walkAtRules(rule => {
  const inside = [];
  rule.walkRules(nested => {
    inside.push(nested.selector);
  });
  themeAtRules.push(`@${rule.name} ${rule.params} ${inside.join(', ')}`);
});
assert.deepEqual(themeAtRules, [], 'themes.css may hold no at-rule');
const PRESET_SELECTOR =
  /^:where\(\[data-fve-preset=['"]?([a-z][a-z0-9-]*)['"]?\]\)$/;
const registry = JSON.parse(
  readFileSync(new URL('dist/theme-tokens.json', packageRoot), 'utf8'),
);
assert.ok(
  Array.isArray(registry.tokens) && registry.tokens.length > 0,
  'dist/theme-tokens.json holds no tokens; the build writes it from src/ui/theme/tokens.ts',
);
const hostVariables = value => value.match(/--fve-[\w-]+/g) ?? [];
// The minifier may split one token block of the source into several rules
// with the same selector, so every rule on either block's selector counts.
const blockReads = mode =>
  new Set(
    styleRules(stylesheet)
      .filter(
        ({ selector }) =>
          selector === (mode === 'light' ? lightTokens : darkTokens).selector,
      )
      .flatMap(({ tokens }) => tokens)
      .flatMap(([, value]) => hostVariables(value)),
  );
const blockVariables = new Set([...blockReads('light'), ...blockReads('dark')]);
// Every host variable the stylesheet reads anywhere — a token block, the
// surface's font, the density rule — is one the registry names. What the
// engine writes for itself is `--_fve-*`, no host variable at all.
const registered = new Set(registry.tokens.flatMap(entry => entry.variables));
const readAnywhere = new Set(
  scopedRules
    .flatMap(({ values }) => [...values.values()])
    .flatMap(hostVariables),
);
assert.deepEqual(
  [...readAnywhere].filter(variable => !registered.has(variable)).sort(),
  [],
  'The stylesheet reads host variables the theme registry does not name',
);
// And a token the registry puts in the blocks is declared there, reading its
// host variable — its dark half in the dark block — and nothing else is.
assert.deepEqual(
  [...blockVariables].sort(),
  registry.tokens
    .filter(entry => entry.block)
    .flatMap(entry => entry.variables)
    .sort(),
  "The token blocks' host variables must be the registry's block tokens",
);
for (const entry of registry.tokens.filter(entry => entry.block)) {
  const [light, dark] = entry.variables;
  assert.ok(
    blockReads('light').has(light),
    `The light tokens must declare ${entry.declared}, reading ${light}`,
  );
  if (dark)
    assert.ok(
      blockReads('dark').has(dark),
      `The dark tokens must declare ${entry.declared}, reading ${dark}`,
    );
}
const presetWhere = test =>
  registry.tokens.filter(test).flatMap(entry => entry.presetVariables);
const presetLayer = presetWhere(() => true).sort();
assert.ok(presetLayer.length > 0, 'The registry gives a preset nothing to set');
// Wherever the stylesheet reads a preset variable it reads the host's first:
// `var(--fve-x, var(--fvp-x …))`, so no preset can beat the host — with the
// brand's derivation between the two for a token the registry marks
// `brand`: `var(--fve-x, var(--_fve-brand-x, var(--fvp-x …)))`, or a link
// for a role a link token names (theme-architecture.md 9.3):
// `var(--fve-x, var(--_fve-link-x, var(--fvp-x …)))`, one for both halves.
const presetReads = scopedRules.flatMap(({ values }) =>
  [...values.values()].flatMap(value =>
    (value.match(/--fvp-[\w-]+/g) ?? []).map(variable => [variable, value]),
  ),
);
assert.deepEqual(
  presetReads
    .filter(
      ([variable, value]) =>
        !presetLayer.includes(variable) ||
        !new RegExp(
          `var\\(${variable.replace('--fvp-', '--fve-')},\\s*(var\\((${variable.replace('--fvp-', '--_fve-brand-')}|${variable.replace(/^--fvp-(dark-)?/, '--_fve-link-')}),\\s*)?var\\(${variable}[,)]`,
        ).test(value),
    )
    .map(([variable]) => variable),
  [],
  'The stylesheet must read each preset variable the registry names, and only after its host variable',
);
// The reset empties exactly the preset layer.
assert.deepEqual(
  [...reset.declarations].sort(),
  presetLayer,
  `@layer ${RESET_LAYER} must empty exactly the registry's preset variables`,
);
const groups = Object.fromEntries(
  Object.keys(registry.groups).map(group => [
    group,
    presetWhere(entry => entry.group === group),
  ]),
);
assert.deepEqual(
  Object.entries(groups)
    .filter(([, members]) => members.length === 0)
    .map(([group]) => group),
  [],
  'Every optional group of the registry names variables',
);
const presets = new Map();
themes.walkRules(rule => {
  const match = PRESET_SELECTOR.exec(rule.selector);
  assert.ok(
    match,
    `themes.css rule ${rule.selector} must be :where([data-fve-preset='<name>'])`,
  );
  const [, preset] = match;
  assert.ok(!presets.has(preset), `themes.css declares ${preset} twice`);
  const assigned = new Map();
  rule.walkDecls(decl => {
    assert.ok(
      presetLayer.includes(decl.prop),
      `themes.css preset ${preset} sets ${decl.prop}; a preset only assigns the registry's --fvp-* variables`,
    );
    assert.notEqual(
      decl.value,
      'initial',
      `themes.css preset ${preset} sets ${decl.prop} to initial; the reset already does, so a preset writes only what it changes`,
    );
    assigned.set(decl.prop, decl.value);
  });
  for (const [group, members] of Object.entries(groups)) {
    if (!registry.groups[group].whole) continue;
    const given = members.filter(variable => assigned.has(variable));
    assert.ok(
      given.length === 0 || given.length === members.length,
      `themes.css preset ${preset} gives ${given.length} of the ${members.length} ${group} variables; the group is given whole or not at all`,
    );
  }
  presets.set(preset, assigned);
});
assert.ok(presets.has('neutral'), 'themes.css carries no neutral preset');
assert.equal(
  presets.get('neutral').size,
  0,
  "The neutral preset is the theme's own values, so it writes nothing",
);

// 9b. One file per preset (themes.md 4.1, 5.6): `themes/<name>.css`, for a
// host that wears one preset and should not ship the rest. Each holds its
// preset and nothing else, and the files in `themes.css`'s order are
// `themes.css` itself — the two forms cannot drift, because one is the
// other.
assert.equal(
  patterns.get('./themes/*.css'),
  './dist/themes/*.css',
  'package.json must export each preset as ./themes/<name>.css',
);
assert.deepEqual(
  [...patterns.keys()],
  ['./themes/*.css'],
  'The one pattern entry is the presets',
);
const presetFiles = new Map(
  readdirSync(new URL('dist/themes/', packageRoot))
    .filter(file => file.endsWith('.css'))
    .map(file => [
      file.slice(0, -'.css'.length),
      readFileSync(new URL(`dist/themes/${file}`, packageRoot), 'utf8'),
    ]),
);
assert.deepEqual(
  [...presetFiles.keys()].sort(),
  [...presets.keys()].sort(),
  'dist/themes/ must hold one file per preset of themes.css, and no other',
);
for (const [preset, text] of presetFiles) {
  const rules = [];
  postcss.parse(text).walkRules(rule => {
    rules.push(PRESET_SELECTOR.exec(rule.selector)?.[1]);
  });
  assert.deepEqual(
    rules,
    [preset],
    `themes/${preset}.css must hold its own preset and nothing else`,
  );
}
assert.equal(
  [...presets.keys()].map(preset => presetFiles.get(preset)).join('\n'),
  themesText,
  'themes.css must be the single-preset files, in its order',
);

// 10. The shadcn bridge reads a host's shadcn tokens into the preset layer,
// and only that (D30 Q46).
//
// `/shadcn-bridge.css` sits on the host's `<html>` like a preset and writes
// what a preset writes, `--fvp-*`, so a host's own `--fve-*` are read first
// and a surface pinned to a preset empties it (4d). One rule, weighing
// nothing, and only while `<html>` names no preset
// (`:root:not([data-fve-preset])`, themes.md 2.8) — the bridge or a preset,
// never whichever was imported last; no at-rule; and every declaration points
// one preset variable at the shadcn token of the same name — `--fvp-<token>`
// and `--fvp-dark-<token>` alike at `var(--<token>)`, since the host's `.dark`
// on `<html>` is what makes that token its dark value. It assigns every
// colour a preset may except four kinds kept out on purpose: `input` and `ring` (a shadcn theme's
// `var(--border)` and `var(--primary)` owe no 3:1), the status colours (text
// measured to 4.5:1; shadcn has no `success` or `warning`) and what is
// derived rather than set — plus the font stack, `--font-sans` in shadcn v4.
// The chart colours (shadcn's five start on red) and the shadows (shadcn has
// no standard name for them) are not bridged.
const bridgePath = manifest.exports['./shadcn-bridge.css'];
assert.equal(
  typeof bridgePath,
  'string',
  './shadcn-bridge.css must be a single target',
);
const bridgeText = readFileSync(new URL(bridgePath, packageRoot), 'utf8');
const bridge = postcss.parse(bridgeText);
const bridgeAtRules = [];
bridge.walkAtRules(rule => {
  bridgeAtRules.push(`@${rule.name}`);
});
assert.deepEqual(bridgeAtRules, [], 'shadcn-bridge.css may hold no at-rule');
const bridgeRules = [];
bridge.walkRules(rule => {
  bridgeRules.push(rule);
});
assert.deepEqual(
  bridgeRules.map(({ selector }) => unquoted(selector)),
  [':where(:root:not([data-fve-preset]))'],
  'shadcn-bridge.css must be one :where(:root:not([data-fve-preset])) rule',
);
const bridged = new Map();
bridgeRules[0].walkDecls(decl => {
  const token = /^--fvp-(?:dark-)?([\w-]+)$/.exec(decl.prop)?.[1];
  assert.ok(
    token,
    `shadcn-bridge.css sets ${decl.prop}; the bridge only assigns --fvp-* variables`,
  );
  assert.equal(
    decl.value,
    `var(--${token})`,
    `shadcn-bridge.css must point ${decl.prop} at the shadcn token of the same name`,
  );
  bridged.set(decl.prop, decl.value);
});
assert.deepEqual(
  [...bridged.keys()].sort(),
  presetWhere(entry => entry.bridge).sort(),
  'shadcn-bridge.css must assign exactly the preset variables the registry bridges: every colour bar input, ring, the status colours, the chart colours and the derived ones, plus radius and the font stack',
);

// 11. What the stylesheets weigh on the wire (themes.md 5.6). Every preset
// together stays under 8 KB gzipped and each one alone under 1.4 KB — a
// regression guard, raised from 1.2 KB when the presets took the bounds a
// brand colour is held to (S4: porcelain 1,232 B); the numbers are printed
// so each theme batch can write them into its pull request.
const gzipped = text => gzipSync(text, { level: 9 }).length;
const cssSizes = {
  'styles.css': gzipped(stylesheet),
  'themes.css': gzipped(themesText),
  'shadcn-bridge.css': gzipped(bridgeText),
  ...Object.fromEntries(
    [...presets.keys()].map(preset => [
      `themes/${preset}.css`,
      gzipped(presetFiles.get(preset)),
    ]),
  ),
};
assert.ok(
  cssSizes['themes.css'] <= 8 * 1024,
  `themes.css is ${cssSizes['themes.css']} bytes gzipped, over the 8 KB budget`,
);
for (const preset of presets.keys()) {
  const size = cssSizes[`themes/${preset}.css`];
  assert.ok(
    size <= 1.4 * 1024,
    `themes/${preset}.css is ${size} bytes gzipped, over the 1.4 KB budget`,
  );
}

/** A selector list split on its top-level commas, each part trimmed. */
function selectorList(selectors) {
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let at = 0; at < selectors.length; at += 1) {
    const char = selectors[at];
    // An escaped character is part of a class name (`.\\[a\\,b\\]`).
    if (char === '\\') at += 1;
    else if (char === '(') depth += 1;
    else if (char === ')') depth -= 1;
    else if (char === ',' && depth === 0) {
      parts.push(selectors.slice(start, at).trim());
      start = at + 1;
    }
  }
  parts.push(selectors.slice(start).trim());
  return parts;
}

/**
 * The argument of every outermost `:where()` (or `:is()`) in a selector, each
 * read to the parenthesis that closes it — an argument holds a `:not()` of
 * its own, and a scoped `dark:` utility carries its variant's `:where()` and
 * then the scope's `:is()`.
 */
function whereArguments(selector, pseudo = ':where(') {
  const args = [];
  let at = selector.indexOf(pseudo);
  while (at >= 0) {
    const from = at + pseudo.length;
    let depth = 1;
    let end = from;
    for (; end < selector.length && depth > 0; end += 1) {
      if (selector[end] === '\\') end += 1;
      else if (selector[end] === '(') depth += 1;
      else if (selector[end] === ')') depth -= 1;
    }
    assert.equal(depth, 0, `${selector} has an unbalanced ${pseudo})`);
    args.push(selector.slice(from, end - 1));
    at = selector.indexOf(pseudo, end);
  }
  return args;
}

/** Every scope a selector carries: its `:where()` and its `:is()` arguments. */
function scopeArguments(selector) {
  return [...whereArguments(selector), ...whereArguments(selector, ':is(')];
}

/** One selector part with the scope's `:is()` taken out of it. */
function unscoped(part) {
  let text = part;
  for (const scope of whereArguments(part, ':is(')) {
    const parts = selectorList(scope);
    if (
      parts.length === SCOPE_PARTS.length &&
      SCOPE_PARTS.every(expected => parts.includes(expected))
    )
      text = text.replace(`:is(${scope})`, '');
  }
  return text;
}

/**
 * The minifier drops the quotes in `[data-theme='dark']` and the space after a
 * comma inside `:not()`; neither changes what a selector matches.
 */
function unquoted(selector) {
  return selector.replace(/['"]/g, '').replace(/,\s*/g, ', ');
}

/**
 * Every style rule in a stylesheet as its selector, the properties it sets
 * and, for the custom properties among them, `tokens` — the property paired
 * with its value; `@keyframes` steps are not selectors and are left out.
 */
function styleRules(css) {
  const rules = [];
  postcss.parse(css).walkRules(rule => {
    if (rule.parent?.type === 'atrule' && rule.parent.name === 'keyframes')
      return;
    const declarations = [];
    const tokens = [];
    const values = new Map();
    rule.each(node => {
      if (node.type !== 'decl') return;
      declarations.push(node.prop);
      values.set(node.prop, node.value);
      if (node.prop.startsWith('--')) tokens.push([node.prop, node.value]);
    });
    let layer;
    for (let node = rule.parent; node; node = node.parent)
      if (node.type === 'atrule' && node.name === 'layer') {
        layer = node.params;
        break;
      }
    rules.push({
      selector: rule.selector,
      declarations,
      tokens,
      values,
      layer,
    });
  });
  return rules;
}

// 2. The root entry's types compile without the DOM lib.
const typeProbe = mkdtempSync(new URL('.package-types-', packageRoot));
try {
  const file = `${typeProbe}/consumer.ts`;
  writeFileSync(
    file,
    [
      `import { ViewEngine, MemoryViewStore, validateDashboard } from '${name}';`,
      `import type { ViewStore, RecordViewConfig } from '${name}';`,
      `declare const engine: ViewEngine;`,
      `declare const store: ViewStore;`,
      `declare const config: RecordViewConfig;`,
      `void [engine, store, config, MemoryViewStore, validateDashboard];`,
      '',
    ].join('\n'),
  );
  const program = ts.createProgram([file], {
    noEmit: true,
    strict: true,
    // The package's own declarations are exactly what this checks, so they
    // cannot be skipped; `skipDefaultLibCheck` still skips TypeScript's own.
    skipLibCheck: false,
    skipDefaultLibCheck: true,
    target: ts.ScriptTarget.ES2022,
    // The point of the check: ES2022 and Node, never DOM.
    lib: ['lib.es2022.d.ts'],
    types: ['node'],
    module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
  });
  const diagnostics = ts.getPreEmitDiagnostics(program);
  assert.equal(
    diagnostics.length,
    0,
    `The root entry's types need the DOM lib:\n${ts.formatDiagnostics(
      diagnostics,
      {
        getCanonicalFileName: path => path,
        getCurrentDirectory: () => process.cwd(),
        getNewLine: () => '\n',
      },
    )}`,
  );
} finally {
  rmSync(typeProbe, { recursive: true, force: true });
}

// 3. No JavaScript entry imports the stylesheet, at any depth.
//
// This runs before the entries are imported, because Node refuses a `.css`
// specifier with an error about file extensions that says nothing about why
// the rule exists.
const visited = new Set();
for (const entry of jsEntries) visited.add(entry.resolved);
for (const file of visited) {
  const code = readFileSync(new URL(file), 'utf8');
  for (const { fileName } of ts.preProcessFile(code, true, true)
    .importedFiles) {
    assert.ok(
      !fileName.endsWith('.css'),
      `${fileURLToPath(file)} imports ${fileName}; the theme is an explicit entry`,
    );
    if (fileName.startsWith('.')) visited.add(new URL(fileName, file).href);
  }
}

// 7. And, that settled, every entry actually imports — and exports at run
// time exactly the values its surface list names (A-16, D29). The list is
// written from the source by `test/publicSurface.test.ts`; this holds the
// built entry to it, so a bundler that drops or adds a binding fails here.
const SURFACE_LISTS = {
  [name]: 'test/surface/root.txt',
  [`${name}/react`]: 'test/surface/react.txt',
  [`${name}/ui`]: 'test/surface/ui.txt',
};
for (const { specifier, resolved } of jsEntries) {
  const module = await import(resolved);
  assert.ok(Object.keys(module).length > 0, `${specifier} exports nothing`);
  const list = SURFACE_LISTS[specifier];
  assert.ok(list, `${specifier} has no surface list`);
  const values = readFileSync(new URL(list, packageRoot), 'utf8')
    .split('\n')
    .filter(line => line.startsWith('value '))
    .map(line => line.slice('value '.length).trim())
    .sort();
  assert.deepEqual(
    Object.keys(module).sort(),
    values,
    `${specifier} does not export at run time the values ${list} names`,
  );
}

// 7b. The names `/ui` lists as built in are the presets the stylesheet ships,
// in its order: a picker a host builds from the list offers no name the
// stylesheet lacks, and misses none.
const ui = await import(import.meta.resolve(`${name}/ui`));
assert.deepEqual(
  [...ui.BUILT_IN_PRESETS],
  [...presets.keys()],
  'BUILT_IN_PRESETS must name the presets of themes.css, in its order',
);

// 8. The chart chunk draws. It is loaded on a chart's first use, so no entry
// imports it; a production build once kept its `init` and dropped the
// registration of every chart type and the renderer (the package declares no
// side effects but its stylesheet), and the first chart threw
// 「lg[a] is not a constructor」. Drawn here through the library's
// server-side rendering, which needs no DOM.
const chartChunks = readdirSync(new URL('dist/', packageRoot)).filter(file =>
  /^echarts-[\w-]+\.js$/.test(file),
);
assert.equal(
  chartChunks.length,
  1,
  `one chart chunk in dist, found ${chartChunks.join(', ') || 'none'}`,
);
const charts = await import(
  new URL(`dist/${chartChunks[0]}`, packageRoot).href
);
const probe = charts.init(null, null, {
  renderer: 'svg',
  ssr: true,
  width: 200,
  height: 100,
});
probe.setOption({
  animation: false,
  xAxis: { type: 'category', data: ['a', 'b'] },
  yAxis: { type: 'value' },
  series: [
    { type: 'bar', data: [1, 2] },
    { type: 'line', data: [1, 2] },
    { type: 'pie', data: [{ value: 1 }] },
    { type: 'scatter', data: [[1, 2]] },
  ],
});
assert.match(
  probe.renderToSVGString(),
  /<path/,
  'the chart chunk draws no mark: its chart types or renderer are not registered',
);
probe.dispose();

// 12. What each entry weighs, against its regression ceiling: what importing
// it loads — its module and every module of the package it imports
// statically — gzipped. The chart chunk is weighed as the first chart loads
// it, less what `/ui` has already loaded, and has to stay lazy: `/ui`
// reaches it by `import()` alone. Size is not a target (see
// `.github/scripts/size-budget.mjs`); the ceilings catch a blow-up.
const inDist = file => fileURLToPath(new URL(`dist/${file}`, packageRoot));
const entryFiles = entry =>
  staticClosure(inDist(manifest.exports[entry].import.slice('./dist/'.length)));
const uiFiles = entryFiles('./ui');
const chartChunk = inDist(chartChunks[0]);
assert.ok(
  !uiFiles.includes(chartChunk) && lazyChunks(uiFiles).includes(chartChunk),
  `the chart chunk ${chartChunks[0]} is no longer loaded lazily: /ui must reach it by import() alone`,
);
const sizes = checkSizes({
  packageName: name,
  budgetFile: fileURLToPath(new URL('scripts/size-budget.json', packageRoot)),
  measured: {
    '.': gzippedSize(entryFiles('.')),
    './react': gzippedSize(entryFiles('./react')),
    './ui': gzippedSize(uiFiles),
    'echarts chunk': gzippedSize(staticClosure(chartChunk, new Set(uiFiles))),
    './styles.css': cssSizes['styles.css'],
    './themes.css': cssSizes['themes.css'],
  },
});

// 8b. Each family chunk registers what it draws. They load after the first
// chunk, on their family's first chart (`loadCharts(chunk)`), so the same
// build that once dropped the first chunk's registration could drop theirs.
const familyChunks = {
  echartsStatistics: [
    { type: 'boxplot', data: [[1, 2, 3, 4, 5]] },
    { type: 'funnel', data: [{ name: 'a', value: 1 }] },
    { type: 'gauge', data: [{ value: 1 }] },
    { type: 'radar', data: [{ value: [1, 2, 3] }] },
    { type: 'parallel', data: [[1, 2, 3]] },
  ],
  echartsHierarchy: [
    { type: 'sunburst', data: [{ name: 'a', value: 1 }] },
    {
      type: 'tree',
      data: [{ name: 'a', children: [{ name: 'b', value: 1 }] }],
    },
    {
      type: 'sankey',
      data: [{ name: 'a' }, { name: 'b' }],
      links: [{ source: 'a', target: 'b', value: 1 }],
    },
  ],
  echartsGeo: [
    {
      type: 'map',
      map: 'probe',
      data: [{ name: 'a', value: 1 }],
    },
  ],
  echartsTime: [
    {
      type: 'heatmap',
      coordinateSystem: 'calendar',
      data: [['2026-01-01', 1]],
    },
    {
      type: 'themeRiver',
      data: [
        [0, 1, 'a'],
        [1, 2, 'a'],
      ],
    },
  ],
};
for (const [chunk, series] of Object.entries(familyChunks)) {
  const files = readdirSync(new URL('dist/', packageRoot)).filter(file =>
    new RegExp(`^${chunk}-[\\w-]+\\.js$`).test(file),
  );
  assert.equal(
    files.length,
    1,
    `one ${chunk} chunk in dist, found ${files.join(', ') || 'none'}`,
  );
  const family = await import(new URL(`dist/${files[0]}`, packageRoot).href);
  family.register();
  // The map chunk carries no geography: the probe registers a square of its
  // own, as a host registers its map (`registerChartMap`).
  family.registerGeoMap?.('probe', {
    type: 'FeatureCollection',
    features: [
      {
        type: 'Feature',
        properties: { name: 'a' },
        geometry: {
          type: 'Polygon',
          coordinates: [
            [
              [0, 0],
              [1, 0],
              [1, 1],
              [0, 1],
              [0, 0],
            ],
          ],
        },
      },
    ],
  });
  for (const one of series) {
    const drawing = charts.init(null, null, {
      renderer: 'svg',
      ssr: true,
      width: 200,
      height: 100,
    });
    drawing.setOption({
      animation: false,
      ...(one.type === 'boxplot'
        ? { xAxis: { type: 'category', data: ['a'] }, yAxis: { type: 'value' } }
        : {}),
      ...(one.type === 'radar'
        ? { radar: { indicator: [{ max: 3 }, { max: 3 }, { max: 3 }] } }
        : {}),
      ...(one.coordinateSystem === 'calendar'
        ? { calendar: { range: '2026' }, visualMap: { min: 0, max: 1 } }
        : {}),
      ...(one.type === 'themeRiver'
        ? { singleAxis: { type: 'value', min: 0, max: 1 } }
        : {}),
      ...(one.type === 'parallel'
        ? { parallelAxis: [{ dim: 0 }, { dim: 1 }, { dim: 2 }] }
        : {}),
      series: [one],
    });
    // A funnel's stages are polygons; every other family draws paths.
    assert.match(
      drawing.renderToSVGString(),
      /<(?:path|polygon)/,
      `the ${chunk} chunk draws no ${one.type}: it is not registered`,
    );
    drawing.dispose();
  }
}

// The command, `wow-view-engine theme-check` (theme-architecture.md 5.2, S7):
// the file `bin` names runs from `dist` alone — it reads the registry, the
// token rules and the presets shipped beside it — clears a sound host theme
// and fails an unsound one.
const manifestBin = JSON.parse(
  readFileSync(new URL('package.json', packageRoot), 'utf8'),
).bin;
assert.deepEqual(
  manifestBin,
  { 'wow-view-engine': './dist/theme-check.mjs' },
  'package.json names one command, wow-view-engine, at dist/theme-check.mjs',
);
const checkDir = mkdtempSync(join(tmpdir(), 'fve-theme-check-'));
const themeCheck = (css, ...options) => {
  const file = join(checkDir, 'host.css');
  writeFileSync(file, css);
  return spawnSync(
    process.execPath,
    [
      fileURLToPath(new URL('dist/theme-check.mjs', packageRoot)),
      'theme-check',
      file,
      ...options,
    ],
    { encoding: 'utf8' },
  );
};
const sound = themeCheck(':root { --fve-brand: #0f766e; }');
assert.equal(
  sound.status,
  0,
  `theme-check fails a brand colour alone:\n${sound.stdout}${sound.stderr}`,
);
for (const [css, found] of [
  [':root { --fve-bogus: red; }', /--fve-bogus is no host variable/],
  [':root { --fve-ring: #eeeeee; }', /ring edge on page/],
]) {
  const unsound = themeCheck(css);
  assert.equal(
    unsound.status,
    1,
    `theme-check clears ${css}:\n${unsound.stdout}${unsound.stderr}`,
  );
  assert.match(unsound.stdout, found);
}
rmSync(checkDir, { recursive: true, force: true });

console.log(
  `${targets.size} entries resolve and import, the code entries export at run time exactly the values their surface lists name, the root entry's types need no DOM lib, ${visited.size} runtime modules import no CSS, the chart chunk and each family chunk draw, the stylesheet holds no rule outside ${BOUNDARIES.join(' / ')} bar the preset reset (@layer ${RESET_LAYER}, the first layer, emptying ${reset.declarations.length} --fvp-* variables) and no :root selector at all, every one of its ${scopedRules.length} other rules carries the one-class scope naming both boundaries and none names only one, its dark: utilities turn on the same ${tokenSelectors.length} roots as its dark tokens, its ${lightTokens.tokens.length} light and ${darkTokens.tokens.length} dark tokens all read --fve-* host variables before the preset layer, themes.css holds ${presets.size} preset(s) (${[...presets.keys()].join(', ')}), each shipped alone too as themes/<name>.css, each assigning only --fvp-* variables it changes (${[
    ...presets,
  ]
    .map(([preset, assigned]) => `${preset} ${assigned.size}`)
    .join(', ')}) and whole groups (${Object.entries(groups)
    .filter(([group]) => registry.groups[group].whole)
    .map(([group, members]) => `${group} ${members.length}`)
    .join(
      ', ',
    )}), shadcn-bridge.css points ${bridged.size} of them at the host's shadcn tokens, and gzipped the stylesheets weigh ${Object.entries(
    cssSizes,
  )
    .map(([file, size]) => `${file} ${size} B`)
    .join(
      ', ',
    )}; the entries weigh, gzipped against their ceilings, ${sizes}; and the theme-check command runs from dist, clearing a sound theme and failing an unsound one.`,
);
