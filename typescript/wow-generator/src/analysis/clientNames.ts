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

import type { TagAliasAggregate } from '../wow/model';
import { GeneratorError } from '../api/errors';
import {
  camelCase,
  isIdentifier,
  toIdentifier,
  toTypeIdentifier,
} from '../naming/naming';
import type { Operation } from '@ahoo-wang/fetcher-openapi';

/**
 * The path of a client module of an aggregate, relative to the output
 * directory: `<contextAlias>/<aggregateName>/<fileName>.ts`.
 *
 * @param aggregate - The aggregate metadata containing context alias and aggregate name
 * @param fileName - The name of the file, without extension
 *
 * @example
 * ```typescript
 * clientModulePath({ contextAlias: 'user', aggregateName: 'profile' }, 'queryClient');
 * // 'user/profile/queryClient.ts'
 * ```
 */
export function clientModulePath(
  aggregate: Pick<TagAliasAggregate, 'contextAlias' | 'aggregateName'>,
  fileName: string,
): string {
  return `${aggregate.contextAlias}/${aggregate.aggregateName}/${fileName}.ts`;
}

/**
 * The name of a class or type of an aggregate: its name as a type, then the
 * suffix (`order`, `CommandClient` → `OrderCommandClient`).
 */
export function resolveClassName(
  aggregate: Pick<TagAliasAggregate, 'aggregateName'>,
  suffix: string,
): string {
  return `${toTypeIdentifier(aggregate.aggregateName)}${suffix}`;
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
