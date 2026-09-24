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
// 1. Every declared entry resolves and imports.
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
import postcss from 'postcss';
import ts from 'typescript';

const packageRoot = new URL('../', import.meta.url);
const manifest = JSON.parse(
  readFileSync(new URL('package.json', packageRoot), 'utf8'),
);
const name = manifest.name;

/** Every file `exports` promises, by the specifier that reaches it. */
const targets = new Map();
for (const [specifier, entry] of Object.entries(manifest.exports)) {
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
    rule.each(node => {
      if (node.type !== 'decl') return;
      declarations.push(node.prop);
      if (node.prop.startsWith('--')) tokens.push([node.prop, node.value]);
    });
    rules.push({ selector: rule.selector, declarations, tokens });
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

// 7. And, that settled, every entry actually imports.
for (const { specifier, resolved } of jsEntries) {
  const module = await import(resolved);
  assert.ok(Object.keys(module).length > 0, `${specifier} exports nothing`);
}

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
  `${targets.size} entries resolve and import, the root entry's types need no DOM lib, ${visited.size} runtime modules import no CSS, the chart chunk draws, the stylesheet holds no rule outside ${BOUNDARIES.join(' / ')} and no :root selector at all, ${fullyScoped.length} of its rules carry the scope naming both boundaries and none names only one, its dark: utilities turn on the same ${tokenSelectors.length} roots as its dark tokens, and its ${lightTokens.tokens.length} light and ${darkTokens.tokens.length} dark tokens all defer to --fve-* host variables.`,
);
