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

import type { Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import { GeneratorError } from '../api/errors';
import { boundedContextFilePath, modelFilePath } from '../naming/paths';
import type { OpenApiDocument } from '../openapi/document';
import { isReference } from '../openapi/references';
import { isComposition } from '../openapi/schemas';
import { COMPONENTS_PREFIX } from '../openapi/components';
import {
  aggregatedTypeNames,
  IGNORED_API_CLIENT_TAGS,
  isWowSchema,
} from '../wow/conventions';
import type { WowModel } from '../wow/model';
import { withDocOverride } from '../wow/model';
import type { BoundedContextModel, ModelDeclaration } from './model';
import { resolveContextDeclarationName, resolveModelInfo } from './modelInfo';

/**
 * The bounded contexts whose alias constant is generated: every context with
 * aggregates, and the document's own, which API clients take their base path
 * from. Sorted by alias.
 */
export function analyzeContexts(wow: WowModel): BoundedContextModel[] {
  const aliases = new Set(wow.contexts.keys());
  if (wow.contextAlias) aliases.add(wow.contextAlias);
  return [...aliases].sort().map(alias => ({
    alias,
    constantName: resolveContextDeclarationName(alias),
    file: boundedContextFilePath(alias),
  }));
}

/**
 * The models of a document: one per component schema, in the document's
 * order, but for Wow's own schemas, which wow-client declares (`wow.*`, and
 * the types every aggregate derives from its state).
 *
 * @throws GeneratorError when two schemas generate the same model
 */
export function analyzeModels(
  document: OpenApiDocument,
  wow: WowModel,
): ModelDeclaration[] {
  const schemas = document.components?.schemas;
  if (!schemas) return [];
  const derived = derivedTypeNames(wow);
  const ofApiClients = apiClientSchemaKeys(document, wow);
  const keys = Object.keys(schemas).filter(
    key =>
      !isWowSchema(key, () => resolveModelInfo(key).name, derived) ||
      // A type derived from an aggregate's state that an API client returns
      // or takes (a service's own route answering a snapshot): the query
      // clients name it by wow-client's generics, but the API client by the
      // schema, so it is generated like any other model.
      (!key.startsWith('wow.') && ofApiClients.has(key)),
  );
  assertUniqueModelNames(keys);
  const bodies = messageBodyKeys(wow);
  return keys.map(key => {
    const schema = schemas[key];
    const info = resolveModelInfo(key);
    return {
      key,
      info,
      file: modelFilePath(info),
      schema,
      // The doc reads the schema with what the Wow metadata lends it; the
      // types read the schema as the document has it.
      docSchema: withDocOverride(schema, wow.schemaDocOverrides.get(key)),
      emptyMessageBody: bodies.has(key) && isEmptyMessageBody(schema),
    };
  });
}

/**
 * The schemas the operations of the API clients reach: those of their
 * parameters, bodies and responses, and every schema those reach in turn.
 * An API client holds the operations whose tags are all neither Wow's own,
 * the actuator's nor an aggregate's (`analyzeApiClients`).
 */
function apiClientSchemaKeys(
  document: OpenApiDocument,
  wow: WowModel,
): Set<string> {
  const keys = new Set<string>();
  const seen = new Set<unknown>();
  const components = (document.components ?? {}) as Record<
    string,
    Record<string, unknown> | undefined
  >;
  const visit = (node: unknown): void => {
    if (node === null || typeof node !== 'object' || seen.has(node)) return;
    seen.add(node);
    if (Array.isArray(node)) {
      node.forEach(visit);
      return;
    }
    const { $ref } = node as { $ref?: unknown };
    if (typeof $ref === 'string' && $ref.startsWith(COMPONENTS_PREFIX)) {
      const [kind, ...name] = $ref.slice(COMPONENTS_PREFIX.length).split('/');
      const key = name.join('/');
      if (kind === 'schemas') keys.add(key);
      visit(components[kind]?.[key]);
    }
    Object.values(node).forEach(visit);
  };
  for (const { operation } of document.endpoints) {
    const tags = operation.tags ?? [];
    if (
      tags.length > 0 &&
      tags.every(
        tag => !IGNORED_API_CLIENT_TAGS.has(tag) && !wow.aggregateTags.has(tag),
      )
    ) {
      visit(operation);
    }
  }
  return keys;
}

/** The types wow-client derives from the state of every aggregate. */
function derivedTypeNames(wow: WowModel): Set<string> {
  const names = new Set<string>();
  for (const aggregates of wow.contexts.values()) {
    for (const aggregate of aggregates) {
      const state = resolveModelInfo(aggregate.state.key);
      aggregatedTypeNames(state.name).forEach(name => names.add(name));
    }
  }
  return names;
}

/**
 * Fails when two schemas generate the same model in the same file.
 *
 * Names are normalised to PascalCase, so `Foo-Bar`, `FooBar` and `foo_bar`
 * all become `FooBar`; TypeScript would silently merge three interfaces of
 * that name into one type that matches none of them.
 *
 * @throws GeneratorError listing every group of colliding schema keys
 */
function assertUniqueModelNames(keys: readonly string[]) {
  const byModel = new Map<string, string[]>();
  for (const key of keys) {
    const modelInfo = resolveModelInfo(key);
    const model = `${modelInfo.path === '/' ? '' : modelInfo.path}/${modelInfo.name}`;
    byModel.set(model, [...(byModel.get(model) ?? []), key]);
  }
  const collisions = [...byModel].filter(([, keys]) => keys.length > 1);
  if (collisions.length === 0) return;
  throw new GeneratorError(
    'specification',
    `Schemas generate the same model: ${collisions
      .map(([model, keys]) => `${keys.join(', ')} → ${model}`)
      .join('; ')}. Rename all but one of them in the document.`,
  );
}

/** The schema keys of every command and event body of the aggregates. */
function messageBodyKeys(wow: WowModel): Set<string> {
  return new Set(
    [...wow.contexts.values()].flatMap(aggregates =>
      [...aggregates].flatMap(aggregate => [
        ...[...aggregate.commands.values()].map(command => command.schema.key),
        ...[...aggregate.events.values()].map(event => event.schema.key),
      ]),
    ),
  );
}

/**
 * Tells whether a schema is an object with nothing declared: no properties,
 * no additional properties, no composition. A command or event of this shape
 * - a Kotlin `data object` - is an empty object, not any object: `{type:
 * object}` alone would otherwise generate `Record<string, any>`, which every
 * event union absorbs.
 */
function isEmptyMessageBody(schema: Schema | Reference): boolean {
  if (isReference(schema)) return false;
  return (
    schema.type === 'object' &&
    Object.keys(schema.properties ?? {}).length === 0 &&
    (schema.additionalProperties === undefined ||
      schema.additionalProperties === false) &&
    !schema.required?.length &&
    !isComposition(schema) &&
    schema.enum === undefined &&
    schema.const === undefined
  );
}
