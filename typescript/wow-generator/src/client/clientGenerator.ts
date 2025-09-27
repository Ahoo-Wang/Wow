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

import { BaseCodeGenerator } from '../baseCodeGenerator';
import { QueryClientGenerator } from './queryClientGenerator';
import { CommandClientGenerator } from './commandClientGenerator';
import { GenerateContext } from '../types';
import { getOrCreateSourceFile } from '../utils';

/**
 * Generates TypeScript client classes for aggregates.
 * Creates query clients and command clients based on aggregate definitions.
 */
export class ClientGenerator extends BaseCodeGenerator {
  private readonly queryClientGenerator: QueryClientGenerator;
  private readonly commandClientGenerator: CommandClientGenerator;

  /**
   * Creates a new ClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  constructor(context: GenerateContext) {
    super(context);
    this.queryClientGenerator = new QueryClientGenerator(context);
    this.commandClientGenerator = new CommandClientGenerator(context);
  }

  /**
   * Generates client classes for all aggregates.
   */
  generate(): void {
    this.logger.info('--- Generating Clients ---');
    this.logger.progress(
      `Generating clients for ${this.contextAggregates.size} bounded contexts`,
    );
    let currentIndex = 0;
    for (const [contextAlias] of this.contextAggregates) {
      currentIndex++;
      this.logger.progressWithCount(
        currentIndex,
        this.contextAggregates.size,
        `Processing bounded context: ${contextAlias}`,
        1,
      );
      this.processBoundedContext(contextAlias);
    }
    this.queryClientGenerator.generate();
    this.commandClientGenerator.generate();
    this.logger.success('Client generation completed');
  }

  /**
   * Processes a bounded context by creating a file with the context alias constant.
   * @param contextAlias - The alias of the bounded context to process
   */
  processBoundedContext(contextAlias: string) {
    const filePath = `${contextAlias}/boundedContext.ts`;
    this.logger.info(`Creating bounded context file: ${filePath}`);
    const file = getOrCreateSourceFile(this.project, this.outputDir, filePath);
    this.logger.info(
      `Adding bounded context alias constant: BOUNDED_CONTEXT_ALIAS = '${contextAlias}'`,
    );
    file.addStatements(
      `export const BOUNDED_CONTEXT_ALIAS = '${contextAlias}';`,
    );
    this.logger.success(
      `Bounded context file created successfully: ${filePath}`,
    );
  }
}
