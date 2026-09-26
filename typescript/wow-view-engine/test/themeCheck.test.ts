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
 * theme-check (theme-architecture.md 5.2, S7): the package's command that
 * holds a host's theme to the gates the built-in presets are held to.
 *
 * - What it resolves by is what the suites resolve by: the token rules the
 *   build ships (`dist/theme-source.css`, `themeSource`) resolve every
 *   preset, mode and convention exactly as `src/styles.css` does.
 * - Each check fires on the mistake it names, and a sound theme — the
 *   Storybook sample `host-theme/acme.css`, a brand colour alone — clears.
 * - The command reads its files beside it, prints the findings (or JSON)
 *   and exits 1 on an error, 2 on a usage mistake.
 */

import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { themesSource } from '../scripts/themes.mjs';
import { checkTheme, type Finding } from '../theme-check/check';
import { run } from '../theme-check/cli';
import { registryData } from '../theme-check/registry';
import { createResolver, themeSource } from '../theme-check/resolve';
import { CONVENTIONS, PRESET_NAMES, RESOLVER } from './fixtures/themeTokens';

const ROOT = join(import.meta.dirname, '..');
const STYLES = readFileSync(join(ROOT, 'src/styles.css'), 'utf8');
const REGISTRY = registryData();
const ACME = readFileSync(
  join(ROOT, '../storybook/stories/view-engine/host-theme/acme.css'),
  'utf8',
);

const check = (css: string, presets: readonly string[] = ['neutral']) =>
  checkTheme(css, RESOLVER, REGISTRY, { presets });

/** Each finding as `severity check`, the way a reader scans the report. */
const kinds = (findings: readonly Finding[]) => [
  ...new Set(findings.map(({ severity, check }) => `${severity} ${check}`)),
];

describe('the token rules the build ships', () => {
  const shipped = createResolver({
    styles: themeSource(STYLES),
    presets: themesSource(),
    registry: REGISTRY,
  });

  it('are a fraction of the stylesheet', () => {
    expect(themeSource(STYLES).length).toBeLessThan(STYLES.length / 2);
  });

  it.each(
    PRESET_NAMES.flatMap(preset =>
      (['light', 'dark'] as const).map(mode => [preset, mode] as const),
    ),
  )('resolve %s, %s as the stylesheet does', (preset, mode) => {
    for (const convention of CONVENTIONS)
      for (const host of [{}, { '--fve-brand': '#7c3aed' }] as Record<
        string,
        string
      >[])
        expect(
          shipped.declared(preset, mode, convention, host, {
            brandChart: true,
          }),
        ).toEqual(
          RESOLVER.declared(preset, mode, convention, host, {
            brandChart: true,
          }),
        );
    expect(shipped.resetVariables()).toEqual(RESOLVER.resetVariables());
  });
});

describe('a sound theme', () => {
  it('clears: the Storybook sample host theme', () => {
    expect(checkTheme(ACME, RESOLVER, REGISTRY)).toEqual([]);
  });

  it('clears: a brand colour alone, on every preset', () => {
    expect(
      checkTheme(':root { --fve-brand: #0f766e; }', RESOLVER, REGISTRY),
    ).toEqual([]);
  });

  it('clears: roles and a link on a preset of its own', () => {
    expect(
      check(`:where([data-fve-preset='mine']) {
        --fvp-table-header-weight: 600;
        --fvp-highlight-link: 100%;
        --fvp-highlight-foreground-link: 100%;
      }`),
    ).toEqual([]);
  });

  it('clears: a Tailwind v3 bridge written with hsl()', () => {
    expect(
      check(`:root { --primary: 222.2 47.4% 11.2%; }
      :where(:root:not([data-fve-preset])) {
        --fvp-primary: hsl(var(--primary));
        --fvp-dark-primary: hsl(var(--primary));
      }`),
    ).toEqual([]);
  });

  it('reads the host’s own variables it points at', () => {
    expect(
      check(
        ':root { --teal: oklch(0.2 0.1 190deg); --fve-ring: var(--teal); }',
      ),
    ).toEqual([]);
    expect(
      kinds(check(':root { --pale: #f4f4f4; --fve-ring: var(--pale); }')),
    ).toEqual(['error contrast']);
  });
});

describe('each check fires on what it names', () => {
  it.each([
    [
      'an unregistered host variable',
      ':root { --fve-bogus: red; }',
      'error registry',
    ],
    [
      'an unregistered preset variable',
      ":where([data-fve-preset='x']) { --fvp-bogus: red; }",
      'error registry',
    ],
    [
      'the engine’s own, written',
      ':root { --_fve-row-hover: red; }',
      'error registry',
    ],
    [
      'the engine’s own, read',
      ':root { --fve-ring: var(--_fve-row-hover); }',
      'warning registry',
    ],
    [
      'an unregistered variable, read',
      ':root { --fve-ring: var(--fve-bogus, red); }',
      'error registry',
    ],
    [
      'a preset inside a layer',
      "@layer host { :where([data-fve-preset='x']) { --fvp-radius: 0; } }",
      'error layer',
    ],
    [
      'a preset variable outside a preset',
      '.page { --fvp-radius: 0; }',
      'warning layer',
    ],
    [
      'a host variable inside a preset',
      ":where([data-fve-preset='x']) { --fve-radius: 0; }",
      'warning layer',
    ],
    [
      'initial in a preset',
      ":where([data-fve-preset='x']) { --fvp-radius: initial; }",
      'warning registry',
    ],
    [
      'HSL channels as a colour',
      ':root { --fve-primary: 222 47% 11%; }',
      'error hsl',
    ],
    [
      'a colour that reads HSL channels',
      ':root { --primary: 222 47% 11%; --fve-primary: var(--primary); }',
      'error hsl',
    ],
    [
      'a v3 shadcn theme',
      ':root { --background: 0 0% 100%; --primary: 222 47% 11%; }',
      'warning hsl',
    ],
    [
      'a chart palette given in part',
      ":where([data-fve-preset='x']) { --fvp-chart-1: #0f766e; }",
      'error registry',
    ],
    ['a bound out of range', ':root { --fve-brand-l-max: 2; }', 'error brand'],
    [
      'a lower bound above its upper',
      ':root { --fve-brand-l-min: 0.6; --fve-brand-l-max: 0.5; }',
      'error brand',
    ],
    [
      'a bound that lets a brand fall short',
      ':root { --fve-brand-l-max: 0.75; }',
      'error brand',
    ],
    [
      'a focus ring under 3:1',
      ':root { --fve-ring: #eeeeee; }',
      'error contrast',
    ],
    [
      'a palette whose neighbours merge',
      ':root { --fve-chart-1: #777777; --fve-chart-2: #787878; }',
      'error palette',
    ],
    ['unparseable CSS', ':root { --fve-ring: #000', 'error parse'],
  ])('%s', (_, css, kind) => {
    expect(kinds(check(css))).toContain(kind);
  });

  it('says where, by line and column', () => {
    const [finding] = check('\n\n:root {\n  --fve-bogus: red;\n}');
    expect(finding.at).toBe('4:3');
  });

  it('reports a pair short in every convention once', () => {
    const findings = check(':root { --fve-ring: #eeeeee; }');
    const names = findings.map(({ message }) => message);
    expect(new Set(names).size).toBe(names.length);
    expect(names.some(name => name.includes('(red-up)'))).toBe(false);
  });

  it('measures a host’s own preset beside the built-in ones', () => {
    const findings = check(
      ":where([data-fve-preset='mine']) { --fvp-table-header: #222222; }",
    );
    expect(findings.map(({ message }) => message)).toContain(
      'mine/light: table-header-foreground text on header band 1.24:1 < 4.5:1',
    );
  });
});

describe('the command', () => {
  /** A `dist` of the command's three files, written from the sources. */
  const dist = mkdtempSync(join(tmpdir(), 'theme-check-'));
  writeFileSync(join(dist, 'theme-tokens.json'), JSON.stringify(REGISTRY));
  writeFileSync(join(dist, 'theme-source.css'), themeSource(STYLES));
  writeFileSync(join(dist, 'themes.css'), themesSource());
  const file = (name: string, css: string) => {
    const path = join(dist, name);
    writeFileSync(path, css);
    return path;
  };
  const invoke = (...argv: string[]) => {
    const lines: string[] = [];
    const code = run(argv, line => lines.push(line), dist);
    return { code, text: lines.join('\n') };
  };

  it('exits 0 on a theme that clears', () => {
    const { code, text } = invoke('theme-check', file('acme.css', ACME));
    expect(code).toBe(0);
    expect(text).toBe('theme-check: every gate clears');
  });

  it('exits 1 on an error, naming the file, the line and the check', () => {
    const path = file('bad.css', ':root {\n  --fve-bogus: red;\n}');
    const { code, text } = invoke('theme-check', path);
    expect(code).toBe(1);
    expect(text).toContain(`error registry ${path}:2:3 --fve-bogus`);
    expect(text).toContain('theme-check: 1 error(s), 0 warning(s)');
  });

  it('exits 0 on warnings alone', () => {
    const { code } = invoke(
      'theme-check',
      file('warn.css', '.page { --fvp-radius: 0; }'),
    );
    expect(code).toBe(0);
  });

  it('prints JSON', () => {
    const { text } = invoke(
      'theme-check',
      file('json.css', ':root { --fve-bogus: red; }'),
      '--json',
    );
    expect(JSON.parse(text)).toEqual([
      expect.objectContaining({ severity: 'error', check: 'registry' }),
    ]);
  });

  it('measures only the presets it is told to', () => {
    const path = file('ring.css', ':root { --fve-ring: #eeeeee; }');
    const { text } = invoke('theme-check', path, '--preset', 'azure');
    expect(text).toContain('azure/light');
    expect(text).not.toContain('neutral/light');
  });

  it.each([
    [[], 2],
    [['--help'], 0],
    [['theme-check', '--help'], 0],
    [['lint'], 2],
    [['theme-check'], 2],
    [['theme-check', 'x.css', '--preset', 'brand'], 2],
    [['theme-check', 'x.css', '--nope'], 2],
    [['theme-check', join(dist, 'missing.css')], 2],
  ])('answers %j with %i', (argv, code) => {
    expect(invoke(...argv).code).toBe(code);
  });
});
