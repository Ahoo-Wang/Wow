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

import { JSDocableNode, Project, SourceFile } from 'ts-morph';
import { combineURLs } from '@ahoo-wang/fetcher';
import { ModelInfo } from '../model';

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
 * Gets or creates a source file in the project.
 * @param project - The ts-morph project
 * @param outputDir - The output directory
 * @param filePath - The relative file path
 * @returns The source file
 */
export function getOrCreateSourceFile(
  project: Project,
  outputDir: string,
  filePath: string,
): SourceFile {
  const fileName = combineURLs(outputDir, filePath);
  const file = project.getSourceFile(fileName);
  if (file) {
    return file;
  }
  return project.createSourceFile(fileName, '', {
    overwrite: true,
  });
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
    const exited = declaration
      .getNamedImports()
      .some(
        existingNamedImport => existingNamedImport.getName() === namedImport,
      );
    if (exited) {
      return;
    }
    declaration.addNamedImport(namedImport);
  });
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
  let fileName = getModelFileName(refModelInfo);
  // If the path already starts with an alias, don't combine with outputDir
  if (!refModelInfo.path.startsWith(IMPORT_ALIAS)) {
    fileName = combineURLs(outputDir, fileName);
  }
  let moduleSpecifier = fileName;
  if (!fileName.startsWith(IMPORT_ALIAS)) {
    moduleSpecifier = combineURLs(IMPORT_ALIAS, fileName);
  }
  addImport(sourceFile, moduleSpecifier, [refModelInfo.name]);
}

/**
 * Adds an import for a model if it's in a different path.
 * @param modelInfo - The current model information
 * @param sourceFile - The source file to modify
 * @param outputDir - The output directory
 * @param refModelInfo - The referenced model information
 */
export function addImportModelInfo(
  modelInfo: ModelInfo,
  sourceFile: SourceFile,
  outputDir: string,
  refModelInfo: ModelInfo,
) {
  if (modelInfo.path === refModelInfo.path) {
    return;
  }
  addImportRefModel(sourceFile, outputDir, refModelInfo);
}

/**
 * Generates a JSDoc comment string from a title and description.
 * @param title - The title for the JSDoc comment
 * @param description - The description for the JSDoc comment
 * @returns The formatted JSDoc string or undefined if both title and description are empty
 */
export function jsDoc(
  title?: string,
  description?: string,
): string | undefined {
  const filtered = [title, description].filter(
    v => v !== undefined && v.length > 0,
  );
  return filtered.length > 0 ? filtered.join('\n') : undefined;
}

export function jsDocs(title?: string, description?: string): string[] {
  const jsdoc = jsDoc(title, description);
  return jsdoc ? [jsdoc] : [];
}

/**
 * Adds a JSDoc comment to a node with the provided title and description.
 * @param node - The node to add the JSDoc comment to
 * @param title - The title for the JSDoc comment
 * @param description - The description for the JSDoc comment
 */
export function addJSDoc(
  node: JSDocableNode,
  title?: string,
  description?: string,
) {
  const jsdoc = jsDoc(title, description);
  if (!jsdoc) {
    return;
  }
  node.addJsDoc({
    description: jsdoc,
  });
}
