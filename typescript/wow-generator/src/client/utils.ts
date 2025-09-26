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

import { ResourceAttributionPathSpec } from '@ahoo-wang/fetcher-wow';
import { Project, SourceFile } from 'ts-morph';
import { AggregateDefinition, TagAliasAggregate } from '../aggregate';
import { getOrCreateSourceFile, pascalCase } from '../utils';

export function inferPathSpecType(
  aggregateDefinition: AggregateDefinition,
): string {
  let tenantSpecCount = 0;
  let ownerSpecCount = 0;
  aggregateDefinition.commands.forEach(command => {
    if (command.path.startsWith(ResourceAttributionPathSpec.TENANT)) {
      tenantSpecCount += 1;
    }
    if (command.path.startsWith(ResourceAttributionPathSpec.OWNER)) {
      ownerSpecCount += 1;
    }
  });
  if (tenantSpecCount === 0 && ownerSpecCount === 0) {
    return 'ResourceAttributionPathSpec.NONE';
  }
  return tenantSpecCount > ownerSpecCount
    ? 'ResourceAttributionPathSpec.TENANT'
    : 'ResourceAttributionPathSpec.OWNER';
}

export function createClientFilePath(
  project: Project,
  outputDir: string,
  aggregate: TagAliasAggregate,
  fileName: string,
): SourceFile {
  const filePath = `${aggregate.contextAlias}/${aggregate.aggregateName}/${fileName}.ts`;
  return getOrCreateSourceFile(project, outputDir, filePath);
}

/**
 * Generates the client class name for an aggregate.
 * @param aggregate - The aggregate metadata
 * @param suffix - The suffix to append to the aggregate name
 * @returns The generated client class name
 */
export function getClientName(
  aggregate: TagAliasAggregate,
  suffix: string,
): string {
  return `${pascalCase(aggregate.aggregateName)}${suffix}`;
}
