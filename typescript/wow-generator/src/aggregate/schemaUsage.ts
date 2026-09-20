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
  Components,
  OpenAPI,
  Reference,
  Schema,
} from '@ahoo-wang/fetcher-openapi';
import {
  COMPONENTS_SCHEMAS_REF,
  extractOperationEndpoints,
  isNullableSchema,
} from '../utils';
import type { BoundedContextAggregates } from './aggregate';

/**
 * Which side of CQRS a component schema is reachable from.
 *
 * - `write`: reachable only from a request - a Wow command body, or the body
 *   or parameters of any other operation.
 * - `read`: reachable only from aggregate state or domain event bodies.
 * - `shared`: reachable from both sides.
 * - `unknown`: reachable from neither, e.g. a schema used only by ordinary
 *   responses, or any schema in a document without Wow aggregates.
 */
export type SchemaUsage = 'read' | 'write' | 'shared' | 'unknown';

/**
 * Classifies component schemas by the CQRS side that reaches them.
 *
 * Requests describe what a client sends, so their schemas must be read exactly
 * as the document declares them. Aggregate state and domain events describe
 * what the server returns, where a property left out of `required` is usually
 * an artefact of the exporter rather than a genuinely absent field.
 * Distinguishing the two makes it safe to relax optionality on the read side
 * only.
 *
 * The request side spans every operation, not only resolved Wow commands: a
 * generated model can serve as an ordinary endpoint's request body while an
 * aggregate also reaches it, and promoting its properties would make a request
 * demand fields the document permits a client to omit.
 */
export class SchemaUsageResolver {
  private readonly usages = new Map<string, SchemaUsage>();
  private readonly schemas: Record<string, Schema | Reference>;

  private readonly components: Components | undefined;

  /**
   * @param openAPI - The document whose component schemas are classified
   * @param contextAggregates - Resolved aggregates providing the command, state and event roots
   */
  constructor(openAPI: OpenAPI, contextAggregates: BoundedContextAggregates) {
    this.components = openAPI.components;
    this.schemas = this.components?.schemas ?? {};
    const writeRoots = requestSchemaKeys(openAPI);
    const readRoots = new Set<string>();
    for (const aggregates of contextAggregates.values()) {
      for (const aggregate of aggregates) {
        readRoots.add(aggregate.state.key);
        for (const event of aggregate.events.values()) {
          readRoots.add(event.schema.key);
        }
        for (const command of aggregate.commands.values()) {
          writeRoots.add(command.schema.key);
        }
      }
    }
    const write = this.closure(writeRoots);
    const read = this.closure(readRoots);
    for (const key of write) {
      this.usages.set(key, read.has(key) ? 'shared' : 'write');
    }
    for (const key of read) {
      if (!write.has(key)) {
        this.usages.set(key, 'read');
      }
    }
  }

  /**
   * Resolves how a component schema is used.
   * @param schemaKey - The component schema key
   * @returns The usage classification, `unknown` when no aggregate reaches it
   */
  usageOf(schemaKey: string): SchemaUsage {
    return this.usages.get(schemaKey) ?? 'unknown';
  }

  /**
   * Lists the schemas reachable from both commands and read models.
   * @returns The shared schema keys, in classification order
   */
  sharedKeys(): string[] {
    return [...this.usages.entries()]
      .filter(([, usage]) => usage === 'shared')
      .map(([key]) => key);
  }

  /**
   * Lists shared schemas that a read-model rule would have changed, so callers
   * can report what the write side kept intact. Schemas whose properties are
   * already required, or are nullable anyway, produce identical output on both
   * sides and are left out.
   *
   * @returns The shared schema keys holding a non-nullable optional property
   */
  contestedKeys(): string[] {
    return this.sharedKeys().filter(key => {
      const schema = this.schemas[key];
      if (!schema || '$ref' in schema) {
        return false;
      }
      const required = new Set(schema.required ?? []);
      return Object.entries(schema.properties ?? {}).some(
        ([propName, propSchema]) =>
          !required.has(propName) &&
          !isNullableSchema(propSchema, this.components),
      );
    });
  }

  private closure(roots: Set<string>): Set<string> {
    const reached = new Set<string>();
    const pending = [...roots];
    while (pending.length) {
      const key = pending.pop()!;
      if (reached.has(key)) {
        continue;
      }
      const schema = this.schemas[key];
      if (schema === undefined) {
        continue;
      }
      reached.add(key);
      for (const referenced of referencedSchemaKeys(schema)) {
        if (!reached.has(referenced)) {
          pending.push(referenced);
        }
      }
    }
    return reached;
  }
}

/**
 * Collects the component schema keys a client may have to send.
 *
 * Every operation's request body and parameters count, along with the
 * `requestBodies` and `parameters` components themselves - a component declared
 * but not yet referenced still describes a request, and treating it as one only
 * ever preserves the document's declared optionality.
 *
 * @param openAPI - The document to scan
 * @returns The request-side component schema keys
 */
export function requestSchemaKeys(openAPI: OpenAPI): Set<string> {
  const keys = new Set<string>();
  const collect = (node: unknown) => {
    for (const key of referencedSchemaKeys(node)) {
      keys.add(key);
    }
  };
  for (const endpoint of extractOperationEndpoints(
    openAPI.paths ?? {},
    openAPI.components,
  )) {
    collect(endpoint.operation.requestBody);
    collect(endpoint.operation.parameters);
  }
  collect(openAPI.components?.requestBodies);
  collect(openAPI.components?.parameters);
  return keys;
}

/**
 * Collects the component schema keys referenced anywhere inside a schema.
 *
 * The walk is structural rather than keyword-driven so that references buried
 * in vendor extensions or in keywords the generator does not model are still
 * followed.
 *
 * @param node - The schema, or any node within it, to walk
 * @returns The referenced component schema keys
 */
export function referencedSchemaKeys(node: unknown): Set<string> {
  const keys = new Set<string>();
  const visited = new Set<object>();
  const walk = (current: unknown): void => {
    if (current === null || typeof current !== 'object') {
      return;
    }
    if (visited.has(current)) {
      return;
    }
    visited.add(current);
    if (Array.isArray(current)) {
      current.forEach(walk);
      return;
    }
    for (const [key, value] of Object.entries(current)) {
      if (
        key === '$ref' &&
        typeof value === 'string' &&
        value.startsWith(COMPONENTS_SCHEMAS_REF)
      ) {
        keys.add(value.slice(COMPONENTS_SCHEMAS_REF.length));
        continue;
      }
      walk(value);
    }
  };
  walk(node);
  return keys;
}
