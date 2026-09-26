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
 * The package's command, `wow-view-engine` (theme-architecture.md 5.2, S7),
 * built with `bin.ts` into `dist/theme-check.mjs`. One command today:
 *
 *   wow-view-engine theme-check <stylesheet…> [--preset <name>]… [--brand-chart] [--json]
 *
 * It reads the registry, the token rules and the presets the build ships
 * beside it (`theme-tokens.json`, `theme-source.css`, `themes.css`) and the
 * host's stylesheets, put together in the order given, and exits 1 when
 * anything is an error — so a host's CI holds its theme to the package's
 * gates. Nothing here runs in a browser, and no runtime entry imports it.
 */

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';
import { checkTheme, type Finding } from './check';
import type { Registry } from './registry';
import { createResolver } from './resolve';

/** The command's subcommands: its public surface (`test/surface/bin.txt`). */
export const COMMANDS = ['theme-check'] as const;

const USAGE = `Usage: wow-view-engine theme-check <stylesheet…> [options]

Checks a host's theme against @ahoo-wang/wow-view-engine's theme registry:
unregistered variables, the three layers, Tailwind v3 HSL channels, the
brand colour's bounds, every contrast pair in both modes, and the chart
palette's gates.

Options:
  --preset <name>  a built-in preset the host's :root variables are worn on
                   (repeatable; every built-in preset when left out)
  --brand-chart    the page sets data-fve-brand-chart: slot 1 takes the brand
  --json           print the findings as JSON
  --help           print this and exit`;

/**
 * Where the command's own files are: the package's `dist`, beside the
 * built command.
 */
const DIST = dirname(fileURLToPath(import.meta.url));

/** Runs the command; the exit code it asks for. */
export function run(
  argv: readonly string[],
  out: (line: string) => void = line => {
    process.stdout.write(`${line}\n`);
  },
  dist: string = DIST,
): number {
  const [command, ...rest] = argv;
  if (command === '--help' || command === '-h' || command === undefined) {
    out(USAGE);
    return command === undefined ? 2 : 0;
  }
  if (!(COMMANDS as readonly string[]).includes(command)) {
    out(`Unknown command ${command}.\n\n${USAGE}`);
    return 2;
  }
  let parsed;
  try {
    parsed = parseArgs({
      args: [...rest],
      allowPositionals: true,
      options: {
        preset: { type: 'string', multiple: true },
        'brand-chart': { type: 'boolean' },
        json: { type: 'boolean' },
        help: { type: 'boolean', short: 'h' },
      },
    });
  } catch (error) {
    out(`${(error as Error).message}\n\n${USAGE}`);
    return 2;
  }
  const { values, positionals } = parsed;
  if (values.help) {
    out(USAGE);
    return 0;
  }
  if (positionals.length === 0) {
    out(`Name a stylesheet to check.\n\n${USAGE}`);
    return 2;
  }
  const registry = JSON.parse(
    readFileSync(join(dist, 'theme-tokens.json'), 'utf8'),
  ) as Registry;
  const resolver = createResolver({
    styles: readFileSync(join(dist, 'theme-source.css'), 'utf8'),
    presets: readFileSync(join(dist, 'themes.css'), 'utf8'),
    registry,
  });
  const unknown = (values.preset ?? []).filter(
    name => !resolver.presetNames.includes(name),
  );
  if (unknown.length) {
    out(
      `No built-in preset ${unknown.join(', ')}; the built-in ones are ${resolver.presetNames.join(', ')}.`,
    );
    return 2;
  }
  let css: string;
  try {
    css = positionals.map(path => readFileSync(path, 'utf8')).join('\n');
  } catch (error) {
    out((error as Error).message);
    return 2;
  }
  const findings = checkTheme(css, resolver, registry, {
    presets: values.preset,
    brandChart: values['brand-chart'],
  });
  const errors = findings.filter(({ severity }) => severity === 'error');
  if (values.json) out(JSON.stringify(findings, null, 2));
  else report(findings, positionals, out);
  return errors.length ? 1 : 0;
}

function report(
  findings: readonly Finding[],
  files: readonly string[],
  out: (line: string) => void,
): void {
  const place = files.length === 1 ? files[0] : 'the stylesheets';
  for (const { severity, check, message, at } of findings)
    out(
      `${severity} ${check.padEnd(8)} ${at ? `${place}:${at} ` : ''}${message}`,
    );
  const count = (severity: string) =>
    findings.filter(finding => finding.severity === severity).length;
  out(
    findings.length
      ? `theme-check: ${count('error')} error(s), ${count('warning')} warning(s)`
      : 'theme-check: every gate clears',
  );
}
