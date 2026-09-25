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
  HTTPMethod,
  Operation,
  Parameter,
  PathItem,
  Paths,
  Reference,
  Response,
  Schema,
} from '@ahoo-wang/fetcher-openapi';
import { compareNames } from '../naming/order';
import { extractParameter, extractResponse } from './components';
import { isReference } from './references';
import { extractResponseJsonSchema } from './responses';
import { isPrimitive, resolvePrimitiveType } from './schemas';

/**
 * Represents an HTTP method and its associated operation.
 */
export interface MethodOperation {
  /** The HTTP method */
  method: HTTPMethod;
  /** The OpenAPI operation */
  operation: Operation;
}

export interface OperationEndpoint extends MethodOperation {
  path: string;
}

export function operationEndpointComparator(
  left: OperationEndpoint,
  right: OperationEndpoint,
): number {
  if (left.operation.operationId && right.operation.operationId) {
    return compareNames(
      left.operation.operationId,
      right.operation.operationId,
    );
  }
  if (left.path && right.path) {
    return compareNames(left.path, right.path);
  }
  if (left.method && right.method) {
    return compareNames(left.method, right.method);
  }
  return 0;
}

function mergeParameters(
  inherited: (Parameter | Reference)[],
  own: (Parameter | Reference)[],
  components?: Components,
): (Parameter | Reference)[] {
  const parameters = new Map<string, Parameter | Reference>();
  for (const parameter of [...inherited, ...own]) {
    const resolved = isReference(parameter)
      ? components && extractParameter(parameter, components)
      : parameter;
    const key = resolved
      ? `${resolved.in}:${resolved.name}`
      : (parameter as Reference).$ref;
    parameters.set(key, parameter);
  }
  return [...parameters.values()];
}

export function extractOperationEndpoints(
  paths: Paths,
  components?: Components,
): Array<OperationEndpoint> {
  const operationEndpoints: OperationEndpoint[] = [];
  for (const [path, pathItem] of Object.entries(paths)) {
    extractOperations(pathItem).forEach(methodOperation => {
      operationEndpoints.push({
        method: methodOperation.method,
        operation: pathItem.parameters?.length
          ? {
              ...methodOperation.operation,
              parameters: mergeParameters(
                pathItem.parameters,
                methodOperation.operation.parameters ?? [],
                components,
              ),
            }
          : methodOperation.operation,
        path,
      });
    });
  }
  return operationEndpoints.sort(operationEndpointComparator);
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
 * Picks the status code of an operation's success response: `200`, else the
 * lowest other 2xx code, else `2XX`.
 *
 * @param operation - The OpenAPI operation
 * @returns The status code key, or undefined when no 2xx response is declared
 */
export function okResponseStatus(operation: Operation): string | undefined {
  const statuses = Object.keys(operation.responses ?? {});
  if (statuses.includes('200')) return '200';
  const exact = statuses.filter(status => /^2\d\d$/.test(status)).sort();
  if (exact.length > 0) return exact[0];
  return statuses.find(status => status.toUpperCase() === '2XX');
}

/**
 * Extracts the success response from an operation: `200`, else the lowest
 * other 2xx response, else `2XX`.
 * @param operation - The OpenAPI operation
 * @param components - Optional components used to resolve response references
 * @returns The success response or undefined if not found
 */
export function extractOkResponse(
  operation: Operation,
  components?: Components,
): Response | Reference | undefined {
  const status = okResponseStatus(operation);
  const response = status ? operation.responses[status] : undefined;
  return components && isReference(response)
    ? extractResponse(response, components)
    : response;
}

/**
 * Extracts the JSON schema from the OK response of an operation.
 * @param operation - The OpenAPI operation
 * @param components - Optional components used to resolve response references
 * @returns The JSON schema from the OK response or undefined if not found
 */
export function extractOperationOkResponseJsonSchema(
  operation: Operation,
  components?: Components,
): Schema | Reference | undefined {
  const okResponse = extractOkResponse(operation, components);
  return extractResponseJsonSchema(okResponse);
}

/**
 * Extracts the parameters of an operation, references resolved, in document
 * order.
 * @param operation - The OpenAPI operation
 * @param components - The OpenAPI components object used to resolve references
 * @returns The parameters
 */
export function extractParameters(
  operation: Operation,
  components: Components,
): Parameter[] {
  return (operation.parameters ?? [])
    .map(parameter =>
      isReference(parameter)
        ? extractParameter(parameter, components)
        : parameter,
    )
    .filter((parameter): parameter is Parameter => parameter !== undefined);
}

/**
 * Extracts path parameters from an operation.
 * @param operation - The OpenAPI operation to extract path parameters from
 * @param components - The OpenAPI components object used to resolve references
 * @returns Array of path parameters
 */
export function extractPathParameters(
  operation: Operation,
  components: Components,
): Parameter[] {
  if (!operation.parameters) {
    return [];
  }
  return operation.parameters
    .map(parameter => {
      if (isReference(parameter)) {
        return extractParameter(parameter, components);
      }
      return parameter;
    })
    .filter((parameter): parameter is Parameter => parameter?.in === 'path');
}

const DEFAULT_PATH_PARAMETER_TYPE = 'string';

/**
 * Resolves the type of a path parameter.
 * @param parameter - The path parameter to resolve the type for
 * @returns The resolved primitive type as a string, or the default path parameter type if the schema is missing,
 *          is a reference, lacks a type, or the type is not primitive
 */
export function resolvePathParameterType(parameter: Parameter): string {
  if (
    !parameter.schema ||
    isReference(parameter.schema) ||
    !parameter.schema.type ||
    !isPrimitive(parameter.schema.type)
  ) {
    return DEFAULT_PATH_PARAMETER_TYPE;
  }
  return resolvePrimitiveType(parameter.schema.type);
}
