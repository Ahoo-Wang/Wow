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

import { ResourceAttributionPathSpec } from '@ahoo-wang/fetcher-wow';
import { Project, SourceFile } from 'ts-morph';
import { AggregateDefinition, TagAliasAggregate } from '../aggregate';
import { camelCase, getOrCreateSourceFile, pascalCase, splitName } from '../utils';
import { Operation } from '@ahoo-wang/fetcher-openapi';

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

/**
 * Generates the client class name for an aggregate by combining the aggregate name with a suffix.
 *
 * This function converts the aggregate name to PascalCase and appends the specified suffix
 * to create a standardized client class name.
 *
 * @param aggregate - The aggregate metadata containing the aggregate name
 * @param suffix - The suffix to append to the aggregate name (e.g., 'Client', 'QueryClient')
 * @returns The generated client class name in PascalCase format
 *
 * @example
 * ```typescript
 * const aggregate = { aggregateName: 'user_profile' };
 * const clientName = getClientName(aggregate, 'Client');
 * // Returns: 'UserProfileClient'
 *
 * const queryClientName = getClientName(aggregate, 'QueryClient');
 * // Returns: 'UserProfileQueryClient'
 * ```
 */
export function getClientName(
  aggregate: TagAliasAggregate,
  suffix: string,
): string {
  return `${pascalCase(aggregate.aggregateName)}${suffix}`;
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

const OPERATION_METHOD_NAME_KEY = 'x-fetcher-method';

/**
 * Resolves a unique method name for an OpenAPI operation.
 *
 * This function attempts to generate a unique method name for an operation by:
 * 1. Using the custom 'x-fetcher-method' extension if present
 * 2. Deriving from the operationId by trying progressively shorter suffixes
 * 3. Falling back to the full camelCase operationId if no unique name is found
 *
 * The function splits the operationId by common separators and tries to find the shortest
 * unique method name by checking from the end of the name parts.
 *
 * @param operation - The OpenAPI operation object containing operationId and other metadata
 * @param isExists - A function that checks if a method name already exists in the target class
 * @returns A unique method name string, or undefined if the operation has no operationId
 *
 * @throws Will not throw, but returns undefined for operations without operationId
 *
 * @example
 * ```typescript
 * const operation = { operationId: 'user.getProfile' };
 * const isExists = (name) => name === 'getProfile'; // Assume getProfile exists
 * const methodName = resolveMethodName(operation, isExists);
 * // Returns: 'user.getProfile' (fallback since getProfile exists)
 *
 * const operation2 = { operationId: 'user.create' };
 * const methodName2 = resolveMethodName(operation2, isExists);
 * // Returns: 'create' (unique method name found)
 * ```
 */
export function resolveMethodName(operation: Operation, isExists: (methodName: string) => boolean): string | undefined {
  const methodName = operation[OPERATION_METHOD_NAME_KEY];
  if (methodName) {
    return methodName;
  }
  if (!operation.operationId) {
    return undefined;
  }

  const nameParts = splitName(operation.operationId);
  for (let i = nameParts.length - 1; i >= 0; i--) {
    const operationName = camelCase(nameParts.slice(i));
    if (isExists(operationName)) {
      continue;
    }
    return operationName;
  }
  return camelCase(nameParts);
}