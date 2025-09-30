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
import { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { GenerateContextInit, GeneratorConfiguration, Logger } from './types';
import { BoundedContextAggregates } from './aggregate';
import { getOrCreateSourceFile } from './utils';

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
  private readonly defaultIgnorePathParameters = ['tenantId', 'ownerId'];
  readonly currentContextAlias: string | undefined;

  constructor(context: GenerateContextInit) {
    this.project = context.project;
    this.openAPI = context.openAPI;
    this.outputDir = context.outputDir;
    this.contextAggregates = context.contextAggregates;
    this.logger = context.logger;
    this.config = context.config ?? {};
    this.currentContextAlias = this.openAPI.info['x-wow-context-alias'];
  }

  getOrCreateSourceFile(filePath: string): SourceFile {
    return getOrCreateSourceFile(this.project, this.outputDir, filePath);
  }

  isIgnoreApiClientPathParameters(tagName: string, parameterName: string): boolean {
    const ignorePathParameters = this.config.apiClients?.[tagName]?.ignorePathParameters ?? this.defaultIgnorePathParameters;
    return ignorePathParameters.includes(parameterName);
  }

  isIgnoreCommandClientPathParameters(tagName: string, parameterName: string): boolean {
    return this.defaultIgnorePathParameters.includes(parameterName);
  }
}

export interface Generator {
  /**
   * Generates code based on the provided context.
   * Subclasses must implement this method to define their specific generation logic.
   */
  generate(): void;
}

