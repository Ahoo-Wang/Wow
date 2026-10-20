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
  resolveModelInfo,
  resolveReferenceModelInfo,
} from '../../src/analysis/modelInfo';

describe('modelInfo', () => {
  describe('resolveModelInfo', () => {
    it('should return empty name and root path for empty schemaKey', () => {
      const result = resolveModelInfo('');
      expect(result).toEqual({ name: '', path: '/' });
    });

    it('names a wow. schema like any other, as a model of its own', () => {
      expect(resolveModelInfo('wow.api.BindingError')).toEqual({
        name: 'BindingError',
        path: '/wow/api',
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
        name: 'Result',
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
    it('should keep the acronyms of a model name', () => {
      expect(resolveModelInfo('ai.MCPListTools')).toEqual({
        name: 'MCPListTools',
        path: '/ai',
      });
      expect(resolveModelInfo('ai.MCPTool.call_status')).toEqual({
        name: 'MCPToolCallStatus',
        path: '/ai',
      });
    });

    it('should handle schema key with api.byteArray', () => {
      const result = resolveModelInfo('api.byteArray');
      expect(result).toEqual({
        name: 'ByteArray',
        path: '/api',
      });
    });
  });

  describe('resolveReferenceModelInfo', () => {
    it('names the model a component reference points at', () => {
      expect(
        resolveReferenceModelInfo({ $ref: '#/components/schemas/shop.Item' }),
      ).toEqual({ name: 'Item', path: '/shop' });
    });

    it('refuses a reference outside the component schemas', () => {
      expect(() =>
        resolveReferenceModelInfo({ $ref: 'other.json#/Item' }),
      ).toThrow(/Unsupported schema reference: other.json#\/Item/);
    });
  });
});
