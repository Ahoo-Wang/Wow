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

import type { ImportDeclarationStructure, OptionalKind } from 'ts-morph';

/** A name one module imports from another, under an alias or its own name. */
export interface NamedImport {
  readonly name: string;
  alias?: string;
}

/**
 * The imports of one generated module, kept in memory until the module is
 * written.
 *
 * Declarations stay in the order their modules were first imported, and
 * names in the order they were first asked for, as the file would have
 * received them one by one; `organizeImports` sorts them when the file is
 * finished.
 */
export class ImportRegistry {
  private readonly declarations = new Map<string, NamedImport[]>();

  /**
   * Imports names from a module, keeping the ones it already imports.
   *
   * @param moduleSpecifier - The module to import from
   * @param names - The names to import
   * @returns The names the module now imports
   */
  add(
    moduleSpecifier: string,
    names: readonly string[],
  ): readonly NamedImport[] {
    let imported = this.declarations.get(moduleSpecifier);
    if (!imported) {
      imported = [];
      this.declarations.set(moduleSpecifier, imported);
    }
    for (const name of names) {
      if (!imported.some(item => item.name === name)) {
        imported.push({ name });
      }
    }
    return imported;
  }

  /**
   * The local names every import binds, the ones of `except` aside: an
   * alias where it has one, else the name.
   */
  localNames(except?: NamedImport): string[] {
    return [...this.declarations.values()]
      .flat()
      .filter(item => item !== except)
      .map(item => item.alias ?? item.name);
  }

  /** The import declarations, in the order they were first asked for. */
  structures(): OptionalKind<ImportDeclarationStructure>[] {
    return [...this.declarations].map(([moduleSpecifier, imported]) => ({
      moduleSpecifier,
      namedImports: imported.map(({ name, alias }) =>
        alias === undefined ? name : { name, alias },
      ),
    }));
  }
}
