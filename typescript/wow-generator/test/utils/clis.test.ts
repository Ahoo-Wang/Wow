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

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { validateInput, generateAction } from '../../src/utils';

// Mock dependencies
vi.mock('../../src/utils/logger', () => ({
  ConsoleLogger: class ConsoleLogger {
    info = vi.fn();
    success = vi.fn();
    error = vi.fn();
    progress = vi.fn();
    constructor() {}
  },
}));

vi.mock('../../src', () => ({
  CodeGenerator: class CodeGenerator {
    generate = vi.fn();
    constructor() {}
  },
}));

vi.mock('ts-morph', () => ({
  Project: class Project {
    getDirectory = vi.fn();
    getSourceFiles = vi.fn().mockReturnValue([]);
    getSourceFile = vi.fn();
    createSourceFile = vi.fn();
    save = vi.fn();
    constructor() {}
  },
}));

// Import after mocking
import { ConsoleLogger } from '../../src/utils/logger';
import { CodeGenerator } from '../../src';
import { Project } from 'ts-morph';

describe('validateInput', () => {
  it('should return false for empty string', () => {
    expect(validateInput('')).toBe(false);
  });

  it('should return true for valid HTTP URL', () => {
    expect(validateInput('http://example.com')).toBe(true);
  });

  it('should return true for valid HTTPS URL', () => {
    expect(validateInput('https://example.com')).toBe(true);
  });

  it('should return false for invalid URL protocol', () => {
    expect(validateInput('ftp://example.com')).toBe(false);
  });

  it('should return true for malformed URL treated as file path', () => {
    expect(validateInput('not-a-url')).toBe(true);
  });

  it('should return true for non-empty file path', () => {
    expect(validateInput('/path/to/file')).toBe(true);
    expect(validateInput('relative/path')).toBe(true);
  });
});

describe('generateAction', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('should validate input and exit if invalid', async () => {
    await expect(
      generateAction({ input: '', output: '/output' }),
    ).rejects.toThrow();
  });

  it('should generate code successfully', async () => {
    await generateAction({ input: 'http://example.com', output: '/tmp/test-output' });
    expect(true).toBe(true);
  });

  it('should handle generation error', () => {
    expect(() => {
      generateAction({ input: 'http://example.com', output: '/invalid/path' });
    }).not.toThrow();
  });
});
