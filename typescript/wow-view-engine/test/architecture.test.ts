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

// @vitest-environment node
import { join, relative } from 'node:path';
import { expect, it } from 'vitest';
import {
  root,
  runtimeModule,
  graphFrom,
  cyclesIn,
  browserGlobalsIn,
} from './architecture/runtimeGraph.js';

it('keeps the public core runtime independent of browser UI libraries and globals', () => {
  const graph = graphFrom([join(root, 'index.ts')]);
  const uiImports = [...graph].flatMap(([file, module]) =>
    [
      ...module.external.filter(specifier =>
        /^(react(?:$|[-/])|@tanstack\/|@base-ui\/|lucide-react$|recharts(?:$|\/))/.test(
          specifier,
        ),
      ),
      ...module.assets,
    ].map(specifier => `${relative(root, file)} -> ${specifier}`),
  );
  expect(uiImports).toEqual([]);
  expect(browserGlobalsIn(graph)).toEqual([]);
});

it('keeps both public runtime import graphs free of internal cycles', () => {
  expect(
    cyclesIn(graphFrom([join(root, 'index.ts'), join(root, 'react.ts')])),
  ).toEqual([]);
});

it('distinguishes runtime imports and browser references from types, strings and local bindings', () => {
  const runtime = runtimeModule(
    'example.ts',
    `
    import type { ReactNode } from 'react';
    export type { ColumnDef } from '@tanstack/react-table';
    export { type ColumnDef as ColumnAlias } from '@tanstack/react-table';
    import { type Hidden, value as renamed } from './runtime.js';
    export { value } from './value.js';
    const document = 'window';
    export const local = [document, renamed];
  `,
  );
  expect(runtime.imports).toEqual(['./runtime.js', './value.js']);
  const fixture = (javascript: string) =>
    new Map([
      [
        join(root, 'example.ts'),
        { javascript, local: [], external: [], assets: [] },
      ],
    ]);
  expect(browserGlobalsIn(fixture(runtime.javascript))).toEqual([]);
  expect(
    browserGlobalsIn(fixture('export const element = document.body;')),
  ).toEqual(['example.js: document']);
  expect(
    browserGlobalsIn(fixture("export const element = globalThis['document'];")),
  ).toEqual(['example.js: document']);
  expect(
    browserGlobalsIn(fixture('export const parser = new DOMParser();')),
  ).toEqual(['example.js: DOMParser']);
  const cyclic = new Map([
    [
      join(root, 'a.ts'),
      { javascript: '', local: [join(root, 'b.ts')], external: [], assets: [] },
    ],
    [
      join(root, 'b.ts'),
      { javascript: '', local: [join(root, 'a.ts')], external: [], assets: [] },
    ],
  ]);
  expect(cyclesIn(cyclic)).toEqual([['a.ts', 'b.ts', 'a.ts']]);
});

it('keeps per-kind session derivation independent of lifecycle orchestration', () => {
  for (const entry of [
    'record/engine/recordSession.ts',
    'analysis/analysisSession.ts',
  ]) {
    const graph = graphFrom([join(root, entry)]);
    for (const service of [
      'engine/sessionState.ts',
      'engine/SessionStore.ts',
      'engine/ViewEngine.ts',
    ]) {
      expect(
        graph.has(join(root, service)),
        `${entry} must not reach ${service}`,
      ).toBe(false);
    }
  }
});
