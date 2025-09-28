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
  let mockLogger: any;
  let mockOpenAPI: any;
  let mockContextAggregates: any;
  let mockAggregateResolver: any;
  let mockModelGenerator: any;
  let mockClientGenerator: any;
  let options: GeneratorOptions;

  beforeEach(() => {
    vi.clearAllMocks();

    mockProject = {
      getDirectory: vi.fn(),
      getSourceFiles: vi.fn().mockReturnValue([]),
      getSourceFile: vi.fn(),
      createSourceFile: vi.fn(),
      save: vi.fn(),
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

    mockAggregateResolver = {
      resolve: vi.fn().mockReturnValue(mockContextAggregates),
    };

    mockModelGenerator = {
      generate: vi.fn(),
    };

    mockClientGenerator = {
      generate: vi.fn(),
    };

    (parseOpenAPI as any).mockResolvedValue(mockOpenAPI);
    (AggregateResolver as any).mockImplementation(() => mockAggregateResolver);
    (ModelGenerator as any).mockImplementation(() => mockModelGenerator);
    (ClientGenerator as any).mockImplementation(() => mockClientGenerator);
    mockProject.save.mockResolvedValue(undefined);

    options = {
      project: mockProject,
      inputPath: '/path/to/openapi.yaml',
      outputDir: '/output/dir',
      logger: mockLogger,
    };
  });

  describe('constructor', () => {
    it('should initialize with provided options', () => {
      const generator = new CodeGenerator(options);

      expect(generator['project']).toBe(mockProject);
      expect(generator['options']).toBe(options);
    });

    it('should log initialization message', () => {
      new CodeGenerator(options);

      expect(mockLogger.info).toHaveBeenCalledWith(
        'CodeGenerator instance created',
      );
    });
  });

  describe('generate', () => {
    it('should execute the complete code generation process', async () => {
      const generator = new CodeGenerator(options);

      await generator.generate();

      // Verify parseOpenAPI was called
      expect(parseOpenAPI).toHaveBeenCalledWith(options.inputPath);

      // Verify AggregateResolver was created and resolve called
      expect(AggregateResolver).toHaveBeenCalledWith(mockOpenAPI);
      expect(mockAggregateResolver.resolve).toHaveBeenCalled();

      // Verify ModelGenerator was created and generate called
      expect(ModelGenerator).toHaveBeenCalledWith({
        openAPI: mockOpenAPI,
        project: mockProject,
        outputDir: options.outputDir,
        contextAggregates: mockContextAggregates,
        logger: mockLogger,
      });
      expect(mockModelGenerator.generate).toHaveBeenCalled();

      // Verify ClientGenerator was created and generate called
      expect(ClientGenerator).toHaveBeenCalledWith({
        openAPI: mockOpenAPI,
        project: mockProject,
        outputDir: options.outputDir,
        contextAggregates: mockContextAggregates,
        logger: mockLogger,
      });
      expect(mockClientGenerator.generate).toHaveBeenCalled();

      // Verify project.save was called
      expect(mockProject.save).toHaveBeenCalled();
    });

    it('should log progress throughout the generation process', async () => {
      const generator = new CodeGenerator(options);

      await generator.generate();

      expect(mockLogger.info).toHaveBeenCalledWith(
        'Starting code generation from OpenAPI specification',
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        `Input path: ${options.inputPath}`,
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        `Output directory: ${options.outputDir}`,
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        'OpenAPI specification parsed successfully',
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Resolving bounded context aggregates',
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        `Resolved ${mockContextAggregates.size} bounded context aggregates`,
      );
      expect(mockLogger.info).toHaveBeenCalledWith('Generating models');
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Models generated successfully',
      );
      expect(mockLogger.info).toHaveBeenCalledWith('Generating clients');
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Clients generated successfully',
      );
      expect(mockLogger.info).toHaveBeenCalledWith('Generating index files');
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Index files generated successfully',
      );
      expect(mockLogger.info).toHaveBeenCalledWith('Optimizing source files');
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Source files optimized successfully',
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Code generation completed successfully',
      );
    });

    it('should call generateIndex and optimizeSourceFiles', async () => {
      const generator = new CodeGenerator(options);
      const generateIndexSpy = vi.spyOn(generator as any, 'generateIndex');
      const optimizeSourceFilesSpy = vi.spyOn(
        generator as any,
        'optimizeSourceFiles',
      );

      await generator.generate();

      expect(generateIndexSpy).toHaveBeenCalled();
      expect(optimizeSourceFilesSpy).toHaveBeenCalled();
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

      generator.generateIndex();

      expect(mockProject.getDirectory).toHaveBeenCalledWith(options.outputDir);
      expect(mockLogger.info).toHaveBeenCalledWith(
        `Generating index files for output directory: ${options.outputDir}`,
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Index file generation completed',
      );
    });

    it('should skip if output directory not found', () => {
      const generator = new CodeGenerator(options);
      mockProject.getDirectory.mockReturnValue(null);

      generator.generateIndex();

      expect(mockLogger.info).toHaveBeenCalledWith(
        'Output directory not found, skipping index generation',
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
      mockProject.createSourceFile.mockImplementation(path => ({
        removeText: vi.fn(),
        addExportDeclaration: vi.fn(),
      }));

      generator.generateIndex();

      expect(mockLogger.info).toHaveBeenCalledWith(
        'Processing 1 subdirectories',
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Processing subdirectory: /output/dir/sub',
      );
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

      const mockIndexFile = {
        removeText: vi.fn(),
        addExportDeclaration: vi.fn(),
      };

      mockProject.getSourceFile.mockReturnValue(null);
      mockProject.createSourceFile.mockReturnValue(mockIndexFile);

      (generator as any).generateIndexForDirectory(mockDir);

      expect(mockProject.createSourceFile).toHaveBeenCalledWith(
        '/test/dir/index.ts',
        '',
        { overwrite: true },
      );
      expect(mockIndexFile.removeText).toHaveBeenCalled();
      expect(mockIndexFile.addExportDeclaration).toHaveBeenCalledTimes(2);
      expect(mockIndexFile.addExportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: './model',
        isTypeOnly: false,
        namedExports: [],
      });
      expect(mockIndexFile.addExportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: './service',
        isTypeOnly: false,
        namedExports: [],
      });
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

      const mockIndexFile = {
        removeText: vi.fn(),
        addExportDeclaration: vi.fn(),
      };

      mockProject.getSourceFile.mockReturnValue(null);
      mockProject.createSourceFile.mockReturnValue(mockIndexFile);

      (generator as any).generateIndexForDirectory(mockDir);

      expect(mockIndexFile.addExportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: './models',
        isTypeOnly: false,
        namedExports: [],
      });
    });

    it('should skip index generation if no files or subdirectories', () => {
      const generator = new CodeGenerator(options);
      const mockDir = {
        getPath: vi.fn().mockReturnValue('/empty/dir'),
        getSourceFiles: vi.fn().mockReturnValue([]),
        getDirectories: vi.fn().mockReturnValue([]),
      };

      (generator as any).generateIndexForDirectory(mockDir);

      expect(mockLogger.info).toHaveBeenCalledWith(
        'No files or subdirectories to export in /empty/dir, skipping index generation',
      );
      expect(mockProject.createSourceFile).not.toHaveBeenCalled();
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

      (generator as any).generateIndexForDirectory(mockDir);

      expect(mockProject.createSourceFile).not.toHaveBeenCalled();
      expect(mockIndexFile.removeText).toHaveBeenCalled();
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

      const mockIndexFile = {
        removeText: vi.fn(),
        addExportDeclaration: vi.fn(),
      };

      mockProject.getSourceFile.mockReturnValue(null);
      mockProject.createSourceFile.mockReturnValue(mockIndexFile);

      (generator as any).generateIndexForDirectory(mockDir);

      expect(mockIndexFile.addExportDeclaration).toHaveBeenCalledTimes(1);
      expect(mockIndexFile.addExportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: './model',
        isTypeOnly: false,
        namedExports: [],
      });
    });
  });

  describe('optimizeSourceFiles', () => {
    it('should optimize all source files in the project', () => {
      const generator = new CodeGenerator(options);
      const mockSourceFiles = [
        {
          formatText: vi.fn(),
          organizeImports: vi.fn(),
          fixMissingImports: vi.fn(),
        },
        {
          formatText: vi.fn(),
          organizeImports: vi.fn(),
          fixMissingImports: vi.fn(),
        },
      ];

      mockProject.getSourceFiles.mockReturnValue(mockSourceFiles);

      (generator as any).optimizeSourceFiles();

      expect(mockProject.getSourceFiles).toHaveBeenCalled();
      expect(mockLogger.info).toHaveBeenCalledWith(
        `Optimizing ${mockSourceFiles.length} source files`,
      );

      mockSourceFiles.forEach((file, index) => {
        expect(mockLogger.info).toHaveBeenCalledWith(
          `Optimizing file ${index + 1}/${mockSourceFiles.length}`,
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
