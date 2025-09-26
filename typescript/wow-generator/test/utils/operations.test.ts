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

import { describe, expect, it } from 'vitest';
import {
  extractOperations,
  extractOkResponse,
  extractOperationOkResponseJsonSchema,
} from '@/utils/operations.ts';
import {
  PathItem,
  Operation,
  Response,
  Schema,
} from '@ahoo-wang/fetcher-openapi';

describe('operations', () => {
  describe('extractOperations', () => {
    it('should extract all operations from path item', () => {
      const getOperation: Operation = {
        operationId: 'getUsers',
        responses: {},
      };
      const postOperation: Operation = {
        operationId: 'createUser',
        responses: {},
      };
      const pathItem: PathItem = {
        get: getOperation,
        post: postOperation,
      };

      const result = extractOperations(pathItem);
      expect(result).toEqual([
        { method: 'get', operation: getOperation },
        { method: 'post', operation: postOperation },
      ]);
    });

    it('should filter out undefined operations', () => {
      const getOperation: Operation = {
        operationId: 'getUsers',
        responses: {},
      };
      const pathItem: PathItem = {
        get: getOperation,
        post: undefined,
        put: undefined,
      };

      const result = extractOperations(pathItem);
      expect(result).toEqual([{ method: 'get', operation: getOperation }]);
    });

    it('should handle all HTTP methods', () => {
      const operations: Record<string, Operation> = {
        get: { operationId: 'get', responses: {} },
        put: { operationId: 'put', responses: {} },
        post: { operationId: 'post', responses: {} },
        delete: { operationId: 'delete', responses: {} },
        options: { operationId: 'options', responses: {} },
        head: { operationId: 'head', responses: {} },
        patch: { operationId: 'patch', responses: {} },
        trace: { operationId: 'trace', responses: {} },
      };

      const pathItem: PathItem = operations;

      const result = extractOperations(pathItem);
      expect(result).toHaveLength(8);
      expect(result.map(r => r.method)).toEqual([
        'get',
        'put',
        'post',
        'delete',
        'options',
        'head',
        'patch',
        'trace',
      ]);
    });

    it('should return empty array for path item with no operations', () => {
      const pathItem: PathItem = {};
      const result = extractOperations(pathItem);
      expect(result).toEqual([]);
    });
  });

  describe('extractOkResponse', () => {
    it('should return the 200 response', () => {
      const okResponse: Response = { description: 'OK' };
      const operation: Operation = {
        responses: {
          '200': okResponse,
          '404': { description: 'Not Found' },
        },
      };

      expect(extractOkResponse(operation)).toBe(okResponse);
    });

    it('should return undefined if no 200 response', () => {
      const operation: Operation = {
        responses: {
          '404': { description: 'Not Found' },
        },
      };

      expect(extractOkResponse(operation)).toBeUndefined();
    });
  });

  describe('extractOperationOkResponseJsonSchema', () => {
    it('should return the JSON schema from OK response', () => {
      const schema: Schema = { type: 'object' };
      const okResponse: Response = {
        description: 'OK',
        content: {
          'application/json': { schema },
        },
      };
      const operation: Operation = {
        responses: {
          '200': okResponse,
        },
      };

      expect(extractOperationOkResponseJsonSchema(operation)).toBe(schema);
    });

    it('should return undefined if no OK response', () => {
      const operation: Operation = {
        responses: {},
      };

      expect(extractOperationOkResponseJsonSchema(operation)).toBeUndefined();
    });

    it('should return undefined if OK response has no content', () => {
      const okResponse: Response = { description: 'OK' };
      const operation: Operation = {
        responses: {
          '200': okResponse,
        },
      };

      expect(extractOperationOkResponseJsonSchema(operation)).toBeUndefined();
    });

    it('should return undefined if OK response has no JSON content', () => {
      const okResponse: Response = {
        description: 'OK',
        content: {
          'text/plain': { schema: { type: 'string' } },
        },
      };
      const operation: Operation = {
        responses: {
          '200': okResponse,
        },
      };

      expect(extractOperationOkResponseJsonSchema(operation)).toBeUndefined();
    });
  });
});
