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

import {
  HTTPMethod,
  Operation,
  PathItem,
  Reference,
  Response,
  Schema,
} from '@ahoo-wang/fetcher-openapi';
import { extractOkResponseJsonSchema } from '@/utils/responses.ts';

/**
 * Represents an HTTP method and its associated operation.
 */
export interface MethodOperation {
  /** The HTTP method */
  method: HTTPMethod;
  /** The OpenAPI operation */
  operation: Operation;
}

/**
 * Extracts all operations from a path item.
 * @param pathItem - The OpenAPI path item
 * @returns Array of method-operation pairs
 */
export function extractOperations(pathItem: PathItem): MethodOperation[] {
  return [
    { method: 'get', operation: pathItem.get },
    { method: 'put', operation: pathItem.put },
    { method: 'post', operation: pathItem.post },
    { method: 'delete', operation: pathItem.delete },
    { method: 'options', operation: pathItem.options },
    { method: 'head', operation: pathItem.head },
    { method: 'patch', operation: pathItem.patch },
    { method: 'trace', operation: pathItem.trace },
  ].filter(({ operation }) => operation !== undefined) as Array<{
    method: HTTPMethod;
    operation: Operation;
  }>;
}

/**
 * Extracts the OK (200) response from an operation.
 * @param operation - The OpenAPI operation
 * @returns The 200 response or undefined if not found
 */
export function extractOkResponse(
  operation: Operation,
): Response | Reference | undefined {
  return operation.responses['200'];
}

/**
 * Extracts the JSON schema from the OK response of an operation.
 * @param operation - The OpenAPI operation
 * @returns The JSON schema from the OK response or undefined if not found
 */
export function extractOperationOkResponseJsonSchema(
  operation: Operation,
): Schema | Reference | undefined {
  const okResponse = extractOkResponse(operation);
  return extractOkResponseJsonSchema(okResponse);
}
