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
  parseOpenAPI,
  inferFileFormat,
  FileFormat,
} from '@/utils/openAPIParser.ts';
import { loadResource } from '@/utils/resources.ts';

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
      });
      expect(mockLoadResource).toHaveBeenCalledWith('test.json');
    });

    it('should parse YAML content', async () => {
      const yamlContent = 'openapi: 3.0.0\ninfo:\n  title: Test API';
      mockLoadResource.mockResolvedValue(yamlContent);

      const result = await parseOpenAPI('test.yaml');
      expect(result).toEqual({
        openapi: '3.0.0',
        info: { title: 'Test API' },
      });
      expect(mockLoadResource).toHaveBeenCalledWith('test.yaml');
    });

    it('should throw error for invalid YAML content', async () => {
      mockLoadResource.mockResolvedValue('invalid: content: [unbalanced');

      await expect(parseOpenAPI('test.unknown')).rejects.toThrow();
    });
  });
});
