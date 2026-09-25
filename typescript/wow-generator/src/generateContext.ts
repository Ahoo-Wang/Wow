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
import type { Project, SourceFile } from 'ts-morph';
import type { BoundedContextAggregates } from './aggregate';
import type { GeneratorConfiguration } from './api/configuration';
import type { Logger } from './api/logger';
import type { SchemaDocs } from './api/options';
import { getOrCreateSourceFile } from './output/generatedFiles';

/**
 * Context object containing all necessary data for code generation.
 */
export interface GenerateContextInit {
  /** The parsed OpenAPI specification */
  openAPI: OpenAPI;
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
  /** How much of each schema the model doc comments carry. Defaults to `summary`. */
  schemaDocs?: SchemaDocs;
}

export class GenerateContext implements GenerateContextInit {
  /** The ts-morph project instance used for code generation */
  readonly project: Project;
  /** The OpenAPI specification object */
  readonly openAPI: OpenAPI;
  /** The output directory path for generated files */
  readonly outputDir: string;
  /** Map of bounded context aggregates for domain modeling */
  readonly contextAggregates: BoundedContextAggregates;
  /** Optional logger for generation progress and errors */
  readonly logger: Logger;
  readonly config: GeneratorConfiguration;
  /**
   * The resource-attribution path parameters Wow's CoSec interceptor fills,
   * which generated clients therefore leave out.
   */
  private readonly wowIgnorePathParameters = ['tenantId', 'ownerId'];
  readonly currentContextAlias: string | undefined;
  /** Tags of Wow aggregates, whose operations do not go to API clients. */
  readonly aggregateTags: ReadonlySet<string>;
  readonly schemaDocs: SchemaDocs;

  constructor(context: GenerateContextInit) {
    this.project = context.project;
    this.openAPI = context.openAPI;
    this.outputDir = context.outputDir;
    this.contextAggregates = context.contextAggregates;
    this.logger = context.logger;
    this.config = context.config ?? {};
    this.currentContextAlias = this.openAPI.info['x-wow-context-alias'];
    this.aggregateTags =
      context.aggregateTags ??
      new Set(
        [...this.contextAggregates.values()].flatMap(aggregates =>
          [...aggregates].map(aggregate => aggregate.aggregate.tag.name),
        ),
      );
    this.schemaDocs = context.schemaDocs ?? 'summary';
  }

  /**
   * Tells whether the document comes from a Wow service: it names its bounded
   * context, or it has aggregates.
   */
  get isWowDocument(): boolean {
    return (
      this.currentContextAlias !== undefined || this.aggregateTags.size > 0
    );
  }

  getOrCreateSourceFile(filePath: string): SourceFile {
    return getOrCreateSourceFile(this.project, this.outputDir, filePath);
  }

  isIgnoreApiClientPathParameters(
    tagName: string,
    parameterName: string,
  ): boolean {
    const ignorePathParameters =
      this.config.apiClients?.[tagName]?.ignorePathParameters ??
      (this.isWowDocument ? this.wowIgnorePathParameters : []);
    return ignorePathParameters.includes(parameterName);
  }

  isIgnoreCommandClientPathParameters(parameterName: string): boolean {
    return this.wowIgnorePathParameters.includes(parameterName);
  }
}

export interface Generator {
  /**
   * Generates code based on the provided context.
   * Subclasses must implement this method to define their specific generation logic.
   */
  generate(): void;
}
