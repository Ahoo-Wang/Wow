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
import { loadResource, loadHttpResource, loadFile } from '../../src/utils';
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
        ok: true,
        text: () => Promise.resolve(content),
      } as Response);

      const result = await loadResource(url);
      expect(result).toBe(content);
      expect(mockFetch).toHaveBeenCalledWith(url, {
        headers: undefined,
        signal: expect.any(AbortSignal),
      });
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
        ok: true,
        text: () => Promise.resolve(content),
      } as Response);

      const result = await loadHttpResource(url, {
        headers: { Authorization: 'Bearer token' },
      });
      expect(result).toBe(content);
      expect(mockFetch).toHaveBeenCalledWith(url, {
        headers: { Authorization: 'Bearer token' },
        signal: expect.any(AbortSignal),
      });
    });

    it('should throw error if fetch fails', async () => {
      const url = 'https://example.com/api.json';
      const error = new Error('Network error');
      mockFetch.mockRejectedValue(error);

      await expect(loadHttpResource(url)).rejects.toThrow('Network error');
    });

    it('names the underlying cause Node hides behind "fetch failed"', async () => {
      mockFetch.mockRejectedValue(
        new TypeError('fetch failed', {
          cause: new Error('getaddrinfo ENOTFOUND nonexistent.invalid'),
        }),
      );

      await expect(
        loadHttpResource('https://nonexistent.invalid/v3/api-docs'),
      ).rejects.toThrow(
        'fetch failed (getaddrinfo ENOTFOUND nonexistent.invalid)',
      );
    });

    it('fails on a response outside 2xx instead of parsing an error page', async () => {
      const text = vi.fn();
      mockFetch.mockResolvedValue({
        ok: false,
        status: 401,
        statusText: 'Unauthorized',
        text,
      } as unknown as Response);

      await expect(
        loadHttpResource('https://example.com/v3/api-docs'),
      ).rejects.toThrow('HTTP 401 Unauthorized');
      expect(text).not.toHaveBeenCalled();
    });

    it('abandons a request after the timeout', async () => {
      mockFetch.mockImplementation(
        (_url: string, init: RequestInit) =>
          new Promise((_resolve, reject) => {
            init.signal!.addEventListener('abort', () =>
              reject(init.signal!.reason),
            );
          }),
      );

      await expect(
        loadHttpResource('https://example.com/v3/api-docs', { timeoutMs: 10 }),
      ).rejects.toThrow('no response within 10 ms');
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
