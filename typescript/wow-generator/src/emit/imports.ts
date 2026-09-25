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

import { combineURLs } from '@ahoo-wang/fetcher';
import { join, relative, sep } from 'path';
import type { SourceFile } from 'ts-morph';
import type { ModelInfo } from '../model/modelInfo';

/** Default file name for model files */
const MODEL_FILE_NAME = 'types.ts';

/** Alias for import paths */
const IMPORT_ALIAS = '@';

/**
 * Generates the file path for a model file.
 * @param modelInfo - The model information
 * @returns The full file path for the model
 */
export function getModelFileName(modelInfo: ModelInfo): string {
  return combineURLs(modelInfo.path, MODEL_FILE_NAME);
}

/**
 * Adds named imports to a source file.
 * @param sourceFile - The source file to modify
 * @param moduleSpecifier - The module to import from
 * @param namedImports - Array of named imports to add
 */
export function addImport(
  sourceFile: SourceFile,
  moduleSpecifier: string,
  namedImports: string[],
) {
  let declaration = sourceFile.getImportDeclaration(
    importDeclaration =>
      importDeclaration.getModuleSpecifierValue() === moduleSpecifier,
  );
  if (!declaration) {
    declaration = sourceFile.addImportDeclaration({
      moduleSpecifier,
    });
  }
  namedImports.forEach(namedImport => {
    const exists = declaration
      .getNamedImports()
      .some(
        existingNamedImport => existingNamedImport.getName() === namedImport,
      );
    if (exists) {
      return;
    }
    declaration.addNamedImport(namedImport);
  });
  return declaration;
}

/**
 * Adds an import for a referenced model.
 * @param sourceFile - The source file to modify
 * @param outputDir - The output directory
 * @param refModelInfo - The referenced model information
 */
export function addImportRefModel(
  sourceFile: SourceFile,
  outputDir: string,
  refModelInfo: ModelInfo,
) {
  if (refModelInfo.path.startsWith(IMPORT_ALIAS)) {
    return addImport(sourceFile, refModelInfo.path, [refModelInfo.name]);
  }
  const targetFilePath = join(outputDir, refModelInfo.path, MODEL_FILE_NAME);
  return addImport(
    sourceFile,
    relativeModuleSpecifier(sourceFile, targetFilePath),
    [refModelInfo.name],
  );
}

/**
 * The specifier a source file imports another generated file by.
 *
 * Relative specifiers carry the `.js` extension, which every module
 * resolution TypeScript offers accepts for a `.ts` file: `NodeNext` and
 * `Node16` require it, and `bundler` and `node10` map it back to the source.
 *
 * @param sourceFile - The importing file
 * @param targetFilePath - The imported `.ts` file, or a directory holding an `index.ts`
 * @returns A specifier starting with `./` or `../` and ending with `.js`
 */
export function relativeModuleSpecifier(
  sourceFile: SourceFile,
  targetFilePath: string,
): string {
  let relativePath = relative(sourceFile.getDirectoryPath(), targetFilePath)
    .split(sep)
    .join('/');
  relativePath = relativePath.endsWith('.ts')
    ? relativePath.replace(/\.ts$/, '.js')
    : `${relativePath}/index.js`;
  return relativePath.startsWith('.') ? relativePath : `./${relativePath}`;
}

/** The file that declares a bounded context's alias constant. */
export function boundedContextFilePath(contextAlias: string): string {
  return `${contextAlias}/boundedContext.ts`;
}

/**
 * Imports a bounded context's alias constant into a generated file.
 *
 * @param sourceFile - The importing file
 * @param outputDir - The output directory
 * @param contextAlias - The bounded context alias
 * @param declarationName - The name of the alias constant
 */
export function addImportBoundedContext(
  sourceFile: SourceFile,
  outputDir: string,
  contextAlias: string,
  declarationName: string,
) {
  return addImport(
    sourceFile,
    relativeModuleSpecifier(
      sourceFile,
      join(outputDir, boundedContextFilePath(contextAlias)),
    ),
    [declarationName],
  );
}

/**
 * Adds an import for a model if it's in a different path.
 * @param currentModel - The current model information
 * @param sourceFile - The source file to modify
 * @param outputDir - The output directory
 * @param refModel - The referenced model information
 */
export function addImportModelInfo(
  currentModel: ModelInfo,
  sourceFile: SourceFile,
  outputDir: string,
  refModel: ModelInfo,
) {
  if (currentModel.path === refModel.path) {
    return;
  }
  return addImportRefModel(sourceFile, outputDir, refModel);
}
