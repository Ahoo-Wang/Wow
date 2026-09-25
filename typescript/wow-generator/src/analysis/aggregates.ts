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

import { camelCase, resolvePropertyName } from '../naming/naming';
import type { OpenApiDocument } from '../openapi/document';
import { resolvePathParameterType } from '../openapi/operations';
import { isEmptyObject, resolveOptionalFields } from '../openapi/schemas';
import {
  inferPathSpecType,
  RESOURCE_ATTRIBUTION_PATH_PARAMETERS,
} from '../wow/conventions';
import type {
  AggregateDefinition,
  CommandDefinition,
  WowModel,
} from '../wow/model';
import {
  clientModulePath,
  resolveClassName,
  uniqueParameterName,
} from './clientNames';
import type {
  AggregateModel,
  CommandClientModel,
  CommandModel,
  QueryClientModel,
} from './model';
import { resolveContextDeclarationName, resolveModelInfo } from './modelInfo';

/**
 * The command and query clients of every aggregate that has state and query
 * fields, by bounded context in the order the Wow metadata lists them.
 */
export function analyzeAggregates(
  document: OpenApiDocument,
  wow: WowModel,
): AggregateModel[] {
  return [...wow.contexts.values()].flatMap(aggregates =>
    [...aggregates].map(aggregate => analyzeAggregate(document, aggregate)),
  );
}

function analyzeAggregate(
  document: OpenApiDocument,
  definition: AggregateDefinition,
): AggregateModel {
  const { contextAlias, aggregateName } = definition.aggregate;
  return {
    contextAlias,
    aggregateName,
    context: {
      alias: contextAlias,
      constantName: resolveContextDeclarationName(contextAlias),
    },
    commandClient: commandClient(document, definition),
    queryClient: queryClient(definition),
  };
}

function commandClient(
  document: OpenApiDocument,
  definition: AggregateDefinition,
): CommandClientModel {
  const aggregate = definition.aggregate;
  const members = endpointMemberNames(definition);
  return {
    file: clientModulePath(aggregate, 'commandClient'),
    endpointPathsName: resolveClassName(aggregate, 'CommandEndpointPaths'),
    className: resolveClassName(aggregate, 'CommandClient'),
    streamClassName: resolveClassName(aggregate, 'StreamCommandClient'),
    commands: [...definition.commands.values()].map((command): CommandModel => {
      const body = resolveModelInfo(command.schema.key);
      const used = new Set(['commandRequest', 'attributes']);
      return {
        path: command.path,
        endpointMember: members.get(command)!,
        httpMethod: command.method,
        methodName: commandMethodName(command),
        typeName: `${body.name}Command`,
        body,
        optionalFields: resolveOptionalFields(
          command.schema.schema,
          document.components,
        ),
        requestOptional: isEmptyObject(command.schema.schema),
        pathParameters: command.pathParameters
          .filter(
            parameter =>
              !RESOURCE_ATTRIBUTION_PATH_PARAMETERS.includes(parameter.name),
          )
          .map(parameter => ({
            name: uniqueParameterName(parameter.name, used),
            pathName: parameter.name,
            type: resolvePathParameterType(parameter),
          })),
        docs: [
          command.summary,
          command.description,
          `- operationId: \`${command.operation.operationId}\``,
          `- path: \`${command.path}\``,
        ],
      };
    }),
  };
}

/**
 * Names each command's endpoint member: its name upper-cased
 * (`add_cart_item` → `ADD_CART_ITEM`), with characters no identifier may
 * hold replaced by `_` (`pay-order` → `PAY_ORDER`), a leading digit
 * prefixed with `_`, and a number appended to the second of two commands
 * that end up alike.
 */
function endpointMemberNames(
  definition: AggregateDefinition,
): Map<CommandDefinition, string> {
  const names = new Map<CommandDefinition, string>();
  const used = new Set<string>();
  for (const command of definition.commands.values()) {
    let base = command.name.toUpperCase().replace(/[^\p{L}\p{N}_$]/gu, '_');
    if (!base || /^\p{N}/u.test(base)) base = `_${base}`;
    let name = base;
    for (let suffix = 2; used.has(name); suffix++) name = `${base}_${suffix}`;
    used.add(name);
    names.set(command, name);
  }
  return names;
}

/**
 * The method a command generates: its name camel-cased, as
 * `add_cart_item` → `addCartItem`.
 */
function commandMethodName(definition: CommandDefinition): string {
  const name = camelCase(definition.name) || '_';
  return /^\p{N}/u.test(name) ? `_${name}` : name;
}

function queryClient(definition: AggregateDefinition): QueryClientModel {
  const aggregate = definition.aggregate;
  const factoryPrefix = camelCase(aggregate.aggregateName) || '_';
  return {
    file: clientModulePath(aggregate, 'queryClient'),
    // aggregateName is the route segment the query paths use, which the
    // aggregate's resource name can make differ from its name.
    resourceName: definition.resourceName,
    resourceAttribution: inferPathSpecType(definition),
    state: resolveModelInfo(definition.state.key),
    fields: resolveModelInfo(definition.fields.key),
    eventTitlesName: resolveClassName(aggregate, 'DomainEventTypeMapTitle'),
    eventTypeName: resolveClassName(aggregate, 'DomainEventType'),
    events: [...definition.events.values()].map(event => ({
      memberName: resolvePropertyName(event.name),
      title: event.title,
      body: resolveModelInfo(event.schema.key),
    })),
    factoryName: `${/^\p{N}/u.test(factoryPrefix) ? '_' : ''}${factoryPrefix}QueryClientFactory`,
  };
}
