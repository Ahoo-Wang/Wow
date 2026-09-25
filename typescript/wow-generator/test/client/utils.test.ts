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
  resolveClassName,
  clientModulePath,
  methodToDecorator,
  resolveMethodName,
  uniqueParameterName,
} from '../../src/client';

describe('client utils', () => {
  describe('getClientName', () => {
    it('should generate client class name with suffix', () => {
      const aggregate = {
        aggregateName: 'user',
      };

      const result = resolveClassName(aggregate as any, 'Client');
      expect(result).toBe('UserClient');
    });

    it('should handle complex aggregate names', () => {
      const aggregate = {
        aggregateName: 'user-profile',
      };

      const result = resolveClassName(aggregate as any, 'QueryClient');
      expect(result).toBe('UserProfileQueryClient');
    });
  });

  describe('clientModulePath', () => {
    it('places a client under its bounded context and aggregate', () => {
      const aggregate = {
        contextAlias: 'test-context',
        aggregateName: 'test-aggregate',
      };

      expect(clientModulePath(aggregate as any, 'TestFile')).toBe(
        'test-context/test-aggregate/TestFile.ts',
      );
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
    it.each([
      ['getUserProfile', 'getUserProfile'],
      ['delete_user_by_id', 'deleteUserById'],
      ['get_user_by_id', 'getUserById'],
      ['getUser_1', 'getUser1'],
      ['users.list', 'list'],
      ['user.getProfile', 'getProfile'],
      ['example.cart.add_cart_item', 'addCartItem'],
      ['create', 'create'],
      ['1st-step', '_1stStep'],
    ])('names %s %s', (operationId, methodName) => {
      expect(resolveMethodName({ operationId } as any)).toBe(methodName);
    });

    it('prefers the configured name, then x-fetcher-method', () => {
      const operation = {
        'x-fetcher-method': 'customMethod',
        operationId: 'user.getProfile',
      };
      expect(resolveMethodName(operation as any)).toBe('customMethod');
      expect(resolveMethodName(operation as any, 'configured')).toBe(
        'configured',
      );
    });

    it('accepts a reserved word, which may name a method', () => {
      expect(resolveMethodName({ 'x-fetcher-method': 'delete' } as any)).toBe(
        'delete',
      );
    });

    it('rejects an explicit name that is not a method name', () => {
      expect(() =>
        resolveMethodName({
          operationId: 'users.list',
          'x-fetcher-method': 'list users',
        } as any),
      ).toThrow(
        'x-fetcher-method of users.list is "list users", which is not a valid method name.',
      );
    });

    it('returns undefined without an operationId', () => {
      expect(resolveMethodName({} as any)).toBeUndefined();
    });
  });

  describe('uniqueParameterName', () => {
    it('turns names into identifiers and numbers repeats', () => {
      const used = new Set(['attributes']);
      expect(uniqueParameterName('item-id', used)).toBe('itemId');
      expect(uniqueParameterName('itemId', used)).toBe('itemId2');
      expect(uniqueParameterName('attributes', used)).toBe('attributes2');
      expect(uniqueParameterName('default', used)).toBe('default_');
    });
  });
});
