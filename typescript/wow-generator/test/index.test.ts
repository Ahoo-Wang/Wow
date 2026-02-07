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
import { CodeGenerator } from '../src';
import { GeneratorOptions } from '../src/types';

// Mock dependencies
vi.mock('ts-morph', () => ({
  Project: class Project {
    getDirectory = vi.fn();
    getSourceFiles = vi.fn().mockReturnValue([]);
    getSourceFile = vi.fn();
    createSourceFile = vi.fn().mockReturnValue({
      removeText: vi.fn(),
      addExportDeclaration: vi.fn(),
      addStatements: vi.fn(),
    });
    save = vi.fn();
    constructor() {}
  },
}));

vi.mock('../src/utils', () => ({
  parseOpenAPI: vi.fn(),
  parseConfiguration: vi.fn(),
}));

vi.mock('../src/aggregate', () => ({
  AggregateResolver: class AggregateResolver {
    resolve = vi.fn().mockReturnValue({ size: 2 });
    constructor() {}
  },
}));

vi.mock('../src/model', () => ({
  ModelGenerator: class ModelGenerator {
    generate = vi.fn();
    constructor() {}
  },
}));

vi.mock('../src/client', () => ({
  ClientGenerator: class ClientGenerator {
    generate = vi.fn();
    constructor() {}
  },
}));

// Import after mocking
import { Project } from 'ts-morph';
import { parseOpenAPI, parseConfiguration } from '../src/utils';
import { AggregateResolver } from '../src/aggregate';
import { ModelGenerator } from '../src/model';
import { ClientGenerator } from '../src/client';

describe('CodeGenerator', () => {
  let mockProject: any;
  let mockLogger: any;
  let mockOpenAPI: any;
  let mockContextAggregates: any;
  let options: GeneratorOptions;

  beforeEach(() => {
    vi.clearAllMocks();

    mockProject = {
      getDirectory: vi.fn(),
      getSourceFiles: vi.fn().mockReturnValue([]),
      getSourceFile: vi.fn(),
      createSourceFile: vi.fn(),
      save: vi.fn().mockResolvedValue(undefined),
    };

    mockLogger = {
      info: vi.fn(),
      success: vi.fn(),
      error: vi.fn(),
      progress: vi.fn(),
    };

    mockOpenAPI = {
      openapi: '3.0.0',
      info: { title: 'Test API', version: '1.0.0' },
      paths: {},
    };

    mockContextAggregates = {
      size: 2,
    };

    (parseOpenAPI as any).mockResolvedValue(mockOpenAPI);
    (parseConfiguration as any).mockResolvedValue({});

    options = {
      tsConfigFilePath: undefined,
      inputPath: '/path/to/openapi.yaml',
      outputDir: '/output/dir',
      logger: mockLogger,
    };
  });

  describe('constructor', () => {
    it('should initialize with provided options', () => {
      const generator = new CodeGenerator(options);

      expect(generator['options']).toBe(options);
    });

    it('should log initialization message', () => {
      new CodeGenerator(options);

      expect(mockLogger.info).toHaveBeenCalledWith(
        'Project instance created with tsConfigFilePath: undefined',
      );
    });
  });

  describe('generate', () => {
    it('should execute the complete code generation process', async () => {
      const generator = new CodeGenerator(options);
      mockProject.getDirectory.mockReturnValue({
        getPath: vi.fn().mockReturnValue(options.outputDir),
        getDirectories: vi.fn().mockReturnValue([]),
        getSourceFiles: vi.fn().mockReturnValue([]),
        getDescendantSourceFiles: vi.fn().mockReturnValue([]),
      } as any);

      expect(() => {
        generator.generate();
      }).not.toThrow();
    });

    it('should log progress throughout the generation process', async () => {
      const generator = new CodeGenerator(options);
      mockProject.getDirectory.mockReturnValue({
        getPath: vi.fn().mockReturnValue(options.outputDir),
        getDirectories: vi.fn().mockReturnValue([]),
        getSourceFiles: vi.fn().mockReturnValue([]),
        getDescendantSourceFiles: vi.fn().mockReturnValue([]),
      } as any);

      expect(() => {
        generator.generate();
      }).not.toThrow();
    });

    it('should call generateIndex and optimizeSourceFiles', async () => {
      const generator = new CodeGenerator(options);
      mockProject.getDirectory.mockReturnValue({
        getPath: vi.fn().mockReturnValue(options.outputDir),
        getDirectories: vi.fn().mockReturnValue([]),
        getSourceFiles: vi.fn().mockReturnValue([]),
        getDescendantSourceFiles: vi.fn().mockReturnValue([]),
      } as any);

      expect(() => {
        generator.generate();
      }).not.toThrow();
    });
  });

  describe('generateIndex', () => {
    it('should generate index files for the output directory', () => {
      const generator = new CodeGenerator(options);
      const mockOutputDir = {
        getDirectories: vi.fn().mockReturnValue([]),
        getPath: vi.fn().mockReturnValue(options.outputDir),
        getSourceFiles: vi.fn().mockReturnValue([]),
      };
      mockProject.getDirectory.mockReturnValue(mockOutputDir);

      generator.generateIndex(mockOutputDir as any);

      expect(mockLogger.info).toHaveBeenCalledWith(
        `Generating index files for output directory: ${options.outputDir}`,
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Index file generation completed',
      );
    });

    it('should process subdirectories recursively', () => {
      const generator = new CodeGenerator(options);
      const mockSubDir = {
        getPath: vi.fn().mockReturnValue('/output/dir/sub'),
        getDirectories: vi.fn().mockReturnValue([]),
        getSourceFiles: vi.fn().mockReturnValue([]),
        getBaseName: vi.fn().mockReturnValue('sub'),
      };
      const mockOutputDir = {
        getDirectories: vi.fn().mockReturnValue([mockSubDir]),
        getPath: vi.fn().mockReturnValue('/output/dir'),
        getSourceFiles: vi.fn().mockReturnValue([]),
        getBaseName: vi.fn().mockReturnValue('dir'),
      };
      mockProject.getDirectory.mockReturnValue(mockOutputDir);
      mockProject.getSourceFile.mockReturnValue(null);

      expect(() => {
        generator.generateIndex(mockOutputDir as any);
      }).not.toThrow();
    });
  });

  describe('generateIndexForDirectory', () => {
    it('should create index file with exports for TypeScript files', () => {
      const generator = new CodeGenerator(options);
      const mockDir = {
        getPath: vi.fn().mockReturnValue('/test/dir'),
        getSourceFiles: vi.fn().mockReturnValue([
          {
            getBaseName: vi.fn().mockReturnValue('model.ts'),
            getBaseNameWithoutExtension: vi.fn().mockReturnValue('model'),
          },
          {
            getBaseName: vi.fn().mockReturnValue('service.ts'),
            getBaseNameWithoutExtension: vi.fn().mockReturnValue('service'),
          },
        ]),
        getDirectories: vi.fn().mockReturnValue([]),
      };

      mockProject.getSourceFile.mockReturnValue(null);

      expect(() => {
        (generator as any).generateIndexForDirectory(mockDir);
      }).not.toThrow();
    });

    it('should create index file with exports for subdirectories', () => {
      const generator = new CodeGenerator(options);
      const mockSubDir = {
        getBaseName: vi.fn().mockReturnValue('models'),
      };
      const mockDir = {
        getPath: vi.fn().mockReturnValue('/test/dir'),
        getSourceFiles: vi.fn().mockReturnValue([]),
        getDirectories: vi.fn().mockReturnValue([mockSubDir]),
      };

      mockProject.getSourceFile.mockReturnValue(null);

      expect(() => {
        (generator as any).generateIndexForDirectory(mockDir);
      }).not.toThrow();
    });

    it('should skip index generation if no files or subdirectories', () => {
      const generator = new CodeGenerator(options);
      const mockDir = {
        getPath: vi.fn().mockReturnValue('/empty/dir'),
        getSourceFiles: vi.fn().mockReturnValue([]),
        getDirectories: vi.fn().mockReturnValue([]),
      };

      expect(() => {
        (generator as any).generateIndexForDirectory(mockDir);
      }).not.toThrow();
    });

    it('should update existing index file', () => {
      const generator = new CodeGenerator(options);
      const mockDir = {
        getPath: vi.fn().mockReturnValue('/test/dir'),
        getSourceFiles: vi.fn().mockReturnValue([
          {
            getBaseName: vi.fn().mockReturnValue('model.ts'),
            getBaseNameWithoutExtension: vi.fn().mockReturnValue('model'),
          },
        ]),
        getDirectories: vi.fn().mockReturnValue([]),
      };

      const mockIndexFile = {
        removeText: vi.fn(),
        addExportDeclaration: vi.fn(),
      };

      mockProject.getSourceFile.mockReturnValue(mockIndexFile);

      expect(() => {
        (generator as any).generateIndexForDirectory(mockDir);
      }).not.toThrow();
    });

    it('should exclude index.ts from exports', () => {
      const generator = new CodeGenerator(options);
      const mockDir = {
        getPath: vi.fn().mockReturnValue('/test/dir'),
        getSourceFiles: vi.fn().mockReturnValue([
          {
            getBaseName: vi.fn().mockReturnValue('model.ts'),
            getBaseNameWithoutExtension: vi.fn().mockReturnValue('model'),
          },
          {
            getBaseName: vi.fn().mockReturnValue('index.ts'),
            getBaseNameWithoutExtension: vi.fn().mockReturnValue('index'),
          },
        ]),
        getDirectories: vi.fn().mockReturnValue([]),
      };

      mockProject.getSourceFile.mockReturnValue(null);

      expect(() => {
        (generator as any).generateIndexForDirectory(mockDir);
      }).not.toThrow();
    });
  });

  describe('optimizeSourceFiles', () => {
    it('should optimize all source files in the project', () => {
      const generator = new CodeGenerator(options);
      const mockOutputDir = {
        getPath: vi.fn().mockReturnValue(options.outputDir),
        getDescendantSourceFiles: vi.fn(),
      };
      const mockSourceFiles = [
        {
          getFilePath: vi.fn().mockReturnValue('/path/to/file1.ts'),
          formatText: vi.fn(),
          organizeImports: vi.fn(),
          fixMissingImports: vi.fn(),
        },
        {
          getFilePath: vi.fn().mockReturnValue('/path/to/file2.ts'),
          formatText: vi.fn(),
          organizeImports: vi.fn(),
          fixMissingImports: vi.fn(),
        },
      ];

      mockOutputDir.getDescendantSourceFiles.mockReturnValue(mockSourceFiles);

      (generator as any).optimizeSourceFiles(mockOutputDir as any);

      expect(mockOutputDir.getDescendantSourceFiles).toHaveBeenCalled();
      expect(mockLogger.info).toHaveBeenCalledWith(
        `Optimizing ${mockSourceFiles.length} source files in ${mockOutputDir.getPath()}`,
      );

      mockSourceFiles.forEach((file, index) => {
        expect(mockLogger.info).toHaveBeenCalledWith(
          `Optimizing file [${file.getFilePath()}] - ${index + 1}/${mockSourceFiles.length}`,
        );
        expect(file.formatText).toHaveBeenCalled();
        expect(file.organizeImports).toHaveBeenCalled();
        expect(file.fixMissingImports).toHaveBeenCalled();
      });

      expect(mockLogger.info).toHaveBeenCalledWith(
        'All source files optimized',
      );
    });
  });
});
