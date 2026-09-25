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

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { openApiDocument } from '../../src/openapi/document';

const spec = (): OpenAPI => ({
  openapi: '3.0.3',
  info: { title: 'Items', version: '1' },
  paths: {
    '/items/{id}': {
      parameters: [
        { name: 'id', in: 'path', required: true, description: 'shared' },
        { $ref: '#/components/parameters/Trace' },
      ],
      get: {
        operationId: 'getItem',
        parameters: [
          { name: 'id', in: 'path', required: true, description: 'own' },
        ],
        responses: {},
      },
      delete: { operationId: 'deleteItem', responses: {} },
    },
    '/items': { get: { operationId: 'listItems', responses: {} } },
  },
  components: {
    parameters: { Trace: { name: 'trace', in: 'header' } },
  },
});

describe('openApiDocument', () => {
  it('lists every operation once, by operation id', () => {
    const document = openApiDocument(spec());
    expect(
      document.endpoints.map(
        ({ method, path, operation }) =>
          `${method} ${path} ${operation.operationId}`,
      ),
    ).toEqual([
      'delete /items/{id} deleteItem',
      'get /items/{id} getItem',
      'get /items listItems',
    ]);
  });

  it('merges the path item parameters into each operation', () => {
    const [deleteItem, getItem] = openApiDocument(spec()).endpoints;
    expect(deleteItem.operation.parameters).toEqual([
      { name: 'id', in: 'path', required: true, description: 'shared' },
      { $ref: '#/components/parameters/Trace' },
    ]);
    expect(getItem.operation.parameters).toEqual([
      { name: 'id', in: 'path', required: true, description: 'own' },
      { $ref: '#/components/parameters/Trace' },
    ]);
  });

  it('leaves the document as it was', () => {
    const openAPI = spec();
    const document = openApiDocument(openAPI);
    expect(document.openAPI).toBe(openAPI);
    expect(document.components).toBe(openAPI.components);
    expect(openAPI).toEqual(spec());
  });
});
