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

import type {
  OpenAPI,
  Operation,
  Reference,
  RequestBody,
  Schema,
} from '@ahoo-wang/fetcher-openapi';
import type {
  AggregateDefinition,
  BoundedContextAggregates,
  CommandDefinition,
  EventDefinition,
} from './aggregate';

import type { PartialBy } from '@ahoo-wang/fetcher';
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import { GeneratorError } from '../errors';
import type { Logger } from '../types';
import type { MethodOperation } from '../utils';
import {
  extractOkResponse,
  extractOperationEndpoints,
  extractOperationOkResponseJsonSchema,
  extractPathParameters,
  extractRequestBody,
  extractSchema,
  isReference,
  keySchema,
  SilentLogger,
  warn,
} from '../utils';
import { COMPONENTS_RESPONSES_REF } from '../utils/components';
import { operationIdToCommandName, tagsToAggregates } from './utils';

const CommandOkResponseRef = '#/components/responses/wow.CommandOk';

/** The oldest Wow server whose OpenAPI metadata the generator reads fully. */
const MINIMUM_WOW_VERSION = '8.10';

/**
 * The route segment of an aggregate, read off one of its snapshot routes:
 * `/tenant/{tenantId}/owner/{ownerId}/sales-order/snapshot/count` →
 * `sales-order`. It differs from the aggregate name when the aggregate sets
 * a resource name (`@AggregateRoute(resourceName = "sales-order")`).
 */
const SNAPSHOT_ROUTE =
  /^(?:\/tenant\/\{tenantId\})?(?:\/owner\/\{ownerId\})?\/(.+?)\/snapshot(?:\/|$)/;

type PartialAggregateDefinition = PartialBy<
  AggregateDefinition,
  'state' | 'fields' | 'resourceName'
>;

/**
 * Resolves aggregate definitions from OpenAPI specifications.
 * Parses operations to extract commands, events, and state information for each aggregate.
 *
 * Resolution enriches the command and event schemas it discovers with the
 * title and description of the operation that uses them, so it works on the
 * document it was given; the caller passes a document it owns.
 */
export class AggregateResolver {
  private readonly aggregates: Map<string, PartialAggregateDefinition>;
  /** Tags of aggregates that expose at least one Wow route. */
  private readonly wowTags = new Set<string>();

  /**
   * Creates a new AggregateResolver instance.
   * @param openAPI - The OpenAPI specification to resolve aggregates from
   * @param logger - Receives a warning for each aggregate it cannot resolve
   * @throws GeneratorError when an aggregate's Wow metadata is malformed
   */
  constructor(
    private readonly openAPI: OpenAPI,
    private readonly logger: Logger = new SilentLogger(),
  ) {
    const declared = openAPI.tags ?? [];
    const declaredNames = new Set(declared.map(tag => tag.name));
    // A document may tag operations without declaring the tags.
    const undeclared = new Set(
      extractOperationEndpoints(openAPI.paths, openAPI.components)
        .flatMap(endpoint => endpoint.operation.tags ?? [])
        .filter(name => !declaredNames.has(name)),
    );
    this.aggregates = tagsToAggregates([
      ...declared,
      ...[...undeclared].map(name => ({ name })),
    ]);
    this.build();
  }

  /**
   * Builds the aggregate definitions by processing all operations in the OpenAPI spec.
   * @private
   */
  private build() {
    const endpoints = extractOperationEndpoints(
      this.openAPI.paths,
      this.openAPI.components,
    );
    for (const endpoint of endpoints) {
      this.commands(endpoint.path, endpoint);
      this.state(endpoint.operation, endpoint.path);
      this.events(endpoint.operation);
      this.fields(endpoint.operation, endpoint.path);
    }
  }

  /**
   * Returns the resolved aggregate definitions.
   *
   * An aggregate needs its state (`snapshot_state.single`) and its query
   * fields (`snapshot.count`) to generate query clients; one that lacks
   * either is skipped with a warning.
   *
   * @returns Map of aggregate definitions keyed by context alias
   */
  resolve(): BoundedContextAggregates {
    const resolvedContextAggregates = new Map<
      string,
      Set<AggregateDefinition>
    >();
    for (const [tag, aggregate] of this.aggregates) {
      if (!aggregate.state || !aggregate.fields) {
        if (this.wowTags.has(tag)) {
          const missing = [
            aggregate.state ? undefined : `${tag}.snapshot_state.single`,
            aggregate.fields ? undefined : `${tag}.snapshot.count`,
          ].filter(Boolean);
          warn(
            this.logger,
            `Skipping aggregate ${tag}: the document has no ${missing.join(' or ')} operation, so it generates neither command nor query clients for it.`,
          );
        }
        continue;
      }
      const contextAlias = aggregate.aggregate.contextAlias;
      let aggregates = resolvedContextAggregates.get(contextAlias);
      if (!aggregates) {
        aggregates = new Set<AggregateDefinition>();
        resolvedContextAggregates.set(contextAlias, aggregates);
      }
      aggregates.add({
        ...aggregate,
        resourceName:
          aggregate.resourceName ?? aggregate.aggregate.aggregateName,
      } as AggregateDefinition);
    }
    return resolvedContextAggregates;
  }

  /**
   * The tags of every aggregate that exposes a Wow route, resolved or not.
   * Their operations belong to command and query clients, not to API clients.
   */
  aggregateTags(): ReadonlySet<string> {
    return this.wowTags;
  }

  private aggregatesOf(operation: Operation): PartialAggregateDefinition[] {
    return (operation.tags ?? []).flatMap(tag => {
      const aggregate = this.aggregates.get(tag);
      if (!aggregate) return [];
      this.wowTags.add(tag);
      return [aggregate];
    });
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
    let okResponse = extractOkResponse(operation);
    const visited = new Set<string>();
    while (
      okResponse &&
      isReference(okResponse) &&
      okResponse.$ref !== CommandOkResponseRef
    ) {
      if (!okResponse.$ref.startsWith(COMPONENTS_RESPONSES_REF)) return;
      if (visited.has(okResponse.$ref)) {
        throw new TypeError(`Cyclic component reference: ${okResponse.$ref}`);
      }
      visited.add(okResponse.$ref);
      okResponse =
        this.openAPI.components?.responses?.[
          okResponse.$ref.slice(COMPONENTS_RESPONSES_REF.length)
        ];
    }
    if (
      !okResponse ||
      !isReference(okResponse) ||
      okResponse.$ref !== CommandOkResponseRef
    ) {
      return;
    }
    const aggregates = this.aggregatesOf(operation);
    if (!operation.requestBody) {
      return;
    }

    const pathParameters = extractPathParameters(
      operation,
      this.openAPI.components ?? {},
    );
    const requestBody = isReference(operation.requestBody)
      ? extractRequestBody(operation.requestBody, this.openAPI.components ?? {})
      : operation.requestBody;
    const commandRefSchema =
      requestBody?.content?.[ContentTypeValues.APPLICATION_JSON]?.schema;
    if (!isReference(commandRefSchema)) {
      if (aggregates.length > 0) {
        warn(
          this.logger,
          `Skipping command ${operation.operationId} (${methodOperation.method.toUpperCase()} ${path}): its application/json request body is not a $ref to a component schema.`,
        );
      }
      return;
    }
    const commandKeyedSchema = keySchema(
      commandRefSchema,
      this.openAPI.components ?? {},
    );
    if (!commandKeyedSchema.schema) return;
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
    aggregates.forEach(aggregate => {
      aggregate.commands.set(commandName, commandDefinition);
    });
  }

  /**
   * Processes state snapshot operations and associates them with aggregates.
   * @param operation - The OpenAPI operation
   * @param path - The operation's route
   */
  state(operation: Operation, path = '') {
    if (!operation.operationId?.endsWith('.snapshot_state.single')) {
      return;
    }
    const aggregates = this.aggregatesOf(operation);
    this.resourceName(aggregates, path);
    const stateRefSchema = extractOperationOkResponseJsonSchema(
      operation,
      this.openAPI.components,
    );
    if (!isReference(stateRefSchema)) {
      if (aggregates.length > 0) {
        throw this.malformed(
          operation,
          'its 200 application/json response is not a $ref to the state schema',
        );
      }
      return;
    }
    const stateKeyedSchema = keySchema(
      stateRefSchema,
      this.openAPI.components!,
    );
    aggregates.forEach(aggregate => {
      aggregate.state = stateKeyedSchema;
    });
  }

  /**
   * Records the route segment of the aggregates a snapshot route belongs to.
   */
  private resourceName(aggregates: PartialAggregateDefinition[], path: string) {
    const resourceName = SNAPSHOT_ROUTE.exec(path)?.[1];
    if (!resourceName) return;
    aggregates.forEach(aggregate => {
      aggregate.resourceName ??= resourceName;
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
    const aggregates = this.aggregatesOf(operation);
    if (aggregates.length === 0) return;
    const eventStreamArraySchema = extractOperationOkResponseJsonSchema(
      operation,
      this.openAPI.components,
    );
    if (isReference(eventStreamArraySchema)) {
      return;
    }
    const eventStreamRefSchema = eventStreamArraySchema?.items;
    if (!isReference(eventStreamRefSchema)) {
      throw this.malformed(
        operation,
        'its 200 application/json response is not an array of a $ref to the event stream schema',
      );
    }
    const eventStreamSchema = extractSchema(
      eventStreamRefSchema,
      this.openAPI.components,
    );
    const domainEventSchemas = objectAt(eventStreamSchema, [
      'properties',
      'body',
      'items',
      'anyOf',
    ]);
    if (!Array.isArray(domainEventSchemas)) {
      throw this.malformed(
        operation,
        `the event stream schema ${eventStreamRefSchema.$ref} has no properties.body.items.anyOf listing the domain events`,
      );
    }
    const events: EventDefinition[] = domainEventSchemas.map(
      (domainEventSchema: Schema, index: number) => {
        const eventName = objectAt(domainEventSchema, [
          'properties',
          'name',
          'const',
        ]);
        const eventBodySchema = objectAt(domainEventSchema, [
          'properties',
          'body',
        ]);
        if (typeof eventName !== 'string' || !isReference(eventBodySchema)) {
          throw this.malformed(
            operation,
            `domain event ${index} of ${eventStreamRefSchema.$ref} needs a properties.name.const and a properties.body that is a $ref to the event schema`,
          );
        }
        const eventBodyKeyedSchema = keySchema(
          eventBodySchema,
          this.openAPI.components!,
        );
        eventBodyKeyedSchema.schema.title =
          eventBodyKeyedSchema.schema.title || domainEventSchema.title;
        return {
          title: domainEventSchema.title ?? eventName,
          name: eventName,
          schema: eventBodyKeyedSchema,
        };
      },
    );

    aggregates.forEach(aggregate => {
      events.forEach(event => {
        aggregate.events.set(event.name, event);
      });
    });
  }

  /**
   * Processes field query operations and associates field schemas with aggregates.
   * @param operation - The OpenAPI operation
   * @param path - The operation's route
   */
  fields(operation: Operation, path = ''): void {
    if (!this.openAPI.components) {
      return;
    }
    if (!operation.operationId?.endsWith('.snapshot.count')) {
      return;
    }
    const aggregates = this.aggregatesOf(operation);
    if (aggregates.length === 0) return;
    this.resourceName(aggregates, path);
    const requestBody = operation.requestBody
      ? isReference(operation.requestBody)
        ? extractRequestBody(operation.requestBody, this.openAPI.components)
        : (operation.requestBody as RequestBody)
      : undefined;
    if (!requestBody) {
      throw this.malformed(operation, 'it has no request body');
    }
    const queryFields = requestBody['x-wow-query-fields'];
    let fieldRefSchema: Reference;
    if (queryFields !== undefined) {
      if (!isReference(queryFields)) {
        throw this.malformed(
          operation,
          'x-wow-query-fields must be a schema reference',
        );
      }
      fieldRefSchema = queryFields;
    } else {
      // compat(wow<9): servers before Wow 8.11.1 have no x-wow-query-fields and name the
      // query fields only on the Condition schema's `field` (Ahoo-Wang/fetcher#1359).
      const conditionRefSchema =
        requestBody.content?.[ContentTypeValues.APPLICATION_JSON]?.schema;
      const conditionSchema = isReference(conditionRefSchema)
        ? extractSchema(conditionRefSchema, this.openAPI.components)
        : undefined;
      const field = conditionSchema?.properties?.field;
      if (!isReference(field)) {
        throw this.malformed(
          operation,
          'its request body has no x-wow-query-fields, and its condition schema has no field $ref to read the query fields from',
        );
      }
      fieldRefSchema = field;
    }
    const fieldKeyedSchema = keySchema(fieldRefSchema, this.openAPI.components);
    aggregates.forEach(aggregate => {
      aggregate.fields = fieldKeyedSchema;
    });
  }

  private malformed(operation: Operation, problem: string): GeneratorError {
    return new GeneratorError(
      'specification',
      `Cannot read the Wow metadata of ${operation.operationId}: ${problem}. wow-generator reads documents from Wow ${MINIMUM_WOW_VERSION} or later.`,
    );
  }
}

/** Reads a nested property of a schema that may be anything. */
function objectAt(value: unknown, path: string[]): any {
  let current: any = value;
  for (const key of path) {
    if (current === null || typeof current !== 'object') return undefined;
    current = current[key];
  }
  return current;
}
