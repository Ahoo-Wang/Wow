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
import type { ModelInfo } from '../naming/modelInfo';
import type { NamedImport } from './importRegistry';
import type { ModuleBuilder } from './moduleBuilder';

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
 * Adds named imports to a module.
 * @param module - The module to modify
 * @param moduleSpecifier - The module to import from
 * @param namedImports - Array of named imports to add
 * @returns The names the module now imports from `moduleSpecifier`
 */
export function addImport(
  module: ModuleBuilder,
  moduleSpecifier: string,
  namedImports: string[],
): readonly NamedImport[] {
  return module.imports.add(moduleSpecifier, namedImports);
}

/**
 * Adds an import for a referenced model.
 * @param module - The module to modify
 * @param outputDir - The output directory
 * @param refModelInfo - The referenced model information
 */
export function addImportRefModel(
  module: ModuleBuilder,
  outputDir: string,
  refModelInfo: ModelInfo,
): readonly NamedImport[] {
  return addImport(
    module,
    modelModuleSpecifier(module, outputDir, refModelInfo),
    [refModelInfo.name],
  );
}

/**
 * The specifier a module imports a model by: the package of a model imported
 * from one (a path starting with `@`), else the relative path of the
 * `types.ts` that declares it.
 *
 * @param module - The importing module
 * @param outputDir - The output directory
 * @param model - The model
 */
export function modelModuleSpecifier(
  module: ModuleBuilder,
  outputDir: string,
  model: ModelInfo,
): string {
  if (model.path.startsWith(IMPORT_ALIAS)) return model.path;
  return relativeModuleSpecifier(
    module.directoryPath,
    join(outputDir, model.path, MODEL_FILE_NAME),
  );
}

/**
 * The specifier a source file imports another generated file by.
 *
 * Relative specifiers carry the `.js` extension, which every module
 * resolution TypeScript offers accepts for a `.ts` file: `NodeNext` and
 * `Node16` require it, and `bundler` and `node10` map it back to the source.
 *
 * @param fromDirectory - The directory of the importing file
 * @param targetFilePath - The imported `.ts` file, or a directory holding an `index.ts`
 * @returns A specifier starting with `./` or `../` and ending with `.js`
 */
export function relativeModuleSpecifier(
  fromDirectory: string,
  targetFilePath: string,
): string {
  let relativePath = relative(fromDirectory, targetFilePath)
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
 * @param module - The importing module
 * @param outputDir - The output directory
 * @param contextAlias - The bounded context alias
 * @param declarationName - The name of the alias constant
 */
export function addImportBoundedContext(
  module: ModuleBuilder,
  outputDir: string,
  contextAlias: string,
  declarationName: string,
): readonly NamedImport[] {
  return addImport(
    module,
    relativeModuleSpecifier(
      module.directoryPath,
      join(outputDir, boundedContextFilePath(contextAlias)),
    ),
    [declarationName],
  );
}
