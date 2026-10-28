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

import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const KEBAB = /^[a-z0-9]+(-[a-z0-9]+)*$/;

function source(path: string): ts.SourceFile {
  const file = new URL(path, import.meta.url);
  return ts.createSourceFile(
    file.pathname,
    readFileSync(file, 'utf8'),
    ts.ScriptTarget.Latest,
  );
}

/** Every string literal under `node`, in a type or a value. */
function literals(node: ts.Node): string[] {
  const found: string[] = [];
  const visit = (child: ts.Node) => {
    if (ts.isStringLiteral(child)) found.push(child.text);
    ts.forEachChild(child, visit);
  };
  visit(node);
  return found;
}

/** The literals of the top-level declarations of `file` named `names`. */
function declared(file: ts.SourceFile, names: readonly string[]): string[] {
  return file.statements.flatMap(statement => {
    if (ts.isTypeAliasDeclaration(statement)) {
      return names.includes(statement.name.text)
        ? literals(statement.type)
        : [];
    }
    if (ts.isVariableStatement(statement)) {
      return statement.declarationList.declarations
        .filter(d => ts.isIdentifier(d.name) && names.includes(d.name.text))
        .flatMap(d => (d.initializer ? literals(d.initializer) : []));
    }
    return [];
  });
}

/**
 * Wow spells every value of its own enumerations lower-kebab on the wire, and
 * the client's types name them so: the result's enumerations, an aggregate's
 * ownership, and every enumeration of the generated Manifest types.
 */
describe('the wire spelling of the types', () => {
  it('should be lower-kebab for the result enumerations and the ownership', () => {
    const values = declared(source('../src/wire.ts'), [
      'Stage',
      'FunctionKind',
      'CommandResultStatus',
      'Ownership',
    ]);
    expect(values).toHaveLength(3 + 7 + 3 + 2);
    expect(values.filter(value => !KEBAB.test(value))).toEqual([]);
  });

  it('should be lower-kebab for every enumeration of the Manifest types', () => {
    const values = literals(source('../src/manifestTypes.ts'));
    expect(values).not.toHaveLength(0);
    expect(values.filter(value => !KEBAB.test(value))).toEqual([]);
  });
});
