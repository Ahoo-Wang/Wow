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
  camelCase,
  pascalCase,
  quoteStringLiteral,
  resolvePropertyName,
  toIdentifier,
  toTypeIdentifier,
  upperSnakeCase,
} from '../naming/naming';
import { compareNames } from '../naming/order';
import {
  type JsonSchema,
  type ManifestAggregate,
  type ManifestCommand,
  type ManifestContext,
  type ManifestDocument,
  schemaKey,
} from './document';

/**
 * The formats of numbers a Wow Manifest marks for reading from their text,
 * typed `string` (review R4-Q, Q-9; decision D254): an integer past
 * JavaScript's safe range (`int64`, `bigint`) and an exact decimal
 * (`decimal`). The wire keeps JSON numbers; `@ahoo-wang/wow-client` reads these
 * from the response's source text. A plain OpenAPI document keeps `number`.
 */
const TEXT_NUMBER_FORMATS: ReadonlySet<string> = new Set([
  'int64',
  'bigint',
  'decimal',
]);

/** A property of a generated interface. */
export interface PropertyModel {
  /** As written in the interface: quoted when it is no identifier. */
  readonly name: string;
  readonly type: string;
  readonly optional: boolean;
  readonly description?: string;
}

/** A type declared for a schema of `$defs`. */
export interface TypeModel {
  /** The `$defs` key. */
  readonly key: string;
  readonly name: string;
  /** The file it is declared in, relative to the output directory. */
  readonly file: string;
  readonly description?: string;
  /** An interface's properties, or undefined for a type alias. */
  readonly properties?: readonly PropertyModel[];
  /** A type alias's type; or, for an interface, its index signature's. */
  readonly type?: string;
  /** The type names it refers to. */
  readonly references: readonly string[];
}

/**
 * The numbers of a value read and written as their exact text, as
 * `@ahoo-wang/wow-client`'s `TextNumbers` holds them: `true` at such a number,
 * else its members' marks by name, `'*'` for every item or map value.
 */
export type TextNumbersModel =
  true | { readonly [member: string]: TextNumbersModel };

/** A command of an aggregate client. */
export interface CommandModel {
  readonly name: string;
  readonly method: string;
  /** Its routes, as the Manifest lists them: the client sends it by them. */
  readonly routes: readonly Route[];
  /** Its route by id, and its route without one. */
  readonly byIdPath?: { readonly path: string };
  readonly unaddressedPath?: { readonly path: string };
  /** The body's type name, or undefined when its schema is unknown. */
  readonly bodyType: string;
  /** The body may be left out: its schema requires nothing. */
  readonly bodyOptional: boolean;
  readonly creation: string;
  readonly mounted: boolean;
  readonly produces: readonly string[];
  /** What the command's class says it is, as the Manifest describes it. */
  readonly description?: string;
  /** Its body has exact numbers, under `textNumbers.commands` of its aggregate. */
  readonly bodyNumbers?: boolean;
}

/** A route of the Manifest: a method and a path, `{id}` the aggregate id. */
export interface Route {
  readonly method: string;
  readonly path: string;
}

/** A lifecycle command an aggregate lists. */
export interface LifecycleModel {
  /** `wow.lifecycle.DeleteAggregate`, … */
  readonly name: string;
  /** The `LifecycleCommands` member. */
  readonly member: string;
  readonly method: string;
  /** Its routes, as the Manifest lists them. */
  readonly routes: readonly Route[];
  /**
   * The body's type, or undefined for a command without one: the type
   * generated from the Manifest's `$defs` entry of the command's name when it
   * holds one (`bodyGenerated`), else `@ahoo-wang/wow-client`'s.
   */
  readonly bodyType?: string;
  /** The body's type is generated from the Manifest, not `@ahoo-wang/wow-client`'s. */
  readonly bodyGenerated?: boolean;
  /** What the Manifest's schema of the body says the command does. */
  readonly description?: string;
}

/** An aggregate and its clients. */
export interface AggregateModel {
  readonly context: string;
  readonly name: string;
  readonly description?: string;
  /** The type-name prefix: `Product`. */
  readonly typeName: string;
  /** The directory of its modules, relative to the output directory. */
  readonly directory: string;
  readonly ownership: string;
  readonly snapshot: boolean;
  readonly stateType: string;
  readonly stateFields: readonly string[];
  readonly commands: readonly CommandModel[];
  readonly events: readonly {
    /** The name the Manifest declares: `ProductCreated`. */
    readonly name: string;
    /**
     * The event type the server names it by on the wire (08-data-protocols §2):
     * `context.aggregate.EventName`, or the declared name when it is already
     * qualified.
     */
    readonly wireName: string;
    readonly revision: number;
    readonly type: string;
  }[];
  readonly lifecycle: readonly LifecycleModel[];
  readonly queryModels: readonly string[];
  /** The exact numbers of its state, events (by wire name) and commands; none when it has none. */
  readonly textNumbers?: {
    readonly state?: TextNumbersModel;
    readonly events?: Readonly<Record<string, TextNumbersModel>>;
    readonly commands?: Readonly<Record<string, TextNumbersModel>>;
  };
}

/** A bounded context and what its modules declare. */
export interface ContextModel {
  readonly name: string;
  readonly description?: string;
  /** The type-name prefix: `EshopCatalog`. */
  readonly typeName: string;
  /** The constant prefix: `ESHOP_CATALOG`. */
  readonly constantName: string;
  readonly directory: string;
  readonly errors: readonly {
    readonly code: string;
    readonly status: number;
    readonly description?: string;
  }[];
  readonly subscriptions: readonly string[];
  readonly aggregates: readonly AggregateModel[];
}

/** What a Manifest generates, decided before anything is written. */
export interface ManifestModel {
  readonly service?: string;
  readonly types: readonly TypeModel[];
  readonly contexts: readonly ContextModel[];
}

const LIFECYCLE_BODIES: Readonly<Record<string, string>> = {
  'wow.lifecycle.TransferOwner': 'TransferOwner',
  'wow.lifecycle.TransferSpace': 'TransferSpace',
  'wow.lifecycle.ApplyTags': 'ApplyTags',
};

/** The lifecycle commands `@ahoo-wang/wow-client` sends (decision D113). */
const LIFECYCLE_COMMANDS: ReadonlySet<string> = new Set([
  'wow.lifecycle.DeleteAggregate',
  'wow.lifecycle.RestoreAggregate',
  'wow.lifecycle.TransferOwner',
  'wow.lifecycle.TransferSpace',
  'wow.lifecycle.ApplyTags',
]);

/** The deepest `state.*` path listed as a query field. */
const MAX_FIELD_DEPTH = 4;

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function typesOf(schema: JsonSchema): string[] {
  const type = schema.type;
  if (typeof type === 'string') return [type];
  if (Array.isArray(type)) return type.filter(t => typeof t === 'string');
  return [];
}

/** A union or an intersection, its members set apart when they are compound. */
function join(members: string[], separator: ' | ' | ' & '): string {
  const unique = [...new Set(members)];
  if (unique.length === 1) return unique[0];
  return unique
    .map(member =>
      / [|&] /.test(member) && !member.startsWith('{') ? `(${member})` : member,
    )
    .join(separator);
}

function literal(value: unknown): string {
  return typeof value === 'string'
    ? quoteStringLiteral(value)
    : JSON.stringify(value);
}

/**
 * Turns the schemas of a Manifest into types: names for `$defs` keys and
 * type text for any schema.
 */
class SchemaTypes {
  readonly names = new Map<string, string>();
  readonly warnings: string[] = [];

  constructor(readonly defs: Readonly<Record<string, JsonSchema>>) {}

  /**
   * Whether `key` is a parameterized type's, such as
   * `java.util.Map(java.lang.String,java.lang.Object)`: its schema is written
   * in place (`Record<string, unknown>`), never declared, since a name taken
   * from its last type argument would shadow a global such as `Object`.
   */
  inlined(key: string): boolean {
    return key.includes('(');
  }

  /** Names each key after its simple class name, qualified as far as needed to be unique. */
  name(keys: readonly string[]): void {
    const pending = new Set(keys);
    for (let depth = 0; pending.size > 0; depth++) {
      const byName = new Map<string, string[]>();
      for (const key of pending.keys()) {
        const segments = key.split('.');
        const simple = segments[segments.length - 1].split('$');
        const qualifier = segments.slice(
          Math.max(0, segments.length - 1 - depth),
          segments.length - 1,
        );
        const name = toTypeIdentifier(
          [...qualifier, ...simple].map(part =>
            /^[a-z]/.test(part) ? pascalCase(part) : part,
          ),
        );
        byName.set(name, [...(byName.get(name) ?? []), key]);
      }
      const taken = new Set(this.names.values());
      for (const [name, owners] of byName) {
        // A key whose packages are used up takes the name, numbered when it
        // is taken; the others take it alone, or qualify one package more.
        const exhausted = owners.filter(
          key => key.split('.').length - 1 <= depth,
        );
        const assigned =
          owners.length === 1 && !taken.has(name) ? owners : exhausted;
        for (const key of assigned) {
          let unique = name;
          for (let index = 2; taken.has(unique); index++) {
            unique = `${name}${index}`;
          }
          taken.add(unique);
          this.names.set(key, unique);
          pending.delete(key);
        }
      }
    }
  }

  /** The schema of a key, or undefined with a warning. */
  schemaOf(key: string): JsonSchema | undefined {
    const schema = this.defs[key];
    return isObject(schema) ? schema : undefined;
  }

  /** The schema a reference resolves to, through `$ref`s. */
  resolve(schema: JsonSchema, seen = new Set<string>()): JsonSchema {
    const ref = schema.$ref;
    if (typeof ref !== 'string') return schema;
    const key = schemaKey(ref);
    if (key === undefined || seen.has(key)) return schema;
    const target = this.schemaOf(key);
    return target ? this.resolve(target, new Set([...seen, key])) : schema;
  }

  /** The keys a schema refers to, directly. */
  references(schema: unknown, into: Set<string> = new Set()): Set<string> {
    if (Array.isArray(schema)) {
      schema.forEach(item => this.references(item, into));
    } else if (isObject(schema)) {
      for (const [member, value] of Object.entries(schema)) {
        if (member === '$ref' && typeof value === 'string') {
          const key = schemaKey(value);
          if (key !== undefined) into.add(key);
        } else if (member !== 'enum' && member !== 'const') {
          this.references(value, into);
        }
      }
    }
    return into;
  }

  /** The keys a schema refers to by name: an inlined key's own, in its place. */
  namedReferences(schema: unknown): Set<string> {
    const named = new Set<string>();
    for (const key of this.references(schema)) {
      const target = this.schemaOf(key);
      if (target !== undefined && this.inlined(key)) {
        this.namedReferences(target).forEach(inner => named.add(inner));
      } else {
        named.add(key);
      }
    }
    return named;
  }

  /** Every key reachable from `roots`. */
  closure(roots: Iterable<string>): string[] {
    const reached = new Set<string>();
    const pending = [...roots];
    while (pending.length > 0) {
      const key = pending.pop()!;
      if (reached.has(key)) continue;
      reached.add(key);
      const schema = this.schemaOf(key);
      if (schema) pending.push(...this.references(schema));
    }
    return [...reached];
  }

  /** The type of a reference to `key`. */
  refType(key: string, where: string): string {
    const schema = this.schemaOf(key);
    if (schema !== undefined && this.inlined(key)) {
      return this.type(schema, where);
    }
    const name = this.names.get(key);
    if (name !== undefined && this.schemaOf(key)) return name;
    this.warnings.push(
      `${where} refers to the schema ${key}, which the Manifest's $defs does not hold; it is typed as unknown.`,
    );
    return 'unknown';
  }

  /** The type text of a schema. */
  type(schema: unknown, where: string): string {
    if (schema === true || schema === undefined) return 'unknown';
    if (schema === false) return 'never';
    if (!isObject(schema)) return 'unknown';
    if (typeof schema.$ref === 'string') {
      const key = schemaKey(schema.$ref);
      return key === undefined ? 'unknown' : this.refType(key, where);
    }
    if ('const' in schema) return literal(schema.const);
    if (Array.isArray(schema.enum)) {
      return schema.enum.length === 0
        ? 'never'
        : join(schema.enum.map(literal), ' | ');
    }
    for (const [member, separator] of [
      ['oneOf', ' | '],
      ['anyOf', ' | '],
      ['allOf', ' & '],
    ] as const) {
      const options = schema[member];
      if (Array.isArray(options) && options.length > 0) {
        return join(
          options.map(option => this.type(option, where)),
          separator,
        );
      }
    }
    const types = typesOf(schema);
    if (types.length === 0) {
      return isObject(schema.properties)
        ? this.objectType(schema, where)
        : 'unknown';
    }
    return join(
      types.map(type => this.primitive(type, schema, where)),
      ' | ',
    );
  }

  private primitive(type: string, schema: JsonSchema, where: string): string {
    switch (type) {
      case 'string':
        return 'string';
      case 'integer':
      case 'number':
        return typeof schema.format === 'string' &&
          TEXT_NUMBER_FORMATS.has(schema.format)
          ? 'string'
          : 'number';
      case 'boolean':
        return 'boolean';
      case 'null':
        return 'null';
      case 'array': {
        const item =
          schema.items === undefined
            ? 'unknown'
            : this.type(schema.items, where);
        return / [|&] /.test(item) ? `(${item})[]` : `${item}[]`;
      }
      case 'object':
        return this.objectType(schema, where);
      default:
        return 'unknown';
    }
  }

  /** An inline object type. */
  private objectType(schema: JsonSchema, where: string): string {
    const properties = this.properties(schema, where);
    const extra = this.additional(schema, where);
    if (properties.length === 0) {
      return `Record<string, ${extra ?? 'unknown'}>`;
    }
    const members = properties.map(
      property =>
        `${property.name}${property.optional ? '?' : ''}: ${property.type}`,
    );
    // A typed index signature would have to admit every property's type.
    if (extra !== undefined) members.push('[key: string]: unknown');
    return `{ ${members.join('; ')} }`;
  }

  /** The properties of an object schema, in their order. */
  properties(schema: JsonSchema, where: string): PropertyModel[] {
    if (!isObject(schema.properties)) return [];
    const required = new Set(
      Array.isArray(schema.required) ? (schema.required as string[]) : [],
    );
    return Object.entries(schema.properties).map(([name, property]) => {
      const description = isObject(property) ? property.description : undefined;
      return {
        name: resolvePropertyName(name),
        type: this.type(property, `${where}.${name}`),
        optional: !required.has(name),
        ...(typeof description === 'string' ? { description } : {}),
      };
    });
  }

  /** The type of an object's additional properties, if it allows a typed set. */
  additional(schema: JsonSchema, where: string): string | undefined {
    const extra = schema.additionalProperties;
    if (extra === undefined || extra === false) return undefined;
    return this.type(extra, where);
  }

  /** Whether a schema is an object with properties, so it names an interface. */
  isInterface(schema: JsonSchema): boolean {
    const types = typesOf(schema);
    return (
      isObject(schema.properties) &&
      (types.length === 0 || (types.length === 1 && types[0] === 'object')) &&
      schema.$ref === undefined &&
      schema.allOf === undefined &&
      schema.oneOf === undefined &&
      schema.anyOf === undefined
    );
  }

  /**
   * The exact numbers of a schema: each `integer` or `number` of a
   * {@link TEXT_NUMBER_FORMATS} format, through objects, arrays, maps and
   * alternatives; undefined when it has none. A recursive reference is not
   * followed: its numbers read as numbers.
   */
  textNumbers(
    schema: unknown,
    seen: ReadonlySet<string> = new Set(),
  ): TextNumbersModel | undefined {
    if (!isObject(schema)) return undefined;
    const ref =
      typeof schema.$ref === 'string' ? schemaKey(schema.$ref) : undefined;
    if (ref !== undefined) {
      const target = this.schemaOf(ref);
      if (seen.has(ref) || target === undefined) return undefined;
      return this.textNumbers(target, new Set([...seen, ref]));
    }
    const alternatives = ['oneOf', 'anyOf', 'allOf'].flatMap(member =>
      Array.isArray(schema[member]) ? (schema[member] as unknown[]) : [],
    );
    const marks = alternatives
      .map(alternative => this.textNumbers(alternative, seen))
      .filter((mark): mark is TextNumbersModel => mark !== undefined);
    const types = typesOf(schema);
    if (
      (types.includes('integer') || types.includes('number')) &&
      typeof schema.format === 'string' &&
      TEXT_NUMBER_FORMATS.has(schema.format)
    ) {
      marks.push(true);
    }
    const members: Record<string, TextNumbersModel> = {};
    if (isObject(schema.properties)) {
      for (const [name, property] of Object.entries(schema.properties)) {
        const mark = this.textNumbers(property, seen);
        if (mark !== undefined) members[name] = mark;
      }
    }
    const each =
      this.textNumbers(schema.items, seen) ??
      this.textNumbers(schema.additionalProperties, seen);
    if (each !== undefined) members['*'] = each;
    if (Object.keys(members).length > 0) marks.push(members);
    return mergeTextNumbers(marks);
  }

  /** The `state.*` paths of a state schema a query can filter and sort on. */
  fields(
    schema: JsonSchema,
    prefix: string,
    depth = 1,
    seen = new Set<string>(),
  ): string[] {
    const resolved = this.resolve(schema);
    const ref =
      typeof schema.$ref === 'string' ? schemaKey(schema.$ref) : undefined;
    if (ref !== undefined && seen.has(ref)) return [];
    const nextSeen = ref === undefined ? seen : new Set([...seen, ref]);
    if (!isObject(resolved.properties)) return [];
    const paths: string[] = [];
    for (const [name, property] of Object.entries(resolved.properties)) {
      const path = `${prefix}.${name}`;
      paths.push(path);
      if (depth < MAX_FIELD_DEPTH && isObject(property)) {
        const target = this.resolve(property);
        if (!typesOf(target).includes('array')) {
          paths.push(...this.fields(property, path, depth + 1, nextSeen));
        }
      }
    }
    return paths;
  }
}

/**
 * The marks of a value any of `marks` describes: `true` when each is, else
 * the members of the objects among them merged (a value is a number or an
 * object, so its object marks are the ones that apply to members).
 */
function mergeTextNumbers(
  marks: readonly TextNumbersModel[],
): TextNumbersModel | undefined {
  if (marks.length === 0) return undefined;
  const objects = marks.filter(
    (mark): mark is { readonly [member: string]: TextNumbersModel } =>
      mark !== true,
  );
  if (objects.length === 0) return true;
  const merged: Record<string, TextNumbersModel[]> = {};
  for (const object of objects) {
    for (const [member, mark] of Object.entries(object)) {
      (merged[member] ??= []).push(mark);
    }
  }
  return Object.fromEntries(
    Object.entries(merged).map(([member, all]) => [
      member,
      mergeTextNumbers(all)!,
    ]),
  );
}

function emptyBody(
  types: SchemaTypes,
  schema: JsonSchema | undefined,
): boolean {
  if (schema === undefined) return true;
  const resolved = types.resolve(schema);
  return !Array.isArray(resolved.required) || resolved.required.length === 0;
}

/** The domain commands of `aggregate`: its system commands left out. */
function domainCommands(
  aggregate: ManifestAggregate,
): readonly ManifestCommand[] {
  return (aggregate.commands ?? []).filter(command => command.system !== true);
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

function aggregateRoots(
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

/**
 * Decides what a Manifest generates: a `types.ts` per bounded context with
 * the types of the schemas its aggregates reach (a schema two contexts reach
 * is declared in the first), a `boundedContext.ts` with its error codes and
 * wait helpers, and for each aggregate its `aggregate.ts`,
 * `commandClient.ts` and `queryClient.ts`.
 *
 * @param manifest - The Manifest
 * @returns The model and the warnings it raised
 */
export function analyzeManifest(manifest: ManifestDocument): {
  model: ManifestModel;
  warnings: string[];
} {
  const types = new SchemaTypes(manifest.$defs ?? {});
  const contexts = manifest.contexts;
  const reach = new Map<string, string>();
  for (const context of contexts) {
    const roots = (context.aggregates ?? []).flatMap(aggregate =>
      aggregateRoots(types, aggregate),
    );
    for (const key of types.closure(roots).sort(compareNames)) {
      if (!reach.has(key)) reach.set(key, context.name);
    }
  }
  const keys = [...reach.keys()].filter(
    key => types.schemaOf(key) && !types.inlined(key),
  );
  types.name([...keys].sort(compareNames));

  const typeModels: TypeModel[] = keys
    .map(key => typeModel(types, key, `${reach.get(key)}/types.ts`))
    .sort(
      (left, right) =>
        compareNames(left.file, right.file) ||
        compareNames(left.name, right.name),
    );

  const contextModels = contexts.map(context => contextModel(types, context));
  return {
    model: {
      ...(manifest.service?.name ? { service: manifest.service.name } : {}),
      types: typeModels,
      contexts: contextModels,
    },
    warnings: [...new Set(types.warnings)],
  };
}

function typeModel(types: SchemaTypes, key: string, file: string): TypeModel {
  const schema = types.schemaOf(key)!;
  const name = types.names.get(key)!;
  const description =
    typeof schema.description === 'string' ? schema.description : undefined;
  const references = [...types.namedReferences(schema)]
    .map(ref => types.names.get(ref))
    .filter((ref): ref is string => ref !== undefined && ref !== name);
  const base = {
    key,
    name,
    file,
    ...(description === undefined ? {} : { description }),
    references: [...new Set(references)].sort(compareNames),
  };
  if (types.isInterface(schema)) {
    // A typed index signature would have to admit every property's type.
    const extra = types.additional(schema, key);
    return {
      ...base,
      properties: types.properties(schema, key),
      ...(extra === undefined ? {} : { type: 'unknown' }),
    };
  }
  return { ...base, type: types.type(schema, key) };
}

function contextModel(
  types: SchemaTypes,
  context: ManifestContext,
): ContextModel {
  return {
    name: context.name,
    ...(context.description ? { description: context.description } : {}),
    typeName: toTypeIdentifier(pascalCase(context.name)),
    constantName: upperSnakeCase(context.name),
    directory: context.name,
    errors: (context.errors ?? []).map(error => ({
      code: error.code,
      status: error.status,
      ...(error.description ? { description: error.description } : {}),
    })),
    subscriptions: [
      ...new Set((context.subscriptions ?? []).map(s => s.name)),
    ].sort(compareNames),
    aggregates: (context.aggregates ?? []).map(aggregate =>
      aggregateModel(types, context, aggregate),
    ),
  };
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

function aggregateModel(
  types: SchemaTypes,
  context: ManifestContext,
  aggregate: ManifestAggregate,
): AggregateModel {
  const where = `${context.name}.${aggregate.name}`;
  const typeName = toTypeIdentifier(pascalCase(aggregate.name));
  const generated = new Set(lifecycleBodies(types, aggregate));
  const lifecycle: LifecycleModel[] = lifecycleCommands(aggregate)
    .filter(command => LIFECYCLE_COMMANDS.has(command.name))
    .map(command => {
      const name = command.name;
      const simple = name.split('.').pop()!;
      const description = types.schemaOf(name)?.description;
      return {
        name,
        member: upperSnakeCase(simple),
        method: toIdentifier(camelCase(simple)),
        routes: routesOf(command),
        ...(generated.has(name)
          ? { bodyType: types.refType(name, where), bodyGenerated: true }
          : LIFECYCLE_BODIES[name]
            ? { bodyType: LIFECYCLE_BODIES[name] }
            : {}),
        ...(typeof description === 'string' ? { description } : {}),
      };
    });
  const taken = new Set(lifecycle.map(item => item.method));
  const commands: CommandModel[] = domainCommands(aggregate).map(command => {
    let method = toIdentifier(camelCase(command.name));
    if (taken.has(method) || method === 'gateway') method = `${method}Command`;
    taken.add(method);
    const routes = (command.routes ?? []).filter(
      route => route.method === 'POST',
    );
    const byId = routes.find(route => route.path.includes('{id}'));
    const unaddressed = routes.find(route => !route.path.includes('{id}'));
    const key = schemaKey(command.schema);
    return {
      name: command.name,
      method,
      routes: routesOf(command),
      ...(byId ? { byIdPath: { path: byId.path } } : {}),
      ...(unaddressed ? { unaddressedPath: { path: unaddressed.path } } : {}),
      bodyType: typeOfRef(types, command.schema, `${where}.${command.name}`),
      bodyOptional: emptyBody(
        types,
        key === undefined ? undefined : types.schemaOf(key),
      ),
      creation: command.creation ?? 'none',
      mounted: command.mounted ?? false,
      produces: command.produces ?? [],
      ...(command.description ? { description: command.description } : {}),
      ...(types.textNumbers({ $ref: command.schema }) === undefined
        ? {}
        : { bodyNumbers: true }),
    };
  });
  const stateNumbers = types.textNumbers({ $ref: aggregate.state.schema });
  const eventNumbers = Object.fromEntries(
    (aggregate.events ?? []).flatMap(event => {
      const mark = types.textNumbers({ $ref: event.schema });
      return mark === undefined
        ? []
        : [[wireNameOf(context, aggregate, event.name), mark]];
    }),
  );
  const commandNumbers = Object.fromEntries(
    domainCommands(aggregate).flatMap(command => {
      const mark = types.textNumbers({ $ref: command.schema });
      return mark === undefined ? [] : [[command.name, mark]];
    }),
  );
  const textNumbers = {
    ...(stateNumbers === undefined ? {} : { state: stateNumbers }),
    ...(Object.keys(eventNumbers).length === 0 ? {} : { events: eventNumbers }),
    ...(Object.keys(commandNumbers).length === 0
      ? {}
      : { commands: commandNumbers }),
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
