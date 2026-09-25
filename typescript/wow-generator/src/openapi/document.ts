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

import type { Components, OpenAPI } from '@ahoo-wang/fetcher-openapi';
import type { OperationEndpoint } from './operations';
import { extractOperationEndpoints } from './operations';

/**
 * A parsed OpenAPI document as the generator reads it: the document itself,
 * which nothing changes, and its operations, listed once.
 */
export interface OpenApiDocument {
  /** The parsed document. Read only: every stage reads the same one. */
  readonly openAPI: OpenAPI;
  /** Its components, if it has any. */
  readonly components?: Components;
  /**
   * Every operation with its method and path, the path item's parameters
   * merged into its own (an operation's parameter overrides the path item's
   * one of the same name and location), ordered by operation id, then path,
   * then method.
   */
  readonly endpoints: readonly OperationEndpoint[];
}

/**
 * Reads a parsed document: lists its operations once, for every stage.
 *
 * @param openAPI - The parsed document; left unchanged
 */
export function openApiDocument(openAPI: OpenAPI): OpenApiDocument {
  return {
    openAPI,
    components: openAPI.components,
    endpoints: extractOperationEndpoints(openAPI.paths, openAPI.components),
  };
}
