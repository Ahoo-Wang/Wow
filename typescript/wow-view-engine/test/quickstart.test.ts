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

import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { orders } from '../examples/quickstart.js';
import {
  builtinFieldKinds,
  MemoryViewStore,
  systemInstanceId,
  validateDefinition,
  ViewEngine,
} from '../src/index.js';
import { testEnvironment, testSource } from './fixtures.js';

/**
 * The README's quick start, executed.
 *
 * Documentation drifts because nothing runs it. A definition in particular
 * reads plausibly while being one the engine refuses — the operators a field
 * offers come from its kind, and `validateDefinition` is the only thing that
 * says so. These tests make the quick start fail the build rather than the
 * reader's first afternoon.
 */
describe('the quick start definition', () => {
  it('passes admission', () => {
    const found = validateDefinition(orders, builtinFieldKinds);

    expect(found).toEqual([]);
  });

  it('opens its system view and runs', async () => {
    const environment = testEnvironment();
    const engine = new ViewEngine({
      definitions: [orders],
      store: new MemoryViewStore(),
      resolveSource: () => testSource(),
      environment: environment.environment,
    });

    const runtime = await engine.open(systemInstanceId('orders', 'pending'));

    expect(runtime.kind).toBe('record');
    expect(runtime.getSnapshot().issues).toEqual([]);
  });
});

/**
 * Every identifier the README names in a code fence has to be one the package
 * exports. `createViewEngine` and a bare `Workbench` were both in there for a
 * while, and neither has ever existed.
 */
describe('the README only names symbols that exist', () => {
  const read = (path: string) =>
    readFileSync(new URL(path, import.meta.url), 'utf8');
  const readme = read('../README.md');

  it('names no engine factory, only the class', () => {
    expect(readme).not.toContain('createViewEngine');
    expect(readme).toContain('new ViewEngine');
  });

  /**
   * Both READMEs print the definition that `examples/quickstart.ts` holds, and
   * only that file is executed above. Pinning them together is what makes the
   * executed one speak for the printed ones.
   */
  it.each(['../README.md', '../README.zh-CN.md'])(
    'prints the definition that the tests run (%s)',
    file => {
      const printed = definitionIn(read(file));
      const executed = definitionIn(read('../examples/quickstart.ts'));

      expect(printed).not.toBeNull();
      expect(printed).toEqual(executed);
    },
  );

  /**
   * The definition's structure: comments, layout and display labels removed.
   * The localised README translates every `label`, which is the point of it;
   * what has to match is the shape the engine admits.
   */
  function definitionIn(source: string): string | null {
    const found = /export const orders: ViewDefinition = \{[\s\S]*?\n\};/.exec(
      source,
    );
    return found
      ? found[0]
          .replace(/\/\/[^\n]*/g, '')
          .replace(/label: '[^']*'/g, "label: '…'")
          .replace(/,(\s*[\]}])/g, '$1')
          .replace(/\s+/g, ' ')
          .trim()
      : null;
  }

  /**
   * Only what the README imports *from this package* is checked. Its own
   * examples stand in an application's `<Spinner />` and `<NotFound />`, which
   * are the reader's to supply and were never ours to export.
   */
  it('imports only names the entry it names exports', () => {
    const entries: Record<string, string> = {
      '@ahoo-wang/fetcher-view-engine': '../src/index.ts',
      '@ahoo-wang/fetcher-view-engine/react': '../src/react/index.ts',
      '@ahoo-wang/fetcher-view-engine/ui': '../src/ui/index.ts',
    };

    const imports = readme.matchAll(
      /import (?:type )?\{([^}]+)\} from '(@ahoo-wang\/fetcher-view-engine[^']*)'/g,
    );

    let checked = 0;
    for (const [, names, entry] of imports) {
      const index = entries[entry];
      if (!index) continue;
      const surface = sourcesOf(index).join('\n');
      for (const name of names.split(',').map(part => part.trim())) {
        if (name.length === 0) continue;
        checked += 1;
        expect(`${entry} exports ${name}`).toBe(
          new RegExp(
            `export (?:abstract )?(?:function|class|const|interface|type) ${name}\\b`,
          ).test(surface)
            ? `${entry} exports ${name}`
            : `${entry} does not export ${name}, but README.md imports it`,
        );
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  /**
   * An entry and everything it re-exports, transitively: the entries are
   * barrels over barrels, so a type declared in `model/definition.ts` is only
   * reachable by following `index.ts` twice.
   */
  function sourcesOf(index: string, seen = new Set<string>()): string[] {
    if (seen.has(index) || !existsSync(new URL(index, import.meta.url)))
      return [];
    seen.add(index);

    const body = read(index);
    const directory = index.slice(0, index.lastIndexOf('/'));
    const nested = [...body.matchAll(/^export \* from '\.\/(.+)\.js';$/gm)]
      .flatMap(([, name]) =>
        ['.ts', '.tsx'].map(extension => `${directory}/${name}${extension}`),
      )
      .flatMap(path => sourcesOf(path, seen));
    return [body, ...nested];
  }
});
