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
 * The theme's registry as data (theme-architecture.md 5.2, D46 Q13): what
 * the build writes out as `dist/theme-tokens.json`, and what theme-check
 * reads back. The package's own tests build the same object from the
 * sources, so the checker and the suites resolve and measure by one registry.
 *
 * Only `import type` reaches this file from the checker: the command reads
 * the JSON beside it, never the sources.
 */

import { GROUNDS, LINES, PRESET_LINES } from '../src/ui/theme/pairs';
import type { Ground, PairKind } from '../src/ui/theme/pairs';
import { TOKEN_DOCS } from '../src/ui/theme/tokenDocs';
import {
  CHART_TOKENS,
  declaredVariable,
  hostVariables,
  presetVariables,
  THEME_ATTRIBUTES,
  THEME_AXES,
  TOKEN_GROUPS,
  type TokenEntry,
  TOKENS,
} from '../src/ui/theme/tokens';

/** One registry entry, with the variables it is written and declared as. */
export interface RegistryToken extends TokenEntry {
  /** `--fve-<name>`, and `--fve-dark-<name>` for a token with a dark half. */
  readonly variables: readonly string[];
  /** The same under `--fvp-`; none for a token no preset owns. */
  readonly presetVariables: readonly string[];
  /** What the token blocks declare it as; absent for one read in place. */
  readonly declared?: string;
  /** The README's words for it, in both languages. */
  readonly doc?: unknown;
}

/** The registry as `dist/theme-tokens.json` holds it. */
export interface Registry {
  readonly tokens: readonly RegistryToken[];
  readonly groups: Readonly<Record<string, { readonly whole: boolean }>>;
  readonly axes: readonly {
    readonly name: string;
    readonly attributes: readonly string[];
  }[];
  readonly themeAttributes: readonly string[];
  readonly chartTokens: readonly string[];
  readonly grounds: readonly Ground[];
  readonly lines: Readonly<Record<PairKind, number>>;
  readonly presetLines: Readonly<
    Record<string, Partial<Record<PairKind, number>>>
  >;
}

/** The registry, built from the sources as the build writes it out. */
export function registryData(): Registry {
  const entries: readonly TokenEntry[] = TOKENS;
  return {
    tokens: entries.map(entry => ({
      ...entry,
      variables: hostVariables(entry),
      presetVariables: presetVariables(entry),
      declared: declaredVariable(entry),
      doc: TOKEN_DOCS[entry.name as keyof typeof TOKEN_DOCS],
    })),
    groups: TOKEN_GROUPS,
    axes: THEME_AXES,
    themeAttributes: THEME_ATTRIBUTES,
    chartTokens: CHART_TOKENS,
    grounds: GROUNDS,
    lines: LINES,
    presetLines: PRESET_LINES,
  };
}
