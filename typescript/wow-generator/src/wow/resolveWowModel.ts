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
  Operation,
  Reference,
  RequestBody,
  Schema,
  Tag,
} from '@ahoo-wang/fetcher-openapi';
import type { PartialBy } from '@ahoo-wang/fetcher';
import { GeneratorError } from '../api/errors';
import type { KeySchema } from '../openapi/components';
import {
  COMPONENTS_RESPONSES_REF,
  extractRequestBody,
  extractSchema,
  keySchema,
} from '../openapi/components';
import type { OpenApiDocument } from '../openapi/document';
import type { OperationEndpoint } from '../openapi/operations';
import {
  extractOkResponse,
  extractOperationOkResponseJsonSchema,
  extractPathParameters,
} from '../openapi/operations';
import { isReference } from '../openapi/references';
import { APPLICATION_JSON } from '../openapi/responses';
import {
  COMMAND_OK_RESPONSE_REF,
  contextAliasOf,
  EVENTS_OPERATION_SUFFIX,
  FIELDS_OPERATION_SUFFIX,
  MINIMUM_WOW_VERSION,
  operationIdToCommandName,
  QUERY_FIELDS_EXTENSION,
  SEND_COMMAND_OPERATION_ID,
  SNAPSHOT_ROUTE,
  STATE_OPERATION_SUFFIX,
  tagToAggregate,
} from './conventions';
import type {
  AggregateDefinition,
  CommandDefinition,
  EventDefinition,
  SchemaDocOverride,
  WowModel,
} from './model';

type PartialAggregateDefinition = PartialBy<
  AggregateDefinition,
  'state' | 'fields' | 'resourceName'
>;

/**
 * Reads the Wow metadata of a document: its bounded context, and each
 * aggregate its tags name with the aggregate's commands, events, state,
 * query fields and route segment.
 *
 * An aggregate needs its state (`snapshot_state.single`) and its query fields
 * (`snapshot.count`) to generate clients; one that lacks either is left out
 * with a warning, and so is a command whose body is not a component schema.
 *
 * It is a pure function: the document is left as it was. The doc comments the
 * metadata lends command and event bodies are in
 * {@link WowModel.schemaDocOverrides}.
 *
 * @param document - The document
 * @returns The Wow model, and the warnings to log
 * @throws GeneratorError (`specification`) when an aggregate's Wow metadata
 * is malformed, or a command's response references form a cycle
 */
export function resolveWowModel(document: OpenApiDocument): WowModel {
  const { openAPI, components, endpoints } = document;
  const warnings: string[] = [];
  const schemaDocOverrides = new Map<string, Record<string, unknown>>();
  /** Tags of aggregates that expose at least one Wow route. */
  const wowTags = new Set<string>();

  const declared = openAPI.tags ?? [];
  const declaredNames = new Set(declared.map(tag => tag.name));
  // A document may tag operations without declaring the tags.
  const undeclared = new Set(
    endpoints
      .flatMap(endpoint => endpoint.operation.tags ?? [])
      .filter(name => !declaredNames.has(name)),
  );
  const aggregates = new Map<string, PartialAggregateDefinition>();
  for (const tag of [
    ...declared,
    ...[...undeclared].map((name): Tag => ({ name })),
  ]) {
    const aggregate = tagToAggregate(tag);
    if (aggregate) {
      aggregates.set(tag.name, {
        aggregate,
        commands: new Map(),
        events: new Map(),
      });
    }
  }

  const aggregatesOf = (operation: Operation): PartialAggregateDefinition[] =>
    (operation.tags ?? []).flatMap(tag => {
      const aggregate = aggregates.get(tag);
      if (!aggregate) return [];
      wowTags.add(tag);
      return [aggregate];
    });

  /**
   * Lends a schema a doc field where it has none, as `field ||= value`
   * would, recording the result instead of writing it into the schema.
   */
  const lend = (
    keyed: KeySchema,
    field: 'title' | 'description',
    value: string | undefined,
  ) => {
    let override = schemaDocOverrides.get(keyed.key);
    if (!override) {
      override = {};
      schemaDocOverrides.set(keyed.key, override);
    }
    const current = field in override ? override[field] : keyed.schema[field];
    override[field] = current || value;
  };

  const malformed = (operation: Operation, problem: string) =>
    new GeneratorError(
      'specification',
      `Cannot read the Wow metadata of ${operation.operationId}: ${problem}. wow-generator reads documents from Wow ${MINIMUM_WOW_VERSION} or later.`,
    );

  /** Records the route segment of the aggregates a snapshot route belongs to. */
  const resourceName = (owners: PartialAggregateDefinition[], path: string) => {
    const name = SNAPSHOT_ROUTE.exec(path)?.[1];
    if (!name) return;
    owners.forEach(aggregate => {
      aggregate.resourceName ??= name;
    });
  };

  const readCommand = (endpoint: OperationEndpoint) => {
    const { operation, path } = endpoint;
    if (operation.operationId === SEND_COMMAND_OPERATION_ID) {
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
      okResponse.$ref !== COMMAND_OK_RESPONSE_REF
    ) {
      if (!okResponse.$ref.startsWith(COMPONENTS_RESPONSES_REF)) return;
      if (visited.has(okResponse.$ref)) {
        throw new GeneratorError(
          'specification',
          `Cyclic component reference: ${okResponse.$ref}`,
        );
      }
      visited.add(okResponse.$ref);
      okResponse =
        components?.responses?.[
          okResponse.$ref.slice(COMPONENTS_RESPONSES_REF.length)
        ];
    }
    if (
      !okResponse ||
      !isReference(okResponse) ||
      okResponse.$ref !== COMMAND_OK_RESPONSE_REF
    ) {
      return;
    }
    const owners = aggregatesOf(operation);
    if (!operation.requestBody) {
      return;
    }

    const pathParameters = extractPathParameters(operation, components ?? {});
    const requestBody = isReference(operation.requestBody)
      ? extractRequestBody(operation.requestBody, components ?? {})
      : operation.requestBody;
    const commandRefSchema = requestBody?.content?.[APPLICATION_JSON]?.schema;
    if (!isReference(commandRefSchema)) {
      if (owners.length > 0) {
        warnings.push(
          `Skipping command ${operation.operationId} (${endpoint.method.toUpperCase()} ${path}): its application/json request body is not a $ref to a component schema.`,
        );
      }
      return;
    }
    const commandKeyedSchema = keySchema(commandRefSchema, components ?? {});
    if (!commandKeyedSchema.schema) return;
    lend(commandKeyedSchema, 'title', operation.summary);
    lend(commandKeyedSchema, 'description', operation.description);
    const commandDefinition: CommandDefinition = {
      name: commandName,
      method: endpoint.method,
      path,
      pathParameters,
      summary: operation.summary,
      description: operation.description,
      schema: commandKeyedSchema,
      operation: operation,
    };
    owners.forEach(aggregate => {
      aggregate.commands.set(commandName, commandDefinition);
    });
  };

  const readState = ({ operation, path }: OperationEndpoint) => {
    if (!operation.operationId?.endsWith(STATE_OPERATION_SUFFIX)) {
      return;
    }
    const owners = aggregatesOf(operation);
    resourceName(owners, path);
    const stateRefSchema = extractOperationOkResponseJsonSchema(
      operation,
      components,
    );
    if (!isReference(stateRefSchema)) {
      if (owners.length > 0) {
        throw malformed(
          operation,
          'its 200 application/json response is not a $ref to the state schema',
        );
      }
      return;
    }
    const stateKeyedSchema = keySchema(stateRefSchema, components!);
    owners.forEach(aggregate => {
      aggregate.state = stateKeyedSchema;
    });
  };

  const readEvents = ({ operation }: OperationEndpoint) => {
    if (!components) {
      return;
    }
    if (!operation.operationId?.endsWith(EVENTS_OPERATION_SUFFIX)) {
      return;
    }
    const owners = aggregatesOf(operation);
    if (owners.length === 0) return;
    const eventStreamArraySchema = extractOperationOkResponseJsonSchema(
      operation,
      components,
    );
    if (isReference(eventStreamArraySchema)) {
      return;
    }
    const eventStreamRefSchema = eventStreamArraySchema?.items;
    if (!isReference(eventStreamRefSchema)) {
      throw malformed(
        operation,
        'its 200 application/json response is not an array of a $ref to the event stream schema',
      );
    }
    const eventStreamSchema = extractSchema(eventStreamRefSchema, components);
    const domainEventSchemas = objectAt(eventStreamSchema, [
      'properties',
      'body',
      'items',
      'anyOf',
    ]);
    if (!Array.isArray(domainEventSchemas)) {
      throw malformed(
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
          throw malformed(
            operation,
            `domain event ${index} of ${eventStreamRefSchema.$ref} needs a properties.name.const and a properties.body that is a $ref to the event schema`,
          );
        }
        const eventBodyKeyedSchema = keySchema(eventBodySchema, components);
        lend(eventBodyKeyedSchema, 'title', domainEventSchema.title);
        return {
          title: domainEventSchema.title ?? eventName,
          name: eventName,
          schema: eventBodyKeyedSchema,
        };
      },
    );

    owners.forEach(aggregate => {
      events.forEach(event => {
        aggregate.events.set(event.name, event);
      });
    });
  };

  const readFields = ({ operation, path }: OperationEndpoint) => {
    if (!components) {
      return;
    }
    if (!operation.operationId?.endsWith(FIELDS_OPERATION_SUFFIX)) {
      return;
    }
    const owners = aggregatesOf(operation);
    if (owners.length === 0) return;
    resourceName(owners, path);
    const requestBody = operation.requestBody
      ? isReference(operation.requestBody)
        ? extractRequestBody(operation.requestBody, components)
        : (operation.requestBody as RequestBody)
      : undefined;
    if (!requestBody) {
      throw malformed(operation, 'it has no request body');
    }
    const queryFields = requestBody[QUERY_FIELDS_EXTENSION];
    let fieldRefSchema: Reference;
    if (queryFields !== undefined) {
      if (!isReference(queryFields)) {
        throw malformed(
          operation,
          `${QUERY_FIELDS_EXTENSION} must be a schema reference`,
        );
      }
      fieldRefSchema = queryFields;
    } else {
      // compat(wow<9): servers before Wow 8.11.1 have no x-wow-query-fields and name the
      // query fields only on the Condition schema's `field` (Ahoo-Wang/fetcher#1359).
      const conditionRefSchema =
        requestBody.content?.[APPLICATION_JSON]?.schema;
      const conditionSchema = isReference(conditionRefSchema)
        ? extractSchema(conditionRefSchema, components)
        : undefined;
      const field = conditionSchema?.properties?.field;
      if (!isReference(field)) {
        throw malformed(
          operation,
          `its request body has no ${QUERY_FIELDS_EXTENSION}, and its condition schema has no field $ref to read the query fields from`,
        );
      }
      fieldRefSchema = field;
    }
    const fieldKeyedSchema = keySchema(fieldRefSchema, components);
    owners.forEach(aggregate => {
      aggregate.fields = fieldKeyedSchema;
    });
  };

  for (const endpoint of endpoints) {
    readCommand(endpoint);
    readState(endpoint);
    readEvents(endpoint);
    readFields(endpoint);
  }

  const contexts = new Map<string, Set<AggregateDefinition>>();
  for (const [tag, aggregate] of aggregates) {
    if (!aggregate.state || !aggregate.fields) {
      if (wowTags.has(tag)) {
        const missing = [
          aggregate.state ? undefined : `${tag}${STATE_OPERATION_SUFFIX}`,
          aggregate.fields ? undefined : `${tag}${FIELDS_OPERATION_SUFFIX}`,
        ].filter(Boolean);
        warnings.push(
          `Skipping aggregate ${tag}: the document has no ${missing.join(' or ')} operation, so it generates neither command nor query clients for it.`,
        );
      }
      continue;
    }
    const contextAlias = aggregate.aggregate.contextAlias;
    let resolved = contexts.get(contextAlias);
    if (!resolved) {
      resolved = new Set<AggregateDefinition>();
      contexts.set(contextAlias, resolved);
    }
    resolved.add({
      ...aggregate,
      resourceName: aggregate.resourceName ?? aggregate.aggregate.aggregateName,
    } as AggregateDefinition);
  }

  return {
    contextAlias: contextAliasOf(openAPI),
    contexts,
    aggregateTags: wowTags,
    schemaDocOverrides: schemaDocOverrides as ReadonlyMap<
      string,
      SchemaDocOverride
    >,
    warnings,
  };
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
