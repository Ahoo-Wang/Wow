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
  getModelFileName,
  getOrCreateSourceFile,
  addImport,
  addImportRefModel,
  addImportModelInfo,
  jsDoc,
  jsDocs,
  addJSDoc,
} from '../../src/utils';
import { ModelInfo } from '../../src/model';

// Mock ts-morph
vi.mock('ts-morph', () => ({
  Project: vi.fn(),
  SourceFile: vi.fn(),
}));

// Mock @/model
vi.mock('@/model', () => ({
  ModelInfo: {},
}));

const mockProject = {
  getSourceFile: vi.fn(),
  createSourceFile: vi.fn(),
};

const mockSourceFile = {
  getImportDeclaration: vi.fn(),
  addImportDeclaration: vi.fn(),
  addNamedImport: vi.fn(),
  addJsDoc: vi.fn(),
};

describe('sourceFiles', () => {
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
  });

  describe('addImportRefModel', () => {
    it('should add import for referenced model with alias prefix', () => {
      const sourceFile = mockSourceFile as any;
      const outputDir = '/output';
      const refModelInfo: ModelInfo = {
        name: 'User',
        path: 'models/user',
      };

      const mockDeclaration = {
        getNamedImports: vi.fn().mockReturnValue([]),
        addNamedImport: vi.fn(),
      };

      sourceFile.getImportDeclaration.mockReturnValue(undefined);
      sourceFile.addImportDeclaration.mockReturnValue(mockDeclaration);

      addImportRefModel(sourceFile, outputDir, refModelInfo);

      expect(sourceFile.addImportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: '@/output/models/user/types.ts',
      });
      expect(mockDeclaration.addNamedImport).toHaveBeenCalledWith('User');
    });

    it('should add import for referenced model without alias prefix', () => {
      const sourceFile = mockSourceFile as any;
      const outputDir = '/output';
      const refModelInfo: ModelInfo = {
        name: 'User',
        path: '@/models/user',
      };

      const mockDeclaration = {
        getNamedImports: vi.fn().mockReturnValue([]),
        addNamedImport: vi.fn(),
      };

      // Reset mocks
      sourceFile.getImportDeclaration.mockClear();
      sourceFile.addImportDeclaration.mockClear();

      sourceFile.getImportDeclaration.mockReturnValue(undefined);
      sourceFile.addImportDeclaration.mockReturnValue(mockDeclaration);

      addImportRefModel(sourceFile, outputDir, refModelInfo);

      expect(sourceFile.addImportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: '@/models/user/types.ts',
      });
      expect(mockDeclaration.addNamedImport).toHaveBeenCalledWith('User');
    });
  });

  describe('addImportModelInfo', () => {
    it('should add import when paths are different', () => {
      const modelInfo: ModelInfo = {
        name: 'Order',
        path: 'models/order',
      };
      const sourceFile = mockSourceFile as any;
      const outputDir = '/output';
      const refModelInfo: ModelInfo = {
        name: 'User',
        path: 'models/user',
      };

      const mockDeclaration = {
        getNamedImports: vi.fn().mockReturnValue([]),
        addNamedImport: vi.fn(),
      };

      sourceFile.getImportDeclaration.mockReturnValue(undefined);
      sourceFile.addImportDeclaration.mockReturnValue(mockDeclaration);

      addImportModelInfo(modelInfo, sourceFile, outputDir, refModelInfo);

      expect(sourceFile.addImportDeclaration).toHaveBeenCalledWith({
        moduleSpecifier: '@/output/models/user/types.ts',
      });
      expect(mockDeclaration.addNamedImport).toHaveBeenCalledWith('User');
    });

    it('should not add import when paths are the same', () => {
      const modelInfo: ModelInfo = {
        name: 'User',
        path: 'models/user',
      };
      const sourceFile = mockSourceFile as any;
      const outputDir = '/output';
      const refModelInfo: ModelInfo = {
        name: 'User',
        path: 'models/user',
      };

      // Reset the mock call count
      sourceFile.addImportDeclaration.mockClear();

      addImportModelInfo(modelInfo, sourceFile, outputDir, refModelInfo);

      expect(sourceFile.addImportDeclaration).not.toHaveBeenCalled();
    });
  });

  describe('jsDoc', () => {
    it('should return undefined when both title and description are empty', () => {
      expect(jsDoc()).toBeUndefined();
      expect(jsDoc('')).toBeUndefined();
      expect(jsDoc('', '')).toBeUndefined();
      expect(jsDoc(undefined, undefined)).toBeUndefined();
    });

    it('should return title when only title is provided', () => {
      expect(jsDoc('User model')).toBe('User model');
    });

    it('should return description when only description is provided', () => {
      expect(jsDoc(undefined, 'A user model')).toBe('A user model');
    });

    it('should join title and description with newline', () => {
      expect(jsDoc('User model', 'A user model')).toBe(
        'User model\nA user model',
      );
    });

    it('should filter out empty strings', () => {
      expect(jsDoc('User model', '')).toBe('User model');
      expect(jsDoc('', 'A user model')).toBe('A user model');
    });
  });

  describe('jsDocs', () => {
    it('should return empty array when jsDoc returns undefined', () => {
      expect(jsDocs()).toEqual([]);
      expect(jsDocs('', '')).toEqual([]);
    });

    it('should return array with jsDoc string', () => {
      expect(jsDocs('User model')).toEqual(['User model']);
      expect(jsDocs('User model', 'A user model')).toEqual([
        'User model\nA user model',
      ]);
    });
  });

  describe('addJSDoc', () => {
    it('should add JSDoc comment when jsDoc returns content', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };

      addJSDoc(mockNode as any, 'User model', 'A user model');

      expect(mockNode.addJsDoc).toHaveBeenCalledWith({
        description: 'User model\nA user model',
      });
    });

    it('should not add JSDoc comment when jsDoc returns undefined', () => {
      const mockNode = {
        addJsDoc: vi.fn(),
      };

      addJSDoc(mockNode as any);
      addJSDoc(mockNode as any, '');
      addJSDoc(mockNode as any, '', '');

      expect(mockNode.addJsDoc).not.toHaveBeenCalled();
    });
  });
});
