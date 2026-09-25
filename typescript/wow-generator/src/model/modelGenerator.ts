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

import type { Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import type { TypeAliasDeclarationStructure } from 'ts-morph';
import { StructureKind } from 'ts-morph';

import { GeneratorError } from '../api/errors';
import type { GenerateContext, Generator } from '../generateContext';
import type { KeySchema } from '../openapi/components';
import { addMainSchemaJSDoc } from '../emit/jsdoc';
import { boundedContextFilePath, getModelFileName } from '../emit/imports';
import type { ModuleBuilder } from '../emit/moduleBuilder';
import { isComposition } from '../openapi/schemas';
import { isReference } from '../openapi/references';
import { quoteStringLiteral } from '../naming/naming';
import type { ModelInfo } from './modelInfo';
import { resolveContextDeclarationName, resolveModelInfo } from './modelInfo';
import { TypeGenerator } from './typeGenerator';

/**
 * Generates TypeScript models from OpenAPI schemas.
 * Handles enum, object, union, and type alias generation.
 *
 * @property project - The ts-morph project instance
 * @property openAPI - The OpenAPI specification
 * @property outputDir - The output directory for generated files
 * @property contextAggregates - Map of aggregate definitions
 */
export class ModelGenerator implements Generator {
  constructor(public readonly context: GenerateContext) {}

  private modelModule(modelInfo: ModelInfo): ModuleBuilder {
    return this.context.module(getModelFileName(modelInfo));
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
    const stateAggregatedTypeNames = this.stateAggregatedTypeNames();
    const schemas = this.context.openAPI.components?.schemas;
    if (!schemas) {
      this.context.logger.debug('No schemas found in OpenAPI specification');
      return;
    }
    const keySchemas = this.filterSchemas(schemas, stateAggregatedTypeNames);
    this.assertUniqueModelNames(keySchemas);
    this.context.logger.debug(
      `Generating models for ${keySchemas.length} schemas`,
    );
    keySchemas.forEach((keySchema, index) => {
      this.context.logger.debug(
        `[${index + 1}/${keySchemas.length}] Processing schema: ${keySchema.key}`,
      );
      this.generateKeyedSchema(keySchema);
    });
    this.context.logger.debug('Model generation completed');
  }

  private filterSchemas(
    schemas: Record<string, Schema | Reference>,
    aggregatedTypeNames: Set<string>,
  ): KeySchema<Schema | Reference>[] {
    return Object.entries(schemas)
      .map(([key, schema]) => ({ key, schema }))
      .filter(
        keySchema => !this.isWowSchema(keySchema.key, aggregatedTypeNames),
      );
  }

  /**
   * Fails when two schemas generate the same model in the same file.
   *
   * Names are normalised to PascalCase, so `Foo-Bar`, `FooBar` and `foo_bar`
   * all become `FooBar`; TypeScript would silently merge three interfaces of
   * that name into one type that matches none of them.
   *
   * @throws GeneratorError listing every group of colliding schema keys
   */
  private assertUniqueModelNames(keySchemas: KeySchema<Schema | Reference>[]) {
    const byModel = new Map<string, string[]>();
    for (const { key } of keySchemas) {
      const modelInfo = resolveModelInfo(key);
      const model = `${modelInfo.path === '/' ? '' : modelInfo.path}/${modelInfo.name}`;
      byModel.set(model, [...(byModel.get(model) ?? []), key]);
    }
    const collisions = [...byModel].filter(([, keys]) => keys.length > 1);
    if (collisions.length === 0) return;
    throw new GeneratorError(
      'specification',
      `Schemas generate the same model: ${collisions
        .map(([model, keys]) => `${keys.join(', ')} → ${model}`)
        .join('; ')}. Rename all but one of them in the document.`,
    );
  }

  private isWowSchema(
    schemaKey: string,
    stateAggregatedTypeNames: Set<string>,
  ): boolean {
    if (
      schemaKey !== 'wow.api.query.PagedList' &&
      schemaKey.startsWith('wow.api.query.') &&
      schemaKey.endsWith('PagedList')
    ) {
      return false;
    }

    if (
      schemaKey.startsWith('wow.api.query.Operator') &&
      schemaKey.endsWith('Map')
    ) {
      return false;
    }

    if (
      schemaKey.startsWith('wow.') ||
      schemaKey.endsWith('AggregatedCondition') ||
      schemaKey.endsWith('AggregatedDomainEventStream') ||
      schemaKey.endsWith('AggregatedDomainEventStreamPagedList') ||
      schemaKey.endsWith('AggregatedDomainEventStreamCursorPage') ||
      schemaKey.endsWith(
        'AggregatedDomainEventStreamServerSentEventNonNullData',
      ) ||
      schemaKey.endsWith('AggregatedListQuery') ||
      schemaKey.endsWith('AggregatedPagedQuery') ||
      schemaKey.endsWith('AggregatedSingleQuery')
    ) {
      return true;
    }
    const modelInfo = resolveModelInfo(schemaKey);
    return stateAggregatedTypeNames.has(modelInfo.name);
  }

  private aggregatedSchemaSuffix = [
    'MaterializedSnapshot',
    'MaterializedSnapshotPagedList',
    'MaterializedSnapshotCursorPage',
    'MaterializedSnapshotServerSentEventNonNullData',
    'PagedList',
    'ServerSentEventNonNullData',
    'Snapshot',
    'StateEvent',
  ];

  private stateAggregatedTypeNames() {
    const typeNames = new Set<string>();
    const contextAliases = new Set(this.context.contextAggregates.keys());
    // API clients import the document's own bounded context alias too.
    if (this.context.currentContextAlias) {
      contextAliases.add(this.context.currentContextAlias);
    }
    [...contextAliases]
      .sort()
      .forEach(contextAlias => this.generateBoundedContext(contextAlias));
    for (const aggregates of this.context.contextAggregates.values()) {
      for (const aggregate of aggregates) {
        const modelInfo = resolveModelInfo(aggregate.state.key);
        this.aggregatedSchemaSuffix.forEach(suffix => {
          typeNames.add(modelInfo.name + suffix);
        });
      }
    }
    return typeNames;
  }

  /**
   * Generates a model for a specific schema key.
   * Processes enums, objects, unions, and type aliases in order.
   *
   *
   * @remarks
   * The generation process follows this order:
   * 1. Enum processing
   * 2. Object processing
   * 3. Union processing
   * 4. Type alias processing
   */
  generateKeyedSchema(keySchema: KeySchema<Schema | Reference>) {
    const modelInfo = resolveModelInfo(keySchema.key);
    const module = this.modelModule(modelInfo);
    if (
      this.messageBodyKeys().has(keySchema.key) &&
      isEmptyMessageBody(keySchema.schema)
    ) {
      // A command or event without fields - a Kotlin `data object` - is an
      // empty object, not any object: `{type: object}` alone would otherwise
      // generate `Record<string, any>`, which every event union absorbs.
      const alias = module.add<TypeAliasDeclarationStructure>({
        kind: StructureKind.TypeAlias,
        name: modelInfo.name,
        type: 'globalThis.Record<string, never>',
        isExported: true,
      });
      addMainSchemaJSDoc(
        alias,
        keySchema.schema,
        keySchema.key,
        this.context.schemaDocs === 'full',
      );
      return;
    }
    const typeGenerator = new TypeGenerator(
      modelInfo,
      module,
      keySchema,
      this.context.outputDir,
      this.context.openAPI.components,
      this.context.schemaDocs,
      this.context.types,
    );
    typeGenerator.generate();
  }

  private bodyKeys?: Set<string>;

  /** The schema keys of every command and event body of the aggregates. */
  private messageBodyKeys(): Set<string> {
    this.bodyKeys ??= new Set(
      [...this.context.contextAggregates.values()].flatMap(aggregates =>
        [...aggregates].flatMap(aggregate => [
          ...[...aggregate.commands.values()].map(
            command => command.schema.key,
          ),
          ...[...aggregate.events.values()].map(event => event.schema.key),
        ]),
      ),
    );
    return this.bodyKeys;
  }

  generateBoundedContext(contextAlias: string) {
    const filePath = boundedContextFilePath(contextAlias);
    this.context.logger.debug(`Creating bounded context file: ${filePath}`);
    const module = this.context.module(filePath);
    const contextName = resolveContextDeclarationName(contextAlias);
    module.add(
      `export const ${contextName} = ${quoteStringLiteral(contextAlias)};`,
    );
  }
}

/**
 * Tells whether a schema is an object with nothing declared: no properties,
 * no additional properties, no composition.
 */
function isEmptyMessageBody(schema: Schema | Reference): boolean {
  if (isReference(schema)) return false;
  return (
    schema.type === 'object' &&
    Object.keys(schema.properties ?? {}).length === 0 &&
    (schema.additionalProperties === undefined ||
      schema.additionalProperties === false) &&
    !schema.required?.length &&
    !isComposition(schema) &&
    schema.enum === undefined &&
    schema.const === undefined
  );
}
