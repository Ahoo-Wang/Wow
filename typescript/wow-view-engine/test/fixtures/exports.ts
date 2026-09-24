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
import { dirname, join, resolve } from 'node:path';
import ts from 'typescript';

/** The package root, which every path below is relative to. */
export const PACKAGE_ROOT = join(import.meta.dirname, '../..');

/**
 * The three code entries, by the name a host imports them under, and the
 * source file each is built from (`package.json`'s `exports`).
 */
export const ENTRIES = {
  '@ahoo-wang/wow-view-engine': 'src/index.ts',
  '@ahoo-wang/wow-view-engine/react': 'src/react/index.ts',
  '@ahoo-wang/wow-view-engine/ui': 'src/ui/index.ts',
} as const;

export type Entry = keyof typeof ENTRIES;

/**
 * Whether a name is only a type — gone once compiled — or a value a module
 * really exports at run time. A class is a value.
 */
export type ExportKind = 'type' | 'value';

/** The file a relative module specifier names, `.js` read as its source. */
function moduleFile(from: string, specifier: string): string {
  const base = resolve(dirname(from), specifier.replace(/\.js$/, ''));
  const found = [`${base}.ts`, `${base}.tsx`, join(base, 'index.ts')].find(
    existsSync,
  );
  if (!found) throw new Error(`${from}: cannot resolve ${specifier}`);
  return found;
}

/** One exported name: its kind, and the declaration it is bound to. */
interface Binding {
  kind: ExportKind;
  /** `file#name` of the declaration, so one binding reached twice is one. */
  origin: string;
}

const cache = new Map<string, ReadonlyMap<string, Binding>>();

/**
 * Every name a module exports and what it is bound to, following `export *`
 * and `export { … } from` through the source tree. Read off the syntax, one
 * file at a time — no program, no checker — so the suites that read it stay
 * quick.
 *
 * A form it does not know is thrown rather than guessed at: a reading that
 * quietly dropped a name would let that name out of the public surface
 * without anybody deciding it. So is one name bound to two declarations,
 * which a module cannot export at all.
 */
function bindingsOf(file: string): ReadonlyMap<string, Binding> {
  const known = cache.get(file);
  if (known) return known;
  const names = new Map<string, Binding>();
  const source = ts.createSourceFile(
    file,
    readFileSync(file, 'utf8'),
    ts.ScriptTarget.Latest,
    false,
    file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
  const add = (name: string, binding: Binding) => {
    const before = names.get(name);
    if (before && before.origin !== binding.origin)
      throw new Error(`${file}: ${name} is bound to two declarations`);
    // The same binding reached twice, once as a type only: it is a value.
    if (!before || binding.kind === 'value') names.set(name, binding);
  };
  const own = (name: string, kind: ExportKind) =>
    add(name, { kind, origin: `${file}#${name}` });
  for (const statement of source.statements) {
    if (ts.isExportDeclaration(statement)) {
      const clause = statement.exportClause;
      const specifier = statement.moduleSpecifier;
      if (!specifier || !ts.isStringLiteral(specifier))
        throw new Error(`${file}: a local \`export { … }\` is not read here`);
      const target = moduleFile(file, specifier.text);
      const typeOnly = (kind: ExportKind, element?: ts.ExportSpecifier) =>
        statement.isTypeOnly || element?.isTypeOnly ? 'type' : kind;
      if (!clause) {
        for (const [name, binding] of bindingsOf(target))
          add(name, { ...binding, kind: typeOnly(binding.kind) });
      } else if (ts.isNamespaceExport(clause)) {
        own(clause.name.text, typeOnly('value'));
      } else {
        const inner = bindingsOf(target);
        for (const element of clause.elements) {
          const original = (element.propertyName ?? element.name).text;
          const binding = inner.get(original);
          if (!binding)
            throw new Error(`${target} does not export ${original}`);
          add(element.name.text, {
            ...binding,
            kind: typeOnly(binding.kind, element),
          });
        }
      }
      continue;
    }
    if (ts.isExportAssignment(statement))
      throw new Error(`${file}: a default export is not read here`);
    const exported =
      ts.canHaveModifiers(statement) &&
      ts
        .getModifiers(statement)
        ?.some(modifier => modifier.kind === ts.SyntaxKind.ExportKeyword);
    if (!exported) continue;
    if (ts.isVariableStatement(statement)) {
      for (const declaration of statement.declarationList.declarations) {
        if (!ts.isIdentifier(declaration.name))
          throw new Error(`${file}: a destructured export is not read here`);
        own(declaration.name.text, 'value');
      }
    } else if (
      ts.isInterfaceDeclaration(statement) ||
      ts.isTypeAliasDeclaration(statement)
    ) {
      own(statement.name.text, 'type');
    } else if (
      (ts.isFunctionDeclaration(statement) ||
        ts.isClassDeclaration(statement) ||
        ts.isEnumDeclaration(statement)) &&
      statement.name
    ) {
      own(statement.name.text, 'value');
    } else {
      throw new Error(
        `${file}: an export of kind ${ts.SyntaxKind[statement.kind]} is not read here`,
      );
    }
  }
  cache.set(file, names);
  return names;
}

/** Every name a module exports, and whether it is a type or a value. */
export function exportsOf(file: string): ReadonlyMap<string, ExportKind> {
  return new Map(
    [...bindingsOf(file)].map(([name, { kind }]) => [name, kind] as const),
  );
}

/** What one of the three entries exports, from its source. */
export function entryExports(entry: Entry): ReadonlyMap<string, ExportKind> {
  return exportsOf(join(PACKAGE_ROOT, ENTRIES[entry]));
}
