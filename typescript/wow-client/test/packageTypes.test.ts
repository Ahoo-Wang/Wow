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

import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { expect, it } from 'vitest';

it('preserves public Wow types under NodeNext without skipping declaration checks', () => {
  const file = fileURLToPath(
    new URL('./__package_consumer__.mts', import.meta.url),
  );
  const source = `
    import { aggregation, type AggregationQuery, type QueryApi } from '@ahoo-wang/fetcher-wow';
    import { zh_CN } from '@ahoo-wang/fetcher-wow/query/locale/zh_CN';
    import { en_US } from '@ahoo-wang/fetcher-wow/query/locale/en_US';
    const valid: AggregationQuery = { metrics: [aggregation.count('orders')] };
    // @ts-expect-error Aggregation must retain its nonempty metrics contract.
    const empty: AggregationQuery = { metrics: [] };
    // @ts-expect-error QueryApi is a real interface, not an unresolved type.
    const invalidApi: QueryApi = {};
    void [valid, empty, invalidApi, zh_CN, en_US];
  `;
  const options: ts.CompilerOptions = {
    noEmit: true,
    strict: true,
    skipLibCheck: false,
    target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.NodeNext,
    moduleResolution: ts.ModuleResolutionKind.NodeNext,
    types: [],
  };
  const host = ts.createCompilerHost(options);
  const read = host.readFile.bind(host);
  const exists = host.fileExists.bind(host);
  host.readFile = name => (name === file ? source : read(name));
  host.fileExists = name => name === file || exists(name);
  const program = ts.createProgram([file], options, host);
  expect(
    ts
      .getPreEmitDiagnostics(program)
      .map(diagnostic =>
        ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n'),
      ),
  ).toEqual([]);
}, 20_000);
