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

import { ResourceAttributionPathSpec } from '@ahoo-wang/wow-client';
import type { Project, SourceFile } from 'ts-morph';
import type { AggregateDefinition, TagAliasAggregate } from '../aggregate';
import { GeneratorError } from '../errors';
import {
  camelCase,
  getOrCreateSourceFile,
  isIdentifier,
  toIdentifier,
  toTypeIdentifier,
} from '../utils';
import type { Operation } from '@ahoo-wang/fetcher-openapi';

/**
 * Infers the appropriate resource attribution path specification type based on command paths in an aggregate definition.
 *
 * This function analyzes the command paths within an aggregate to determine whether the aggregate primarily uses
 * tenant-based or owner-based resource attribution. It counts occurrences of tenant and owner path prefixes
 * and returns the most prevalent type.
 *
 * @param aggregateDefinition - The aggregate definition containing commands with path specifications
 * @returns The inferred path specification type as a string constant:
 *          - 'ResourceAttributionPathSpec.NONE' if no tenant or owner paths are found
 *          - 'ResourceAttributionPathSpec.TENANT' if tenant paths are more prevalent
 *          - 'ResourceAttributionPathSpec.OWNER' if owner paths are more prevalent or equal
 *
 * @example
 * ```typescript
 * const aggregateDef = {
 *   commands: [
 *     { path: '/tenant/{tenantId}/users' },
 *     { path: '/tenant/{tenantId}/orders' },
 *     { path: '/owner/{ownerId}/profile' }
 *   ]
 * };
 * const pathSpec = inferPathSpecType(aggregateDef);
 * // Returns: 'ResourceAttributionPathSpec.TENANT'
 * ```
 */
export function inferPathSpecType(
  aggregateDefinition: AggregateDefinition,
): string {
  let tenantSpecCount = 0;
  let ownerSpecCount = 0;
  aggregateDefinition.commands.forEach(command => {
    if (command.path.startsWith(ResourceAttributionPathSpec.TENANT)) {
      tenantSpecCount += 1;
    }
    if (command.path.startsWith(ResourceAttributionPathSpec.OWNER)) {
      ownerSpecCount += 1;
    }
  });
  if (tenantSpecCount === 0 && ownerSpecCount === 0) {
    return 'ResourceAttributionPathSpec.NONE';
  }
  return tenantSpecCount > ownerSpecCount
    ? 'ResourceAttributionPathSpec.TENANT'
    : 'ResourceAttributionPathSpec.OWNER';
}

/**
 * Creates or retrieves a source file for a client within an aggregate's directory structure.
 *
 * This function generates the appropriate file path based on the aggregate's context alias and name,
 * then uses the project's file management utilities to create or get the source file.
 *
 * @param project - The TypeScript project instance managing the source files
 * @param outputDir - The base output directory where generated files will be placed
 * @param aggregate - The aggregate metadata containing context alias and aggregate name
 * @param fileName - The name of the file to create (without extension)
 * @returns The created or retrieved SourceFile instance
 *
 * @throws Will throw an error if the file cannot be created or retrieved
 *
 * @example
 * ```typescript
 * const project = new Project();
 * const aggregate = { contextAlias: 'user', aggregateName: 'profile' };
 * const sourceFile = createClientFilePath(project, '/output', aggregate, 'UserProfileClient');
 * // Creates/retrieves file at: /output/user/profile/UserProfileClient.ts
 * ```
 */
export function createClientFilePath(
  project: Project,
  outputDir: string,
  aggregate: TagAliasAggregate,
  fileName: string,
): SourceFile {
  const filePath = `${aggregate.contextAlias}/${aggregate.aggregateName}/${fileName}.ts`;
  return getOrCreateSourceFile(project, outputDir, filePath);
}

export function resolveClassName(
  aggregate: TagAliasAggregate,
  suffix: string,
): string {
  return `${toTypeIdentifier(aggregate.aggregateName)}${suffix}`;
}

/**
 * Converts HTTP method names to their corresponding decorator names.
 *
 * This function handles special cases where HTTP method names need to be mapped to different
 * decorator names for compatibility with the decorator framework.
 *
 * @param method - The HTTP method name (e.g., 'get', 'post', 'delete')
 * @returns The corresponding decorator name, with 'delete' mapped to 'del'
 *
 * @example
 * ```typescript
 * methodToDecorator('get');    // Returns: 'get'
 * methodToDecorator('post');   // Returns: 'post'
 * methodToDecorator('delete'); // Returns: 'del'
 * ```
 */
export function methodToDecorator(method: string): string {
  if (method === 'delete') {
    return 'del';
  }
  return method;
}

/** Operation extension naming the method an operation generates. */
export const OPERATION_METHOD_NAME_KEY = 'x-fetcher-method';

/**
 * Names the method an operation generates.
 *
 * The name depends on the operation alone, so adding an operation never
 * renames an existing method:
 *
 * 1. a name configured for the operationId (`apiClients[tag].methodNames`);
 * 2. else the operation's `x-fetcher-method` extension;
 * 3. else the last dot-separated segment of the operationId, camel-cased:
 *    `getUserById` → `getUserById`, `delete_user_by_id` → `deleteUserById`,
 *    `getUser_1` → `getUser1`, `users.list` → `list`,
 *    `example.cart.add_cart_item` → `addCartItem`; a name starting with a
 *    digit is prefixed with `_`.
 *
 * Two operations of one client that arrive at the same name are an error the
 * caller reports; the first two sources resolve it.
 *
 * @param operation - The OpenAPI operation
 * @param configured - The name configured for its operationId, if any
 * @returns The method name, or undefined for an operation without an operationId
 * @throws GeneratorError when a configured or extension name is not a valid method name
 */
export function resolveMethodName(
  operation: Operation,
  configured?: string,
): string | undefined {
  const explicit = configured ?? operation[OPERATION_METHOD_NAME_KEY];
  if (explicit !== undefined) {
    if (typeof explicit !== 'string' || !isMethodName(explicit)) {
      throw new GeneratorError(
        'specification',
        `${OPERATION_METHOD_NAME_KEY} of ${operation.operationId ?? 'an operation'} is ${JSON.stringify(explicit)}, which is not a valid method name.`,
      );
    }
    return explicit;
  }
  if (!operation.operationId) {
    return undefined;
  }
  const lastSegment = operation.operationId.split('.').pop()!;
  const name = camelCase(lastSegment) || '_';
  return /^\p{N}/u.test(name) ? `_${name}` : name;
}

/**
 * Tells whether a name can name a method. Unlike a variable, a method may be
 * named by a reserved word: `delete()` is fine.
 */
function isMethodName(name: string): boolean {
  return isIdentifier(name) || /^[a-z]+$/.test(name);
}

/**
 * Turns a parameter name from the document into a unique parameter
 * identifier: `item-id` → `itemId`, and `id` twice → `id`, `id2`.
 *
 * @param name - The name the document uses
 * @param used - The identifiers the method already uses; updated
 * @returns The identifier
 */
export function uniqueParameterName(name: string, used: Set<string>): string {
  const identifier = toIdentifier(name);
  let unique = identifier;
  for (let suffix = 2; used.has(unique); suffix++) {
    unique = `${identifier}${suffix}`;
  }
  used.add(unique);
  return unique;
}
