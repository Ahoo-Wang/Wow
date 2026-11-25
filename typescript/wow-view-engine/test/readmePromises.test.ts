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
 * What the READMEs promise a host about stored views and the packages they
 * send it to, held to what the engine and those packages do (#7065).
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DashboardViewConfig,
} from '../src/index.js';
import {
  dashboardConfig,
  overviewDefinition,
  testEnvironment,
} from './fixtures.js';

describe('README', () => {
  const readme = (name: string) =>
    readFileSync(join(import.meta.dirname, '..', name), 'utf8');

  it('promises no migration on read, which the engine does not do (AGENTS.md「Stored views are read as they are」, D306)', async () => {
    // A board saved in an older form: the 12-column grid.
    const board = {
      ...dashboardConfig(),
      columns: 12,
    } as unknown as DashboardViewConfig;
    const store = new MemoryViewStore({
      instances: [
        {
          id: 'board-1',
          definitionId: 'overview',
          title: 'Old board',
          scope: 'personal',
          revision: '1',
          config: board,
        },
      ],
    });
    const engine = new ViewEngine({
      store,
      resources: [{ definition: overviewDefinition() }],
      environment: testEnvironment().environment,
    });
    const runtime = await engine.open('board-1');
    // Refused, not migrated: the board does not keep opening.
    expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
      'dashboard.grid.unsupported',
    );
    expect(runtime.getSnapshot().draft).toMatchObject({ columns: 12 });

    // So the READMEs tell a host what the engine does.
    expect(readme('README.md')).not.toMatch(/migrates stored configs on read/);
    expect(readme('README.zh-CN.md')).not.toMatch(/读取时迁移存储的配置/);
    expect(readme('README.md')).toContain(
      'nothing migrates a stored config on read',
    );
    expect(readme('README.zh-CN.md')).toContain('读取时不迁移任何存储的配置');
  });
});

describe('README entries of the packages it sends a host to', () => {
  it('names no entry of @ahoo-wang/wow-view-store that the package does not export', () => {
    const exported = Object.keys(
      (
        JSON.parse(
          readFileSync(
            join(import.meta.dirname, '../../wow-view-store/package.json'),
            'utf8',
          ),
        ) as { exports: Record<string, unknown> }
      ).exports,
    );
    for (const name of ['README.md', 'README.zh-CN.md']) {
      const text = readFileSync(join(import.meta.dirname, '..', name), 'utf8');
      expect(text, name).toContain('`@ahoo-wang/wow-view-store`');
      // "add `@ahoo-wang/wow-view-store` (its `/v10` entry on a Wow V10
      // service …)": D265 promoted `/v10` to the package root.
      const named = [
        ...text.matchAll(
          /wow-view-store[^)]*?\)[^`]*`(\/[a-z0-9-]+)` (?:entry|入口)/g,
        ),
      ]
        .map(match => `.${match[1]}`)
        .concat(
          [
            ...text.matchAll(
              /用它的 `(\/[a-z0-9-]+)` 入口|its `(\/[a-z0-9-]+)` entry/g,
            ),
          ].map(match => `.${match[1] ?? match[2]}`),
        );
      for (const entry of named)
        expect({
          readme: name,
          entry,
          exported: exported.includes(entry),
        }).toEqual({
          readme: name,
          entry,
          exported: true,
        });
    }
  });
});
