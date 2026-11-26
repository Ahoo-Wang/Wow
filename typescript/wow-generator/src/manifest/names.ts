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

import { GeneratorError } from '../api/errors';
import { toTypeIdentifier, uniqueParameterName } from '../naming/naming';
import {
  type ManifestAggregate,
  type ManifestContext,
  type ManifestDocument,
} from './document';

/**
 * The TypeScript globals the generated modules refer to by name. A type of
 * the Manifest named the same would shadow one wherever it is imported.
 */
const GLOBALS = ['Omit', 'Promise', 'Record'];

/** What the generated modules import from `@ahoo-wang/wow-client`. */
const CLIENT_NAMES = [
  'CommandClient',
  'CommandOptions',
  'CommandResult',
  'CommandResultStream',
  'CommittedEvent',
  'CreateCommandOptions',
  'EventQueryClient',
  'Hop',
  'LifecycleCommands',
  'MANIFEST_FORMAT',
  'NoAccess',
  'OwnStateClient',
  'RequestOptions',
  'RoutingManifest',
  'ServiceRoutes',
  'SnapshotQueryClient',
  'StateClient',
  'SystemEvent',
  'WowClientOptions',
];

/**
 * The lifecycle bodies a command client imports from `@ahoo-wang/wow-client`,
 * by the command's name: always these, the same shape whatever `$defs` holds,
 * so the type's name never depends on an unrelated key (#7022, row 21).
 */
export const LIFECYCLE_BODIES: Readonly<Record<string, string>> = {
  'wow.lifecycle.TransferOwner': 'TransferOwner',
  'wow.lifecycle.TransferSpace': 'TransferSpace',
};

/** What the generator declares for each context, after its type name. */
const CONTEXT_DECLARATIONS = [
  'Client',
  'Contract',
  'ErrorCode',
  'Manifest',
  'Routes',
  'Subscription',
];

/** What the generator declares for each aggregate, after its type name. */
const AGGREGATE_DECLARATIONS = [
  'Access',
  'Aggregate',
  'CommandClient',
  'Event',
  'EventQueryClient',
  'SnapshotQueryClient',
  'StateClient',
  'StateField',
];

/**
 * `name` in Pascal case as the Kotlin contract writes it
 * (`ContractSources.pascal` of `compiler/manifest-tools`): its words split on
 * `-`, `_`, `.` and spaces, each with its first letter upper-cased and the
 * rest kept as written, so `orderID` is `OrderID` and `HTTPLog` is `HTTPLog`.
 */
export function contractPascal(name: string): string {
  return name
    .split(/[-_. ]/)
    .filter(word => word !== '')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join('');
}

/**
 * The member or method name of `name`: {@link contractPascal} with its first
 * letter lowered, so `CreateURLShortcut` is `createURLShortcut`.
 */
export function memberName(name: string): string {
  const pascal = contractPascal(name);
  return pascal.charAt(0).toLowerCase() + pascal.slice(1);
}

/** The type name of a context, which the names of its clients start with. */
export function contextTypeName(
  context: Pick<ManifestContext, 'name'>,
): string {
  return toTypeIdentifier(contractPascal(context.name));
}

/**
 * The type name of an aggregate, which the names of its clients start with,
 * and its member of the contract, as the Kotlin contract names its object.
 */
export function aggregateTypeName(
  aggregate: Pick<ManifestAggregate, 'name'>,
): string {
  return toTypeIdentifier(contractPascal(aggregate.name));
}

/**
 * The names the modules generated from `manifest` bind besides its types:
 * the TypeScript globals they use, what they import from
 * `@ahoo-wang/wow-client` (the lifecycle bodies among them), and what the
 * generator declares for each context
 * and aggregate. A type of `$defs` takes none of them, as it takes no name of
 * another type: it is qualified by its package instead (`shop.Record` is
 * `ShopRecord`), so every generated module can import it beside them.
 *
 * @param manifest - The Manifest
 */
export function generatedNames(manifest: ManifestDocument): Set<string> {
  const names = new Set([...GLOBALS, ...CLIENT_NAMES]);
  for (const name of Object.values(LIFECYCLE_BODIES)) names.add(name);
  for (const context of manifest.contexts) {
    const name = contextTypeName(context);
    for (const suffix of CONTEXT_DECLARATIONS) names.add(`${name}${suffix}`);
    for (const aggregate of context.aggregates ?? []) {
      const type = aggregateTypeName(aggregate);
      for (const suffix of AGGREGATE_DECLARATIONS) {
        names.add(`${type}${suffix}`);
      }
    }
  }
  return names;
}

/**
 * Refuses a Manifest two of whose names in one context give one generated
 * name: two aggregates of one type name (`order-item` and `order_item`, which
 * the Kotlin contract cannot hold either), or a context's declaration that is
 * an aggregate's (a context `order-command` holding an aggregate `order`,
 * both `OrderCommandClient`). Names two contexts share are the root index's
 * to leave out, not a refusal.
 *
 * @param manifest - The Manifest
 * @throws GeneratorError of kind `specification` naming both and the name
 */
export function checkGeneratedNames(manifest: ManifestDocument): void {
  for (const context of manifest.contexts) {
    const owners = new Map<string, string>();
    const claim = (name: string, owner: string) => {
      const other = owners.get(name);
      if (other !== undefined && other !== owner) {
        throw new GeneratorError(
          'specification',
          `In ${context.name}, ${other} and ${owner} both generate ${name}: name one of them otherwise (@Name).`,
        );
      }
      owners.set(name, owner);
    };
    const type = contextTypeName(context);
    for (const suffix of CONTEXT_DECLARATIONS) {
      claim(`${type}${suffix}`, `the context ${context.name}`);
    }
    for (const aggregate of context.aggregates ?? []) {
      const name = aggregateTypeName(aggregate);
      for (const suffix of AGGREGATE_DECLARATIONS) {
        claim(`${name}${suffix}`, `the aggregate ${aggregate.name}`);
      }
    }
  }
}

/**
 * The parameters a service route's method declares besides its path
 * variables, which take other names: a fresh set for one method.
 */
export function routeMethodParameters(): Set<string> {
  return new Set(['body', 'query', 'options']);
}

/**
 * A route parameter's name in its method: a path variable's, an identifier no
 * other parameter of the method takes (`used`, updated); a query
 * parameter's, its name, a member of the method's `query`, which takes none.
 */
export function routeParameterIdentifier(
  parameter: { readonly name: string; readonly in: string },
  used: Set<string>,
): string {
  return parameter.in === 'path'
    ? uniqueParameterName(parameter.name, used)
    : parameter.name;
}

/**
 * The constant of an error code's `name` (what follows its aggregate, or its
 * context), as the Kotlin contract's `ContractSources.errorName` names it: its
 * dotted segments each in upper snake case (`QueryFieldNames.upperSnake`),
 * joined by `__`, and `_` before a leading digit. `invalid-item` is
 * `INVALID_ITEM`, `invalidURLValue` is `INVALID_URL_VALUE`, `item2Count` is
 * `ITEM2_COUNT` and `404-not-found` is `_404_NOT_FOUND`.
 */
export function errorConstantName(name: string): string {
  const constant = name
    .split('.')
    .map(segment =>
      segment
        .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
        .replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
        .replace(/[^A-Za-z0-9_]/g, '_')
        .toUpperCase(),
    )
    .join('__');
  return /^[0-9]/.test(constant) ? `_${constant}` : constant;
}

/**
 * The member of each of `names` in a generated class whose own members are
 * `reserved`: the name itself, or, for one a reserved member takes, the name
 * and `suffix` (then `suffix2` and so on), apart from every other name. In the
 * order given.
 *
 * @param names - The names, in the Manifest's order
 * @param reserved - The class's own members
 * @param suffix - What a name a member takes is given
 */
export function classMembers(
  names: readonly string[],
  reserved: readonly string[],
  suffix: string,
): string[] {
  const used = new Set([...reserved, ...names]);
  const taken = new Set(reserved);
  return names.map(name => {
    if (!taken.has(name)) {
      taken.add(name);
      return name;
    }
    let member = `${name}${suffix}`;
    for (let index = 2; used.has(member); index += 1) {
      member = `${name}${suffix}${index}`;
    }
    used.add(member);
    taken.add(member);
    return member;
  });
}

/**
 * The members of a generated `<Context>Client` besides its aggregates'. A
 * member `then` would make the client a thenable, which `await` would read
 * as a promise: every generated class reserves it.
 */
export const CONTEXT_CLIENT_MEMBERS = [
  'commands',
  'routes',
  'constructor',
  'then',
];

/** The members of a generated `<Context>Routes` besides its routes' methods. */
export const ROUTES_CLIENT_MEMBERS = ['routes', 'constructor', 'then'];
