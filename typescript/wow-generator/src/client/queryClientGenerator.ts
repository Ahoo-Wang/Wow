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

import type { SourceFile } from 'ts-morph';
import { VariableDeclarationKind } from 'ts-morph';
import type { AggregateDefinition, TagAliasAggregate } from '../aggregate';
import type { GenerateContext, Generator } from '../generateContext';
import type { ModelInfo } from '../model';
import {
  IMPORT_WOW_PATH,
  resolveContextDeclarationName,
  resolveModelInfo,
} from '../model';
import {
  addImport,
  addImportBoundedContext,
  addImportRefModel,
  camelCase,
  quoteStringLiteral,
  resolvePropertyName,
} from '../utils';
import {
  createClientFilePath,
  inferPathSpecType,
  resolveClassName,
} from './utils';

/**
 * Generates TypeScript query client classes for aggregates.
 * Creates query clients that can perform state queries and event streaming.
 */
export class QueryClientGenerator implements Generator {
  private readonly domainEventTypeSuffix = 'DomainEventType';
  private readonly domainEventTypeMapTitleSuffix = 'DomainEventTypeMapTitle';

  /**
   * Creates a new QueryClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  constructor(public readonly context: GenerateContext) {}

  /**
   * Generates query client classes for all aggregates.
   */
  generate(): void {
    const totalAggregates = Array.from(
      this.context.contextAggregates.values(),
    ).reduce((sum, set) => sum + set.size, 0);
    this.context.logger.debug('--- Generating Query Clients ---');
    this.context.logger.debug(
      `Generating query clients for ${totalAggregates} aggregates`,
    );
    let currentIndex = 0;
    for (const [, aggregates] of this.context.contextAggregates) {
      aggregates.forEach(aggregateDefinition => {
        currentIndex++;
        this.context.logger.debug(
          `[${currentIndex}/${totalAggregates}] Processing query client for aggregate: ${aggregateDefinition.aggregate.aggregateName}`,
        );
        this.processQueryClient(aggregateDefinition);
      });
    }
    this.context.logger.debug('Query client generation completed');
  }

  /**
   * Creates or retrieves a source file for client generation.
   * @param aggregate - The aggregate metadata
   * @param fileName - The name of the client file
   * @returns The source file for the client
   */
  createClientFilePath(
    aggregate: TagAliasAggregate,
    fileName: string,
  ): SourceFile {
    return createClientFilePath(
      this.context.project,
      this.context.outputDir,
      aggregate,
      fileName,
    );
  }

  /**
   * Processes and generates query client classes for an aggregate.
   * @param aggregate - The aggregate definition
   */
  processQueryClient(aggregate: AggregateDefinition) {
    const queryClientFile = this.createClientFilePath(
      aggregate.aggregate,
      'queryClient',
    );
    this.context.logger.debug(
      `Processing query client for aggregate: ${aggregate.aggregate.aggregateName} in context: ${aggregate.aggregate.contextAlias}`,
    );

    this.context.logger.debug(
      `Adding imports from ${IMPORT_WOW_PATH}: QueryClientFactory, QueryClientOptions, ResourceAttributionPathSpec`,
    );
    addImport(queryClientFile, IMPORT_WOW_PATH, [
      'QueryClientFactory',
      'QueryClientOptions',
      'ResourceAttributionPathSpec',
    ]);
    const contextDeclarationName = resolveContextDeclarationName(
      aggregate.aggregate.contextAlias,
    );
    addImportBoundedContext(
      queryClientFile,
      this.context.outputDir,
      aggregate.aggregate.contextAlias,
      contextDeclarationName,
    );

    const defaultClientOptionsName = 'DEFAULT_QUERY_CLIENT_OPTIONS';
    this.context.logger.debug(
      `Creating default query client options: ${defaultClientOptionsName}`,
    );
    queryClientFile.addVariableStatement({
      declarationKind: VariableDeclarationKind.Const,
      declarations: [
        {
          name: defaultClientOptionsName,
          type: 'QueryClientOptions',
          // aggregateName is the route segment the query paths use, which
          // the aggregate's resource name can make differ from its name.
          initializer: `{
        contextAlias: ${contextDeclarationName},
        aggregateName: ${quoteStringLiteral(aggregate.resourceName)},
        resourceAttribution: ${inferPathSpecType(aggregate)},
      }`,
        },
      ],
      isExported: false,
    });
    this.processAggregateDomainEventTypes(aggregate, queryClientFile);
    const aggregateDomainEventType = this.processAggregateDomainEventType(
      aggregate,
      queryClientFile,
    );

    const factoryPrefix = camelCase(aggregate.aggregate.aggregateName) || '_';
    const clientFactoryName = `${/^\p{N}/u.test(factoryPrefix) ? '_' : ''}${factoryPrefix}QueryClientFactory`;
    const stateModelInfo = resolveModelInfo(aggregate.state.key);
    const fieldsModelInfo = resolveModelInfo(aggregate.fields.key);

    this.context.logger.debug(
      `Adding import for state model: ${stateModelInfo.name} from path: ${stateModelInfo.path}`,
    );
    addImportRefModel(queryClientFile, this.context.outputDir, stateModelInfo);
    this.context.logger.debug(
      `Adding import for fields model: ${fieldsModelInfo.name} from path: ${fieldsModelInfo.path}`,
    );
    addImportRefModel(queryClientFile, this.context.outputDir, fieldsModelInfo);

    this.context.logger.debug(
      `Creating query client factory: ${clientFactoryName}`,
    );
    queryClientFile.addVariableStatement({
      declarationKind: VariableDeclarationKind.Const,
      declarations: [
        {
          name: clientFactoryName,
          // `${Fields}` is the union of the field enum's values: a field
          // is named by its enum member or by its string, and a misspelt
          // one does not compile.
          initializer: `new QueryClientFactory<${stateModelInfo.name}, \`\${${fieldsModelInfo.name}}\`, ${aggregateDomainEventType}>(${defaultClientOptionsName})`,
        },
      ],
      isExported: true,
    });

    this.context.logger.debug(
      `Query client generation completed for aggregate: ${aggregate.aggregate.aggregateName}`,
    );
  }

  private processAggregateDomainEventType(
    aggregate: AggregateDefinition,
    queryClientFile: SourceFile,
  ) {
    const eventModelInfos: ModelInfo[] = [];
    this.context.logger.debug(
      `Processing ${aggregate.events.size} domain events for aggregate: ${aggregate.aggregate.aggregateName}`,
    );
    for (const event of aggregate.events.values()) {
      const eventModelInfo = resolveModelInfo(event.schema.key);
      this.context.logger.debug(
        `Adding import for event model: ${eventModelInfo.name} from path: ${eventModelInfo.path}`,
      );
      addImportRefModel(
        queryClientFile,
        this.context.outputDir,
        eventModelInfo,
      );
      eventModelInfos.push(eventModelInfo);
    }
    const aggregateDomainEventType = resolveClassName(
      aggregate.aggregate,
      this.domainEventTypeSuffix,
    );
    const eventTypeUnion =
      eventModelInfos.map(it => it.name).join(' | ') || 'never';
    this.context.logger.debug(
      `Creating domain event types union: ${aggregateDomainEventType} = ${eventTypeUnion}`,
    );
    queryClientFile.addTypeAlias({
      isExported: true,
      name: aggregateDomainEventType,
      type: eventTypeUnion,
    });
    return aggregateDomainEventType;
  }

  private processAggregateDomainEventTypes(
    aggregate: AggregateDefinition,
    queryClientFile: SourceFile,
  ) {
    const aggregateDomainEventTypes = resolveClassName(
      aggregate.aggregate,
      this.domainEventTypeMapTitleSuffix,
    );
    const enumDeclaration = queryClientFile.addEnum({
      name: aggregateDomainEventTypes,
      isExported: true,
    });
    for (const event of aggregate.events.values()) {
      enumDeclaration.addMember({
        name: resolvePropertyName(event.name),
        initializer: quoteStringLiteral(event.title),
      });
    }
  }
}
