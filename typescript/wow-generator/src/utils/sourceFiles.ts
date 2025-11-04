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
import { join, relative } from 'path';
import { JSDocableNode, Project, SourceFile } from 'ts-morph';
import { ModelInfo } from '../model';
import { Reference, Schema } from '@ahoo-wang/fetcher-openapi';

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
  if (refModelInfo.path.startsWith(IMPORT_ALIAS)) {
    addImport(sourceFile, refModelInfo.path, [refModelInfo.name]);
    return;
  }

  const sourceDir = sourceFile.getDirectoryPath();
  const targetFilePath = join(outputDir, refModelInfo.path, MODEL_FILE_NAME);
  let relativePath = relative(sourceDir, targetFilePath);

  relativePath = relativePath.replace(/\.ts$/, '');

  if (!relativePath.startsWith('.')) {
    relativePath = './' + relativePath;
  }

  addImport(sourceFile, relativePath, [refModelInfo.name]);
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
  addImportRefModel(sourceFile, outputDir, refModel);
}

/**
 * Generates a JSDoc comment string from a title and description.
 * @returns The formatted JSDoc string or undefined if both title and description are empty
 */
export function jsDoc(
  descriptions: (string | undefined)[],
  separator = '\n',
): string | undefined {
  if (!Array.isArray(descriptions)) {
    return undefined;
  }
  const filtered = descriptions.filter(
    v => typeof v === 'string' && v.length > 0,
  );
  return filtered.length > 0 ? filtered.join(separator) : undefined;
}

/**
 * Adds a JSDoc comment to a node with the provided title and description.
 */
export function addJSDoc(
  node: JSDocableNode,
  descriptions: (string | undefined)[],
) {
  const jsdoc = jsDoc(descriptions);
  if (!jsdoc) {
    return;
  }
  node.addJsDoc(jsdoc);
}

export function schemaJSDoc(schema: Schema, key?: string) {
  const descriptions: (string | undefined)[] = [
    schema.title,
    schema.description,
  ];
  if (key) {
    descriptions.push(`- key: ${key}`);
  }
  if (schema.format) {
    descriptions.push(`- format: ${schema.format}`);
  }

  addJsonJsDoc(descriptions, schema, 'default');
  addJsonJsDoc(descriptions, schema, 'example');
  addNumericConstraintsJsDoc(descriptions, schema);
  addStringConstraintsJsDoc(descriptions, schema);
  addArrayConstraintsJsDoc(descriptions, schema);
  return descriptions;
}

/**
 * Adds a JSDoc comment to a node based on the schema's title and description.
 * @param node - The node to add the JSDoc comment to
 * @param schema - The schema containing title and description
 * @param key - The key associated with the schema
 */
export function addSchemaJSDoc(node: JSDocableNode, schema: Schema | Reference, key?: string) {
  const descriptions = schemaJSDoc(schema as Schema, key);
  addJSDoc(node, descriptions);
}

function addJsonJsDoc(
  descriptions: (string | undefined)[],
  schema: any,
  propertyName: keyof Schema,
) {
  const json = schema[propertyName];
  if (!json) {
    return;
  }
  if (typeof json !== 'object') {
    descriptions.push(`- ${propertyName}: \`${json}\``);
    return;
  }
  descriptions.push(`- ${propertyName}: `);
  descriptions.push('```json');
  descriptions.push(JSON.stringify(json));
  descriptions.push('```');
}

function addNumericConstraintsJsDoc(
  descriptions: (string | undefined)[],
  schema: Schema,
) {
  const constraintsDescriptions = ['- Numeric Constraints'];
  if (schema.minimum !== undefined) {
    constraintsDescriptions.push(`  - minimum: ${schema.minimum}`);
  }
  if (schema.maximum !== undefined) {
    constraintsDescriptions.push(`  - maximum: ${schema.maximum}`);
  }
  if (schema.exclusiveMinimum !== undefined) {
    constraintsDescriptions.push(
      `  - exclusiveMinimum: ${schema.exclusiveMinimum}`,
    );
  }
  if (schema.exclusiveMaximum !== undefined) {
    constraintsDescriptions.push(
      `  - exclusiveMaximum: ${schema.exclusiveMaximum}`,
    );
  }
  if (schema.multipleOf !== undefined) {
    constraintsDescriptions.push(`  - multipleOf: ${schema.multipleOf}`);
  }
  if (constraintsDescriptions.length === 1) {
    return;
  }
  descriptions.push(...constraintsDescriptions);
}

function addStringConstraintsJsDoc(
  descriptions: (string | undefined)[],
  schema: Schema,
) {
  const constraintsDescriptions = ['- String Constraints'];
  if (schema.minLength !== undefined) {
    constraintsDescriptions.push(`  - minLength: ${schema.minLength}`);
  }
  if (schema.maxLength !== undefined) {
    constraintsDescriptions.push(`  - maxLength: ${schema.maxLength}`);
  }
  if (schema.pattern !== undefined) {
    constraintsDescriptions.push(`  - pattern: ${schema.pattern}`);
  }
  if (constraintsDescriptions.length === 1) {
    return;
  }
  descriptions.push(...constraintsDescriptions);
}

function addArrayConstraintsJsDoc(
  descriptions: (string | undefined)[],
  schema: Schema,
) {
  const constraintsDescriptions = ['- Array Constraints'];
  if (schema.minItems !== undefined) {
    constraintsDescriptions.push(`  - minItems: ${schema.minItems}`);
  }
  if (schema.maxItems !== undefined) {
    constraintsDescriptions.push(`  - maxItems: ${schema.maxItems}`);
  }
  if (schema.uniqueItems !== undefined) {
    constraintsDescriptions.push(`  - uniqueItems: ${schema.uniqueItems}`);
  }
  if (constraintsDescriptions.length === 1) {
    return;
  }
  descriptions.push(...constraintsDescriptions);
}
