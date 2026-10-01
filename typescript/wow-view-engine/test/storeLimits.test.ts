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
 * The store's limits on a view are one rule on both sides of the port: the
 * view store server's `ViewConfigs` is the source, and the model's
 * constants mirror it, read off the Kotlin source here so the two cannot
 * drift. The engine refuses past them before sending, and `MemoryViewStore`
 * as the server does (the port's conformance suite).
 */

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  configBytes,
  MAX_VIEW_CONFIG_BYTES,
  MAX_VIEW_TITLE_LENGTH,
  titleProblem,
  type ViewConfig,
} from '../src/index.js';

const VIEW_CONFIGS = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../../view-store/wow-view-store-domain/src/main/kotlin/me/ahoo/wow/viewstore/domain/view/ViewConfigs.kt',
);

/** A `const val` of the Kotlin object, evaluated as the product it is written as. */
function kotlinConstant(source: string, name: string): number {
  const found = new RegExp(`const val ${name} = ([\\d_ *]+)\\n`).exec(source);
  if (!found) throw new Error(`ViewConfigs.kt declares no ${name}`);
  return found[1]
    .split('*')
    .map(factor => Number(factor.trim().replace(/_/g, '')))
    .reduce((product, factor) => product * factor, 1);
}

describe("the store's limits on a view", () => {
  const source = readFileSync(VIEW_CONFIGS, 'utf8');

  it("are the server's own", () => {
    expect(MAX_VIEW_TITLE_LENGTH).toBe(
      kotlinConstant(source, 'MAX_TITLE_LENGTH'),
    );
    expect(MAX_VIEW_CONFIG_BYTES).toBe(
      kotlinConstant(source, 'MAX_CONFIG_BYTES'),
    );
  });

  it('judge a title as stored, trimmed', () => {
    expect(titleProblem('   ')).toBe('empty');
    expect(titleProblem(` ${'x'.repeat(MAX_VIEW_TITLE_LENGTH)} `)).toBeNull();
    expect(titleProblem('x'.repeat(MAX_VIEW_TITLE_LENGTH + 1))).toBe(
      'too-long',
    );
  });

  it('count a config in UTF-8 bytes of its JSON, as the server does', () => {
    const config = { kind: 'record', note: 'é中😀' } as unknown as ViewConfig;
    expect(configBytes(config)).toBe(
      new TextEncoder().encode(JSON.stringify(config)).length,
    );
  });
});
