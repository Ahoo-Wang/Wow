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
import { pascalCase, camelCase, upperSnakeCase } from '../../src/utils';

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

  describe('upperSnakeCase', () => {
    it('should convert string to UPPER_SNAKE_CASE', () => {
      expect(upperSnakeCase('hello world')).toBe('HELLO_WORLD');
      expect(upperSnakeCase('hello-world')).toBe('HELLO_WORLD');
      expect(upperSnakeCase('hello_world')).toBe('HELLO_WORLD');
      expect(upperSnakeCase('hello.world')).toBe('HELLO_WORLD');
      expect(upperSnakeCase('helloWorld')).toBe('HELLO_WORLD');
      expect(upperSnakeCase('HelloWorld')).toBe('HELLO_WORLD');
      expect(upperSnakeCase('userName')).toBe('USER_NAME');
      expect(upperSnakeCase('APIEndpoint')).toBe('APIENDPOINT');
      expect(upperSnakeCase('XMLHttpRequest')).toBe('XMLHTTP_REQUEST');
      expect(upperSnakeCase('getUserById')).toBe('GET_USER_BY_ID');
      expect(upperSnakeCase('POSTRequest')).toBe('POSTREQUEST');
      expect(upperSnakeCase('HTTPStatusCode')).toBe('HTTPSTATUS_CODE');
    });

    it('should handle empty string', () => {
      expect(upperSnakeCase('')).toBe('');
    });

    it('should handle empty array', () => {
      expect(upperSnakeCase([])).toBe('');
    });

    it('should handle array of strings', () => {
      expect(upperSnakeCase(['hello', 'world'])).toBe('HELLO_WORLD');
      expect(upperSnakeCase(['user', 'name'])).toBe('USER_NAME');
      expect(upperSnakeCase(['API', 'endpoint'])).toBe('API_ENDPOINT');
      expect(upperSnakeCase(['XMLHttp', 'request'])).toBe('XMLHTTP_REQUEST');
    });

    it('should handle mixed separators in array', () => {
      expect(upperSnakeCase(['hello-world', 'user_name'])).toBe(
        'HELLO_WORLD_USER_NAME',
      );
      expect(upperSnakeCase(['XML.Http', 'request'])).toBe('XML_HTTP_REQUEST');
    });

    it('should handle single character strings', () => {
      expect(upperSnakeCase('a')).toBe('A');
      expect(upperSnakeCase('A')).toBe('A');
      expect(upperSnakeCase('1')).toBe('1');
    });

    it('should handle strings with numbers', () => {
      expect(upperSnakeCase('user123')).toBe('USER123');
      expect(upperSnakeCase('version2.0')).toBe('VERSION2_0');
      expect(upperSnakeCase('APIv2Endpoint')).toBe('APIV2ENDPOINT');
    });

    it('should handle strings with special characters', () => {
      expect(upperSnakeCase('user@domain')).toBe('USER@DOMAIN');
      expect(upperSnakeCase('test#value')).toBe('TEST#VALUE');
      expect(upperSnakeCase('data$value')).toBe('DATA$VALUE');
    });

    it('should handle consecutive separators', () => {
      expect(upperSnakeCase('hello__world')).toBe('HELLO_WORLD');
      expect(upperSnakeCase('hello--world')).toBe('HELLO_WORLD');
      expect(upperSnakeCase('hello  world')).toBe('HELLO_WORLD');
      expect(upperSnakeCase('hello..world')).toBe('HELLO_WORLD');
    });

    it('should handle mixed case with separators', () => {
      expect(upperSnakeCase('getUserById')).toBe('GET_USER_BY_ID');
      expect(upperSnakeCase('POSTRequest')).toBe('POSTREQUEST');
      expect(upperSnakeCase('HTTPStatusCode')).toBe('HTTPSTATUS_CODE');
    });

    it('should handle array with empty strings', () => {
      expect(upperSnakeCase(['', 'hello', '', 'world', ''])).toBe(
        'HELLO_WORLD',
      );
      expect(upperSnakeCase(['user', '', 'name'])).toBe('USER_NAME');
    });

    it('should handle array with single element', () => {
      expect(upperSnakeCase(['hello'])).toBe('HELLO');
      expect(upperSnakeCase(['API'])).toBe('API');
      expect(upperSnakeCase(['user123'])).toBe('USER123');
    });
  });
});
