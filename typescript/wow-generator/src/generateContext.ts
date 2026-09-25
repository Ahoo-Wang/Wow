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

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import type { Project } from 'ts-morph';
import type { GeneratorConfiguration } from './api/configuration';
import type { Logger } from './api/logger';
import type { SchemaDocs } from './api/options';
import type { ModuleBuilder } from './emit/moduleBuilder';
import { ModuleSet } from './emit/moduleBuilder';
import { documentTypeContext } from './model/typeGenerator';
import type { OpenApiDocument } from './openapi/document';
import { openApiDocument } from './openapi/document';
import { getOrCreateSourceFile } from './output/generatedFiles';
import type { TypeContext } from './types/typeResolver';
import {
  contextAliasOf,
  RESOURCE_ATTRIBUTION_PATH_PARAMETERS,
} from './wow/conventions';
import type { BoundedContextAggregates, SchemaDocOverride } from './wow/model';
import { isWowDocument } from './wow/model';

/**
 * Context object containing all necessary data for code generation.
 */
export interface GenerateContextInit {
  /** The parsed OpenAPI specification */
  openAPI: OpenAPI;
  /** The document read once; read from `openAPI` when absent. */
  document?: OpenApiDocument;
  /** The ts-morph project instance */
  project: Project;
  /** Output directory for generated files */
  outputDir: string;
  contextAggregates: BoundedContextAggregates;
  /** Optional logger for friendly output */
  logger: Logger;
  config?: GeneratorConfiguration;
  /**
   * Tags of Wow aggregates, resolved or not; their operations do not go to API
   * clients. Defaults to the tags of `contextAggregates`.
   */
  aggregateTags?: ReadonlySet<string>;
  /** The doc comments the Wow metadata lends schemas; none by default. */
  schemaDocOverrides?: ReadonlyMap<string, SchemaDocOverride>;
  /** How much of each schema the model doc comments carry. Defaults to `summary`. */
  schemaDocs?: SchemaDocs;
}

export class GenerateContext implements GenerateContextInit {
  /** The ts-morph project instance used for code generation */
  readonly project: Project;
  /** The OpenAPI specification object */
  readonly openAPI: OpenAPI;
  /** The document, its operations listed once. */
  readonly document: OpenApiDocument;
  /** The output directory path for generated files */
  readonly outputDir: string;
  /** Map of bounded context aggregates for domain modeling */
  readonly contextAggregates: BoundedContextAggregates;
  /** Optional logger for generation progress and errors */
  readonly logger: Logger;
  readonly config: GeneratorConfiguration;
  readonly currentContextAlias: string | undefined;
  /** Tags of Wow aggregates, whose operations do not go to API clients. */
  readonly aggregateTags: ReadonlySet<string>;
  /** The doc comments the Wow metadata lends schemas, by component key. */
  readonly schemaDocOverrides: ReadonlyMap<string, SchemaDocOverride>;
  readonly schemaDocs: SchemaDocs;
  /**
   * The modules the generators write; nothing reaches the source files until
   * {@link ModuleSet.build}.
   */
  readonly modules: ModuleSet;
  /**
   * What every type of the document resolves against, shared by the models
   * and the API clients so the model names of each path are read once.
   */
  readonly types: TypeContext;

  constructor(context: GenerateContextInit) {
    this.project = context.project;
    this.openAPI = context.openAPI;
    this.document = context.document ?? openApiDocument(context.openAPI);
    this.outputDir = context.outputDir;
    this.contextAggregates = context.contextAggregates;
    this.logger = context.logger;
    this.config = context.config ?? {};
    this.currentContextAlias = contextAliasOf(this.openAPI);
    this.aggregateTags =
      context.aggregateTags ??
      new Set(
        [...this.contextAggregates.values()].flatMap(aggregates =>
          [...aggregates].map(aggregate => aggregate.aggregate.tag.name),
        ),
      );
    this.schemaDocOverrides = context.schemaDocOverrides ?? new Map();
    this.schemaDocs = context.schemaDocs ?? 'summary';
    this.types = documentTypeContext(this.openAPI.components);
    this.modules = new ModuleSet(filePath =>
      getOrCreateSourceFile(this.project, this.outputDir, filePath),
    );
  }

  /**
   * Tells whether the document comes from a Wow service: it names its bounded
   * context, or it has aggregates.
   */
  get isWowDocument(): boolean {
    return isWowDocument({
      contextAlias: this.currentContextAlias,
      aggregateTags: this.aggregateTags,
    });
  }

  /** The module written to a path under the output directory. */
  module(filePath: string): ModuleBuilder {
    return this.modules.module(filePath);
  }

  isIgnoreApiClientPathParameters(
    tagName: string,
    parameterName: string,
  ): boolean {
    const ignorePathParameters =
      this.config.apiClients?.[tagName]?.ignorePathParameters ??
      (this.isWowDocument ? RESOURCE_ATTRIBUTION_PATH_PARAMETERS : []);
    return ignorePathParameters.includes(parameterName);
  }

  isIgnoreCommandClientPathParameters(parameterName: string): boolean {
    return RESOURCE_ATTRIBUTION_PATH_PARAMETERS.includes(parameterName);
  }
}

export interface Generator {
  /**
   * Generates code based on the provided context.
   * Subclasses must implement this method to define their specific generation logic.
   */
  generate(): void;
}
