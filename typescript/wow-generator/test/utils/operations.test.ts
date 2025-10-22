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

import { describe, expect, it, vi } from 'vitest';
import {
  extractPathParameters,
  resolvePathParameterType,
  operationEndpointComparator,
  OperationEndpoint,
} from '../../src/utils';
import {
  Operation,
  Components,
  Parameter,
  Reference,
} from '@ahoo-wang/fetcher-openapi';
import { extractParameter } from '../../src/utils';
import { isReference } from '../../src/utils';
import { isPrimitive, resolvePrimitiveType } from '../../src/utils';

// Mock the dependencies
vi.mock('../../src/utils/components', () => ({
  extractParameter: vi.fn(),
}));

vi.mock('../../src/utils/references', () => ({
  isReference: vi.fn(),
}));

vi.mock('../../src/utils/schemas', () => ({
  isPrimitive: vi.fn(),
  resolvePrimitiveType: vi.fn(),
}));

describe('operations', () => {
  describe('extractPathParameters', () => {
    it('should return empty array if operation has no parameters', () => {
      const operation: Operation = {
        responses: {},
      };
      const components: Components = {};

      const result = extractPathParameters(operation, components);
      expect(result).toEqual([]);
    });

    it('should extract path parameters from operation parameters', () => {
      const pathParameter: Parameter = {
        name: 'id',
        in: 'path',
        schema: { type: 'string' },
      };
      const queryParameter: Parameter = {
        name: 'filter',
        in: 'query',
        schema: { type: 'string' },
      };
      const operation: Operation = {
        parameters: [pathParameter, queryParameter],
        responses: {},
      };
      const components: Components = {};

      const result = extractPathParameters(operation, components);
      expect(result).toEqual([pathParameter]);
    });

    it('should resolve reference parameters', () => {
      const reference: Reference = {
        $ref: '#/components/parameters/UserId',
      };
      const resolvedParameter: Parameter = {
        name: 'id',
        in: 'path',
        schema: { type: 'integer' },
      };

      vi.mocked(isReference).mockImplementation(
        (param: any): param is Reference => {
          return param && '$ref' in param;
        },
      );
      vi.mocked(extractParameter).mockReturnValue(resolvedParameter);

      const operation: Operation = {
        parameters: [reference],
        responses: {},
      };
      const components: Components = {};

      const result = extractPathParameters(operation, components);
      expect(isReference).toHaveBeenCalledWith(reference);
      expect(extractParameter).toHaveBeenCalledWith(reference, components);
      expect(result).toEqual([resolvedParameter]);
    });

    it('should filter out non-path parameters after resolving references', () => {
      const pathParameter: Parameter = {
        name: 'id',
        in: 'path',
        schema: { type: 'string' },
      };
      const queryParameter: Parameter = {
        name: 'filter',
        in: 'query',
        schema: { type: 'string' },
      };
      const referenceToQueryParameter: Reference = {
        $ref: '#/components/parameters/Filter',
      };
      const resolvedQueryParameter: Parameter = {
        name: 'resolvedFilter',
        in: 'query',
        schema: { type: 'integer' },
      };

      vi.mocked(isReference).mockImplementation(
        (param: any): param is Reference => {
          return param && '$ref' in param;
        },
      );
      vi.mocked(extractParameter).mockReturnValue(resolvedQueryParameter);

      const operation: Operation = {
        parameters: [pathParameter, queryParameter, referenceToQueryParameter],
        responses: {},
      };
      const components: Components = {};

      const result = extractPathParameters(operation, components);
      expect(result).toEqual([pathParameter]);
    });
  });

  describe('resolvePathParameterType', () => {
    it('should return default type when parameter has no schema', () => {
      const parameter: Parameter = {
        name: 'id',
        in: 'path',
      };

      const result = resolvePathParameterType(parameter);
      expect(result).toBe('string');
    });

    it('should return default type when parameter schema is a reference', () => {
      const parameter: Parameter = {
        name: 'id',
        in: 'path',
        schema: {
          $ref: '#/components/schemas/UserId',
        },
      };

      vi.mocked(isReference).mockReturnValue(true);

      const result = resolvePathParameterType(parameter);
      expect(isReference).toHaveBeenCalledWith(parameter.schema);
      expect(result).toBe('string');
    });

    it('should return default type when parameter schema has no type', () => {
      const parameter: Parameter = {
        name: 'id',
        in: 'path',
        schema: {},
      };

      vi.mocked(isReference).mockReturnValue(false);

      const result = resolvePathParameterType(parameter);
      expect(isReference).toHaveBeenCalledWith(parameter.schema);
      expect(result).toBe('string');
    });

    it('should return default type when parameter schema type is not primitive', () => {
      const parameter: Parameter = {
        name: 'id',
        in: 'path',
        schema: {
          type: 'object',
        },
      };

      vi.mocked(isReference).mockReturnValue(false);
      vi.mocked(isPrimitive).mockReturnValue(false);

      const result = resolvePathParameterType(parameter);
      expect(isReference).toHaveBeenCalledWith(parameter.schema);
      expect(isPrimitive).toHaveBeenCalledWith('object');
      expect(result).toBe('string');
    });

    it('should resolve primitive type when all conditions are met', () => {
      const parameter: Parameter = {
        name: 'id',
        in: 'path',
        schema: {
          type: 'integer',
        },
      };

      vi.mocked(isReference).mockReturnValue(false);
      vi.mocked(isPrimitive).mockReturnValue(true);
      vi.mocked(resolvePrimitiveType).mockReturnValue('number');

      const result = resolvePathParameterType(parameter);
      expect(isReference).toHaveBeenCalledWith(parameter.schema);
      expect(isPrimitive).toHaveBeenCalledWith('integer');
      expect(resolvePrimitiveType).toHaveBeenCalledWith('integer');
      expect(result).toBe('number');
    });
  });

  describe('operationEndpointComparator', () => {
    it('should return 0 when both operations have no operationId, path, or method', () => {
      const left: OperationEndpoint = {
        method: '' as any,
        operation: { responses: {} },
        path: '',
      };
      const right: OperationEndpoint = {
        method: '' as any,
        operation: { responses: {} },
        path: '',
      };

      const result = operationEndpointComparator(left, right);
      expect(result).toBe(0);
    });

    it('should compare by operationId when both operations have operationId', () => {
      const left: OperationEndpoint = {
        method: 'get',
        operation: { operationId: 'getUser', responses: {} },
        path: '/users',
      };
      const right: OperationEndpoint = {
        method: 'get',
        operation: { operationId: 'createUser', responses: {} },
        path: '/users',
      };

      const result = operationEndpointComparator(left, right);
      expect(result).toBeGreaterThan(0); // 'getUser' > 'createUser'
    });

    it('should return negative when left operationId comes before right operationId', () => {
      const left: OperationEndpoint = {
        method: 'get',
        operation: { operationId: 'createUser', responses: {} },
        path: '/users',
      };
      const right: OperationEndpoint = {
        method: 'get',
        operation: { operationId: 'getUser', responses: {} },
        path: '/users',
      };

      const result = operationEndpointComparator(left, right);
      expect(result).toBeLessThan(0); // 'createUser' < 'getUser'
    });

    it('should compare by path when neither operation has operationId but both have path', () => {
      const left: OperationEndpoint = {
        method: 'get',
        operation: { responses: {} },
        path: '/users',
      };
      const right: OperationEndpoint = {
        method: 'get',
        operation: { responses: {} },
        path: '/posts',
      };

      const result = operationEndpointComparator(left, right);
      expect(result).toBeGreaterThan(0); // '/users' > '/posts'
    });

    it('should compare by method when neither operation has operationId or path but both have method', () => {
      const left: OperationEndpoint = {
        method: 'post',
        operation: { responses: {} },
        path: '',
      };
      const right: OperationEndpoint = {
        method: 'get',
        operation: { responses: {} },
        path: '',
      };

      const result = operationEndpointComparator(left, right);
      expect(result).toBeGreaterThan(0); // 'post' > 'get'
    });

    it('should prioritize operationId over path and method', () => {
      const left: OperationEndpoint = {
        method: 'get',
        operation: { operationId: 'getUser', responses: {} },
        path: '/users',
      };
      const right: OperationEndpoint = {
        method: 'post',
        operation: { responses: {} },
        path: '/posts',
      };

      const result = operationEndpointComparator(left, right);
      // Should compare by operationId, not by path or method
      expect(result).toBeGreaterThan(0); // 'getUser' > undefined operationId
    });

    it('should prioritize path over method when no operationId', () => {
      const left: OperationEndpoint = {
        method: 'get',
        operation: { responses: {} },
        path: '/users',
      };
      const right: OperationEndpoint = {
        method: 'post',
        operation: { responses: {} },
        path: '/posts',
      };

      const result = operationEndpointComparator(left, right);
      // Should compare by path, not by method
      expect(result).toBeGreaterThan(0); // '/users' > '/posts'
    });

    it('should handle undefined operationId gracefully', () => {
      const left: OperationEndpoint = {
        method: 'get',
        operation: { operationId: 'getUser', responses: {} },
        path: '/users',
      };
      const right: OperationEndpoint = {
        method: 'get',
        operation: { responses: {} },
        path: '/users',
      };

      const result = operationEndpointComparator(left, right);
      expect(result).toBeGreaterThan(0); // defined operationId > undefined operationId
    });

    it('should handle empty strings as equivalent to undefined', () => {
      const left: OperationEndpoint = {
        method: 'get',
        operation: { operationId: '', responses: {} },
        path: '/users',
      };
      const right: OperationEndpoint = {
        method: 'get',
        operation: { responses: {} },
        path: '/posts',
      };

      const result = operationEndpointComparator(left, right);
      // Both have no operationId (empty string is falsy), so compare by path
      expect(result).toBeGreaterThan(0); // '/users' > '/posts'
    });
  });
});
