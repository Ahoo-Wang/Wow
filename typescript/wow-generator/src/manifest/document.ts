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

/**
 * The part of a Wow V10 service Manifest (`GET /wow/manifest`, or the merged
 * `manifest.json` of the build) the generator
 * reads. Members it does not read are left out; a Manifest carries more.
 */
export interface ManifestDocument {
  readonly format: number;
  readonly service?: { readonly name: string; readonly version?: string };
  readonly contexts: readonly ManifestContext[];
  readonly errors?: readonly ManifestError[];
  readonly $defs?: Readonly<Record<string, JsonSchema>>;
}

/** A bounded context of the Manifest. */
export interface ManifestContext {
  readonly name: string;
  readonly description?: string;
  readonly aggregates?: readonly ManifestAggregate[];
  readonly subscriptions?: readonly { readonly name: string }[];
  readonly errors?: readonly ManifestError[];
}

/** An aggregate of a bounded context. */
export interface ManifestAggregate {
  readonly name: string;
  readonly description?: string;
  readonly state: { readonly schema: string };
  readonly snapshot?: { readonly enabled?: boolean };
  readonly commands?: readonly ManifestCommand[];
  readonly events?: readonly ManifestEvent[];
  readonly lifecycle?: readonly string[];
  readonly queries?: readonly { readonly model: string }[];
  readonly ownership?: string;
}

/** A command of an aggregate. */
export interface ManifestCommand {
  readonly name: string;
  readonly schema: string;
  readonly creation?: string;
  readonly produces?: readonly string[];
  readonly routes?: readonly {
    readonly method: string;
    readonly path: string;
  }[];
  readonly mounted?: boolean;
  readonly description?: string;
}

/** An event of an aggregate. */
export interface ManifestEvent {
  readonly name: string;
  readonly revision?: number;
  readonly schema: string;
}

/** An error code of the Manifest. */
export interface ManifestError {
  readonly code: string;
  readonly status: number;
  readonly description?: string;
}

/** A JSON Schema 2020-12 of `$defs`, as the Manifest writes them. */
export type JsonSchema = Readonly<Record<string, unknown>>;

/** The Manifest format the generator reads. */
export const MANIFEST_FORMAT = 1;

const DEFS_PREFIX = '#/$defs/';

/** The `$defs` key a schema reference points to, or undefined for another. */
export function schemaKey(ref: string): string | undefined {
  return ref.startsWith(DEFS_PREFIX)
    ? ref.slice(DEFS_PREFIX.length)
    : undefined;
}

/**
 * Whether a parsed document is a Wow Manifest rather than an OpenAPI one: an
 * object with a numeric `format` and a `contexts` array, and no `openapi`.
 */
export function isManifestDocument(document: unknown): boolean {
  if (typeof document !== 'object' || document === null) return false;
  const candidate = document as Record<string, unknown>;
  return (
    candidate.openapi === undefined &&
    candidate.swagger === undefined &&
    typeof candidate.format === 'number' &&
    Array.isArray(candidate.contexts)
  );
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Checks that a document is a Manifest of the format the generator reads,
 * with the members it needs.
 *
 * @param document - The parsed document
 * @param source - Where it came from, for messages
 * @throws GeneratorError of kind `input` for another format, and of kind
 * `specification` for a Manifest that misses what it needs
 */
export function validateManifest(
  document: unknown,
  source: string,
): ManifestDocument {
  const manifest = document as ManifestDocument;
  if (manifest.format !== MANIFEST_FORMAT) {
    throw new GeneratorError(
      'input',
      `${source} is a Wow Manifest of format ${manifest.format}; wow-generator reads format ${MANIFEST_FORMAT}.`,
    );
  }
  const problems: string[] = [];
  manifest.contexts.forEach((context, index) => {
    if (!isObject(context) || typeof context.name !== 'string') {
      problems.push(`contexts[${index}] has no name`);
      return;
    }
    (context.aggregates ?? []).forEach((aggregate, at) => {
      const where = `${context.name}.aggregates[${at}]`;
      if (!isObject(aggregate) || typeof aggregate.name !== 'string') {
        problems.push(`${where} has no name`);
      } else if (
        !isObject(aggregate.state) ||
        typeof aggregate.state.schema !== 'string'
      ) {
        problems.push(`${context.name}.${aggregate.name} has no state schema`);
      } else {
        (aggregate.commands ?? []).forEach(command => {
          if (
            typeof command?.name !== 'string' ||
            typeof command.schema !== 'string'
          ) {
            problems.push(
              `a command of ${context.name}.${aggregate.name} has no name or schema`,
            );
          }
        });
        (aggregate.events ?? []).forEach(event => {
          if (
            typeof event?.name !== 'string' ||
            typeof event.schema !== 'string'
          ) {
            problems.push(
              `an event of ${context.name}.${aggregate.name} has no name or schema`,
            );
          }
        });
      }
    });
  });
  if (manifest.$defs !== undefined && !isObject(manifest.$defs)) {
    problems.push('$defs is not an object');
  }
  if (problems.length > 0) {
    throw new GeneratorError(
      'specification',
      `${source} is not a Manifest wow-generator can read: ${problems.join('; ')}.`,
    );
  }
  return manifest;
}
