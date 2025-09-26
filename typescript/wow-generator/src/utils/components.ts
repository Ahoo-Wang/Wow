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
  Components,
  Parameter,
  Reference,
  RequestBody,
  Response,
  Schema,
} from '@ahoo-wang/fetcher-openapi';

/** Prefix for OpenAPI components references */
export const COMPONENTS_PREFIX = '#/components/';
/** Reference prefix for headers components */
export const COMPONENTS_HEADERS_REF = `${COMPONENTS_PREFIX}headers/`;
/** Reference prefix for parameters components */
export const COMPONENTS_PARAMETERS_REF = `${COMPONENTS_PREFIX}parameters/`;
/** Reference prefix for request bodies components */
export const COMPONENTS_REQUEST_BODIES_REF = `${COMPONENTS_PREFIX}requestBodies/`;
/** Reference prefix for responses components */
export const COMPONENTS_RESPONSES_REF = `${COMPONENTS_PREFIX}responses/`;
/** Reference prefix for schemas components */
export const COMPONENTS_SCHEMAS_REF = `${COMPONENTS_PREFIX}schemas/`;

/**
 * Represents a schema with its key identifier.
 */
export interface KeySchema {
  /** The schema key */
  key: string;
  /** The schema definition */
  schema: Schema;
}

/**
 * Extracts the component key from an OpenAPI reference.
 * @param reference - The OpenAPI reference object
 * @returns The component key (last part of the reference path)
 */
export function extractComponentKey(reference: Reference): string {
  return reference.$ref.split('/').pop() as string;
}

/**
 * Extracts a schema from OpenAPI components using a reference.
 * @param reference - The reference to the schema
 * @param components - The OpenAPI components object
 * @returns The schema if found, undefined otherwise
 */
export function extractSchema(
  reference: Reference,
  components: Components,
): Schema | undefined {
  const componentKey = extractComponentKey(reference);
  return components.schemas?.[componentKey];
}

/**
 * Extracts a response from OpenAPI components using a reference.
 * @param reference - The reference to the response
 * @param components - The OpenAPI components object
 * @returns The response if found, undefined otherwise
 */
export function extractResponse(
  reference: Reference,
  components: Components,
): Response | undefined {
  const componentKey = extractComponentKey(reference);
  return components.responses?.[componentKey];
}

/**
 * Extracts a request body from OpenAPI components using a reference.
 * @param reference - The reference to the request body
 * @param components - The OpenAPI components object
 * @returns The request body if found, undefined otherwise
 */
export function extractRequestBody(
  reference: Reference,
  components: Components,
): RequestBody | undefined {
  const componentKey = extractComponentKey(reference);
  return components.requestBodies?.[componentKey];
}

/**
 * Extracts a parameter from OpenAPI components using a reference.
 * @param reference - The reference to the parameter
 * @param components - The OpenAPI components object
 * @returns The parameter if found, undefined otherwise
 */
export function extractParameter(
  reference: Reference,
  components: Components,
): Parameter | undefined {
  const componentKey = extractComponentKey(reference);
  return components.parameters?.[componentKey];
}

/**
 * Creates a KeySchema object from a reference and components.
 * @param reference - The reference to the schema
 * @param components - The OpenAPI components object
 * @returns A KeySchema containing the key and resolved schema
 */
export function keySchema(
  reference: Reference,
  components: Components,
): KeySchema {
  const componentKey = extractComponentKey(reference);
  return {
    key: componentKey,
    schema: extractSchema(reference, components)!,
  };
}
