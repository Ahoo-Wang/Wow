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

import { Project, SourceFile } from 'ts-morph';
import { ModelInfo } from '@/model';
import { combineURLs } from '@ahoo-wang/fetcher';

const MODEL_FILE_NAME = 'types.ts';
const IMPORT_ALIAS = '@';

export function getModelFileName(outputDir: string, modelInfo: ModelInfo): string {
  let fileName = combineURLs(modelInfo.path, MODEL_FILE_NAME);
  return combineURLs(outputDir, fileName);
}

export function getOrCreateSourceFile(project: Project, outputDir: string, filePath: string): SourceFile {
  const fileName = combineURLs(outputDir, filePath);
  const file = project.getSourceFile(fileName);
  if (file) {
    return file;
  }
  return project.createSourceFile(fileName, '', {
    overwrite: true,
  });
}

export function addImport(sourceFile: SourceFile, moduleSpecifier: string, namedImports: string[]) {
  let declaration = sourceFile.getImportDeclaration(importDeclaration => importDeclaration.getModuleSpecifierValue() === moduleSpecifier);
  if (!declaration) {
    declaration = sourceFile.addImportDeclaration({
      moduleSpecifier,
    });
  }
  namedImports.forEach(namedImport => {
    const exited = declaration.getNamedImports().some(existingNamedImport => existingNamedImport.getName() === namedImport);
    if (exited) {
      return;
    }
    declaration.addNamedImport(namedImport);
  });
}

export function addImportRefModel(sourceFile: SourceFile, outputDir: string, refModelInfo: ModelInfo) {
  const fileName = getModelFileName(outputDir, refModelInfo);
  const moduleSpecifier = combineURLs(IMPORT_ALIAS, fileName);
  addImport(sourceFile, moduleSpecifier, [refModelInfo.name]);
}

export function addImportModelInfo(modelInfo: ModelInfo, sourceFile: SourceFile, outputDir: string, refModelInfo: ModelInfo) {
  if (modelInfo.path === refModelInfo.path) {
    return;
  }
  addImportRefModel(sourceFile, outputDir, refModelInfo);
}
