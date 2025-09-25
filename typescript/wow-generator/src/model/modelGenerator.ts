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

import { OpenAPI, Schema, Reference } from '@ahoo-wang/fetcher-openapi';
import {
  EnumDeclaration,
  InterfaceDeclaration,
  JSDocableNode,
  Project,
  SourceFile,
  TypeAliasDeclaration,
} from 'ts-morph';
import { ModelInfo, resolveModelInfo } from '@/model/naming.ts';
import { isEnum, resolvePrimitiveType } from '@/utils/schemas.ts';
import { GenerateContext } from '@/types.ts';
import { AggregateDefinition } from '@/aggregate';
import { IMPORT_WOW_PATH, WOW_TYPE_MAPPING } from '@/model/wowTypeMapping.ts';
import { extractComponentKey, isReference } from '@/utils';
import {
  addImport,
  addImportModelInfo, getModelFileName,
  getOrCreateSourceFile,
} from '@/utils/sourceFiles.ts';

/**
 * Generates TypeScript models from OpenAPI schemas.
 * Handles enum, object, union, and type alias generation.
 *
 * @property project - The ts-morph project instance
 * @property openAPI - The OpenAPI specification
 * @property outputDir - The output directory for generated files
 * @property aggregates - Map of aggregate definitions
 */
export class ModelGenerator implements GenerateContext {
  readonly project: Project;
  readonly openAPI: OpenAPI;
  readonly outputDir: string;
  readonly aggregates: Map<string, AggregateDefinition>;

  constructor(context: GenerateContext) {
    this.project = context.project;
    this.openAPI = context.openAPI;
    this.outputDir = context.outputDir;
    this.aggregates = context.aggregates;
  }

  private getOrCreateSourceFile(modelInfo: ModelInfo): SourceFile {
    let fileName = getModelFileName(this.outputDir, modelInfo);
    return getOrCreateSourceFile(this.project, this.outputDir, fileName);
  }

  /**
   * Generates models for all schemas in the OpenAPI specification.
   * Skips schemas with keys starting with 'wow.'.
   *
   * @remarks
   * This method iterates through all schemas in the OpenAPI specification
   * and generates corresponding TypeScript models for each one.
   */
  generate() {
    const schemas = this.openAPI.components?.schemas;
    if (!schemas) {
      return;
    }
    Object.entries(schemas).forEach(([schemaKey, schema]) => {
      if (schemaKey.startsWith('wow.')) {
        return;
      }
      this.generateKeyedSchema(schemaKey, schema);
    });
  }

  /**
   * Generates a model for a specific schema key.
   * Processes enums, objects, unions, and type aliases in order.
   *
   * @param schemaKey - The key of the schema to generate
   * @param schema - The schema definition
   *
   * @remarks
   * The generation process follows this order:
   * 1. Enum processing
   * 2. Object processing
   * 3. Union processing
   * 4. Type alias processing
   */
  generateKeyedSchema(schemaKey: string, schema: Schema): SourceFile {
    const modelInfo = resolveModelInfo(schemaKey);
    const sourceFile = this.getOrCreateSourceFile(modelInfo);
    const node = this.process(modelInfo, sourceFile, schema);
    if (schema.title) {
      node.addJsDoc({
        description: schema.title,
      });
    }
    if (schema.description) {
      node.addJsDoc({
        description: schema.description,
      });
    }
    return sourceFile;
  }

  private process(modelInfo: ModelInfo,
                  sourceFile: SourceFile,
                  schema: Schema) {
    let declaration: JSDocableNode | undefined = this.processEnum(modelInfo, sourceFile, schema);
    if (declaration) {
      return declaration;
    }
    declaration = this.processObject(modelInfo, sourceFile, schema);
    if (declaration) {
      return declaration;
    }
    declaration = this.processUnion(modelInfo, sourceFile, schema);
    if (declaration) {
      return declaration;
    }
    return this.processTypeAlias(modelInfo, sourceFile, schema);
  }

  /**
   * Processes enum schemas and generates TypeScript enums.
   *
   * @param modelInfo - The model information
   * @param sourceFile - The source file to add the enum to
   * @param schema - The enum schema
   * @returns true if the schema was processed as an enum, false otherwise
   *
   * @remarks
   * This method filters out non-string enum values and generates
   * a TypeScript enum with string literal initializers.
   */
  processEnum(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: Schema,
  ): EnumDeclaration | undefined {
    if (!isEnum(schema)) {
      return undefined;
    }
    return sourceFile.addEnum({
      name: modelInfo.name,
      isExported: true,
      members: schema.enum
        .filter(value => typeof value === 'string' && value.length > 0)
        .map(value => ({
          name: value,
          initializer: `'${value}'`,
        })),
    });
  }

  /**
   * Processes object schemas and generates TypeScript interfaces.
   *
   * @param modelInfo - The model information
   * @param sourceFile - The source file to add the interface to
   * @param schema - The object schema
   * @returns true if the schema was processed as an object, false otherwise
   *
   * @remarks
   * This method handles optional properties by checking the required array
   * and adds undefined union types for optional properties.
   */
  processObject(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: Schema,
  ): InterfaceDeclaration | undefined {
    if (schema.type !== 'object' || !schema.properties) {
      return undefined;
    }
    const properties: Record<string, string> = {};
    const required = schema.required || [];

    for (const [propName, propSchema] of Object.entries(schema.properties)) {
      const propType = this.resolveType(modelInfo, sourceFile, propSchema);
      const isOptional = !required.includes(propName);
      properties[propName] = isOptional ? `${propType} | undefined` : propType;
    }

    return sourceFile.addInterface({
      name: modelInfo.name,
      isExported: true,
      properties: Object.entries(properties).map(([name, type]) => ({
        name,
        type,
      })),
    });
  }

  /**
   * Processes union schemas (allOf, anyOf, oneOf) and generates TypeScript type aliases.
   *
   * @param modelInfo - The model information
   * @param sourceFile - The source file to add the type alias to
   * @param schema - The union schema
   * @returns true if the schema was processed as a union, false otherwise
   *
   * @remarks
   * This method handles three types of unions:
   * - allOf: Generates intersection types (&)
   * - anyOf: Generates union types (|)
   * - oneOf: Generates union types (|)
   */
  processUnion(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: Schema,
  ): TypeAliasDeclaration | undefined {
    let unionType: string | null = null;

    if (schema.allOf) {
      unionType = schema.allOf
        .map(s => this.resolveType(modelInfo, sourceFile, s))
        .join(' & ');
    } else if (schema.anyOf) {
      unionType = schema.anyOf
        .map(s => this.resolveType(modelInfo, sourceFile, s))
        .join(' | ');
    } else if (schema.oneOf) {
      unionType = schema.oneOf
        .map(s => this.resolveType(modelInfo, sourceFile, s))
        .join(' | ');
    }

    if (!unionType) {
      return undefined;
    }

    return sourceFile.addTypeAlias({
      name: modelInfo.name,
      type: unionType,
      isExported: true,
    });
  }

  /**
   * Processes type alias schemas and generates TypeScript type aliases.
   *
   * @param modelInfo - The model information
   * @param sourceFile - The source file to add the type alias to
   * @param schema - The schema to process
   *
   * @remarks
   * This method is used as a fallback for schemas that don't match
   * enum, object, or union patterns. It resolves the type and creates
   * a simple type alias.
   */
  processTypeAlias(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: Schema,
  ): TypeAliasDeclaration {
    const type = this.resolveType(modelInfo, sourceFile, schema);
    return sourceFile.addTypeAlias({
      name: modelInfo.name,
      type,
      isExported: true,
    });
  }

  /**
   * Resolves the TypeScript type for a given schema or reference.
   * Handles arrays, objects, primitives, and references.
   *
   * @param modelInfo - The model information
   * @param sourceFile - The source file for import management
   * @param schema - The schema or reference to resolve
   * @returns The resolved TypeScript type as a string
   *
   * @remarks
   * This method handles various schema types:
   * - References: Resolves to imported types
   * - Arrays: Resolves item types and adds array notation
   * - Objects: Resolves to Record<string, any> for generic objects
   * - Primitives: Maps to TypeScript primitives
   * - Nullable: Adds null union type
   */
  private resolveType(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: Schema | Reference,
  ): string {
    if (isReference(schema)) {
      return this.resolveReference(modelInfo, sourceFile, schema);
    }

    if (schema.type === 'array') {
      const itemType = schema.items
        ? this.resolveType(modelInfo, sourceFile, schema.items)
        : 'any';
      return `${itemType}[]`;
    }

    if (schema.type === 'object' && !schema.properties) {
      return 'Record<string, any>';
    }

    if (schema.type) {
      return resolvePrimitiveType(schema.type);
    }

    // Handle nullable
    if (schema.nullable) {
      return `${this.resolveType(modelInfo, sourceFile, { ...schema, nullable: false })} | null`;
    }

    return 'any';
  }

  /**
   * Resolves a reference to another schema.
   * Handles mapped types and imports.
   *
   * @param modelInfo - The current model information
   * @param sourceFile - The source file for import management
   * @param ref - The reference to resolve
   * @returns The resolved type name
   *
   * @remarks
   * This method checks for mapped types first (WOW_TYPE_MAPPING).
   * If not found, it adds an import for the referenced model.
   */
  private resolveReference(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    ref: Reference,
  ): string {
    const schemaKey = extractComponentKey(ref);
    const refModelInfo = resolveModelInfo(schemaKey);
    const mappedType =
      WOW_TYPE_MAPPING[schemaKey as keyof typeof WOW_TYPE_MAPPING];
    if (mappedType) {
      addImport(sourceFile, IMPORT_WOW_PATH, [mappedType]);
      return mappedType;
    }
    addImportModelInfo(modelInfo, sourceFile, this.outputDir, refModelInfo);
    return refModelInfo.name;
  }
}
