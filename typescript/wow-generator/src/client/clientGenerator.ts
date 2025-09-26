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

import { GenerateContext } from '@/types.ts';
import {
  getOrCreateSourceFile,
} from '@/utils/sourceFiles.ts';
import { BaseCodeGenerator } from '@/BaseCodeGenerator.ts';
import { QueryClientGenerator } from '@/client/queryClientGenerator.ts';
import { CommandClientGenerator } from '@/client/commandClientGenerator.ts';

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
    for (const [contextAlias, _] of this.contextAggregates) {
      this.processBoundedContext(contextAlias);
    }
    this.queryClientGenerator.generate();
    this.commandClientGenerator.generate();
  }

  processBoundedContext(contextAlias: string) {
    const filePath = `${contextAlias}/boundedContext.ts`;
    const file = getOrCreateSourceFile(this.project, this.outputDir, filePath);
    file.addStatements(`export const BOUNDED_CONTEXT_ALIAS = '${contextAlias}';`);
  }

}
