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

import type { SourceFile } from 'ts-morph';
import {
  IndentationText,
  Project,
  QuoteKind,
  StructureKind,
  VariableDeclarationKind,
} from 'ts-morph';
import { describe, expect, it } from 'vitest';
import { ImportRegistry } from '../../src/emit/importRegistry';
import type { ModuleStatement } from '../../src/emit/moduleBuilder';
import {
  indexSignatureMember,
  membersWithTrailingComma,
  ModuleBuilder,
  ModuleSet,
} from '../../src/emit/moduleBuilder';

function project(): Project {
  const created = new Project({ useInMemoryFileSystem: true });
  created.manipulationSettings.set({
    indentationText: IndentationText.TwoSpaces,
    quoteKind: QuoteKind.Single,
    useTrailingCommas: true,
  });
  return created;
}

/** One statement of each kind a generated module holds. */
const STATEMENTS: Record<string, () => ModuleStatement> = {
  'type alias': () => ({
    kind: StructureKind.TypeAlias,
    name: 'Alias',
    type: '{ a: string }',
    isExported: true,
    docs: ['An alias.'],
  }),
  interface: () => ({
    kind: StructureKind.Interface,
    name: 'Model',
    isExported: true,
    properties: [{ name: 'id', type: 'string', docs: ['The id.'] }],
  }),
  enum: () => ({
    kind: StructureKind.Enum,
    name: 'Status',
    isExported: true,
    members: [{ name: 'ON', initializer: "'on'" }],
  }),
  class: () => ({
    kind: StructureKind.Class,
    name: 'Client',
    isExported: true,
    decorators: [{ name: 'api', arguments: [] }],
    methods: [{ name: 'get', statements: 'return 1;', docs: ['Gets.'] }],
  }),
  'variable statement': () => ({
    kind: StructureKind.VariableStatement,
    declarationKind: VariableDeclarationKind.Const,
    declarations: [{ name: 'value', initializer: '{ a: 1 }' }],
  }),
};

/** Adds a statement the way the generators used to: one change at a time. */
function addOneByOne(file: SourceFile, statement: ModuleStatement): void {
  if (typeof statement === 'string') {
    file.addStatements(statement);
    return;
  }
  switch (statement.kind) {
    case StructureKind.TypeAlias:
      file.addTypeAlias(statement);
      break;
    case StructureKind.Interface:
      file.addInterface(statement);
      break;
    case StructureKind.Enum:
      file.addEnum(statement);
      break;
    case StructureKind.Class:
      file.addClass(statement);
      break;
    case StructureKind.VariableStatement:
      file.addVariableStatement(statement);
      break;
  }
}

describe('ModuleBuilder', () => {
  const kinds = Object.keys(STATEMENTS);
  const pairs = [false, true].flatMap(withImports =>
    kinds.flatMap(first =>
      kinds.map(second => ({ withImports, first, second })),
    ),
  );

  it.each(pairs)(
    'writes $first then $second (imports: $withImports) as adding them one at a time does',
    ({ withImports, first, second }) => {
      const files = project();
      const oneByOne = files.createSourceFile('/one-by-one.ts', '');
      const built = new ModuleBuilder(files.createSourceFile('/built.ts', ''));
      if (withImports) {
        oneByOne.addImportDeclarations([
          { moduleSpecifier: 'a', namedImports: ['A'] },
          { moduleSpecifier: 'b', namedImports: ['B'] },
        ]);
        built.imports.add('a', ['A']);
        built.imports.add('b', ['B']);
      }
      for (const name of [first, second]) {
        addOneByOne(oneByOne, STATEMENTS[name]());
        built.add(STATEMENTS[name]());
      }

      expect(built.build().getFullText()).toBe(oneByOne.getFullText());
    },
  );

  it('writes a text statement as adding it does', () => {
    const files = project();
    const oneByOne = files.createSourceFile('/one-by-one.ts', '');
    const built = new ModuleBuilder(files.createSourceFile('/built.ts', ''));
    oneByOne.addStatements("export const ALIAS = 'example';");
    built.add("export const ALIAS = 'example';");

    expect(built.build().getFullText()).toBe(oneByOne.getFullText());
  });

  it('leaves the file untouched when nothing was added', () => {
    const file = project().createSourceFile('/empty.ts', '');

    expect(new ModuleBuilder(file).build().getFullText()).toBe('');
  });

  it('keeps an enum member comma after each member, as addMember leaves it', () => {
    const files = project();
    const oneByOne = files.createSourceFile('/one-by-one.ts', '');
    const members = [
      { name: 'A', initializer: "'a'" },
      { name: 'B', initializer: "'b'" },
    ];
    const declaration = oneByOne.addEnum({ name: 'E', isExported: true });
    members.forEach(member => declaration.addMember(member));
    const built = new ModuleBuilder(files.createSourceFile('/built.ts', ''));
    built.add({
      kind: StructureKind.Enum,
      name: 'E',
      isExported: true,
      members: membersWithTrailingComma(members),
    });

    expect(built.build().getFullText()).toBe(oneByOne.getFullText());
  });

  it('prints an index signature after the properties, where adding it puts it', () => {
    const files = project();
    const oneByOne = files.createSourceFile('/one-by-one.ts', '');
    const declaration = oneByOne.addInterface({ name: 'M', isExported: true });
    declaration.addProperty({ name: 'id', type: 'string' });
    declaration
      .addIndexSignature({
        keyName: 'key',
        keyType: 'string',
        returnType: 'number',
      })
      .addJsDoc('Additional properties');
    const built = new ModuleBuilder(files.createSourceFile('/built.ts', ''));
    built.add({
      kind: StructureKind.Interface,
      name: 'M',
      isExported: true,
      properties: [
        { name: 'id', type: 'string' },
        indexSignatureMember('number', ['Additional properties']),
      ],
    });

    expect(built.build().getFullText()).toBe(oneByOne.getFullText());
  });

  it('resolves relative imports from the directory of its file', () => {
    const file = project().createSourceFile('/out/a/types.ts', '');

    expect(new ModuleBuilder(file).directoryPath).toBe('/out/a');
  });
});

describe('ModuleSet', () => {
  it('gives one builder per file and writes each once', () => {
    const files = project();
    const opened: string[] = [];
    const modules = new ModuleSet(path => {
      opened.push(path);
      return (
        files.getSourceFile(`/out/${path}`) ??
        files.createSourceFile(`/out/${path}`, '')
      );
    });
    const first = modules.module('a/types.ts');
    first.add("export const A = 'a';");

    expect(modules.module('a/types.ts')).toBe(first);
    expect(opened).toEqual(['a/types.ts', 'a/types.ts']);
    expect(files.getSourceFileOrThrow('/out/a/types.ts').getFullText()).toBe(
      '',
    );

    modules.build();

    expect(files.getSourceFileOrThrow('/out/a/types.ts').getFullText()).toBe(
      "export const A = 'a';\n",
    );
    expect(modules.module('a/types.ts')).not.toBe(first);
  });
});

describe('ImportRegistry', () => {
  it('names the local names of every import but one, aliases first', () => {
    const registry = new ImportRegistry();
    const [user] = registry.add('./users.js', ['User']);
    const [role] = registry.add('./roles.js', ['Role']);
    role.alias = '_Role';

    expect(registry.localNames()).toEqual(['User', '_Role']);
    expect(registry.localNames(user)).toEqual(['_Role']);
    expect(registry.structures()).toEqual([
      { moduleSpecifier: './users.js', namedImports: ['User'] },
      {
        moduleSpecifier: './roles.js',
        namedImports: [{ name: 'Role', alias: '_Role' }],
      },
    ]);
  });
});
