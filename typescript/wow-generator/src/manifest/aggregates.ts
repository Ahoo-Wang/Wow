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

import { camelCase, toIdentifier, upperSnakeCase } from '../naming/naming';
import {
  type JsonSchema,
  type ManifestAggregate,
  type ManifestCommand,
  type ManifestContext,
  isObject,
  schemaKey,
} from './document';
import type {
  AccessModel,
  AggregateModel,
  CommandModel,
  LifecycleModel,
  Route,
} from './model';
import { aggregateTypeName } from './names';
import type { SchemaTypes } from './schemaTypes';
import type { TextNumbers, TextNumbersModel } from './textNumbers';

/** The TypeScript type of an access attribute's value of the Manifest's `type`. */
const ACCESS_TYPES: Readonly<Record<string, string>> = {
  string: 'string',
  integer: 'string',
  boolean: 'boolean',
};

/** The access attributes of `aggregate`, typed. A type this generator does not know takes any scalar. */
function accessOf(aggregate: ManifestAggregate): AccessModel[] {
  return (aggregate.access ?? []).map(attribute => ({
    name: attribute.name,
    type: ACCESS_TYPES[attribute.type] ?? 'string | number | boolean',
    multiple: attribute.multiple ?? false,
    integer: attribute.type === 'integer',
  }));
}

const LIFECYCLE_BODIES: Readonly<Record<string, string>> = {
  'wow.lifecycle.TransferOwner': 'TransferOwner',
  'wow.lifecycle.TransferSpace': 'TransferSpace',
};

/** The lifecycle commands `@ahoo-wang/wow-client` sends (decision D113). */
const LIFECYCLE_COMMANDS: ReadonlySet<string> = new Set([
  'wow.lifecycle.DeleteAggregate',
  'wow.lifecycle.RestoreAggregate',
  'wow.lifecycle.TransferOwner',
  'wow.lifecycle.TransferSpace',
]);

/** Whether `schema` requires nothing but `omitted`, a property the method does not take. */
function emptyBody(
  types: SchemaTypes,
  schema: JsonSchema | undefined,
  omitted?: string,
): boolean {
  if (schema === undefined) return true;
  const resolved = types.resolve(schema);
  return (
    !Array.isArray(resolved.required) ||
    resolved.required.every(name => name === omitted)
  );
}

/** Whether the property `name` of `schema` is computed: `readOnly`, written by the service and never read back. */
function computedProperty(
  types: SchemaTypes,
  schema: JsonSchema | undefined,
  name: string,
): boolean {
  if (schema === undefined) return false;
  const properties = types.resolve(schema).properties;
  const property = isObject(properties) ? properties[name] : undefined;
  return isObject(property) && property.readOnly === true;
}

/**
 * The domain commands of `aggregate` a client sends: its system commands left
 * out, and its internal ones (`@InternalCommand`), which only the platform
 * sends and the public port does not serve.
 */
function domainCommands(
  aggregate: ManifestAggregate,
): readonly ManifestCommand[] {
  return (aggregate.commands ?? []).filter(
    command => command.system !== true && command.internal !== true,
  );
}

/** The lifecycle commands `aggregate` lists among its system commands. */
function lifecycleCommands(
  aggregate: ManifestAggregate,
): readonly ManifestCommand[] {
  return (aggregate.commands ?? []).filter(
    command =>
      command.system === true && command.name.startsWith('wow.lifecycle.'),
  );
}

export function aggregateRoots(
  types: SchemaTypes,
  aggregate: ManifestAggregate,
): string[] {
  return [
    aggregate.state.schema,
    ...domainCommands(aggregate).map(command => command.schema),
    ...(aggregate.events ?? []).map(event => event.schema),
  ]
    .map(schemaKey)
    .filter((key): key is string => key !== undefined)
    .concat(lifecycleBodies(types, aggregate));
}

/**
 * The lifecycle commands with a body that `aggregate` lists and whose body
 * the Manifest's `$defs` holds, keyed by the command's name (roadmap 5a.1c).
 */
function lifecycleBodies(
  types: SchemaTypes,
  aggregate: ManifestAggregate,
): string[] {
  return lifecycleCommands(aggregate)
    .map(command => command.name)
    .filter(
      name => LIFECYCLE_BODIES[name] !== undefined && types.schemaOf(name),
    );
}

/** The routes of `command`, as the Manifest lists them. */
function routesOf(command: ManifestCommand): readonly Route[] {
  return (command.routes ?? []).map(route => ({
    method: route.method,
    path: route.path,
  }));
}

function typeOfRef(types: SchemaTypes, ref: string, where: string): string {
  const key = schemaKey(ref);
  return key === undefined ? 'unknown' : types.refType(key, where);
}
export function aggregateModel(
  types: SchemaTypes,
  numbers: TextNumbers,
  context: ManifestContext,
  aggregate: ManifestAggregate,
): AggregateModel {
  const where = `${context.name}.${aggregate.name}`;
  const typeName = aggregateTypeName(aggregate);
  const idSource = aggregate.idSource ?? 'assigned';
  const owned = idSource === 'owner';
  const generated = new Set(lifecycleBodies(types, aggregate));
  const lifecycle: LifecycleModel[] = lifecycleCommands(aggregate)
    .filter(command => LIFECYCLE_COMMANDS.has(command.name))
    .map(command => {
      const name = command.name;
      const simple = name.split('.').pop()!;
      const description = types.schemaOf(name)?.description;
      const method = toIdentifier(camelCase(simple));
      const own =
        owned && (command.routes ?? []).some(r => !r.path.includes('{id}'));
      return {
        name,
        member: upperSnakeCase(simple),
        method,
        routes: routesOf(command),
        ...(own ? { ownMethod: `${method}Own` } : {}),
        ...(generated.has(name)
          ? { bodyType: types.refType(name, where), bodyGenerated: true }
          : LIFECYCLE_BODIES[name]
            ? { bodyType: LIFECYCLE_BODIES[name] }
            : {}),
        ...(typeof description === 'string' ? { description } : {}),
      };
    });
  const taken = new Set(
    lifecycle.flatMap(item => [item.method, item.ownMethod ?? item.method]),
  );
  const commands: CommandModel[] = domainCommands(aggregate).map(command => {
    let method = toIdentifier(camelCase(command.name));
    if (taken.has(method) || method === 'gateway') method = `${method}Command`;
    taken.add(method);
    const routes = (command.routes ?? []).filter(
      route => route.method === 'POST',
    );
    const byId = routes.find(route => route.path.includes('{id}'));
    const unaddressed = routes.find(route => !route.path.includes('{id}'));
    // Of an aggregate whose id is its owner's, the route by no id is the caller's own, but a mounted command's,
    // whose aggregate a transformer finds.
    let ownMethod: string | undefined;
    if (owned && byId && unaddressed && !(command.mounted ?? false)) {
      ownMethod = `${method}Own`;
      if (taken.has(ownMethod)) ownMethod = `${ownMethod}Command`;
      taken.add(ownMethod);
    }
    const key = schemaKey(command.schema);
    const bodySchema = key === undefined ? undefined : types.schemaOf(key);
    const idProperty = command.idProperty;
    const idComputed =
      idProperty !== undefined &&
      computedProperty(types, bodySchema, idProperty);
    return {
      name: command.name,
      method,
      routes: routesOf(command),
      ...(byId ? { byIdPath: { path: byId.path } } : {}),
      ...(unaddressed ? { unaddressedPath: { path: unaddressed.path } } : {}),
      ...(ownMethod === undefined ? {} : { ownMethod }),
      bodyType: typeOfRef(types, command.schema, `${where}.${command.name}`),
      bodyOptional: emptyBody(
        types,
        bodySchema,
        idComputed ? idProperty : undefined,
      ),
      ...(idProperty === undefined ? {} : { idProperty }),
      ...(idComputed ? { idComputed } : {}),
      creation: command.creation ?? 'none',
      mounted: command.mounted ?? false,
      produces: command.produces ?? [],
      ...(command.description ? { description: command.description } : {}),
      ...(numbers.marks({ $ref: command.schema }, command.schema) === undefined
        ? {}
        : { bodyNumbers: true }),
    };
  });
  const stateNumbers = numbers.marks(
    { $ref: aggregate.state.schema },
    aggregate.state.schema,
  );
  const eventNumbers = Object.fromEntries(
    (aggregate.events ?? []).flatMap(event => {
      const mark = numbers.marks({ $ref: event.schema }, event.schema);
      return mark === undefined
        ? []
        : [[wireNameOf(context, aggregate, event.name), mark]];
    }),
  );
  const commandNumbers = Object.fromEntries(
    domainCommands(aggregate).flatMap(command => {
      const mark = numbers.marks({ $ref: command.schema }, command.schema);
      return mark === undefined ? [] : [[command.name, mark]];
    }),
  );
  const access = accessOf(aggregate);
  const accessNumbers: Record<string, TextNumbersModel> = Object.fromEntries(
    access
      .filter(attribute => attribute.integer)
      .map(attribute => [
        attribute.name,
        attribute.multiple ? { members: { '*': true } } : true,
      ]),
  );
  const textNumbers = {
    ...(stateNumbers === undefined ? {} : { state: stateNumbers }),
    ...(Object.keys(eventNumbers).length === 0 ? {} : { events: eventNumbers }),
    ...(Object.keys(commandNumbers).length === 0
      ? {}
      : { commands: commandNumbers }),
    ...(Object.keys(accessNumbers).length === 0
      ? {}
      : { access: { members: accessNumbers } }),
  };
  const stateKey = schemaKey(aggregate.state.schema);
  const stateSchema =
    stateKey === undefined ? undefined : types.schemaOf(stateKey);
  return {
    context: context.name,
    name: aggregate.name,
    ...(aggregate.description ? { description: aggregate.description } : {}),
    typeName,
    directory: `${context.name}/${aggregate.name}`,
    ownership: aggregate.ownership ?? 'owner',
    idSource,
    snapshot: aggregate.snapshot?.enabled ?? true,
    stateType: typeOfRef(types, aggregate.state.schema, `${where} state`),
    stateFields:
      stateSchema === undefined
        ? []
        : types.fields({ $ref: aggregate.state.schema }, 'state'),
    commands,
    events: (aggregate.events ?? []).map(event => ({
      name: event.name,
      wireName: wireNameOf(context, aggregate, event.name),
      revision: event.revision ?? 1,
      type: typeOfRef(types, event.schema, `${where}.${event.name}`),
    })),
    lifecycle,
    queryModels: (aggregate.queries ?? []).map(query => query.model),
    access,
    ...(Object.keys(textNumbers).length === 0 ? {} : { textNumbers }),
  };
}

/** The name the server sends an event by: `context.aggregate.Event`, or a name already qualified. */
function wireNameOf(
  context: ManifestContext,
  aggregate: ManifestAggregate,
  name: string,
): string {
  return name.includes('.')
    ? name
    : `${context.name}.${aggregate.name}.${name}`;
}
