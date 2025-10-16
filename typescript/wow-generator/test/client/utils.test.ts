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
  inferPathSpecType,
  getClientName,
  createClientFilePath,
  methodToDecorator,
  resolveMethodName,
} from '../../src/client';
import { ResourceAttributionPathSpec } from '@ahoo-wang/fetcher-wow';

// Mock the dependencies
vi.mock('../../src/utils', async (importOriginal) => {
  const actual: any = await importOriginal();
  return {
    ...actual,
    getOrCreateSourceFile: vi.fn(() => 'mock-source-file'),
  };
});

describe('client utils', () => {
  describe('inferPathSpecType', () => {
    it('should return NONE when no commands have tenant or owner specs', () => {
      const aggregateDefinition = {
        commands: [{ path: '/api/users' }, { path: '/api/products' }],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.NONE');
    });

    it('should return TENANT when most commands have tenant spec', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.TENANT + '/users' },
          { path: ResourceAttributionPathSpec.TENANT + '/products' },
          { path: ResourceAttributionPathSpec.OWNER + '/orders' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.TENANT');
    });

    it('should return OWNER when most commands have owner spec', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.OWNER + '/users' },
          { path: ResourceAttributionPathSpec.OWNER + '/products' },
          { path: ResourceAttributionPathSpec.TENANT + '/orders' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.OWNER');
    });

    it('should return OWNER when equal number of tenant and owner specs', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.TENANT + '/users' },
          { path: ResourceAttributionPathSpec.OWNER + '/products' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.OWNER');
    });

    it('should return TENANT when only tenant specs are present', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.TENANT + '/users' },
          { path: ResourceAttributionPathSpec.TENANT + '/products' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.TENANT');
    });

    it('should return OWNER when only owner specs are present', () => {
      const aggregateDefinition = {
        commands: [
          { path: ResourceAttributionPathSpec.OWNER + '/users' },
          { path: ResourceAttributionPathSpec.OWNER + '/products' },
        ],
      };

      const result = inferPathSpecType(aggregateDefinition as any);
      expect(result).toBe('ResourceAttributionPathSpec.OWNER');
    });
  });

  describe('getClientName', () => {
    it('should generate client class name with suffix', () => {
      const aggregate = {
        aggregateName: 'user',
      };

      const result = getClientName(aggregate as any, 'Client');
      expect(result).toBe('UserClient');
    });

    it('should handle complex aggregate names', () => {
      const aggregate = {
        aggregateName: 'user-profile',
      };

      const result = getClientName(aggregate as any, 'QueryClient');
      expect(result).toBe('UserProfileQueryClient');
    });
  });

  describe('createClientFilePath', () => {
    it('should create file path with correct structure', () => {
      const mockProject = {};
      const outputDir = './output';
      const aggregate = {
        contextAlias: 'test-context',
        aggregateName: 'test-aggregate',
      };
      const fileName = 'TestFile';

      const result = createClientFilePath(
        mockProject as any,
        outputDir,
        aggregate as any,
        fileName,
      );
      expect(result).toBe('mock-source-file');
    });
  });

  describe('methodToDecorator', () => {
    it('should return "del" for delete method', () => {
      const result = methodToDecorator('delete');
      expect(result).toBe('del');
    });

    it('should return the same method name for non-delete methods', () => {
      expect(methodToDecorator('get')).toBe('get');
      expect(methodToDecorator('post')).toBe('post');
      expect(methodToDecorator('put')).toBe('put');
      expect(methodToDecorator('patch')).toBe('patch');
      expect(methodToDecorator('head')).toBe('head');
      expect(methodToDecorator('options')).toBe('options');
    });
  });

  describe('resolveMethodName', () => {
    it('should return custom method name from x-fetcher-method extension', () => {
      const operation = {
        'x-fetcher-method': 'customMethod',
        operationId: 'user.getProfile',
      };
      const isExists = vi.fn();

      const result = resolveMethodName(operation as any, isExists);

      expect(result).toBe('customMethod');
      expect(isExists).not.toHaveBeenCalled();
    });

    it('should return undefined when operation has no operationId', () => {
      const operation = {};
      const isExists = vi.fn();

      const result = resolveMethodName(operation as any, isExists);

      expect(result).toBeUndefined();
      expect(isExists).not.toHaveBeenCalled();
    });

    it('should return the shortest unique method name from operationId', () => {
      const operation = { operationId: 'user.getProfile' };
      const isExists = vi.fn(() => false); // No methods exist

      const result = resolveMethodName(operation as any, isExists);

      expect(result).toBe('getProfile');
      expect(isExists).toHaveBeenCalledWith('getProfile');
    });

    it('should try shorter suffixes when longer ones exist', () => {
      const operation = { operationId: 'user.get.profile' };
      const isExists = vi.fn(name => name === 'profile'); // Only 'profile' exists

      const result = resolveMethodName(operation as any, isExists);

      expect(result).toBe('getProfile');
      expect(isExists).toHaveBeenCalledWith('profile');
      expect(isExists).toHaveBeenCalledWith('getProfile');
    });

    it('should return full camelCase name when no unique method found', () => {
      const operation = { operationId: 'user.get.profile' };
      const isExists = vi.fn(() => true); // All methods exist

      const result = resolveMethodName(operation as any, isExists);

      expect(result).toBe('userGetProfile');
    });

    it('should handle single part operationId', () => {
      const operation = { operationId: 'create' };
      const isExists = vi.fn(() => false);

      const result = resolveMethodName(operation as any, isExists);

      expect(result).toBe('create');
    });

    it('should handle operationId with underscores', () => {
      const operation = { operationId: 'user_create_profile' };
      const isExists = vi.fn(() => false);

      const result = resolveMethodName(operation as any, isExists);

      expect(result).toBe('profile');
    });

    it('should handle operationId with camelCase', () => {
      const operation = { operationId: 'getUserProfile' };
      const isExists = vi.fn(() => false);

      const result = resolveMethodName(operation as any, isExists);

      expect(result).toBe('getUserProfile');
    });
  });
});
