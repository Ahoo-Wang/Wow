/**
 * @vitest-environment node
 */

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
 * Server rendering: a framework that renders client components on the
 * server — Next.js, Remix — renders the surfaces with `renderToString`, in
 * Node, with no DOM. Every external store a surface reads answers a server
 * snapshot, or React throws "Missing getServerSnapshot"; what comes out is
 * the opening state, the one the first client frame draws as well
 * (`hydration.test.tsx`), and nothing is asked of a source or a store.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderToString } from 'react-dom/server';
import ts from 'typescript';
import { describe, expect, it, vi } from 'vitest';
import { registerChartMap, useChartMaps } from '../src/ui/charts/maps.js';
import { nextTask } from './fixtures.js';
import { SURFACES, serverEngine } from './fixtures/server.js';

describe('renderToString', () => {
  it('runs with no DOM', () => {
    expect(typeof window).toBe('undefined');
    expect(typeof document).toBe('undefined');
    expect(typeof matchMedia).toBe('undefined');
  });

  it.each(Object.entries(SURFACES))(
    '%s renders its opening state and asks nothing',
    async (_, { page, opening, slot }) => {
      const { engine, source, store } = serverEngine();
      const get = vi.spyOn(store, 'get');
      const list = vi.spyOn(store, 'list');

      const html = renderToString(page(engine));
      await nextTask();

      expect(html).toContain(`data-slot="${slot}" aria-busy="true"`);
      expect(html).toContain(`>${opening}</span>`);
      // A view opens in an effect, which no server runs.
      expect(source.paged).not.toHaveBeenCalled();
      expect(source.cursor).not.toHaveBeenCalled();
      expect(source.aggregate).not.toHaveBeenCalled();
      expect(get).not.toHaveBeenCalled();
      expect(list).not.toHaveBeenCalled();
      expect(engine.openRuntimes()).toEqual([]);
    },
  );
});

describe('the chart maps on the server', () => {
  it('reads none, even registered: the browser may register its own alone', () => {
    const off = registerChartMap({
      name: 'squares',
      load: () => Promise.reject(new Error('never loaded on a server')),
    });
    function Maps() {
      return <span>{useChartMaps().length}</span>;
    }
    try {
      expect(renderToString(<Maps />)).toBe('<span>0</span>');
    } finally {
      off();
    }
  });
});

/**
 * The rule behind the suites above, for the stores no surface here reaches
 * on a server: every `useSyncExternalStore` in `src` passes the third
 * argument, the snapshot a server render and the hydrating frame read.
 */
describe('every external store', () => {
  it('answers a server snapshot', () => {
    const src = fileURLToPath(new URL('../src', import.meta.url));
    const missing: string[] = [];
    let calls = 0;
    for (const path of readdirSync(src, {
      recursive: true,
      encoding: 'utf8',
    })) {
      if (!/\.tsx?$/.test(path)) continue;
      const file = ts.createSourceFile(
        path,
        readFileSync(join(src, path), 'utf8'),
        ts.ScriptTarget.Latest,
        true,
        path.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
      );
      const visit = (node: ts.Node): void => {
        if (
          ts.isCallExpression(node) &&
          /(^|\.)useSyncExternalStore$/.test(node.expression.getText(file))
        ) {
          calls += 1;
          if (node.arguments.length < 3) {
            const { line } = file.getLineAndCharacterOfPosition(node.pos);
            missing.push(`${path}:${line + 1}`);
          }
        }
        ts.forEachChild(node, visit);
      };
      visit(file);
    }
    expect(calls).toBeGreaterThan(0);
    expect(missing).toEqual([]);
  });
});
