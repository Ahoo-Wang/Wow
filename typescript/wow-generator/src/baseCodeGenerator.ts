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

import { Project } from 'ts-morph';
import { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { GenerateContext, Logger } from './types';
import { BoundedContextAggregates } from './aggregate';

export abstract class BaseCodeGenerator implements GenerateContext {
  readonly project: Project;
  readonly openAPI: OpenAPI;
  readonly outputDir: string;
  readonly contextAggregates: BoundedContextAggregates;
  readonly logger?: Logger;

  /**
   * Creates a new ClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  protected constructor(context: GenerateContext) {
    this.project = context.project;
    this.openAPI = context.openAPI;
    this.outputDir = context.outputDir;
    this.contextAggregates = context.contextAggregates;
    this.logger = context.logger;
  }

  /**
   * Generates code based on the provided context.
   * Subclasses must implement this method to define their specific generation logic.
   */
  abstract generate(): void;
}
