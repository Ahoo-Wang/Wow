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
import { resolveModelInfo, WOW_TYPE_MAPPING } from '../../src/model';

// Mock the pascalCase function to avoid dependencies on the actual implementation
vi.mock('@/utils/naming.ts', () => ({
  pascalCase: vi.fn(parts => {
    if (Array.isArray(parts)) {
      return parts.join('');
    }
    return parts;
  }),
}));

describe('modelInfo', () => {
  describe('resolveModelInfo', () => {
    it('should return empty name and root path for empty schemaKey', () => {
      const result = resolveModelInfo('');
      expect(result).toEqual({ name: '', path: '/' });
    });

    it('should return mapped type and WOW import path for known schema keys', () => {
      const schemaKey = 'wow.api.BindingError';
      const result = resolveModelInfo(schemaKey);
      expect(result).toEqual({
        name: WOW_TYPE_MAPPING[schemaKey as keyof typeof WOW_TYPE_MAPPING],
        path: '@ahoo-wang/fetcher-wow',
      });
    });

    it('should correctly parse schema key with model name at the end', () => {
      const result = resolveModelInfo('compensation.ApiVersion');
      expect(result).toEqual({
        name: 'ApiVersion',
        path: '/compensation',
      });
    });

    it('should correctly parse schema key with multiple parts after model name', () => {
      const result = resolveModelInfo('ai.AiMessage.Assistant');
      expect(result).toEqual({
        name: 'AiMessageAssistant',
        path: '/ai',
      });
    });

    it('should treat whole string as name when no uppercase letter is found', () => {
      const result = resolveModelInfo('result');
      expect(result).toEqual({
        name: 'result',
        path: '/',
      });
    });

    it('should handle schema key with model name at the beginning', () => {
      const result = resolveModelInfo('User');
      expect(result).toEqual({
        name: 'User',
        path: '/',
      });
    });

    it('should handle schema key with model name in the middle', () => {
      const result = resolveModelInfo('com.example.UserProfile');
      expect(result).toEqual({
        name: 'UserProfile',
        path: '/com/example',
      });
    });

    it('should handle schema key with no path parts', () => {
      const result = resolveModelInfo('User');
      expect(result).toEqual({
        name: 'User',
        path: '/',
      });
    });
  });
});
