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

import { pascalCase, toTypeIdentifier } from '../naming/naming';
import type {
  ManifestAggregate,
  ManifestContext,
  ManifestDocument,
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

/** The type name of a context, which the names of its clients start with. */
export function contextTypeName(
  context: Pick<ManifestContext, 'name'>,
): string {
  return toTypeIdentifier(pascalCase(context.name));
}

/** The type name of an aggregate, which the names of its clients start with. */
export function aggregateTypeName(
  aggregate: Pick<ManifestAggregate, 'name'>,
): string {
  return toTypeIdentifier(pascalCase(aggregate.name));
}

/**
 * The names the modules generated from `manifest` bind besides its types:
 * the TypeScript globals they use, what they import from
 * `@ahoo-wang/wow-client`, and what the generator declares for each context
 * and aggregate. A type of `$defs` takes none of them, as it takes no name of
 * another type: it is qualified by its package instead (`shop.Record` is
 * `ShopRecord`), so every generated module can import it beside them.
 *
 * @param manifest - The Manifest
 */
export function generatedNames(manifest: ManifestDocument): Set<string> {
  const names = new Set([...GLOBALS, ...CLIENT_NAMES]);
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
