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
import { inferPathSpecType, getClientName, createClientFilePath } from '../../src/client/utils';
import { ResourceAttributionPathSpec } from '@ahoo-wang/fetcher-wow';

// Mock the dependencies
vi.mock('../../src/utils', () => ({
  getOrCreateSourceFile: vi.fn(() => 'mock-source-file'),
  pascalCase: vi.fn((name) => {
    // Mock implementation that matches the actual pascalCase function behavior
    if (name === '' || (Array.isArray(name) && name.length === 0)) {
      return '';
    }

    let names: string[];
    if (Array.isArray(name)) {
      names = name.flatMap(part => part.split(/[-_\s.]+|(?=[A-Z])/));
    } else {
      names = name.split(/[-_\s.]+|(?=[A-Z])/);
    }

    return names
      .filter(part => part.length > 0)
      .map(part => {
        if (part.length === 0) return '';
        const firstChar = part.charAt(0);
        const rest = part.slice(1);
        return (
          (/[a-zA-Z]/.test(firstChar) ? firstChar.toUpperCase() : firstChar) +
          rest.toLowerCase()
        );
      })
      .join('');
  }),
}));

describe('client utils', () => {
  describe('inferPathSpecType', () => {
    it('should return NONE when no commands have tenant or owner specs', () => {
      const aggregateDefinition = {
        commands: [
          { path: '/api/users' },
          { path: '/api/products' },
        ],
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

      const result = createClientFilePath(mockProject as any, outputDir, aggregate as any, fileName);
      expect(result).toBe('mock-source-file');
    });
  });
});