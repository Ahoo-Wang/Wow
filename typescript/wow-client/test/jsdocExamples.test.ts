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
 * The `@example` blocks of the query DSL and the command builders compile.
 *
 * An example is the first thing a reader copies, and one that stopped
 * compiling when a signature changed teaches the wrong call. Each block below
 * is compiled as a module of its own, with every export of the root entry in
 * scope and a few declared clients for the examples that send.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

/** The files whose every example must compile. */
const FILES = [
  'src/dsl/filter/builders.ts',
  'src/dsl/aggregation/builders.ts',
  'src/dsl/queryable.ts',
  'src/client/command/commandRequest.ts',
  'src/types/wowError.ts',
  'src/transport/endpoints.ts',
];

const root = new URL('../', import.meta.url);
const entry = fileURLToPath(new URL('src/index.ts', root));

/** Every name the root entry exports, from its surface list. */
const names = readFileSync(new URL('test/surface/root.txt', root), 'utf8')
  .split('\n')
  .filter(line => /^(type|value) /.test(line))
  .map(line => line.slice(6).trim());

const PRELUDE = `
import { ${names.join(', ')} } from ${JSON.stringify(entry.replace(/\.ts$/, '.js'))};
declare const commandClient: CommandClient;
declare const snapshotClient: SnapshotQueryClient<{ status: string }>;
declare const requestId: string;
declare const id: string;
export {};
`;

function examples(file: string): { name: string; code: string }[] {
  const source = readFileSync(new URL(file, root), 'utf8');
  const found: { name: string; code: string }[] = [];
  for (const comment of source.matchAll(/\/\*\*[\s\S]*?\*\//g)) {
    const text = comment[0]
      .split('\n')
      .map(line => line.replace(/^\s*\*( |$)/, ''))
      .join('\n');
    for (const block of text.matchAll(/```typescript\n([\s\S]*?)```/g)) {
      const line = source.slice(0, comment.index).split('\n').length;
      found.push({ name: `${file}:${line}`, code: block[1] });
    }
  }
  return found;
}

describe('JSDoc examples', () => {
  const all = FILES.flatMap(examples);

  it('finds examples in every file it checks', () => {
    for (const file of FILES)
      expect(
        all.some(example => example.name.startsWith(file)),
        file,
      ).toBe(true);
  });

  it('compiles every example against the root entry', () => {
    const files = new Map(
      all.map((example, index) => [
        fileURLToPath(new URL(`test/__example_${index}__.ts`, root)),
        example,
      ]),
    );
    const options: ts.CompilerOptions = {
      noEmit: true,
      strict: true,
      skipLibCheck: true,
      experimentalDecorators: true,
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      lib: ['lib.es2022.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
      types: [],
      noUnusedLocals: false,
    };
    const host = ts.createCompilerHost(options);
    const read = host.readFile.bind(host);
    const exists = host.fileExists.bind(host);
    host.readFile = name => {
      const example = files.get(name);
      return example ? PRELUDE + example.code : read(name);
    };
    host.fileExists = name => files.has(name) || exists(name);
    const program = ts.createProgram([...files.keys()], options, host);
    const failures = [...files].flatMap(([path, example]) =>
      ts
        .getPreEmitDiagnostics(program, program.getSourceFile(path))
        .map(
          diagnostic =>
            `${example.name}: ${ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')}`,
        ),
    );
    expect(failures).toEqual([]);
  }, 60_000);
});
