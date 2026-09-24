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
  FileFormat,
  inferFileFormat,
  parseOpenAPI,
  parseContent,
  validateOpenAPIDocument,
} from '../../src/utils';
import { GeneratorError } from '../../src/errors';
import { loadResource } from '../../src/utils';

// Mock the loadResource function
vi.mock('@/utils/resources.ts', () => ({
  loadResource: vi.fn(),
}));

const mockLoadResource = vi.mocked(loadResource);

describe('openAPIParser', () => {
  describe('FileFormat enum', () => {
    it('should have JSON and YAML values', () => {
      expect(FileFormat.JSON).toBe('json');
      expect(FileFormat.YAML).toBe('yaml');
    });
  });

  describe('inferFileFormat', () => {
    it('should infer JSON for content starting with {', () => {
      expect(inferFileFormat('{ "openapi": "3.0.0" }')).toBe(FileFormat.JSON);
    });

    it('should infer JSON for content starting with [', () => {
      expect(inferFileFormat('[{ "openapi": "3.0.0" }]')).toBe(FileFormat.JSON);
    });

    it('should infer YAML for content starting with -', () => {
      expect(inferFileFormat('- openapi: 3.0.0')).toBe(FileFormat.YAML);
    });

    it('should infer YAML for content starting with %YAML', () => {
      expect(inferFileFormat('%YAML 1.2\nopenapi: 3.0.0')).toBe(
        FileFormat.YAML,
      );
    });

    it('should infer JSON for valid JSON not starting with { or [', () => {
      expect(inferFileFormat('true')).toBe(FileFormat.JSON);
      expect(inferFileFormat('"string"')).toBe(FileFormat.JSON);
      expect(inferFileFormat('42')).toBe(FileFormat.JSON);
    });

    it('should infer YAML for invalid JSON with content', () => {
      expect(inferFileFormat('openapi: 3.0.0')).toBe(FileFormat.YAML);
    });

    it('should throw error for empty content', () => {
      expect(() => inferFileFormat('')).toThrow('Unable to infer file format');
    });

    it('should throw error for whitespace only', () => {
      expect(() => inferFileFormat('   \n\t  ')).toThrow(
        'Unable to infer file format',
      );
    });
  });

  describe('parseOpenAPI', () => {
    it('should parse JSON content', async () => {
      const jsonContent =
        '{ "openapi": "3.0.0", "info": { "title": "Test API" } }';
      mockLoadResource.mockResolvedValue(jsonContent);

      const result = await parseOpenAPI('test.json');
      expect(result).toEqual({
        openapi: '3.0.0',
        info: { title: 'Test API' },
        paths: {},
      });
      expect(mockLoadResource).toHaveBeenCalledWith('test.json', undefined);
    });

    it('should parse YAML content', async () => {
      const yamlContent = 'openapi: 3.0.0\ninfo:\n  title: Test API';
      mockLoadResource.mockResolvedValue(yamlContent);

      const result = await parseOpenAPI('test.yaml');
      expect(result).toEqual({
        openapi: '3.0.0',
        info: { title: 'Test API' },
        paths: {},
      });
      expect(mockLoadResource).toHaveBeenCalledWith('test.yaml', undefined);
    });

    it('should throw error for invalid YAML content', async () => {
      mockLoadResource.mockResolvedValue('invalid: content: [unbalanced');

      await expect(parseOpenAPI('test.unknown')).rejects.toThrow(
        /^Cannot parse the OpenAPI document test\.unknown: /,
      );
    });

    it('names the document when it cannot be read, as an input error', async () => {
      mockLoadResource.mockRejectedValue(new Error('HTTP 404 Not Found'));

      const error = await parseOpenAPI('http://x/v3/api-docs').catch(e => e);
      expect(error).toBeInstanceOf(GeneratorError);
      expect(error.kind).toBe('input');
      expect(error.message).toBe(
        'Cannot read the OpenAPI document http://x/v3/api-docs: HTTP 404 Not Found',
      );
    });

    it('passes headers and timeout through to the loader', async () => {
      mockLoadResource.mockResolvedValue('{"openapi":"3.1.0","info":{}}');
      const options = { headers: { Authorization: 'Bearer t' }, timeoutMs: 5 };

      await parseOpenAPI('http://x/v3/api-docs', options);
      expect(mockLoadResource).toHaveBeenCalledWith(
        'http://x/v3/api-docs',
        options,
      );
    });
  });

  describe('validateOpenAPIDocument', () => {
    it.each([
      ['{"foo":1}', /its "openapi" field is missing/],
      ['[1]', /expected an object at the top level/],
      ['"x"', /expected an object at the top level/],
      ['{"openapi":"2.0","info":{}}', /its "openapi" field is "2\.0"/],
      ['{"openapi":3.1,"info":{}}', /its "openapi" field is 3\.1/],
      ['{"openapi":"3.0.1"}', /its "info" object is missing/],
    ])('refuses %s', (content, message) => {
      const error = (() => {
        try {
          validateOpenAPIDocument(JSON.parse(content), 'spec.json');
        } catch (e) {
          return e as GeneratorError;
        }
      })();
      expect(error).toBeInstanceOf(GeneratorError);
      expect(error!.kind).toBe('input');
      expect(error!.message).toMatch(message);
    });

    it('refuses Swagger 2.0 with a conversion hint instead of generating without models', () => {
      expect(() =>
        validateOpenAPIDocument({ swagger: '2.0', info: {} }, 'spec.json'),
      ).toThrow(
        'spec.json is a Swagger 2.0 document; wow-generator reads OpenAPI 3.x only. Convert it first, for example with swagger2openapi.',
      );
    });

    it('accepts a 3.1 document without paths', () => {
      expect(
        validateOpenAPIDocument(
          { openapi: '3.1.0', info: {}, components: {} },
          'spec.json',
        ),
      ).toEqual({ openapi: '3.1.0', info: {}, components: {}, paths: {} });
    });
  });

  describe('parseContent', () => {
    it('should parse JSON content', () => {
      expect(
        parseContent(
          '{ "apiClients": { "test": { "ignorePathParameters": [] } } }',
        ),
      ).toEqual({ apiClients: { test: { ignorePathParameters: [] } } });
    });

    it('should parse YAML content', () => {
      expect(
        parseContent('apiClients:\n  test:\n    ignorePathParameters: []'),
      ).toEqual({ apiClients: { test: { ignorePathParameters: [] } } });
    });

    it('should throw when the format cannot be inferred', () => {
      expect(() => parseContent('')).toThrow('Unable to infer file format');
    });
  });
});
