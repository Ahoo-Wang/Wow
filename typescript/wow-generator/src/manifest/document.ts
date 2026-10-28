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
  AggregateManifest,
  CommandManifest,
  ContextManifest,
  ErrorManifest,
  EventManifest,
  Manifest,
} from '@ahoo-wang/wow-client';
import { GeneratorError } from '../api/errors';

/**
 * A Wow V10 service Manifest (`GET /wow/manifest`, or the merged
 * `manifest.json` of the build), typed as `@ahoo-wang/wow-client` types it:
 * generated from the Manifest's own JSON Schema, so the generator reads the
 * format as it is.
 */
export type ManifestDocument = Manifest;

/** A bounded context of the Manifest. */
export type ManifestContext = ContextManifest;

/** An aggregate of a bounded context. */
export type ManifestAggregate = AggregateManifest;

/** A command of an aggregate. */
export type ManifestCommand = CommandManifest;

/** An event of an aggregate. */
export type ManifestEvent = EventManifest;

/** An error code of the Manifest. */
export type ManifestError = ErrorManifest;

/** A JSON Schema 2020-12 of `$defs`, as the Manifest writes them. */
export type JsonSchema = Readonly<Record<string, unknown>>;

/** The Manifest format the generator reads. */
export const MANIFEST_FORMAT = 1;

/**
 * The markers of a Manifest's `requires` the generator understands, as the
 * Kotlin reader's `ManifestFeatures`: none yet.
 */
const MANIFEST_FEATURES: ReadonlySet<string> = new Set<string>();

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
  const unknown = (manifest.requires ?? []).filter(
    marker => !MANIFEST_FEATURES.has(marker),
  );
  if (unknown.length > 0) {
    throw new GeneratorError(
      'input',
      `${source} requires ${unknown.join(', ')}, which this wow-generator does not understand: generate with a newer one.`,
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
