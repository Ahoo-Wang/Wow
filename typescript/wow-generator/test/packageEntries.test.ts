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

import { describe, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

describe('review regressions', () => {
  it.each(['commonjs', 'module'] as const)(
    'loads the published generator dependency chain through %s',
    mode => {
      const script = `
        (async () => {
          const load = ${mode === 'commonjs' ? 'require' : 'specifier => import(specifier)'};
          const assertModule = await load('node:assert/strict');
          const assert = assertModule.default ?? assertModule;
          const core = await load('@ahoo-wang/fetcher');
          assert.equal(typeof core.Fetcher, 'function');
          await load('@ahoo-wang/fetcher-eventstream');
          const response = new Response('data: ready\\n\\n', {
            headers: { 'Content-Type': 'text/event-stream' },
          });
          const reader = response.requiredEventStream().getReader();
          assert.equal((await reader.read()).value.data, 'ready');
          await reader.cancel();
          const decorator = await load('@ahoo-wang/fetcher-decorator');
          assert.equal(typeof decorator.api, 'function');
          const wow = await load('@ahoo-wang/wow-client');
          assert.equal(wow.filter.matchAll().op, 'MATCH_ALL');
          const legacy = await load('@ahoo-wang/wow-client/legacy');
          for (const locale of ['en_US', 'zh_CN']) {
            assert.equal(typeof legacy[locale].EQ, 'string');
          }
          const generator = await load('@ahoo-wang/wow-generator');
          assert.equal(typeof generator.CodeGenerator, 'function');
          assert.equal(typeof generator.ConsoleLogger, 'function');
          assert.equal(typeof generator.SilentLogger, 'function');
          assert.equal(typeof generator.GeneratorError, 'function');
          assert.equal(generator.EXIT_CODES.specification, 4);
          assert.equal(generator.DEFAULT_CONFIG_PATH, './wow-generator.config.json');
          console.log('exports-ok');
        })().catch(error => { console.error(error); process.exitCode = 1; });
      `;
      expect(
        execFileSync(process.execPath, [`--input-type=${mode}`, '-e', script], {
          cwd: fileURLToPath(new URL('..', import.meta.url)),
          encoding: 'utf8',
          timeout: 10000,
        }).trim(),
      ).toBe('exports-ok');
    },
  );
});
