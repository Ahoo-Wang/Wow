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
import { loadResource, loadHttpResource, loadFile } from '@/utils/resources.ts';
import { readFile } from 'fs';

// Mock fs.readFile
vi.mock('fs', () => ({
  readFile: vi.fn(),
}));

// Mock global fetch
const mockFetch = vi.fn();
global.fetch = mockFetch;

const mockReadFile = vi.mocked(readFile);

describe('resources', () => {
  describe('loadResource', () => {
    it('should call loadHttpResource for HTTP URLs', async () => {
      const url = 'https://example.com/api.json';
      const content = '{ "test": "data" }';
      mockFetch.mockResolvedValue({
        text: () => Promise.resolve(content),
      } as Response);

      const result = await loadResource(url);
      expect(result).toBe(content);
      expect(mockFetch).toHaveBeenCalledWith(url);
    });

    it('should call loadFile for file paths', async () => {
      const path = '/path/to/file.json';
      const content = '{ "test": "data" }';
      mockReadFile.mockImplementation((...args: any[]) => {
        const callback = args[2];
        callback(null, content);
      });

      const result = await loadResource(path);
      expect(result).toBe(content);
      expect(mockReadFile).toHaveBeenCalledWith(
        path,
        'utf-8',
        expect.any(Function),
      );
    });
  });

  describe('loadHttpResource', () => {
    it('should fetch and return text content', async () => {
      const url = 'https://example.com/api.json';
      const content = '{ "test": "data" }';
      mockFetch.mockResolvedValue({
        text: () => Promise.resolve(content),
      } as Response);

      const result = await loadHttpResource(url);
      expect(result).toBe(content);
      expect(mockFetch).toHaveBeenCalledWith(url);
    });

    it('should throw error if fetch fails', async () => {
      const url = 'https://example.com/api.json';
      const error = new Error('Network error');
      mockFetch.mockRejectedValue(error);

      await expect(loadHttpResource(url)).rejects.toThrow('Network error');
    });
  });

  describe('loadFile', () => {
    it('should read file and return content', async () => {
      const path = '/path/to/file.json';
      const content = '{ "test": "data" }';
      mockReadFile.mockImplementation((...args: any[]) => {
        const callback = args[2];
        callback(null, content);
      });

      const result = await loadFile(path);
      expect(result).toBe(content);
      expect(mockReadFile).toHaveBeenCalledWith(
        path,
        'utf-8',
        expect.any(Function),
      );
    });

    it('should throw error if file read fails', async () => {
      const path = '/path/to/file.json';
      const error = new Error('File not found');
      mockReadFile.mockImplementation((...args: any[]) => {
        const callback = args[2];
        callback(error, '');
      });

      await expect(loadFile(path)).rejects.toThrow('File not found');
    });
  });
});
