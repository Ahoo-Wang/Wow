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
import { pascalCase, camelCase } from '@/utils/naming.ts';

describe('naming', () => {
  describe('pascalCase', () => {
    it('should convert string to PascalCase', () => {
      expect(pascalCase('hello world')).toBe('HelloWorld');
      expect(pascalCase('hello-world')).toBe('HelloWorld');
      expect(pascalCase('hello_world')).toBe('HelloWorld');
      expect(pascalCase('hello.world')).toBe('HelloWorld');
      expect(pascalCase('helloWorld')).toBe('HelloWorld');
    });

    it('should handle empty string', () => {
      expect(pascalCase('')).toBe('');
    });

    it('should handle array of strings', () => {
      expect(pascalCase(['hello', 'world'])).toBe('HelloWorld');
      expect(pascalCase(['user', 'name'])).toBe('UserName');
    });

    it('should handle mixed separators in array', () => {
      expect(pascalCase(['hello-world', 'user_name'])).toBe(
        'HelloWorldUserName',
      );
    });

    it('should preserve numbers and special chars as is', () => {
      expect(pascalCase('user123')).toBe('User123');
      expect(pascalCase('user@domain')).toBe('User@domain');
    });
  });

  describe('camelCase', () => {
    it('should convert string to camelCase', () => {
      expect(camelCase('hello world')).toBe('helloWorld');
      expect(camelCase('hello-world')).toBe('helloWorld');
      expect(camelCase('hello_world')).toBe('helloWorld');
      expect(camelCase('hello.world')).toBe('helloWorld');
      expect(camelCase('HelloWorld')).toBe('helloWorld');
    });

    it('should handle empty string', () => {
      expect(camelCase('')).toBe('');
    });

    it('should handle array of strings', () => {
      expect(camelCase(['hello', 'world'])).toBe('helloWorld');
      expect(camelCase(['user', 'name'])).toBe('userName');
    });

    it('should handle mixed separators in array', () => {
      expect(camelCase(['hello-world', 'user_name'])).toBe(
        'helloWorldUserName',
      );
    });
  });
});
