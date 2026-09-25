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
//    every scope it does carry names both of them.
// 5. The `dark:` utilities and the dark tokens turn on the same roots, so no
//    host can end up with the utilities of one mode over the other's tokens.
// 6. Every token defers to a host-level `--fve-*` variable, so a host can
//    customise the theme from `:root` without reaching inside the root.
// 9. The presets (`/themes.css`, and `/themes/<name>.css` one by one) only
//    assign those host variables, each preset the same required set and
//    every optional group whole or not at all.
// 10. The shadcn bridge (`/shadcn-bridge.css`) only points those host
//    variables at a host's shadcn tokens, bar input, ring and the status
//    colours, and only while no preset is named.
// 11. The stylesheets' gzipped sizes, the presets' under their budget,
//    one by one (`/themes/<name>.css`) and together.
import assert from 'node:assert/strict';
import {
  readdirSync,
  readFileSync,
  statSync,
  mkdtempSync,
  writeFileSync,
  rmSync,
} from 'node:fs';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
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
const leaks = styleRules(stylesheet).filter(
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
const partial = styleRules(stylesheet).filter(({ selector }) =>
  whereArguments(selector).some(scope => {
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
const fullyScoped = styleRules(stylesheet).filter(({ selector }) =>
  whereArguments(selector).some(scope => {
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
const tokenSelectors = selectorList(darkTokens.selector);
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
  lightTokens && selectorList(lightTokens.selector),
  BOUNDARIES,
  'The light tokens must be declared on both boundaries, in one block',
);
assert.ok(lightTokens, 'The stylesheet sets no light tokens');
for (const [mode, rule, prefix] of [
  ['light', lightTokens, '--fve-'],
  ['dark', darkTokens, '--fve-dark-'],
]) {
  // The minifier may drop the space after the comma; the fallback is the rest.
  const literal = rule.tokens.filter(
    ([property, value]) =>
      !new RegExp(`^var\\(${prefix}${property.slice(2)},\\s*.+\\)$`).test(
        value,
      ),
  );
  assert.deepEqual(
    literal.map(([property]) => property),
    [],
    `The ${mode} tokens must each read ${prefix}<token> with the built-in value as the fallback`,
  );
  assert.deepEqual(
    rule.tokens
      .map(([property]) => property)
      .filter(property => property.startsWith('--sidebar')),
    SIDEBAR_TOKENS,
    `The ${mode} tokens must carry exactly the sidebar group the navigation column paints with, in one order`,
  );
}

// 9. The presets are values for the host variables, and only that.
//
// `/themes.css` is an optional entry a host imports beside the theme. It may
// sit on the host's `<html>`, outside every boundary, so what makes it safe is
// what it may say rather than where: every rule is
// `:where([data-fve-preset=<name>])`, weighing nothing, so a host's own
// `--fve-*` on the same element win; every declaration assigns a `--fve-`
// variable, which nothing of the host's reads; and there is no at-rule, so
// nothing is painted, registered or imported — bar one: `brand`'s block sits
// in `@supports (color: oklch(from red l c h))` (themes.md 2.7), so a
// browser without relative colours has no `brand` block rather than a
// broken one.
//
// What a preset assigns is split in two (themes.md 2.2, D35 Q62). The
// required set — every host variable the token blocks read, bar the optional
// groups and the ones a preset never owns — is assigned by every preset, so a
// preset pinned inside another replaces all of its colours. Each optional
// group — the chart colours of both modes, the shadows of both modes, the
// font stack, the chart patterns' pin — a preset gives whole or not at all. Never a preset's:
// `pin-shadow` (the mode's), `text-ui` (the host's typography) and
// `rise` / `fall` (the host's change convention, which a preset would undo).
// `neutral` is the theme's own look, so it assigns every variable, the groups
// included, as `initial`, and the built-in values in `styles.css` stay their
// one source.
const themesPath = manifest.exports['./themes.css'];
assert.equal(
  typeof themesPath,
  'string',
  './themes.css must be a single target',
);
const themesText = readFileSync(new URL(themesPath, packageRoot), 'utf8');
const themes = postcss.parse(themesText);
const BRAND_SUPPORTS = '(color: oklch(from red l c h))';
const themeAtRules = [];
themes.walkAtRules(rule => {
  const inside = [];
  rule.walkRules(nested => {
    inside.push(nested.selector);
  });
  themeAtRules.push(`@${rule.name} ${rule.params} ${inside.join(', ')}`);
});
assert.deepEqual(
  themeAtRules,
  [`@supports ${BRAND_SUPPORTS} :where([data-fve-preset='brand'])`],
  "themes.css may hold one at-rule: brand's @supports around its own block",
);
const PRESET_SELECTOR =
  /^:where\(\[data-fve-preset=['"]?([a-z][a-z0-9-]*)['"]?\]\)$/;
const NOT_PRESET_OWNED = /^--fve-(dark-)?(pin-shadow|text-ui|rise|fall)$/;
const OPTIONAL_GROUPS = {
  chart: /^--fve-(dark-)?chart-\d+$/,
  shadow: /^--fve-(dark-)?shadow-(sm|md|lg)$/,
  font: /^--fve-font-sans$/,
  patterns: /^--fve-chart-patterns$/,
};
// The chart reads the patterns' pin off its computed style
// (`readChartTheme`), not a rule of the stylesheet, so it is named here.
const READ_BY_THE_CHART = ['--fve-chart-patterns'];
const hostVariables = value => value.match(/--fve-[\w-]+/g) ?? [];
// The minifier may split one token block of the source into several rules
// with the same selector, so every rule on either block's selector counts.
// The font stack is read by the surface's base rule rather than a token.
const themeVariables = [
  ...new Set([
    ...styleRules(stylesheet)
      .filter(({ selector }) =>
        [lightTokens.selector, darkTokens.selector].includes(selector),
      )
      .flatMap(({ tokens }) => tokens)
      .flatMap(([, value]) => hostVariables(value)),
    ...styleRules(stylesheet)
      .flatMap(({ values }) => values.get('font-family') ?? [])
      .flatMap(hostVariables),
    ...READ_BY_THE_CHART,
  ]),
]
  .filter(variable => !NOT_PRESET_OWNED.test(variable))
  .sort();
const groups = Object.fromEntries(
  Object.entries(OPTIONAL_GROUPS).map(([group, pattern]) => [
    group,
    themeVariables.filter(variable => pattern.test(variable)),
  ]),
);
assert.deepEqual(
  Object.entries(groups)
    .filter(([, members]) => members.length === 0)
    .map(([group]) => group),
  [],
  'Every optional group names variables the theme reads',
);
assert.equal(groups.chart.length, 16, 'The chart group is 8 slots, 2 modes');
assert.equal(groups.shadow.length, 6, 'The shadow group is 3 steps, 2 modes');
const optional = new Set(Object.values(groups).flat());
const required = themeVariables.filter(variable => !optional.has(variable));
assert.ok(
  required.length > 0,
  'The theme reads no host variable a preset could set',
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
      decl.prop.startsWith('--fve-'),
      `themes.css preset ${preset} sets ${decl.prop}; a preset only assigns --fve-* variables`,
    );
    assigned.set(decl.prop, decl.value);
  });
  assert.deepEqual(
    [...assigned.keys()].filter(variable => !optional.has(variable)).sort(),
    required,
    `themes.css preset ${preset} must assign exactly the required variables the theme reads, plus whole optional groups`,
  );
  for (const [group, members] of Object.entries(groups)) {
    const given = members.filter(variable => assigned.has(variable));
    assert.ok(
      given.length === 0 || given.length === members.length,
      `themes.css preset ${preset} gives ${given.length} of the ${members.length} ${group} variables; an optional group is given whole or not at all`,
    );
  }
  presets.set(preset, assigned);
});
assert.ok(presets.has('neutral'), 'themes.css carries no neutral preset');
assert.deepEqual(
  [...presets.get('neutral').keys()].sort(),
  themeVariables,
  'The neutral preset assigns every variable, the optional groups included',
);
assert.deepEqual(
  [...presets.get('neutral').entries()].filter(
    ([, value]) => value !== 'initial',
  ),
  [],
  "The neutral preset is the theme's own values, so it sets every variable to initial",
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

// 10. The shadcn bridge reads a host's shadcn tokens into the host variables,
// and only that (D30 Q46).
//
// `/shadcn-bridge.css` sits on the host's `<html>` like a preset, so it may
// say as little: one rule, weighing nothing, so a host's own `--fve-*` win,
// and only while `<html>` names no preset (`:root:not([data-fve-preset])`,
// themes.md 2.8) — the bridge or a preset, never whichever was imported
// last; no at-rule; and every declaration points one host variable at the
// shadcn token of the same name — `--fve-<token>` and `--fve-dark-<token>`
// alike at `var(--<token>)`, since the host's `.dark` on `<html>` is what
// makes that token its dark value. It assigns exactly the required variables
// except four kinds kept out on purpose: `input` and `ring` (a shadcn theme's
// `var(--border)` and `var(--primary)` owe no 3:1), the status colours (text
// measured to 4.5:1; shadcn has no `success` or `warning`) and what is
// derived rather than set — plus the font stack, `--font-sans` in shadcn v4.
// The chart colours (shadcn's five start on red) and the shadows (shadcn has
// no standard name for them) are not bridged.
const NOT_BRIDGED =
  /^--fve-(dark-)?(input|ring|destructive|destructive-foreground|success|warning|row-hover|quiet-foreground)$/;
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
  const token = /^--fve-(?:dark-)?([\w-]+)$/.exec(decl.prop)?.[1];
  assert.ok(
    token,
    `shadcn-bridge.css sets ${decl.prop}; the bridge only assigns --fve-* variables`,
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
  [
    ...required.filter(variable => !NOT_BRIDGED.test(variable)),
    ...groups.font,
  ].sort(),
  'shadcn-bridge.css must assign every required variable bar input, ring, the status colours and the derived ones, and the font stack',
);

// 11. What the stylesheets weigh on the wire (themes.md 5.6). Every preset
// together stays under 8 KB gzipped and each one alone under 1.2 KB; the
// numbers are printed so each theme batch can write them into its pull
// request.
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
    size <= 1.2 * 1024,
    `themes/${preset}.css is ${size} bytes gzipped, over the 1.2 KB budget`,
  );
}

/** A selector list split on its top-level commas, each part trimmed. */
function selectorList(selectors) {
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let at = 0; at < selectors.length; at += 1) {
    const char = selectors[at];
    if (char === '(') depth += 1;
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
 * The argument of every outermost `:where()` in a selector, each read to the
 * parenthesis that closes it — an argument holds a `:not()` of its own, and a
 * scoped `dark:` utility carries two of these one after the other.
 */
function whereArguments(selector) {
  const args = [];
  let at = selector.indexOf(':where(');
  while (at >= 0) {
    const from = at + ':where('.length;
    let depth = 1;
    let end = from;
    for (; end < selector.length && depth > 0; end += 1) {
      if (selector[end] === '(') depth += 1;
      else if (selector[end] === ')') depth -= 1;
    }
    assert.equal(depth, 0, `${selector} has an unbalanced :where()`);
    args.push(selector.slice(from, end - 1));
    at = selector.indexOf(':where(', end);
  }
  return args;
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
    rules.push({ selector: rule.selector, declarations, tokens, values });
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

console.log(
  `${targets.size} entries resolve and import, the code entries export at run time exactly the values their surface lists name, the root entry's types need no DOM lib, ${visited.size} runtime modules import no CSS, the chart chunk draws, the stylesheet holds no rule outside ${BOUNDARIES.join(' / ')} and no :root selector at all, ${fullyScoped.length} of its rules carry the scope naming both boundaries and none names only one, its dark: utilities turn on the same ${tokenSelectors.length} roots as its dark tokens, its ${lightTokens.tokens.length} light and ${darkTokens.tokens.length} dark tokens all defer to --fve-* host variables, themes.css holds ${presets.size} preset(s) (${[...presets.keys()].join(', ')}), each shipped alone too as themes/<name>.css, each assigning the same ${required.length} required --fve-* variables and whole optional groups (${Object.entries(
    groups,
  )
    .map(([group, members]) => `${group} ${members.length}`)
    .join(
      ', ',
    )}), shadcn-bridge.css points ${bridged.size} of them at the host's shadcn tokens, and gzipped the stylesheets weigh ${Object.entries(
    cssSizes,
  )
    .map(([file, size]) => `${file} ${size} B`)
    .join(', ')}.`,
);
