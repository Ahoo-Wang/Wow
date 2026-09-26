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
 * What the theming pages tell a host to write is the contract, and runs
 * (theme-architecture.md 9, S6): the theming guide in both languages, the
 * READMEs and the quick start. Their TypeScript samples compile in the
 * documentation package (`documentation/test/typescript-samples.mjs`); this
 * suite holds the rest — every CSS and HTML sample, and every variable and
 * attribute the prose names — to the theme's registry
 * (`src/ui/theme/tokens.ts`):
 *
 * - a CSS sample parses, imports only what the package exports, writes a
 *   host variable (`--fve-*`) outside a preset and a preset variable
 *   (`--fvp-*`) inside one, never the engine's own `--_fve-*`, and reads
 *   only registered variables;
 * - the theme a sample writes is one theme-check (`theme-check/check.ts`,
 *   S7) clears without a warning — the registry and its layers, and every
 *   contrast pair of the package in both modes, on `neutral` and on every
 *   preset the sample imports: a sample is a theme a reader will paste, so
 *   it is held to what a host's own theme is;
 * - an HTML sample names a preset, a density and a change convention that
 *   exist, and only the attributes the theme watches;
 * - a variable in the prose is a registered one, a private one the
 *   stylesheet declares, or a pattern (`--fve-<token>`, `--fvp-*`);
 * - the guide names every role and every attribute a chart redraws on.
 *
 * The sample host theme in Storybook (`host-theme/acme.css`) is held to the
 * same rules and lines: it is the page's worked example.
 */

import { readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import postcss from 'postcss';
import { describe, expect, it } from 'vitest';
import { BUILT_IN_PRESETS } from '../src/ui/presets';
import {
  hostVariables,
  presetVariables,
  THEME_ATTRIBUTES,
  type TokenEntry,
  TOKENS,
} from '../src/ui/theme/tokens';
import { checkTheme } from '../theme-check/check';
import { registryData } from '../theme-check/registry';
import { RESOLVER } from './fixtures/themeTokens';

const ROOT = join(import.meta.dirname, '..');
const REPOSITORY = join(ROOT, '../..');
const GUIDE = (language: string) =>
  join(
    REPOSITORY,
    `documentation/docs/${language}/guide/typescript/view-engine-theming.md`,
  );
const QUICK_START = (language: string) =>
  join(
    REPOSITORY,
    `documentation/docs/${language}/guide/typescript/view-engine.md`,
  );
const ACME = join(ROOT, '../storybook/stories/view-engine/host-theme/acme.css');

const GUIDES = ['en', 'zh'].map(GUIDE);

const PAGES = [
  ...GUIDES,
  ...['en', 'zh'].map(QUICK_START),
  join(ROOT, 'README.md'),
  join(ROOT, 'README.zh-CN.md'),
];

const ENTRIES: readonly TokenEntry[] = TOKENS;
const REGISTRY = registryData();

const HOST = new Set(ENTRIES.flatMap(hostVariables));
const PRESET = new Set(ENTRIES.flatMap(presetVariables));

/** The engine's own variables, as the stylesheet declares them. */
const PRIVATE = new Set(
  [
    ...readFileSync(join(ROOT, 'src/styles.css'), 'utf8').matchAll(
      /(--_fve-[\w-]+)\s*:/g,
    ),
  ].map(([, name]) => name),
);

/** The stylesheets the package exports, as a host imports them. */
const STYLESHEETS = new Set([
  '@ahoo-wang/wow-view-engine/styles.css',
  '@ahoo-wang/wow-view-engine/themes.css',
  '@ahoo-wang/wow-view-engine/shadcn-bridge.css',
  ...BUILT_IN_PRESETS.map(
    preset => `@ahoo-wang/wow-view-engine/themes/${preset}.css`,
  ),
]);

const VALUES: Readonly<Record<string, readonly string[]>> = {
  'data-fve-density': ['compact', 'default', 'comfortable'],
  'data-fve-change-colors': ['semantic', 'green-up', 'red-up'],
};

interface Sample {
  at: string;
  language: string;
  code: string;
}

interface Page {
  at: string;
  text: string;
  samples: Sample[];
}

/** A page's fenced blocks, by language, with where each starts. */
function read(path: string): Page {
  const text = readFileSync(path, 'utf8');
  const at = relative(REPOSITORY, path);
  const samples: Sample[] = [];
  const lines = text.split('\n');
  for (let index = 0; index < lines.length; index++) {
    const open = /^(\s*)```(\w*)/.exec(lines[index]);
    if (!open) continue;
    let end = index + 1;
    while (end < lines.length && !lines[end].trim().startsWith('```')) end++;
    samples.push({
      at: `${at}:${index + 1}`,
      language: open[2],
      code: lines
        .slice(index + 1, end)
        .map(line => line.slice(open[1].length))
        .join('\n'),
    });
    index = end;
  }
  return { at, text, samples };
}

const pages = PAGES.map(read);

const css = pages.flatMap(({ samples }) =>
  samples.filter(({ language }) => language === 'css'),
);
const html = pages.flatMap(({ samples }) =>
  samples.filter(({ language }) => language === 'html'),
);

/** A preset rule's name, or undefined for a rule no preset selects. */
const presetOf = (selector: string) =>
  /data-fve-preset='([^']+)'/.exec(selector)?.[1];

interface Theme {
  /** The host's variables with a value of their own, off `:root`. */
  host: Record<string, string>;
  /** Each preset the sample writes, by name. */
  presets: Map<string, Map<string, string>>;
  /** The built-in presets it imports. */
  imports: string[];
}

/** What one stylesheet says, as a theme to resolve. */
function theme(code: string): Theme {
  const root = postcss.parse(code);
  const host: Record<string, string> = {};
  const presets = new Map<string, Map<string, string>>();
  const imports: string[] = [];
  root.walkAtRules('import', rule => {
    const name = /themes\/([\w-]+)\.css/.exec(rule.params)?.[1];
    if (name) imports.push(name);
  });
  root.walkRules(rule => {
    const preset = presetOf(rule.selector);
    rule.walkDecls(/^--fv[ep]-/, decl => {
      // A value that reads another variable is the page's to resolve (the
      // bridge's `var(--primary)`): nothing here to measure it by.
      if (/var\((?!--fv)/.test(decl.value)) return;
      if (preset) {
        if (!presets.has(preset)) presets.set(preset, new Map());
        if (decl.prop.startsWith('--fvp-'))
          presets.get(preset)!.set(decl.prop, decl.value);
        else host[decl.prop] = decl.value;
      } else if (rule.selector === ':root') host[decl.prop] = decl.value;
    });
  });
  return { host, presets, imports };
}

describe('the CSS samples of the theming pages', () => {
  it('are there to check', () => {
    expect(css.length).toBeGreaterThanOrEqual(12);
  });

  it.each(css.map(sample => [sample.at, sample] as const))(
    '%s imports only what the package exports',
    (_, { code }) => {
      const wrong: string[] = [];
      postcss.parse(code).walkAtRules('import', rule => {
        const path = rule.params.replace(/^['"]|['"]$/g, '');
        if (!STYLESHEETS.has(path)) wrong.push(`imports ${path}`);
      });
      expect(wrong).toEqual([]);
    },
  );

  it.each(css.map(sample => [sample.at, sample] as const))(
    '%s is a theme theme-check clears, warnings included',
    (_, { code }) => {
      expect(
        checkTheme(code, RESOLVER, REGISTRY, {
          presets: ['neutral', ...theme(code).imports],
        }),
      ).toEqual([]);
    },
  );
});

describe('the HTML samples of the theming pages', () => {
  /** Every preset a CSS sample on the same page writes. */
  const own = new Set(
    css.flatMap(({ code }) => [...theme(code).presets.keys()]),
  );

  it.each(html.map(sample => [sample.at, sample] as const))(
    '%s names what exists',
    (_, { code }) => {
      const wrong: string[] = [];
      for (const [, name, value] of code.matchAll(
        /\s(data-fve-[\w-]+)(?:="([^"]*)")?/g,
      )) {
        if (!THEME_ATTRIBUTES.includes(name)) wrong.push(`${name} is no axis`);
        if (
          name === 'data-fve-preset' &&
          !(BUILT_IN_PRESETS as readonly string[]).includes(value) &&
          !own.has(value)
        )
          wrong.push(`no preset ${value}`);
        const allowed = VALUES[name];
        if (allowed && !allowed.includes(value))
          wrong.push(`${name}="${value}"`);
      }
      for (const [, style] of code.matchAll(/style="([^"]*)"/g))
        for (const [, variable] of style.matchAll(/(--[\w-]+)\s*:/g))
          if (!HOST.has(variable))
            wrong.push(`${variable} is no host variable`);
      expect(wrong).toEqual([]);
    },
  );
});

describe('the variables the theming pages name', () => {
  /** A concrete name in an inline code span or a sample, not a pattern. */
  const named = pages.flatMap(({ at, text }) =>
    text.split('\n').flatMap((line, index) =>
      [...line.matchAll(/(--_?fv[ep]-[a-z0-9-]*)(?![a-z0-9<*…-])/g)]
        .map(([, name]) => name)
        .filter(name => !name.endsWith('-'))
        .map(name => ({ at: `${at}:${index + 1}`, name })),
    ),
  );

  it('names variables at all', () => {
    expect(new Set(named.map(({ name }) => name)).size).toBeGreaterThan(40);
  });

  it('names only registered or declared ones', () => {
    const unknown = named
      .filter(
        ({ name }) =>
          !HOST.has(name) && !PRESET.has(name) && !PRIVATE.has(name),
      )
      .map(({ at, name }) => `${at} → ${name}`);
    expect(unknown).toEqual([]);
  });

  it('names only the attributes the theme watches', () => {
    const unknown = pages.flatMap(({ at, text }) =>
      [...text.matchAll(/`(data-fve-[\w-]+)/g)]
        .map(([, name]) => name)
        .filter(name => !THEME_ATTRIBUTES.includes(name))
        .map(name => `${at} → ${name}`),
    );
    expect(unknown).toEqual([]);
  });
});

describe('the theming guide', () => {
  const roles = ENTRIES.filter(entry => entry.tier === 'role').map(
    entry => entry.name,
  );

  it.each(
    GUIDES.map(path => [relative(REPOSITORY, path), read(path)] as const),
  )(
    '%s names every role and every attribute a chart redraws on',
    (_, { text }) => {
      const missing = [
        ...roles.filter(role => !text.includes(`\`${role}\``)),
        ...THEME_ATTRIBUTES.filter(name => !text.includes(`\`${name}\``)),
      ];
      expect(missing).toEqual([]);
    },
  );
});

describe('the sample host theme', () => {
  const code = readFileSync(ACME, 'utf8');
  const sample = theme(code);

  it('is a preset of its own, outside any layer', () => {
    expect([...sample.presets.keys()]).toEqual(['acme']);
    const layers: string[] = [];
    postcss.parse(code).walkAtRules('layer', rule => {
      layers.push(rule.params);
    });
    expect(layers).toEqual([]);
  });

  it('writes only registered variables, its brand as the host', () => {
    const wrong: string[] = [];
    postcss.parse(code).walkDecls(({ prop }) => {
      if (prop.startsWith('--fvp-') ? !PRESET.has(prop) : !HOST.has(prop))
        wrong.push(prop);
    });
    expect(wrong).toEqual([]);
    expect(Object.keys(sample.host).sort()).toEqual([
      '--fve-brand',
      '--fve-dark-brand',
    ]);
  });

  it('copies no colour its brand derives', () => {
    // The first chart slot is the palette's, which is given whole; it takes
    // the brand only under `data-fve-brand-chart`.
    const derived = ENTRIES.filter(
      entry => entry.brand && entry.group !== 'chart',
    ).flatMap(presetVariables);
    const copied = derived.filter(variable =>
      sample.presets.get('acme')!.has(variable),
    );
    expect(copied).toEqual([]);
  });

  it('is a theme theme-check clears, warnings included', () => {
    expect(checkTheme(code, RESOLVER, REGISTRY)).toEqual([]);
  });
});
