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
 * The `@example` blocks of the source compile.
 *
 * An example is the first thing a reader copies, and one that stopped
 * compiling when a signature changed teaches the wrong call. Each block below
 * is compiled as a module of its own, with every export of the root and DSL
 * entries in scope and a few declared clients for the examples that send.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const root = new URL('../', import.meta.url);
const readSource = (file: string) => readFileSync(new URL(file, root), 'utf8');

/**
 * The files whose every example must compile: each source file with a
 * `typescript` block in a doc comment.
 */
const FILES = readdirSync(new URL('src/', root), { recursive: true })
  .map(path => `src/${String(path).split(sep).join('/')}`)
  .filter(file => file.endsWith('.ts'))
  .filter(file =>
    /\/\*\*(?:(?!\*\/)[\s\S])*```typescript/.test(readSource(file)),
  )
  .sort();

/** An entry's source, as a module specifier, and every name its surface list holds. */
function entryOf(source: string, list: string) {
  return {
    specifier: JSON.stringify(
      fileURLToPath(new URL(source, root)).replace(/\.ts$/, '.js'),
    ),
    names: readFileSync(new URL(list, root), 'utf8')
      .split('\n')
      .filter(line => /^(type|value) /.test(line))
      .map(line => line.slice(6).trim()),
  };
}

const ROOT = entryOf('src/index.ts', 'test/surface/root.txt');
const DSL = entryOf('src/dsl.ts', 'test/surface/dsl.txt');

/** The names an example may use without declaring them, with their types. */
const CONTEXT: Record<string, string> = {
  fetcher: 'Fetcher',
  snapshots: 'SnapshotQueryClient<{ status: string }>',
  requestId: 'string',
  id: 'string',
  cartId: 'string',
};

/** The names an example imports or declares at its top level. */
function ownNames(code: string): Set<string> {
  const own = new Set<string>();
  for (const [, list] of code.matchAll(/^import(?: type)? \{([^}]*)\}/gm))
    for (const part of list.split(','))
      own.add(
        part
          .trim()
          .split(/\s+as\s+/)
          .pop()!
          .replace(/^type /, ''),
      );
  for (const [, name] of code.matchAll(
    /^(?:export )?(?:const|let|var|function|class|type|interface|enum) (\w+)/gm,
  ))
    own.add(name);
  return own;
}

/**
 * An example is compiled as a module with every root and DSL export and the
 * {@link CONTEXT} names in scope, except those it imports or declares
 * itself; an import of the package resolves to the source entry.
 */
function module(code: string): string {
  const own = ownNames(code);
  const scope = (entry: typeof ROOT) => {
    const names = entry.names.filter(name => !own.has(name));
    return names.length
      ? `import { ${names.join(', ')} } from ${entry.specifier};\n`
      : '';
  };
  const fetcher = own.has('Fetcher')
    ? ''
    : `import { Fetcher } from '@ahoo-wang/fetcher';\n`;
  const context = Object.entries(CONTEXT)
    .filter(([name]) => !own.has(name))
    .map(([name, type]) => `declare const ${name}: ${type};\n`)
    .join('');
  const body = code
    .split(`'@ahoo-wang/wow-client/dsl'`)
    .join(DSL.specifier)
    .split(`'@ahoo-wang/wow-client'`)
    .join(ROOT.specifier);
  return `${scope(ROOT)}${scope(DSL)}${fetcher}${context}export {};\n${body}`;
}

function examples(file: string): { name: string; code: string }[] {
  const source = readSource(file);
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
      return example ? module(example.code) : read(name);
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
