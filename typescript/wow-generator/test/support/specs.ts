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

/**
 * Minimal OpenAPI documents for the probes: the smallest input that shows
 * each problem the pre-release review found.
 */

type Json = Record<string, any>;

const jsonContent = (schema: Json) => ({
  content: { 'application/json': { schema } },
});

/** A plain OpenAPI document. */
export function document(
  paths: Json,
  schemas: Json = {},
  extra: Json = {},
): Json {
  return {
    openapi: '3.0.3',
    info: { title: 'Probe', version: '1' },
    paths,
    components: { schemas, ...(extra.components ?? {}) },
    ...Object.fromEntries(
      Object.entries(extra).filter(([key]) => key !== 'components'),
    ),
  };
}

/** A GET operation of tag `Items` answering 200 with a JSON schema. */
export function getOperation(
  operationId: string,
  schema: Json = { type: 'string' },
  extra: Json = {},
): Json {
  return {
    get: {
      tags: ['Items'],
      operationId,
      responses: { '200': { description: 'OK', ...jsonContent(schema) } },
      ...extra,
    },
  };
}
