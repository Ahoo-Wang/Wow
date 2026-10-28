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
import { join } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

/**
 * The Manifest types of `src/manifest.ts` are a reader's view of the format:
 * the Manifest's own JSON Schema (`ManifestSchema` of `:manifest`, published
 * as `META-INF/wow/manifest.schema.json`) is its one description. Every key a
 * type declares is the schema's, a key the schema leaves optional is optional
 * here, and an enumeration lists the schema's values.
 */
type Schema = Record<string, unknown>;

const SCHEMA: Schema = JSON.parse(
  readFileSync(
    join(
      import.meta.dirname,
      '../../../api/manifest/src/main/resources/META-INF/wow/manifest.schema.json',
    ),
    'utf8',
  ),
);
const SOURCE = ts.createSourceFile(
  'manifest.ts',
  readFileSync(join(import.meta.dirname, '../src/manifest.ts'), 'utf8'),
  ts.ScriptTarget.Latest,
  true,
);

/** The declarations of the file by name: its interfaces and type aliases. */
const declarations = new Map<string, ts.Node>();
SOURCE.forEachChild(node => {
  if (ts.isInterfaceDeclaration(node) || ts.isTypeAliasDeclaration(node)) {
    declarations.set(node.name.text, node);
  }
});

function resolve(schema: Schema): Schema {
  const ref = schema.$ref;
  if (typeof ref !== 'string') return schema;
  return (SCHEMA.$defs as Record<string, Schema>)[ref.replace('#/$defs/', '')];
}

/** The string literals a type spells, or undefined for another type. */
function literals(type: ts.TypeNode): string[] | undefined {
  if (ts.isTypeReferenceNode(type)) {
    const named = declarations.get(type.typeName.getText());
    return named && ts.isTypeAliasDeclaration(named)
      ? literals(named.type)
      : undefined;
  }
  const members = ts.isUnionTypeNode(type) ? type.types : [type];
  const values = members.flatMap(member =>
    ts.isLiteralTypeNode(member) && ts.isStringLiteral(member.literal)
      ? [member.literal.text]
      : [],
  );
  return values.length > 0 && values.length === members.length
    ? values
    : undefined;
}

/** What `members` breaks of `schema`, an object schema, at `path`. */
function compare(
  members: ts.NodeArray<ts.TypeElement>,
  schema: Schema,
  path: string,
): string[] {
  const resolved = resolve(schema);
  const properties = (resolved.properties ?? {}) as Record<string, Schema>;
  const required = (resolved.required ?? []) as string[];
  return members.flatMap(member => {
    if (!ts.isPropertySignature(member) || member.type === undefined) return [];
    const name = member.name.getText().replace(/['"]/g, '');
    const where = `${path}.${name}`;
    const property = properties[name];
    if (property === undefined) return [`${where} is not in the schema`];
    if (member.questionToken === undefined && !required.includes(name)) {
      return [`${where} is required here, optional in the schema`];
    }
    return name === '$defs' ? [] : compareType(member.type, property, where);
  });
}

function compareType(
  type: ts.TypeNode,
  schema: Schema,
  path: string,
): string[] {
  const resolved = resolve(schema);
  const values = literals(type);
  if (values !== undefined && Array.isArray(resolved.enum)) {
    const known = [...(resolved.enum as string[])].sort();
    return JSON.stringify([...values].sort()) === JSON.stringify(known)
      ? []
      : [`${path} lists ${values.join(', ')}, the schema ${known.join(', ')}`];
  }
  if (ts.isTypeOperatorNode(type)) return compareType(type.type, schema, path);
  if (ts.isArrayTypeNode(type)) {
    return compareType(
      type.elementType,
      (resolved.items ?? {}) as Schema,
      path,
    );
  }
  if (ts.isTypeLiteralNode(type)) return compare(type.members, resolved, path);
  if (ts.isTypeReferenceNode(type)) {
    const named = declarations.get(type.typeName.getText());
    if (named && ts.isInterfaceDeclaration(named)) {
      return compare(named.members, resolved, path);
    }
  }
  return [];
}

describe('the Manifest types', () => {
  it('should be a view of the Manifest schema', () => {
    const manifest = declarations.get('Manifest') as ts.InterfaceDeclaration;
    expect(compare(manifest.members, SCHEMA, 'Manifest')).toEqual([]);
  });

  it('should catch a key or a value the schema does not have', () => {
    const drifted = ts.createSourceFile(
      'drifted.ts',
      `interface X { readonly format: number; readonly flavour?: string; readonly service: { readonly name: string } }`,
      ts.ScriptTarget.Latest,
      true,
    ).statements[0] as ts.InterfaceDeclaration;
    expect(compare(drifted.members, SCHEMA, 'X')).toEqual([
      'X.flavour is not in the schema',
      'X.service is required here, optional in the schema',
    ]);
  });
});
