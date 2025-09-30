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
  ConsoleLogger: vi.fn(),
}));

vi.mock('../../src', () => ({
  CodeGenerator: vi.fn(),
}));

vi.mock('ts-morph', () => ({
  Project: vi.fn(),
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
  let mockLogger: any;
  let mockCodeGenerator: any;
  let mockProject: any;

  beforeEach(() => {
    vi.clearAllMocks();
    mockLogger = {
      info: vi.fn(),
      success: vi.fn(),
      error: vi.fn(),
    };
    (ConsoleLogger as any).mockImplementation(() => mockLogger);

    mockCodeGenerator = {
      generate: vi.fn(),
    };
    (CodeGenerator as any).mockImplementation(() => mockCodeGenerator);

    mockProject = {};
    (Project as any).mockImplementation(() => mockProject);

    // Mock process.exit
    vi.spyOn(process, 'exit').mockImplementation(() => {
      throw new Error('process.exit called');
    });
  });

  it('should validate input and exit if invalid', async () => {
    await expect(
      generateAction({ input: '', output: '/output' }),
    ).rejects.toThrow('process.exit called');
    expect(mockLogger.error).toHaveBeenCalledWith(
      'Invalid input: must be a valid file path or HTTP/HTTPS URL',
    );
  });

  it('should generate code successfully', async () => {
    mockCodeGenerator.generate.mockResolvedValue(undefined);

    await generateAction({ input: 'http://example.com', output: '/output' });

    expect(mockLogger.info).toHaveBeenCalledWith('Starting code generation...');
    expect(CodeGenerator).toHaveBeenCalledWith({
      inputPath: 'http://example.com',
      outputDir: '/output',
      tsConfigFilePath: undefined,
      logger: mockLogger,
    });
    expect(mockCodeGenerator.generate).toHaveBeenCalled();
    expect(mockLogger.success).toHaveBeenCalledWith(
      'Code generation completed successfully! Files generated in: /output',
    );
  });

  it('should handle generation error', async () => {
    const error = new Error('Generation failed');
    mockCodeGenerator.generate.mockRejectedValue(error);

    await expect(
      generateAction({ input: 'http://example.com', output: '/output' }),
    ).rejects.toThrow('process.exit called');
    expect(mockLogger.error).toHaveBeenCalledWith(
      `Error during code generation: ${error}`,
    );
  });
});
