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

import { toIdentifier, upperSnakeCase } from '../naming/naming';
import {
  type JsonSchema,
  type ManifestAggregate,
  type ManifestCommand,
  type ManifestContext,
  isObject,
  requiresOf,
  schemaKey,
  typesOf,
} from './document';
import type {
  AccessModel,
  AggregateModel,
  CommandModel,
  LifecycleModel,
  Route,
  RoutingCommandModel,
} from './model';
import { aggregateTypeName, LIFECYCLE_BODIES, memberName } from './names';
import type { Position, SchemaTypes } from './schemaTypes';
import type { TextNumbers, TextNumbersModel } from './textNumbers';

/** The TypeScript type of an access attribute's value of the Manifest's `type`. */
const ACCESS_TYPES: Readonly<Record<string, string>> = {
  string: 'string',
  integer: 'string',
  boolean: 'boolean',
};

/**
 * The access attributes of `aggregate`, typed. An aggregate with a type this
 * generator does not know is left out before (`knownValues`).
 */
function accessOf(aggregate: ManifestAggregate): AccessModel[] {
  return (aggregate.access ?? []).map(attribute => ({
    name: attribute.name,
    type: ACCESS_TYPES[attribute.type] as string,
    multiple: attribute.multiple ?? false,
    integer: attribute.type === 'integer',
  }));
}

/** The lifecycle commands `@ahoo-wang/wow-client` sends (decision D113). */
const LIFECYCLE_COMMANDS: ReadonlySet<string> = new Set([
  'wow.lifecycle.DeleteAggregate',
  'wow.lifecycle.RestoreAggregate',
  'wow.lifecycle.TransferOwner',
  'wow.lifecycle.TransferSpace',
]);

/** The members of a generated command client its commands' methods never take. */
const COMMAND_CLIENT_MEMBERS = ['commands', 'constructor', 'then'];

/**
 * The method of a command named `method` by its name, apart from every name
 * in `taken`: the method, its stream twin (`…Stream`) and, with `own`, the
 * method to the caller's own and its twin (`…Own`, `…OwnStream`). A name any
 * of them meets takes `Command`, then `Command2` and so on, so a command
 * `StartStream` beside `Start`, or one named `Client`, gets a method of its
 * own. Every name it takes is added to `taken`.
 */
function commandMethod(
  method: string,
  own: boolean,
  taken: Set<string>,
): string {
  const namesOf = (name: string) =>
    own
      ? [name, `${name}Stream`, `${name}Own`, `${name}OwnStream`]
      : [name, `${name}Stream`];
  let chosen = method;
  for (
    let suffix = 1;
    namesOf(chosen).some(name => taken.has(name));
    suffix += 1
  ) {
    chosen = `${method}Command${suffix === 1 ? '' : suffix}`;
  }
  for (const name of namesOf(chosen)) taken.add(name);
  return chosen;
}

/**
 * Whether a command's body `schema` requires nothing a caller sends, so its
 * method can default it to `{}`: `{}` is a value of its type.
 */
function emptyBody(
  types: SchemaTypes,
  schema: JsonSchema | undefined,
): boolean {
  return schema === undefined || admitsEmpty(types, schema, new Set());
}

/**
 * Whether `{}` is a value of `schema` as a caller sends it: it requires no
 * property and is an object, through its `$ref`s, every part of an `allOf` admits it, and so
 * does some alternative of an `anyOf` or a `oneOf`. A sealed body, `anyOf`
 * of subtypes that each require their discriminant, admits none. A
 * definition already on the path admits it, as far as it is concerned.
 */
function admitsEmpty(
  types: SchemaTypes,
  schema: JsonSchema,
  seen: ReadonlySet<string>,
): boolean {
  const key =
    typeof schema.$ref === 'string' ? schemaKey(schema.$ref) : undefined;
  if (key !== undefined) {
    const target = types.schemaOf(key);
    if (target === undefined || seen.has(key)) return true;
    return admitsEmpty(types, target, new Set([...seen, key]));
  }
  const kinds = typesOf(schema);
  if (kinds.length > 0 && !kinds.includes('object')) return false;
  if ('const' in schema || Array.isArray(schema.enum)) return false;
  if (types.required(schema, 'input').size > 0) return false;
  const parts = (member: string): JsonSchema[] =>
    Array.isArray(schema[member])
      ? (schema[member] as unknown[]).filter(isObject)
      : [];
  if (!parts('allOf').every(part => admitsEmpty(types, part, seen))) {
    return false;
  }
  for (const member of ['anyOf', 'oneOf']) {
    const alternatives = parts(member);
    if (
      alternatives.length > 0 &&
      !alternatives.some(part => admitsEmpty(types, part, seen))
    ) {
      return false;
    }
  }
  return true;
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

/**
 * The keys a caller sends of `aggregate`: its domain commands' bodies. A
 * lifecycle body is always `@ahoo-wang/wow-client`'s (`LIFECYCLE_BODIES`),
 * whatever `$defs` holds.
 */
export function aggregateInputs(aggregate: ManifestAggregate): string[] {
  return domainCommands(aggregate)
    .map(command => schemaKey(command.schema))
    .filter((key): key is string => key !== undefined);
}

export function aggregateRoots(aggregate: ManifestAggregate): string[] {
  return [
    aggregate.state.schema,
    ...domainCommands(aggregate).map(command => command.schema),
    ...(aggregate.events ?? []).map(event => event.schema),
  ]
    .map(schemaKey)
    .filter((key): key is string => key !== undefined);
}

/**
 * The commands of `commands` with routes, as the routing part of a generated
 * client lists them: an internal one, which the public port does not serve,
 * left out.
 */
export function routingCommands(
  commands: readonly ManifestCommand[] | undefined,
): RoutingCommandModel[] {
  return (commands ?? [])
    .filter(
      command => command.internal !== true && (command.routes ?? []).length > 0,
    )
    .map(command => ({
      name: command.name,
      ...(aliasesOf(command).length > 0 ? { aliases: aliasesOf(command) } : {}),
      routes: routesOf(command),
      ...(command.idProperty === undefined
        ? {}
        : { idProperty: command.idProperty }),
    }));
}

/** The former names of `command`: the names its `aliases` lists, none when it lists none. */
function aliasesOf(command: ManifestCommand): string[] {
  const aliases: unknown = command.aliases;
  return Array.isArray(aliases)
    ? aliases.filter((alias): alias is string => typeof alias === 'string')
    : [];
}

/** The routes of `command`, as the Manifest lists them. */
function routesOf(command: ManifestCommand): readonly Route[] {
  return (command.routes ?? []).map(route => ({
    method: route.method,
    path: route.path,
  }));
}

function typeOfRef(
  types: SchemaTypes,
  ref: string,
  where: string,
  position?: Position,
): string {
  const key = schemaKey(ref);
  return key === undefined ? 'unknown' : types.refType(key, where, position);
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
  const lifecycle: LifecycleModel[] = lifecycleCommands(aggregate)
    .filter(command => LIFECYCLE_COMMANDS.has(command.name))
    .map(command => {
      const name = command.name;
      const simple = name.split('.').pop()!;
      const description = types.schemaOf(name)?.description;
      const method = toIdentifier(memberName(simple));
      const own =
        owned && (command.routes ?? []).some(r => !r.path.includes('{id}'));
      return {
        name,
        member: upperSnakeCase(simple),
        method,
        routes: routesOf(command),
        ...(own ? { ownMethod: `${method}Own` } : {}),
        ...(LIFECYCLE_BODIES[name] ? { bodyType: LIFECYCLE_BODIES[name] } : {}),
        ...(typeof description === 'string' ? { description } : {}),
      };
    });
  const taken = new Set([
    ...COMMAND_CLIENT_MEMBERS,
    ...lifecycle.flatMap(item => [item.method, item.ownMethod ?? item.method]),
  ]);
  const commands: CommandModel[] = domainCommands(aggregate).map(command => {
    const routes = (command.routes ?? []).filter(
      route => route.method === 'POST',
    );
    const byId = routes.find(route => route.path.includes('{id}'));
    const unaddressed = routes.find(route => !route.path.includes('{id}'));
    // Of an aggregate whose id is its owner's, the route by no id is the caller's own, but a mounted command's,
    // whose aggregate a transformer finds.
    const own = owned && byId && unaddressed && !(command.mounted ?? false);
    const method = commandMethod(
      toIdentifier(memberName(command.name)),
      own === true,
      taken,
    );
    const ownMethod = own ? `${method}Own` : undefined;
    const key = schemaKey(command.schema);
    const bodySchema = key === undefined ? undefined : types.schemaOf(key);
    const idProperty = command.idProperty;
    const idComputed =
      idProperty !== undefined &&
      computedProperty(types, bodySchema, idProperty);
    return {
      name: command.name,
      aliases: aliasesOf(command),
      method,
      routes: routesOf(command),
      ...(byId ? { byIdPath: { path: byId.path } } : {}),
      ...(unaddressed ? { unaddressedPath: { path: unaddressed.path } } : {}),
      ...(ownMethod === undefined ? {} : { ownMethod }),
      bodyType: typeOfRef(
        types,
        command.schema,
        `${where}.${command.name}`,
        'input',
      ),
      bodyOptional: emptyBody(types, bodySchema),
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
      // By the full name, as the events are and as a request names it.
      return mark === undefined
        ? []
        : [[wireNameOf(context, aggregate, command.name), mark]];
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
    ...requiresOf(aggregate.requires),
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
    // Domain commands first, then system ones, as the routing part has always listed them.
    routing: [
      ...routingCommands(domainCommands(aggregate)),
      ...routingCommands(
        (aggregate.commands ?? []).filter(command => command.system === true),
      ),
    ],
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
