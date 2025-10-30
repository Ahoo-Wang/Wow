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

import { GenerateContext, Generator } from '../generateContext';
import { ApiClientGenerator } from './apiClientGenerator';
import { CommandClientGenerator } from './commandClientGenerator';
import { QueryClientGenerator } from './queryClientGenerator';

/**
 * Generates TypeScript client classes for aggregates.
 * Creates query clients and command clients based on aggregate definitions.
 */
export class ClientGenerator implements Generator {
  private readonly queryClientGenerator: QueryClientGenerator;
  private readonly commandClientGenerator: CommandClientGenerator;
  private readonly apiClientGenerator: ApiClientGenerator;

  /**
   * Creates a new ClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  constructor(public readonly context: GenerateContext) {
    this.queryClientGenerator = new QueryClientGenerator(context);
    this.commandClientGenerator = new CommandClientGenerator(context);
    this.apiClientGenerator = new ApiClientGenerator(context);
  }

  /**
   * Generates client classes for all aggregates.
   */
  generate(): void {
    this.context.logger.info('--- Generating Clients ---');
    this.context.logger.progress(
      `Generating clients for ${this.context.contextAggregates.size} bounded contexts`,
    );
    let currentIndex = 0;
    for (const [contextAlias] of this.context.contextAggregates) {
      currentIndex++;
      this.context.logger.progressWithCount(
        currentIndex,
        this.context.contextAggregates.size,
        `Processing bounded context: ${contextAlias}`,
        1,
      );
    }
    this.queryClientGenerator.generate();
    this.commandClientGenerator.generate();
    this.apiClientGenerator.generate();
    this.context.logger.success('Client generation completed');
  }
}
