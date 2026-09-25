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
  camelCase,
  pascalCase,
  toTypeIdentifier,
  upperSnakeCase,
} from '../../src/naming/naming';

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
      // A character no identifier may hold separates words.
      expect(pascalCase('user@domain')).toBe('UserDomain');
      expect(pascalCase('Page«User»')).toBe('PageUser');
    });
  });

  describe('toTypeIdentifier', () => {
    it('keeps a part that starts upper-case and has no separator, acronyms included', () => {
      expect(toTypeIdentifier('MCPListTools')).toBe('MCPListTools');
      expect(toTypeIdentifier('OpenAIFile')).toBe('OpenAIFile');
      expect(toTypeIdentifier('RealtimeMCPHTTPError')).toBe(
        'RealtimeMCPHTTPError',
      );
      expect(toTypeIdentifier('Foo$Bar')).toBe('Foo$Bar');
      expect(toTypeIdentifier('Ünïcode')).toBe('Ünïcode');
    });

    it('pascal-cases a part with a separator or a lower-case start', () => {
      expect(toTypeIdentifier('order_item')).toBe('OrderItem');
      expect(toTypeIdentifier('MCP_list_tools')).toBe('McpListTools');
      expect(toTypeIdentifier('Page«User»')).toBe('PageUser');
      expect(toTypeIdentifier('cart')).toBe('Cart');
      expect(toTypeIdentifier('byteArray')).toBe('ByteArray');
    });

    it('decides part by part', () => {
      expect(toTypeIdentifier(['AiMessage', 'Assistant'])).toBe(
        'AiMessageAssistant',
      );
      expect(toTypeIdentifier(['MCPTool', 'call_status'])).toBe(
        'MCPToolCallStatus',
      );
      expect(toTypeIdentifier(['Foo', '', 'Bar'])).toBe('FooBar');
    });

    it('prefixes a leading digit, and names nothing _', () => {
      expect(toTypeIdentifier('1stThing')).toBe('_1stThing');
      expect(toTypeIdentifier('')).toBe('_');
      expect(toTypeIdentifier([])).toBe('_');
      expect(toTypeIdentifier('«»')).toBe('_');
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
      expect(upperSnakeCase('user@domain')).toBe('USER_DOMAIN');
      expect(upperSnakeCase('test#value')).toBe('TEST_VALUE');
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
