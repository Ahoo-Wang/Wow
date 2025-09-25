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

import { describe, it, expect } from 'vitest';

import { resolveModelInfo, pascalCase } from '@/model/naming.ts';

describe('naming', () => {
  describe('pascalCase', () => {
    it('should convert string to PascalCase', () => {
      expect(pascalCase('hello-world')).toBe('HelloWorld');
      expect(pascalCase('hello_world')).toBe('HelloWorld');
      expect(pascalCase('hello.world')).toBe('HelloWorld');
      expect(pascalCase('hello world')).toBe('HelloWorld');
      expect(pascalCase('hello--world')).toBe('HelloWorld');
      expect(pascalCase('hello123-world')).toBe('Hello123World');
    });

    it('should return PascalCase string as is', () => {
      expect(pascalCase('HelloWorld')).toBe('HelloWorld');
      expect(pascalCase('AI')).toBe('AI');
      expect(pascalCase('APIVersion')).toBe('APIVersion');
    });

    it('should handle array input', () => {
      expect(pascalCase(['hello', 'world'])).toBe('HelloWorld');
      expect(pascalCase(['AiMessage', 'Assistant'])).toBe('AiMessageAssistant');
    });
  });

  describe('resolveModelInfo', () => {
    it('should resolve model info from schema key', () => {
      expect(resolveModelInfo('wow.api.BindingError')).toEqual({ path: '/wow/api', name: 'BindingError' });
      expect(resolveModelInfo('compensation.ApiVersion')).toEqual({ path: '/compensation', name: 'ApiVersion' });
      expect(resolveModelInfo('ai.AiMessage.Assistant')).toEqual({ path: '/ai', name: 'AiMessageAssistant' });
      expect(resolveModelInfo('Result')).toEqual({ path: '/', name: 'Result' });
    });
  });
});