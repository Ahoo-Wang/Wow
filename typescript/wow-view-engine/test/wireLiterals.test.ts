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

import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import {
  WIRE_SPELLING_MARKER,
  wireLiterals,
  wireVocabularies,
} from '../scripts/wire-literals.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const vocabularies = wireVocabularies();

const IMPORTS = `import type { AnalysisGroup, AggregationDateUnitName } from '../src/model/index.js';
import dayjs from 'dayjs';
`;

const SOURCES = {
  slots: `${IMPORTS}
export const unit: AggregationDateUnitName = 'day';
export function at(of: AggregationDateUnitName) {
  if (of === 'second' || of === 'minute') return 0;
  switch (of) {
    case 'hour':
      return 1;
  }
  return 2;
}
`,
  variants: `${IMPORTS}
export const engine: AnalysisGroup = { type: 'terms', field: 'a', alias: 'a' };
export const words: Record<AggregationDateUnitName, string> = {
  second: '', minute: '', hour: '', day: '', week: '', month: '', quarter: '', year: '',
};
`,
  own: `${IMPORTS}
type RelativeDateUnit = 'hour' | 'day' | 'week' | 'month' | 'quarter' | 'year';
export const relative: RelativeDateUnit = 'day';
export const start = dayjs().startOf('day');
export const parts = new Intl.DateTimeFormat('en', { day: '2-digit', hour: '2-digit' })
  .formatToParts().find(part => part.type === 'minute');
export const clock: Record<'year' | 'month' | 'day' | 'hour' | 'minute' | 'second', number> = {
  year: 0, month: 0, day: 0, hour: 0, minute: 0, second: 0,
};
export const word: string = 'day';
`,
  marker: `${IMPORTS}
// ${WIRE_SPELLING_MARKER}: the JSON a host writes.
export const unit: AggregationDateUnitName = 'day';
`,
};

/** The fixture files, beside the test so they import the engine as a test does. */
const path = (name: keyof typeof SOURCES) => join(here, `__wire_${name}__.ts`);

/**
 * One program of every fixture, type-checked beside the engine's own: built
 * once, since type-checking the engine is the cost of each.
 */
let fixtures: ts.Program | undefined;
function program(): ts.Program {
  if (fixtures) return fixtures;
  const parsed = ts.getParsedCommandLineOfConfigFile(
    join(here, '../tsconfig.test.json'),
    {},
    { ...ts.sys, onUnRecoverableConfigFileDiagnostic: () => {} },
  )!;
  const options = { ...parsed.options, composite: false, incremental: false };
  const sources = new Map(
    (Object.keys(SOURCES) as (keyof typeof SOURCES)[]).map(name => [
      path(name),
      SOURCES[name],
    ]),
  );
  const host = ts.createCompilerHost(options, true);
  const getSourceFile = host.getSourceFile;
  host.getSourceFile = (name, version, ...rest) => {
    const source = sources.get(name);
    return source === undefined
      ? getSourceFile.call(host, name, version, ...rest)
      : ts.createSourceFile(name, source, version, true);
  };
  const fileExists = host.fileExists;
  host.fileExists = name => sources.has(name) || fileExists.call(host, name);
  fixtures = ts.createProgram({
    rootNames: [...sources.keys()],
    options,
    host,
  });
  return fixtures;
}

/** The wire literals of one fixture, as `line value`. */
function found(name: keyof typeof SOURCES): string[] {
  return wireLiterals(program(), path(name), vocabularies, {
    honourMarker: true,
  }).map(
    ({ value, line }: { value: string; line: number }) => `${line} ${value}`,
  );
}

describe('wireLiterals', () => {
  it('knows a Wow value by the slot it is written to, compared with or a case of', () => {
    expect(found('slots')).toEqual(['4 day', '6 second', '6 minute', '8 hour']);
  });

  it('knows the variant of a discriminated union, and a Record keyed by an enum', () => {
    expect(found('variants')).toEqual([
      '4 terms',
      '6 second',
      '6 minute',
      '6 hour',
      '6 day',
      '6 week',
      '6 month',
      '6 quarter',
      '6 year',
    ]);
  });

  it('leaves the engine’s own words, a library’s, and plain strings alone', () => {
    expect(found('own')).toEqual([]);
  });

  it('honours the marker of a test about the spelling', () => {
    expect(found('marker')).toEqual([]);
  });
});
