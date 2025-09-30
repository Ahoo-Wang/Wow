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

import {
  OpenAPI,
  Operation,
  Parameter,
  Reference,
  RequestBody,
  Schema,
} from '@ahoo-wang/fetcher-openapi';
import {
  AggregateDefinition,
  BoundedContextAggregates,
  CommandDefinition,
  EventDefinition,
} from './aggregate';

import { ContentTypeValues, PartialBy } from '@ahoo-wang/fetcher';
import {
  extractOkResponse,
  extractOperationOkResponseJsonSchema,
  extractOperations,
  extractParameter,
  extractRequestBody,
  extractSchema,
  isReference,
  keySchema,
  MethodOperation,
} from '../utils';
import { EventStreamSchema } from './types';
import { operationIdToCommandName, tagsToAggregates } from './utils';

const CommandOkResponseRef = '#/components/responses/wow.CommandOk';
const IdParameterRef = '#/components/parameters/wow.id';

/**
 * Resolves aggregate definitions from OpenAPI specifications.
 * Parses operations to extract commands, events, and state information for each aggregate.
 */
export class AggregateResolver {
  private readonly aggregates: Map<
    string,
    PartialBy<AggregateDefinition, 'state' | 'fields'>
  >;

  /**
   * Creates a new AggregateResolver instance.
   * @param openAPI - The OpenAPI specification to resolve aggregates from
   */
  constructor(private readonly openAPI: OpenAPI) {
    this.aggregates = tagsToAggregates(openAPI.tags);
    this.build();
  }

  /**
   * Builds the aggregate definitions by processing all operations in the OpenAPI spec.
   * @private
   */
  private build() {
    for (const [path, pathItem] of Object.entries(this.openAPI.paths)) {
      const methodOperations = extractOperations(pathItem);
      for (const methodOperation of methodOperations) {
        this.commands(path, methodOperation);
        this.state(methodOperation.operation);
        this.events(methodOperation.operation);
        this.fields(methodOperation.operation);
      }
    }
  }

  /**
   * Returns the resolved aggregate definitions.
   * @returns Map of aggregate definitions keyed by context alias
   */
  resolve(): BoundedContextAggregates {
    const resolvedContextAggregates = new Map<
      string,
      Set<AggregateDefinition>
    >();
    for (const aggregate of this.aggregates.values()) {
      if (!aggregate.state || !aggregate.fields) {
        continue;
      }
      const contextAlias = aggregate.aggregate.contextAlias;
      let aggregates = resolvedContextAggregates.get(contextAlias);
      if (!aggregates) {
        aggregates = new Set<AggregateDefinition>();
        resolvedContextAggregates.set(contextAlias, aggregates);
      }
      aggregates.add(aggregate as AggregateDefinition);
    }
    return resolvedContextAggregates;
  }

  /**
   * Processes command operations and adds them to the appropriate aggregates.
   * @param path - The API path
   * @param methodOperation - The HTTP method and operation details
   */
  commands(path: string, methodOperation: MethodOperation) {
    const operation = methodOperation.operation;
    if (operation.operationId === 'wow.command.send') {
      return;
    }
    const commandName = operationIdToCommandName(operation.operationId);
    if (!commandName) {
      return;
    }
    const okResponse = extractOkResponse(operation);
    if (!okResponse) {
      return;
    }
    if (!isReference(okResponse)) {
      return;
    }
    if (okResponse.$ref !== CommandOkResponseRef) {
      return;
    }
    if (!operation.requestBody) {
      return;
    }

    const parameters = operation.parameters ?? [];
    const idRefParameter = parameters
      .filter(p => isReference(p) && p.$ref === IdParameterRef)
      .at(0) as Reference | undefined;
    const pathParameters = parameters.filter(
      p => !isReference(p) && p.in === 'path',
    ) as Parameter[];
    if (idRefParameter) {
      const idParameter = extractParameter(
        idRefParameter,
        this.openAPI.components!,
      );
      pathParameters.push(idParameter!);
    }
    const requestBody = operation.requestBody as RequestBody;
    const commandRefSchema = requestBody.content[
      ContentTypeValues.APPLICATION_JSON
      ].schema as Reference;
    const commandKeyedSchema = keySchema(
      commandRefSchema,
      this.openAPI.components!,
    );
    commandKeyedSchema.schema.title =
      commandKeyedSchema.schema.title || operation.summary;
    commandKeyedSchema.schema.description =
      commandKeyedSchema.schema.description || operation.description;
    const commandDefinition: CommandDefinition = {
      name: commandName,
      method: methodOperation.method,
      path,
      pathParameters,
      summary: operation.summary,
      description: operation.description,
      schema: commandKeyedSchema,
      operation: operation,
    };
    operation.tags?.forEach(tag => {
      const aggregate = this.aggregates.get(tag);
      if (!aggregate) {
        return;
      }
      aggregate.commands.set(commandName, commandDefinition);
    });
  }

  /**
   * Processes state snapshot operations and associates them with aggregates.
   * @param operation - The OpenAPI operation
   */
  state(operation: Operation) {
    if (!operation.operationId?.endsWith('.snapshot_state.single')) {
      return;
    }
    const stateRefSchema = extractOperationOkResponseJsonSchema(operation);
    if (!isReference(stateRefSchema)) {
      return;
    }
    const stateKeyedSchema = keySchema(
      stateRefSchema,
      this.openAPI.components!,
    );
    operation.tags?.forEach(tag => {
      const aggregate = this.aggregates.get(tag);
      if (!aggregate) {
        return;
      }
      aggregate.state = stateKeyedSchema;
    });
  }

  /**
   * Processes event stream operations and extracts domain events for aggregates.
   * @param operation - The OpenAPI operation
   */
  events(operation: Operation) {
    if (!this.openAPI.components) {
      return;
    }
    if (!operation.operationId?.endsWith('.event.list_query')) {
      return;
    }
    const eventStreamArraySchema =
      extractOperationOkResponseJsonSchema(operation);
    if (isReference(eventStreamArraySchema)) {
      return;
    }
    const eventStreamRefSchema = eventStreamArraySchema?.items;
    if (!isReference(eventStreamRefSchema)) {
      return;
    }
    const eventStreamSchema = extractSchema(
      eventStreamRefSchema,
      this.openAPI.components,
    ) as EventStreamSchema;

    const events: EventDefinition[] =
      eventStreamSchema.properties.body.items.anyOf.map(domainEventSchema => {
        const eventTitle = domainEventSchema.title;
        const eventName = domainEventSchema.properties.name.const;
        const eventBodySchema = domainEventSchema.properties.body;
        const eventBodyKeyedSchema = keySchema(
          eventBodySchema,
          this.openAPI.components!,
        );
        eventBodyKeyedSchema.schema.title =
          eventBodyKeyedSchema.schema.title || domainEventSchema.title;
        return {
          title: eventTitle,
          name: eventName,
          schema: eventBodyKeyedSchema,
        };
      });

    operation.tags?.forEach(tag => {
      const aggregate = this.aggregates.get(tag);
      if (!aggregate) {
        return;
      }
      events.forEach(event => {
        aggregate.events.set(event.name, event);
      });
    });
  }

  /**
   * Processes field query operations and associates field schemas with aggregates.
   * @param operation - The OpenAPI operation
   */
  fields(operation: Operation): void {
    if (!this.openAPI.components) {
      return;
    }
    if (!operation.operationId?.endsWith('.snapshot.count')) {
      return;
    }
    const requestBody = extractRequestBody(
      operation.requestBody as Reference,
      this.openAPI.components,
    ) as RequestBody;
    const conditionRefSchema = requestBody.content[
      ContentTypeValues.APPLICATION_JSON
      ].schema as Reference;
    const conditionSchema = extractSchema(
      conditionRefSchema,
      this.openAPI.components,
    ) as Schema;
    const fieldRefSchema = conditionSchema.properties?.field as Reference;
    const fieldKeyedSchema = keySchema(fieldRefSchema, this.openAPI.components);
    operation.tags?.forEach(tag => {
      const aggregate = this.aggregates.get(tag);
      if (!aggregate) {
        return;
      }
      aggregate.fields = fieldKeyedSchema;
    });
  }
}
