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

/**
 * The conventions of the OpenAPI documents a Wow service writes
 * (`wow-openapi`), in one place: how its tags name aggregates, which
 * operations carry commands, state, events and query fields, which schemas
 * are Wow's own and which wow-client type each maps to, which tags and path
 * parameters the generated clients leave out, and how a query client's
 * resource attribution follows from the command routes.
 *
 * Every rule here applies only to what Wow's metadata names, so a document
 * that is not a Wow one is left alone.
 */

import type { OpenAPI, Tag } from '@ahoo-wang/fetcher-openapi';
import type { AggregateDefinition, TagAliasAggregate } from './model';

/** The oldest Wow server whose OpenAPI metadata the generator reads fully. */
export const MINIMUM_WOW_VERSION = '8.10';

/** The `info` extension naming the bounded context the document serves. */
export const CONTEXT_ALIAS_EXTENSION = 'x-wow-context-alias';

/** The bounded context a document names, if it is a Wow service's. */
export function contextAliasOf(openAPI: OpenAPI): string | undefined {
  return openAPI.info[CONTEXT_ALIAS_EXTENSION];
}

// --- Aggregates and their operations -------------------------------------

/**
 * Reads an aggregate off a tag named `<contextAlias>.<aggregateName>`.
 *
 * @param tagName - The tag name
 * @returns `[contextAlias, aggregateName]`, or null when the tag names no aggregate
 */
export function isAliasAggregate(tagName: string): [string, string] | null {
  const parts = tagName.split('.');
  if (parts.length != 2 || parts[0].length === 0 || parts[1].length === 0) {
    return null;
  }
  return parts as [string, string];
}

/**
 * The aggregate a tag names, or null when it names none.
 *
 * @param tag - The tag
 */
export function tagToAggregate(tag: Tag): TagAliasAggregate | null {
  const parts = isAliasAggregate(tag.name);
  if (!parts) {
    return null;
  }
  return {
    tag,
    contextAlias: parts[0],
    aggregateName: parts[1],
  };
}

/**
 * The command an operation sends, read off its id
 * `<contextAlias>.<aggregateName>.<command>`.
 *
 * @param operationId - The operation id
 * @returns The command name, or null when the id has another shape
 */
export function operationIdToCommandName(operationId?: string): string | null {
  if (!operationId) {
    return null;
  }
  const parts = operationId.split('.');
  if (parts.length != 3) {
    return null;
  }
  return parts[2];
}

/** The operation that sends any command; it belongs to no aggregate. */
export const SEND_COMMAND_OPERATION_ID = 'wow.command.send';

/** The response every command operation answers with. */
export const COMMAND_OK_RESPONSE_REF = '#/components/responses/wow.CommandOk';

/** The operation id suffix of the operation that loads an aggregate's state. */
export const STATE_OPERATION_SUFFIX = '.snapshot_state.single';

/** The operation id suffix of the operation that lists an aggregate's events. */
export const EVENTS_OPERATION_SUFFIX = '.event.list_query';

/** The operation id suffix of the operation that counts snapshots by a condition. */
export const FIELDS_OPERATION_SUFFIX = '.snapshot.count';

/** The request body extension naming an aggregate's query fields (Wow 8.11.1+). */
export const QUERY_FIELDS_EXTENSION = 'x-wow-query-fields';

/**
 * The route segment of an aggregate, read off one of its snapshot routes:
 * `/tenant/{tenantId}/owner/{ownerId}/sales-order/snapshot/count` →
 * `sales-order`. It differs from the aggregate name when the aggregate sets
 * a resource name (`@AggregateRoute(resourceName = "sales-order")`).
 */
export const SNAPSHOT_ROUTE =
  /^(?:\/tenant\/\{tenantId\})?(?:\/owner\/\{ownerId\})?\/(.+?)\/snapshot(?:\/|$)/;

// --- Schemas ---------------------------------------------------------------

/**
 * The suffixes of the types Wow derives from an aggregate's state; the
 * wow-client generics stand for them, so they generate no model.
 */
export const AGGREGATED_SCHEMA_SUFFIXES: readonly string[] = [
  'MaterializedSnapshot',
  'MaterializedSnapshotPagedList',
  'MaterializedSnapshotCursorPage',
  'MaterializedSnapshotServerSentEventNonNullData',
  'PagedList',
  'ServerSentEventNonNullData',
  'Snapshot',
  'StateEvent',
];

/**
 * The names of the types Wow derives from an aggregate's state model.
 *
 * @param stateName - The name of the state model
 */
export function aggregatedTypeNames(stateName: string): string[] {
  return AGGREGATED_SCHEMA_SUFFIXES.map(suffix => stateName + suffix);
}

/**
 * Tells whether a schema is Wow's own, which wow-client already declares, so
 * it generates no model: every `wow.` schema but the paged lists and operator
 * maps of the query API, the aggregated query and event stream types, and
 * the types derived from an aggregate's state.
 *
 * @param schemaKey - The schema's component key
 * @param modelName - The name of the model the schema would generate, read
 *   only when the key alone does not decide
 * @param aggregatedNames - The names of the types derived from the aggregates' states
 */
export function isWowSchema(
  schemaKey: string,
  modelName: () => string,
  aggregatedNames: ReadonlySet<string>,
): boolean {
  if (
    schemaKey !== 'wow.api.query.PagedList' &&
    schemaKey.startsWith('wow.api.query.') &&
    schemaKey.endsWith('PagedList')
  ) {
    return false;
  }

  if (
    schemaKey.startsWith('wow.api.query.Operator') &&
    schemaKey.endsWith('Map')
  ) {
    return false;
  }

  if (
    schemaKey.startsWith('wow.') ||
    schemaKey.endsWith('AggregatedCondition') ||
    schemaKey.endsWith('AggregatedDomainEventStream') ||
    schemaKey.endsWith('AggregatedDomainEventStreamPagedList') ||
    schemaKey.endsWith('AggregatedDomainEventStreamCursorPage') ||
    schemaKey.endsWith(
      'AggregatedDomainEventStreamServerSentEventNonNullData',
    ) ||
    schemaKey.endsWith('AggregatedListQuery') ||
    schemaKey.endsWith('AggregatedPagedQuery') ||
    schemaKey.endsWith('AggregatedSingleQuery')
  ) {
    return true;
  }
  return aggregatedNames.has(modelName());
}

/** Import path for the WOW framework types */
export const IMPORT_WOW_PATH = '@ahoo-wang/wow-client';

/**
 * Import path for the deprecated Condition query model, which
 * `@ahoo-wang/wow-client` keeps on its `/legacy` subpath for Wow 8.10 servers.
 */
export const IMPORT_WOW_LEGACY_PATH = '@ahoo-wang/wow-client/legacy';

// compat(wow<9): the schemas of Wow < 8.11 query only through the Condition model, so the
// types they map to come from `/legacy`; the subpath and this set go in v10, and users regenerate.
/** The mapped type names that `IMPORT_WOW_LEGACY_PATH` exports. */
export const WOW_LEGACY_TYPES: ReadonlySet<string> = new Set([
  'Condition',
  'ConditionOptions',
  'ListQuery',
  'Operator',
  'PagedQuery',
]);

/** Mapping of OpenAPI schema keys to WOW framework types */
export const WOW_TYPE_MAPPING = {
  'wow.command.CommandResult': 'CommandResult',
  'wow.command.CommandResultArray': 'CommandResultArray',
  'wow.MessageHeaderSqlType': 'MessageHeaderSqlType',
  'wow.api.BindingError': 'BindingError',
  'wow.api.DefaultErrorInfo': 'ErrorInfo',
  'wow.api.RecoverableType': 'RecoverableType',
  'wow.api.command.DefaultDeleteAggregate': 'DeleteAggregate',
  'wow.api.command.DefaultRecoverAggregate': 'RecoverAggregate',
  'wow.api.abac.DefaultApplyResourceTags': 'ApplyResourceTags',
  'wow.api.messaging.FunctionInfoData': 'FunctionInfo',
  'wow.api.messaging.FunctionKind': 'FunctionKind',
  'wow.api.modeling.AggregateId': 'AggregateId',
  // Condition, ConditionOptions, ListQuery, Operator and PagedQuery name the deprecated
  // Condition API types of `/legacy` (see WOW_LEGACY_TYPES); a ListQuery or PagedQuery schema
  // that carries `filter` maps to FilterListQuery or FilterPagedQuery instead (wowTypeOf).
  'wow.api.query.Condition': 'Condition',
  'wow.api.query.ConditionOptions': 'ConditionOptions',
  'wow.api.query.ListQuery': 'ListQuery',
  'wow.api.query.Operator': 'Operator',
  'wow.api.query.PagedQuery': 'PagedQuery',
  'wow.api.query.Pagination': 'Pagination',
  'wow.api.query.Projection': 'Projection',
  'wow.api.query.Sort': 'FieldSort',
  'wow.api.query.Sort.Direction': 'SortDirection',
  'wow.api.query.DynamicDocument': 'DynamicDocument',
  'wow.api.query.DynamicDocumentArray': 'DynamicDocumentArray',
  'wow.command.CommandStage': 'CommandStage',
  'wow.command.SimpleWaitSignal': 'WaitSignal',
  'wow.configuration.Aggregate': 'Aggregate',
  'wow.configuration.BoundedContext': 'BoundedContext',
  'wow.configuration.WowMetadata': 'WowMetadata',
  'wow.modeling.DomainEvent': 'DomainEvent',
  'wow.openapi.BatchResult': 'BatchResult',
  'wow.messaging.CompensationTarget': 'CompensationTarget',
};

/**
 * The wow-client type a schema maps to, and the module that exports it.
 *
 * @param schemaKey - The schema's component key
 * @param properties - The schema's properties, if the caller has the schema:
 * a ListQuery or PagedQuery that carries `filter` is the filter model's
 * @returns The type, or undefined when the schema is not one of Wow's own
 */
export function wowTypeOf(
  schemaKey: string,
  properties?: Record<string, unknown>,
): { name: string; path: string } | undefined {
  let mappedType: string | undefined =
    WOW_TYPE_MAPPING[schemaKey as keyof typeof WOW_TYPE_MAPPING];
  if (properties && 'filter' in properties) {
    if (schemaKey === 'wow.api.query.ListQuery') {
      mappedType = 'FilterListQuery';
    } else if (schemaKey === 'wow.api.query.PagedQuery') {
      mappedType = 'FilterPagedQuery';
    }
  }
  if (!mappedType) return undefined;
  return {
    name: mappedType,
    path: WOW_LEGACY_TYPES.has(mappedType)
      ? IMPORT_WOW_LEGACY_PATH
      : IMPORT_WOW_PATH,
  };
}

// --- What the generated clients leave out ---------------------------------

/**
 * Tags whose operations generate no API client: Wow's own endpoints and
 * Spring's actuator. The tags of aggregates are left out as well; their
 * operations go to the command and query clients.
 */
export const IGNORED_API_CLIENT_TAGS: ReadonlySet<string> = new Set([
  'wow',
  'Actuator',
]);

/**
 * The resource-attribution path parameters Wow's CoSec interceptor fills,
 * which generated clients therefore leave out.
 */
export const RESOURCE_ATTRIBUTION_PATH_PARAMETERS: readonly string[] = [
  'tenantId',
  'ownerId',
];

/** The route prefix of a tenant's resources (wow-client `ResourceAttributionPathSpec.TENANT`). */
export const TENANT_PATH_PREFIX = '/tenant/{tenantId}';

/** The route prefix of an owner's resources (wow-client `ResourceAttributionPathSpec.OWNER`). */
export const OWNER_PATH_PREFIX = '/owner/{ownerId}';

/**
 * The resource attribution a query client of an aggregate uses, as the
 * `ResourceAttributionPathSpec` member it generates: the prefix most of the
 * aggregate's command routes start with, owner on a tie, none when no route
 * starts with either.
 *
 * @example
 * ```typescript
 * // commands at /tenant/{tenantId}/users, /tenant/{tenantId}/orders and /owner/{ownerId}/profile
 * inferPathSpecType(aggregate); // 'ResourceAttributionPathSpec.TENANT'
 * ```
 */
export function inferPathSpecType(
  aggregateDefinition: Pick<AggregateDefinition, 'commands'>,
): string {
  let tenantSpecCount = 0;
  let ownerSpecCount = 0;
  aggregateDefinition.commands.forEach(command => {
    if (command.path.startsWith(TENANT_PATH_PREFIX)) {
      tenantSpecCount += 1;
    }
    if (command.path.startsWith(OWNER_PATH_PREFIX)) {
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
