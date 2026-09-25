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

import type {
  ClassDeclarationStructure,
  CodeBlockWriter,
  EnumDeclarationStructure,
  EnumMemberStructure,
  ImportDeclarationStructure,
  InterfaceDeclarationStructure,
  OptionalKind,
  SourceFile,
  StatementStructures,
  TypeAliasDeclarationStructure,
  VariableStatementStructure,
  WriterFunction,
} from 'ts-morph';
import { StructureKind } from 'ts-morph';
import { ImportRegistry } from './importRegistry';

/** A top-level statement of a generated module. */
export type ModuleStatement =
  | ClassDeclarationStructure
  | EnumDeclarationStructure
  | InterfaceDeclarationStructure
  | TypeAliasDeclarationStructure
  | VariableStatementStructure
  | string;

/**
 * A generated module, collected in memory and written into its source file
 * once.
 *
 * ts-morph re-parses the whole file on every change, so a file written a
 * declaration, a property and a doc comment at a time costs time that grows
 * with the square of its length: the root `types.ts` of a large document runs
 * to 20,000 lines. Here the emitters describe each declaration as a ts-morph
 * structure, with its members and docs, and {@link build} inserts the lot in
 * one change, printed by the same printers.
 *
 * The blank lines between statements are the ones the file would have got
 * receiving the statements one by one, so the output is byte for byte the
 * same: a type alias follows a type alias, and a variable statement a
 * variable statement, on the next line; anything else is set off by a blank
 * line.
 */
export class ModuleBuilder {
  /** The imports of the module. */
  readonly imports = new ImportRegistry();
  private readonly statements: ModuleStatement[] = [];

  /**
   * @param sourceFile - The file the module is written into; it stays empty
   * until {@link build}.
   */
  constructor(readonly sourceFile: SourceFile) {}

  /** The directory of the module's file, which relative imports start from. */
  get directoryPath(): string {
    return this.sourceFile.getDirectoryPath();
  }

  /**
   * Adds a top-level statement. A structure stays open to changes until the
   * module is built.
   *
   * @returns The statement added
   */
  add<T extends ModuleStatement>(statement: T): T {
    this.statements.push(statement);
    return statement;
  }

  /** Writes the imports and the statements into the file in one change. */
  build(): SourceFile {
    const statements: (StatementStructures | string | WriterFunction)[] = [];
    let previous: ModuleStatement | ImportDeclarationStructure | undefined;
    for (const declaration of this.imports.structures()) {
      previous = { ...declaration, kind: StructureKind.ImportDeclaration };
      statements.push(previous);
    }
    for (const statement of this.statements) {
      if (previous && blankLineBetween(previous, statement)) {
        statements.push(blankLine);
      }
      statements.push(statement);
      previous = statement;
    }
    if (statements.length > 0) {
      this.sourceFile.addStatements(statements);
    }
    return this.sourceFile;
  }
}

/**
 * The modules of one generation, one builder per file, written together at
 * the end.
 */
export class ModuleSet {
  private readonly modules = new Map<string, ModuleBuilder>();

  /**
   * @param open - Returns the source file a path relative to the output
   * directory is written to, claiming it for the generation
   */
  constructor(private readonly open: (filePath: string) => SourceFile) {}

  /**
   * The module written to a path under the output directory, created the
   * first time it is asked for.
   */
  module(filePath: string): ModuleBuilder {
    const sourceFile = this.open(filePath);
    const key = sourceFile.getFilePath();
    let module = this.modules.get(key);
    if (!module) {
      module = new ModuleBuilder(sourceFile);
      this.modules.set(key, module);
    }
    return module;
  }

  /** Writes every module into its file, each in one go. */
  build(): void {
    for (const module of this.modules.values()) module.build();
    this.modules.clear();
  }
}

/** Starts the next statement after a blank line. */
const blankLine: WriterFunction = (writer: CodeBlockWriter) => {
  writer.blankLineIfLastNot();
};

/**
 * Tells whether a statement added after another one is set off by a blank
 * line, as ts-morph separates statements added one at a time.
 */
function blankLineBetween(
  previous: ModuleStatement | ImportDeclarationStructure,
  next: ModuleStatement,
): boolean {
  if (typeof next === 'string') return false;
  switch (next.kind) {
    case StructureKind.TypeAlias:
    case StructureKind.VariableStatement:
      return typeof previous === 'string' || previous.kind !== next.kind;
    default:
      return true;
  }
}

/**
 * Enum members printed as adding them one at a time to an enum leaves them:
 * each one followed by a comma, the last one too. The printer separates
 * members with commas only where a member does not already end with one.
 *
 * @param members - The members, each with a string initializer
 */
export function membersWithTrailingComma(
  members: readonly OptionalKind<EnumMemberStructure>[],
): OptionalKind<EnumMemberStructure>[] {
  return members.map(member => ({
    ...member,
    initializer: `${member.initializer as string},`,
  }));
}

/**
 * The index signature of an interface, as a member printed after its
 * properties, where adding it after them puts it: a structure's
 * `indexSignatures` are printed first.
 *
 * @param returnType - The type of the additional properties
 * @param docs - Its doc comments
 */
export function indexSignatureMember(
  returnType: string,
  docs?: string[],
): OptionalKind<
  NonNullable<InterfaceDeclarationStructure['properties']>[number]
> {
  return { name: '[key: string]', type: returnType, docs };
}
