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
import { Project } from 'ts-morph';
import { applyTypeOnlyImports } from '../../src/finalize/typeOnlyImports';

/**
 * One project for the whole file: a new project parses TypeScript's library
 * before it can tell a type from a value, which with coverage on took most of
 * a test's time. Each call writes its own directory and removes it after.
 */
const project = new Project({ useInMemoryFileSystem: true });
let directories = 0;

function importsOf(...sources: string[]): string[][] {
  const directory = `/src-${++directories}`;
  const files = sources.map((source, index) =>
    project.createSourceFile(`${directory}/file${index}.ts`, source),
  );
  applyTypeOnlyImports(files);
  const imports = files.map(file =>
    file.getImportDeclarations().map(declaration => declaration.getText()),
  );
  files.forEach(file => project.removeSourceFile(file));
  return imports;
}

function importOf(source: string): string[] {
  return importsOf(source)[0];
}

describe('applyTypeOnlyImports', () => {
  it('emits import type when every specifier is used only as a type', () => {
    expect(
      importOf(`
        import { A, B, C, D } from './types';
        export interface X extends A { b: B[] }
        export type Y = Promise<C> | typeof D;
      `),
    ).toEqual(["import type { A, B, C, D } from './types';"]);
  });

  it('keeps a plain import when every specifier is used as a value', () => {
    expect(
      importOf(`
        import { api, Base, ContentTypeValues } from '@ahoo-wang/fetcher-decorator';
        @api()
        export class X extends Base {
          accept = ContentTypeValues.TEXT_EVENT_STREAM;
        }
      `),
    ).toEqual([
      "import { api, Base, ContentTypeValues } from '@ahoo-wang/fetcher-decorator';",
    ]);
  });

  it('marks type-only specifiers inline when values and types mix', () => {
    expect(
      importOf(`
        import { ApiMetadata, ApiMetadataCapable, api } from '@ahoo-wang/fetcher-decorator';
        @api()
        export class X implements ApiMetadataCapable {
          constructor(public readonly apiMetadata?: ApiMetadata) {}
        }
      `),
    ).toEqual([
      "import { type ApiMetadata, type ApiMetadataCapable, api } from '@ahoo-wang/fetcher-decorator';",
    ]);
  });

  it('treats the leftmost name of a qualified or property access as the reference', () => {
    expect(
      importOf(`
        import { Types, values } from './mod';
        export type T = Types.Nested.Name;
        export const v = values.nested.name;
      `),
    ).toEqual(["import { type Types, values } from './mod';"]);
  });

  it('removes a type modifier that a value use contradicts', () => {
    expect(
      importOf(`
        import type { A, B } from './mod';
        export const b = new B();
        export let a: A;
      `),
    ).toEqual(["import { type A, B } from './mod';"]);
    expect(
      importOf(`
        import { type A, type B } from './mod';
        export let a: A | B;
      `),
    ).toEqual(["import type { A, B } from './mod';"]);
  });

  it('ignores names that shadow an import', () => {
    expect(
      importOf(`
        import { path, Request } from './mod';
        export function load(path: string, request: Request) {
          return path + request;
        }
        export interface Options { path: Request }
      `),
    ).toEqual(["import type { path, Request } from './mod';"]);
  });

  it('follows the local name of an aliased specifier', () => {
    expect(
      importOf(`
        import { Order as _Order, create as make } from './types';
        export const order: _Order = make();
        export const alias = make;
      `),
    ).toEqual([
      "import { type Order as _Order, create as make } from './types';",
    ]);
    expect(
      importOf(`
        import { Order as _Order } from './types';
        export let order: _Order;
      `),
    ).toEqual(["import type { Order as _Order } from './types';"]);
  });

  it('leaves default and namespace imports as they are', () => {
    expect(
      importOf(`
        import Default, { A } from './mod';
        import * as ns from './ns';
        export let a: A | Default | ns.T;
      `),
    ).toEqual([
      "import Default, { A } from './mod';",
      "import * as ns from './ns';",
    ]);
  });

  it('plans every file before rewriting any of them', () => {
    expect(
      importsOf(
        `import { A } from './b';\nexport let a: A;`,
        `import { B } from './a';\nexport const b = B;`,
        `export const nothingImported = 1;`,
      ),
    ).toEqual([
      ["import type { A } from './b';"],
      ["import { B } from './a';"],
      [],
    ]);
  });

  it('is idempotent', () => {
    const file = project.createSourceFile(
      '/src/file.ts',
      `import { A, b } from './mod';\nexport const x: A = b;\n`,
    );
    applyTypeOnlyImports([file]);
    const once = file.getFullText();
    applyTypeOnlyImports([file]);
    expect(file.getFullText()).toBe(once);
    expect(once).toContain("import { type A, b } from './mod';");
  });
});
