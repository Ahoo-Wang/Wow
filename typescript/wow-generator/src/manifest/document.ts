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

/** Whether a value of the document is a JSON object. */
export function isObject(value: unknown): value is Record<string, unknown> {
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
  const requires = listOf(manifest.requires, 'requires', problems);
  const unknown = requires.filter(
    marker => typeof marker !== 'string' || !MANIFEST_FEATURES.has(marker),
  );
  if (unknown.length > 0) {
    throw new GeneratorError(
      'input',
      `${source} requires ${unknown.join(', ')}, which this wow-generator does not understand: generate with a newer one.`,
    );
  }
  manifest.contexts.forEach((context, index) => {
    if (!isObject(context) || typeof context.name !== 'string') {
      problems.push(`contexts[${index}] has no name`);
      return;
    }
    const name = context.name;
    listOf(context.aggregates, `${name}.aggregates`, problems).forEach(
      (aggregate, at) => {
        const where = `${name}.aggregates[${at}]`;
        if (!isObject(aggregate) || typeof aggregate.name !== 'string') {
          problems.push(`${where} has no name`);
        } else if (
          !isObject(aggregate.state) ||
          typeof aggregate.state.schema !== 'string'
        ) {
          problems.push(`${name}.${aggregate.name} has no state schema`);
        } else {
          aggregateProblems(aggregate, `${name}.${aggregate.name}`, problems);
        }
      },
    );
    commandsProblems(context.commands, `${name}.commands`, name, problems);
    listOf(context.errors, `${name}.errors`, problems).forEach((error, at) => {
      if (!isObject(error) || typeof error.code !== 'string') {
        problems.push(`${name}.errors[${at}] has no code`);
      }
    });
    listOf(context.subscriptions, `${name}.subscriptions`, problems).forEach(
      (subscription, at) => {
        if (!isObject(subscription) || typeof subscription.name !== 'string') {
          problems.push(`${name}.subscriptions[${at}] has no name`);
        }
      },
    );
    listOf(context.routes, `${name}.routes`, problems).forEach((route, at) =>
      routeProblems(route, name, at, problems),
    );
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

/**
 * `value` as a list: itself when it is an array, none when it is absent or
 * `null`, as the generator has always read a list (`?? []`); a problem
 * `where` is not a list otherwise.
 */
function listOf(value: unknown, where: string, problems: string[]): unknown[] {
  if (value === undefined || value === null) return [];
  if (Array.isArray(value)) return value;
  problems.push(`${where} is not a list`);
  return [];
}

/** The problems of an aggregate's commands and events, and of its lists of queries and access attributes. */
function aggregateProblems(
  aggregate: Record<string, unknown>,
  owner: string,
  problems: string[],
): void {
  listOf(aggregate.commands, `${owner}.commands`, problems).forEach(command => {
    if (
      !isObject(command) ||
      typeof command.name !== 'string' ||
      typeof command.schema !== 'string'
    ) {
      problems.push(`a command of ${owner} has no name or schema`);
    } else {
      commandProblems(command, `${owner}.${command.name}`, problems);
    }
  });
  listOf(aggregate.events, `${owner}.events`, problems).forEach(event => {
    if (
      !isObject(event) ||
      typeof event.name !== 'string' ||
      typeof event.schema !== 'string'
    ) {
      problems.push(`an event of ${owner} has no name or schema`);
    }
  });
  // A query's or an access attribute's members are read as far as they go, as before.
  listOf(aggregate.queries, `${owner}.queries`, problems);
  listOf(aggregate.access, `${owner}.access`, problems);
}

/**
 * The problems of a context's own commands (a job's), read for their names
 * and routes: a command with routes has a name, and each route a method and
 * a path. One without routes is not routed, and is read as before.
 */
function commandsProblems(
  commands: unknown,
  where: string,
  owner: string,
  problems: string[],
): void {
  listOf(commands, where, problems).forEach((command, at) => {
    if (
      !isObject(command) ||
      !Array.isArray(command.routes) ||
      command.routes.length === 0
    ) {
      return;
    }
    if (typeof command.name !== 'string') {
      problems.push(`${where}[${at}] has routes but no name`);
    } else {
      routesProblems(
        command.routes,
        `${owner}.${command.name}.routes`,
        problems,
      );
    }
  });
}

/**
 * The problems of a command's routes and of its list of produced events. Its
 * former names are read only as a list of names, and anything else as none.
 */
function commandProblems(
  command: Record<string, unknown>,
  where: string,
  problems: string[],
): void {
  routesProblems(command.routes, `${where}.routes`, problems);
  listOf(command.produces, `${where}.produces`, problems);
}

/** The problems of a list of routes: each a method and a path. */
function routesProblems(
  routes: unknown,
  where: string,
  problems: string[],
): void {
  listOf(routes, where, problems).forEach((route, at) => {
    if (!isRoute(route)) problems.push(`${where}[${at}] has no method or path`);
  });
}

/** Whether `route` is a method and a path. */
function isRoute(route: unknown): boolean {
  return (
    isObject(route) &&
    typeof route.method === 'string' &&
    typeof route.path === 'string'
  );
}

/** The problems of a service route of `context`, the `at`th: its name, its route and its list of parameters. */
function routeProblems(
  route: unknown,
  context: string,
  at: number,
  problems: string[],
): void {
  if (!isObject(route) || typeof route.name !== 'string') {
    problems.push(`${context}.routes[${at}] has no name`);
    return;
  }
  const named = `the route ${route.name} of ${context}`;
  if (!isRoute(route.route)) {
    problems.push(`${named} has no route of a method and a path`);
  }
  // A parameter's members are read as far as they go, as before.
  listOf(route.parameters, `${named}: parameters`, problems);
}

/** The JSON types a schema names: its `type`, a string or an array of them. */
export function typesOf(schema: JsonSchema): string[] {
  const type = schema.type;
  if (typeof type === 'string') return [type];
  if (Array.isArray(type)) return type.filter(t => typeof t === 'string');
  return [];
}
