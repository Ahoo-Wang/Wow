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
import { Project } from 'ts-morph';
import { CodeGenerator } from '../src/index';

// Mock dependencies
vi.mock('ts-morph', () => ({
  Project: vi.fn(),
}));

vi.mock('../src/utils', () => ({
  parseOpenAPI: vi.fn(),
}));

vi.mock('../src/aggregate', () => ({
  AggregateResolver: vi.fn(),
}));

vi.mock('../src/model', () => ({
  ModelGenerator: vi.fn(),
}));

vi.mock('../src/client', () => ({
  ClientGenerator: vi.fn(),
}));

// Import after mocking
import { parseOpenAPI } from '../src/utils';
import { AggregateResolver } from '../src/aggregate';
import { ModelGenerator } from '../src/model';
import { ClientGenerator } from '../src/client';

describe('CodeGenerator', () => {
  let mockProject: any;
  let mockParseOpenAPI: any;
  let mockAggregateResolver: any;
  let mockModelGenerator: any;
  let mockClientGenerator: any;
  let mockLogger: any;

  beforeEach(() => {
    vi.clearAllMocks();

    mockProject = {
      getSourceFiles: vi.fn().mockReturnValue([]),
      save: vi.fn().mockResolvedValue(undefined),
    };
    (Project as any).mockImplementation(() => mockProject);

    mockParseOpenAPI = vi.fn();
    (parseOpenAPI as any).mockImplementation(mockParseOpenAPI);

    mockAggregateResolver = {
      resolve: vi.fn(),
    };
    (AggregateResolver as any).mockImplementation(() => mockAggregateResolver);

    mockModelGenerator = {
      generate: vi.fn(),
    };
    (ModelGenerator as any).mockImplementation(() => mockModelGenerator);

    mockClientGenerator = {
      generate: vi.fn(),
    };
    (ClientGenerator as any).mockImplementation(() => mockClientGenerator);

    mockLogger = {
      info: vi.fn(),
      success: vi.fn(),
      error: vi.fn(),
    };
  });

  it('should create a CodeGenerator instance', () => {
    const options = {
      inputPath: 'spec.json',
      outputDir: 'src/generated',
      project: mockProject,
      logger: mockLogger,
    };

    const generator = new CodeGenerator(options);

    expect(generator).toBeInstanceOf(CodeGenerator);
  });

  it('should generate code successfully', async () => {
    const mockOpenAPI = { openapi: '3.0.0' };
    const mockAggregates = new Map();

    mockParseOpenAPI.mockResolvedValue(mockOpenAPI);
    mockAggregateResolver.resolve.mockReturnValue(mockAggregates);

    const options = {
      inputPath: 'spec.json',
      outputDir: 'src/generated',
      project: mockProject,
      logger: mockLogger,
    };

    const generator = new CodeGenerator(options);
    await generator.generate();

    expect(mockParseOpenAPI).toHaveBeenCalledWith('spec.json');
    expect(AggregateResolver).toHaveBeenCalledWith(mockOpenAPI);
    expect(mockAggregateResolver.resolve).toHaveBeenCalled();

    expect(ModelGenerator).toHaveBeenCalledWith({
      openAPI: mockOpenAPI,
      project: mockProject,
      outputDir: 'src/generated',
      contextAggregates: mockAggregates,
      logger: mockLogger,
    });
    expect(mockModelGenerator.generate).toHaveBeenCalled();

    expect(ClientGenerator).toHaveBeenCalledWith({
      openAPI: mockOpenAPI,
      project: mockProject,
      outputDir: 'src/generated',
      contextAggregates: mockAggregates,
      logger: mockLogger,
    });
    expect(mockClientGenerator.generate).toHaveBeenCalled();

    expect(mockProject.getSourceFiles).toHaveBeenCalled();
    expect(mockProject.save).toHaveBeenCalled();
  });

  it('should format and organize imports for source files', async () => {
    const mockSourceFile = {
      formatText: vi.fn(),
      organizeImports: vi.fn(),
      fixMissingImports: vi.fn(),
    };
    mockProject.getSourceFiles.mockReturnValue([mockSourceFile]);

    mockParseOpenAPI.mockResolvedValue({ openapi: '3.0.0' });
    mockAggregateResolver.resolve.mockReturnValue(new Map());

    const options = {
      inputPath: 'spec.json',
      outputDir: 'src/generated',
      project: mockProject,
      logger: mockLogger,
    };

    const generator = new CodeGenerator(options);
    await generator.generate();

    expect(mockSourceFile.formatText).toHaveBeenCalled();
    expect(mockSourceFile.organizeImports).toHaveBeenCalled();
    expect(mockSourceFile.fixMissingImports).toHaveBeenCalled();
  });
});
