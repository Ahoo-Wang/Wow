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

import { describe, expect, it, vi, beforeEach } from 'vitest';
import {
  getModelFileName,
  getOrCreateSourceFile,
  addImport,
  jsDoc,
  addJSDoc,
  addSchemaJSDoc,
  addImportRefModel,
  addImportModelInfo,
} from '../../src/utils';
import { ModelInfo } from '../../src/model';

// Mock ts-morph
vi.mock('ts-morph', () => ({
  Project: vi.fn(),
  SourceFile: vi.fn(),
}));

// Mock @/model
vi.mock('../../src/model', () => ({
  IMPORT_WOW_PATH: '@ahoo-wang/fetcher-wow/types.ts',
}));

// Mock @ahoo-wang/fetcher
vi.mock('@ahoo-wang/fetcher', async importOriginal => {
  const actual = await importOriginal();
  return {
    ...(actual as any),
    combineURLs: vi.fn((...args: string[]) => args.join('/')),
  };
});

const mockProject = {
  getSourceFile: vi.fn(),
  createSourceFile: vi.fn(),
};

const mockDeclaration = {
  addNamedImport: vi.fn(),
};

const mockSourceFile = {
  getImportDeclaration: vi.fn(),
  addImportDeclaration: vi.fn().mockReturnValue(mockDeclaration),
  addNamedImport: vi.fn(),
  getDirectoryPath: vi.fn().mockReturnValue('/src'),
};

describe('sourceFiles', () => {
  beforeEach(() => {
    mockSourceFile.getImportDeclaration.mockClear();
    mockSourceFile.addImportDeclaration.mockClear();
    mockDeclaration.addNamedImport.mockClear();
    mockSourceFile.getDirectoryPath.mockClear();
  });

  describe('getModelFileName', () => {
    it('should return the model file path', () => {
      const modelInfo: ModelInfo = {
        name: 'User',
        path: 'models',
      };

      expect(getModelFileName(modelInfo)).toBe('models/types.ts');
    });
  });

  describe('getOrCreateSourceFile', () => {
    it('should return existing source file', () => {
      const project = mockProject as any;
      const outputDir = '/output';
      const filePath = 'models/types.ts';

      project.getSourceFile.mockReturnValue(mockSourceFile);

      const result = getOrCreateSourceFile(project, outputDir, filePath);

      expect(project.getSourceFile).toHaveBeenCalledWith(
        '/output/models/types.ts',
      );
      expect(result).toBe(mockSourceFile);
    });

    it('should create new source file if not exists', () => {
      const project = mockProject as any;
      const outputDir = '/output';
      const filePath = 'models/types.ts';

      project.getSourceFile.mockReturnValue(undefined);
      project.createSourceFile.mockReturnValue(mockSourceFile);

      const result = getOrCreateSourceFile(project, outputDir, filePath);

      expect(project.getSourceFile).toHaveBeenCalledWith(
        '/output/models/types.ts',
      );
      expect(project.createSourceFile).toHaveBeenCalledWith(
        '/output/models/types.ts',
        '',
        {
          overwrite: true,
        },
      );
      expect(result).toBe(mockSourceFile);
    });
  });

  describe('addImport', () => {
    it('should add named imports to existing declaration', () => {
      const sourceFile = mockSourceFile as any;
      const moduleSpecifier = '@/models';
      const namedImports = ['User', 'Address'];

      const mockDeclaration = {
        getNamedImports: vi.fn().mockReturnValue([]),
        addNamedImport: vi.fn(),
      };

      sourceFile.getImportDeclaration.mockReturnValue(mockDeclaration);

      addImport(sourceFile, moduleSpecifier, namedImports);

      expect(sourceFile.getImportDeclaration).toHaveBeenCalledWith(
        expect.any(Function),
      );
      expect(mockDeclaration.addNamedImport).toHaveBeenCalledWith('User');
      expect(mockDeclaration.addNamedImport).toHaveBeenCalledWith('Address');
    });

    it('should create new import declaration if not exists', () => {
      const sourceFile = mockSourceFile as any;
      const moduleSpecifier = '@/models';
      const namedImports = ['User'];

      const mockDeclaration = {
        getNamedImports: vi.fn().mockReturnValue([]),
        addNamedImport: vi.fn(),
      };
      sourceFile.getImportDeclaration.mockReturnValue(undefined);
      sourceFile.addImportDeclaration.mockReturnValue(mockDeclaration);

      addImport(sourceFile, moduleSpecifier, namedImports);

      expect(sourceFile.addImportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier,
      });
    });

    it('should not add duplicate imports', () => {
      const sourceFile = mockSourceFile as any;
      const moduleSpecifier = '@/models';
      const namedImports = ['User'];

      const mockDeclaration = {
        getNamedImports: vi.fn().mockReturnValue([{ getName: () => 'User' }]),
        addNamedImport: vi.fn(),
      };

      sourceFile.getImportDeclaration.mockReturnValue(mockDeclaration);

      addImport(sourceFile, moduleSpecifier, namedImports);

      expect(mockDeclaration.addNamedImport).not.toHaveBeenCalled();
    });

    it('should correctly identify import declarations by module specifier', () => {
      const sourceFile = mockSourceFile as any;
      const moduleSpecifier = '@/models';

      const mockDeclaration = {
        getNamedImports: vi.fn().mockReturnValue([]),
        addNamedImport: vi.fn(),
      };

      // Mock to return the declaration when predicate matches
      sourceFile.getImportDeclaration.mockImplementation((predicate: any) => {
        // Simulate the actual predicate logic
        const mockImportDecl = {
          getModuleSpecifierValue: () => moduleSpecifier,
        };
        if (predicate(mockImportDecl)) {
          return mockDeclaration;
        }
        return undefined;
      });

      addImport(sourceFile, moduleSpecifier, ['User']);

      expect(sourceFile.getImportDeclaration).toHaveBeenCalledWith(
        expect.any(Function),
      );
      expect(mockDeclaration.addNamedImport).toHaveBeenCalledWith('User');
    });
  });

  describe('jsDoc', () => {
    it('should return undefined for empty inputs', () => {
      expect(jsDoc([''])).toBeUndefined();
      expect(jsDoc([undefined, undefined])).toBeUndefined();
    });

    it('should return title only', () => {
      expect(jsDoc(['Title'])).toBe('Title');
    });

    it('should return description only', () => {
      expect(jsDoc([undefined, 'Description'])).toBe('Description');
    });

    it('should join title and description with newline', () => {
      expect(jsDoc(['Title', 'Description'])).toBe('Title\nDescription');
    });

    it('should filter out empty strings', () => {
      expect(jsDoc(['Title', ''])).toBe('Title');
      expect(jsDoc(['', 'Description'])).toBe('Description');
    });
  });

  describe('addJSDoc', () => {
    it('should not add jsdoc if no content', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };

      addJSDoc(mockNode as any, ['', '']);

      expect(mockNode.addJsDoc).not.toHaveBeenCalled();
    });

    it('should add jsdoc with title and description', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };

      addJSDoc(mockNode as any, ['Title', 'Description']);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith('Title\nDescription');
    });

    it('should add jsdoc with title only', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };

      addJSDoc(mockNode as any, ['Title']);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith('Title');
    });
  });

  describe('addImportRefModel', () => {
    it('should add import for wow path', () => {
      const sourceFile = mockSourceFile as any;
      const outputDir = '/output';
      const refModelInfo: ModelInfo = {
        name: 'WowType',
        path: '@ahoo-wang/fetcher-wow/types.ts',
      };

      addImportRefModel(sourceFile, outputDir, refModelInfo);

      expect(mockSourceFile.addImportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: '@ahoo-wang/fetcher-wow/types.ts',
      });
    });

    it('should add import for regular model', () => {
      const sourceFile = mockSourceFile as any;
      const outputDir = '/output';
      const refModelInfo: ModelInfo = {
        name: 'User',
        path: 'models',
      };

      addImportRefModel(sourceFile, outputDir, refModelInfo);

      expect(mockSourceFile.addImportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: '../output/models/types',
      });
    });

    it('should handle path starting with alias', () => {
      const sourceFile = mockSourceFile as any;
      const outputDir = '/output';
      const refModelInfo: ModelInfo = {
        name: 'AliasType',
        path: '@/custom/path',
      };

      addImportRefModel(sourceFile, outputDir, refModelInfo);

      expect(mockSourceFile.addImportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: '@/custom/path',
      });
    });
  });

  describe('addImportModelInfo', () => {
    it('should not add import if paths are the same', () => {
      const currentModel: ModelInfo = {
        name: 'User',
        path: 'models',
      };
      const sourceFile = mockSourceFile as any;
      const outputDir = '/output';
      const refModel: ModelInfo = {
        name: 'Address',
        path: 'models',
      };

      addImportModelInfo(currentModel, sourceFile, outputDir, refModel);

      expect(mockSourceFile.addImportDeclaration).not.toHaveBeenCalled();
    });

    it('should add import if paths are different', () => {
      const currentModel: ModelInfo = {
        name: 'User',
        path: 'models',
      };
      const sourceFile = mockSourceFile as any;
      const outputDir = '/output';
      const refModel: ModelInfo = {
        name: 'Product',
        path: 'products',
      };

      addImportModelInfo(currentModel, sourceFile, outputDir, refModel);

      // This should call addImportRefModel, which calls addImport
      expect(mockSourceFile.addImportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: '../output/products/types',
      });
    });
  });

  describe('addSchemaJSDoc', () => {
    it('should add JSDoc with schema title and description', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
        description: 'Test Description',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith(
        'Test Title\nTest Description',
      );
    });

    it('should handle schema with only title', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        title: 'Test Title',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith('Test Title');
    });

    it('should handle schema with only description', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {
        description: 'Test Description',
      };

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).toHaveBeenCalledWith('Test Description');
    });

    it('should not add JSDoc if schema has no title or description', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };
      const schema = {};

      addSchemaJSDoc(mockNode as any, schema as any);

      expect(mockNode.addJsDoc).not.toHaveBeenCalled();
    });
  });
});
