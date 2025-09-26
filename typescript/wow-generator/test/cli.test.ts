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

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Project } from 'ts-morph';

// Mock dependencies
vi.mock('commander', () => ({
  program: {
    name: vi.fn().mockReturnThis(),
    description: vi.fn().mockReturnThis(),
    version: vi.fn().mockReturnThis(),
    command: vi.fn().mockReturnThis(),
    requiredOption: vi.fn().mockReturnThis(),
    option: vi.fn().mockReturnThis(),
    action: vi.fn(),
    parse: vi.fn(),
  },
}));

vi.mock('ts-morph', () => ({
  Project: vi.fn(),
}));

vi.mock('../../src/index', () => ({
  CodeGenerator: vi.fn(),
}));

vi.mock('../../src/utils/logger', () => ({
  ConsoleLogger: vi.fn(),
}));

// Import after mocking
import { CodeGenerator } from '../../src/index';
import { ConsoleLogger } from '../../src/utils/logger';
import { generateAction } from '../../src/cli';

describe('CLI generateAction', () => {
  let mockCodeGenerator: any;
  let mockLogger: any;
  let mockProject: any;
  let exitSpy: any;

  beforeEach(() => {
    // Reset mocks
    vi.clearAllMocks();

    // Setup mocks
    mockCodeGenerator = {
      generate: vi.fn(),
    };
    (CodeGenerator as any).mockImplementation(() => mockCodeGenerator);

    mockLogger = {
      info: vi.fn(),
      success: vi.fn(),
      error: vi.fn(),
    };
    (ConsoleLogger as any).mockImplementation(() => mockLogger);

    mockProject = {};
    (Project as any).mockImplementation(() => mockProject);

    // Spy on process.exit
    exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => {
      throw new Error('process.exit called');
    });
  });

  afterEach(() => {
    exitSpy.mockRestore();
  });

  it('should generate code successfully with valid options', async () => {
    const options = { input: 'spec.json', output: 'src/generated' };

    // Mock successful generation
    mockCodeGenerator.generate.mockResolvedValue(undefined);

    // Call the action
    await generateAction(options);

    expect(ConsoleLogger).toHaveBeenCalled();
    expect(mockLogger.info).toHaveBeenCalledWith('Starting code generation...');
    expect(Project).toHaveBeenCalled();
    expect(CodeGenerator).toHaveBeenCalledWith({
      inputPath: 'spec.json',
      outputDir: 'src/generated',
      project: mockProject,
      logger: mockLogger,
    });
    expect(mockCodeGenerator.generate).toHaveBeenCalled();
    expect(mockLogger.success).toHaveBeenCalledWith(
      'Code generation completed successfully! Files generated in: src/generated',
    );
    expect(exitSpy).not.toHaveBeenCalled();
  });

  it('should handle generation errors and exit with code 1', async () => {
    const options = { input: 'spec.json', output: 'src/generated' };
    const error = new Error('Generation failed');

    mockCodeGenerator.generate.mockRejectedValue(error);

    await expect(generateAction(options)).rejects.toThrow(
      'process.exit called',
    );

    expect(mockLogger.error).toHaveBeenCalledWith(
      'Error during code generation: Error: Generation failed',
    );
    expect(exitSpy).toHaveBeenCalledWith(1);
  });

  it('should support HTTP URLs as input', async () => {
    const options = {
      input: 'http://example.com/spec.json',
      output: 'src/generated',
    };

    mockCodeGenerator.generate.mockResolvedValue(undefined);

    await generateAction(options);

    expect(CodeGenerator).toHaveBeenCalledWith({
      inputPath: 'http://example.com/spec.json',
      outputDir: 'src/generated',
      project: mockProject,
      logger: mockLogger,
    });
  });

  it('should support HTTPS URLs as input', async () => {
    const options = {
      input: 'https://example.com/spec.json',
      output: 'src/generated',
    };

    mockCodeGenerator.generate.mockResolvedValue(undefined);

    await generateAction(options);

    expect(CodeGenerator).toHaveBeenCalledWith({
      inputPath: 'https://example.com/spec.json',
      outputDir: 'src/generated',
      project: mockProject,
      logger: mockLogger,
    });
  });
});
