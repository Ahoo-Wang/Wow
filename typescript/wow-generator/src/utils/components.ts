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


import { Components, Parameter, Reference, RequestBody, Response, Schema } from '@ahoo-wang/fetcher-openapi';

export const COMPONENTS_PREFIX = '#/components/';
export const COMPONENTS_HEADERS_REF = `${COMPONENTS_PREFIX}headers/`;
export const COMPONENTS_PARAMETERS_REF = `${COMPONENTS_PREFIX}parameters/`;
export const COMPONENTS_REQUEST_BODIES_REF = `${COMPONENTS_PREFIX}requestBodies/`;
export const COMPONENTS_RESPONSES_REF = `${COMPONENTS_PREFIX}responses/`;
export const COMPONENTS_SCHEMAS_REF = `${COMPONENTS_PREFIX}schemas/`;

export interface KeySchema {
  key: string;
  schema: Schema;
}

export function extractComponentKey(reference: Reference): string {
  return reference.$ref.split('/').pop() as string;
}

export function extractSchema(reference: Reference, components: Components): Schema | undefined {
  const componentKey = extractComponentKey(reference);
  return components.schemas?.[componentKey];
}

export function extractResponse(reference: Reference, components: Components): Response | undefined {
  const componentKey = extractComponentKey(reference);
  return components.responses?.[componentKey];
}

export function extractRequestBody(reference: Reference, components: Components): RequestBody | undefined {
  const componentKey = extractComponentKey(reference);
  return components.requestBodies?.[componentKey];
}

export function extractParameter(reference: Reference, components: Components): Parameter | undefined {
  const componentKey = extractComponentKey(reference);
  return components.parameters?.[componentKey];
}

export function keySchema(reference: Reference, components: Components): KeySchema {
  const componentKey = extractComponentKey(reference);
  return {
    key: componentKey,
    schema: extractSchema(reference, components)!,
  };
}