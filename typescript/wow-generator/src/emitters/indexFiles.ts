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

import type { Directory, SourceFile } from 'ts-morph';
import { Node } from 'ts-morph';
import { compareNames } from '../naming/order';

/** A name a module exports, mapped to whether it is only a type. */
type ExportedNames = Map<string, boolean>;

/** A module an index re-exports. */
interface IndexChild {
  readonly specifier: string;
  readonly exports: ExportedNames;
}

/**
 * Writes an `index.ts` into the output directory and each directory under
 * it, re-exporting its files and the indexes of its subdirectories.
 *
 * @param outputDir - The output directory, holding the files written
 * @param claim - Returns the index file of a directory, emptied and claimed
 * for the generation
 * @returns A warning for every name an index leaves out because two of its
 * children export it
 */
export function emitIndexFiles(
  outputDir: Directory,
  claim: (directoryPath: string) => SourceFile,
): string[] {
  const warnings: string[] = [];
  indexDirectory(outputDir, claim, warnings);
  return warnings;
}

/**
 * Writes the index files of a directory and its subdirectories.
 *
 * @returns The names the directory's index exports, each mapped to whether
 * it is a type only; undefined when the directory has no index.
 */
function indexDirectory(
  dir: Directory,
  claim: (directoryPath: string) => SourceFile,
  warnings: string[],
): ExportedNames | undefined {
  const children: IndexChild[] = [];
  for (const file of dir.getSourceFiles()) {
    const baseName = file.getBaseName();
    if (!baseName.endsWith('.ts') || baseName === 'index.ts') continue;
    children.push({
      specifier: `./${file.getBaseNameWithoutExtension()}.js`,
      exports: exportedNames(file),
    });
  }
  for (const subDir of dir.getDirectories()) {
    const exports = indexDirectory(subDir, claim, warnings);
    if (exports) {
      children.push({
        specifier: `./${subDir.getBaseName()}/index.js`,
        exports,
      });
    }
  }
  const dirPath = dir.getPath();
  if (children.length === 0) {
    return dir.getProject().getSourceFile(`${dirPath}/index.ts`)
      ? new Map()
      : undefined;
  }
  return writeIndex(dirPath, children, claim, warnings);
}

/**
 * Writes one index file.
 *
 * `export *` from two modules that export the same name is an error
 * (TS2308); it happens when two packages hold a model of the same name. A
 * child exporting such a name is re-exported by name instead, leaving the
 * ambiguous names out: they stay importable from their own modules.
 *
 * @returns The names the index exports
 */
function writeIndex(
  dirPath: string,
  children: IndexChild[],
  claim: (directoryPath: string) => SourceFile,
  warnings: string[],
): ExportedNames {
  const owners = new Map<string, string[]>();
  for (const child of children) {
    for (const name of child.exports.keys()) {
      owners.set(name, [...(owners.get(name) ?? []), child.specifier]);
    }
  }
  const ambiguous = [...owners]
    .filter(([, specifiers]) => specifiers.length > 1)
    .map(([name]) => name)
    .sort();
  for (const name of ambiguous) {
    warnings.push(
      `${name} is exported by both ${owners.get(name)!.join(' and ')} in ${dirPath}; its index leaves it out, so import it from its own module.`,
    );
  }
  const indexFile = claim(dirPath);
  indexFile.removeText();
  const exported: ExportedNames = new Map();
  for (const child of children) {
    const names = [...child.exports]
      .filter(([name]) => !ambiguous.includes(name))
      .sort(([left], [right]) => compareNames(left, right));
    names.forEach(([name, typeOnly]) => exported.set(name, typeOnly));
    if (names.length === child.exports.size) {
      indexFile.addExportDeclaration({ moduleSpecifier: child.specifier });
    } else if (names.length > 0) {
      indexFile.addExportDeclaration({
        moduleSpecifier: child.specifier,
        namedExports: names.map(([name, isTypeOnly]) => ({
          name,
          isTypeOnly,
        })),
      });
    }
  }
  if (indexFile.getStatements().length === 0) {
    // Every name was ambiguous; the index stays a module its parent re-exports.
    indexFile.addStatements('export {};');
  }
  return exported;
}

/**
 * The names a generated file exports. A name is type-only when every one of
 * its declarations is an interface or a type alias; an enum or a class is a
 * value as well.
 */
function exportedNames(file: SourceFile): ExportedNames {
  const names: ExportedNames = new Map();
  for (const [name, declarations] of file.getExportedDeclarations()) {
    names.set(
      name,
      declarations.every(
        declaration =>
          Node.isInterfaceDeclaration(declaration) ||
          Node.isTypeAliasDeclaration(declaration),
      ),
    );
  }
  return names;
}
