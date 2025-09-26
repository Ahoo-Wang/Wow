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
import { ModelGenerator } from '../../src/model/modelGenerator';
import { GenerateContext } from '../../src/types';
import { Schema } from '@ahoo-wang/fetcher-openapi';

// Mock dependencies
vi.mock('ts-morph', () => ({
  Project: vi.fn(),
  SourceFile: vi.fn(),
}));

vi.mock('../../src/utils', async importOriginal => {
  const actual = await importOriginal<typeof import('../../src/utils')>();
  return {
    ...actual,
    getModelFileName: vi.fn(),
    addImportModelInfo: vi.fn(),
    addJSDoc: vi.fn(),
    extractComponentKey: vi.fn(),
    isEnum: vi.fn(),
    isReference: vi.fn(),
    jsDocs: vi.fn(),
  };
});

vi.mock('../../src/model/modelInfo', () => ({
  resolveModelInfo: vi.fn(),
}));

// Don't mock BaseCodeGenerator to ensure proper inheritance

describe('ModelGenerator', () => {
  const mockOpenAPI = {
    openapi: '3.0.0',
    info: { title: 'Test API', version: '1.0.0' },
    paths: {},
    components: {
      schemas: {
        User: {
          type: 'object' as const,
          properties: {
            id: { type: 'string' as const },
            name: { type: 'string' as const },
          },
          required: ['id'],
        },
        'wow.Status': {
          type: 'string' as const,
          enum: ['active', 'inactive'],
        },
        Product: {
          type: 'object' as const,
          properties: {
            id: { type: 'integer' as const },
            name: { type: 'string' as const },
            price: { type: 'number' as const },
          },
        },
      },
    },
  } as any;

  const mockLogger = {
    info: vi.fn(),
    success: vi.fn(),
    error: vi.fn(),
    progress: vi.fn(),
  };

  const createContext = (logger?: any): GenerateContext => ({
    openAPI: mockOpenAPI as any,
    project: new Project(),
    outputDir: '/tmp/test',
    contextAggregates: new Map(),
    logger,
  });

  let mockProject: any;
  let mockSourceFile: any;
  let mockResolveModelInfo: any;
  let mockGetModelFileName: any;
  let mockAddImportModelInfo: any;
  let mockAddJSDoc: any;
  let mockExtractComponentKey: any;
  let mockIsEnum: any;
  let mockIsReference: any;
  let mockJsDocs: any;

  beforeEach(async () => {
    vi.clearAllMocks();

    // Setup mocks
    mockProject = {
      getSourceFile: vi.fn(),
      createSourceFile: vi.fn(),
    };

    mockSourceFile = {
      addEnum: vi.fn(),
      addInterface: vi.fn(),
      addTypeAlias: vi.fn(),
    };

    const utilsMock = vi.mocked(await import('../../src/utils'));
    mockGetModelFileName = utilsMock.getModelFileName;
    mockAddImportModelInfo = utilsMock.addImportModelInfo;
    mockAddJSDoc = utilsMock.addJSDoc;
    mockExtractComponentKey = utilsMock.extractComponentKey;
    mockIsEnum = utilsMock.isEnum;
    mockIsReference = utilsMock.isReference;
    mockJsDocs = utilsMock.jsDocs;

    const modelInfoMock = vi.mocked(await import('../../src/model/modelInfo'));
    mockResolveModelInfo = modelInfoMock.resolveModelInfo;

    // Default mock implementations
    mockResolveModelInfo.mockReturnValue({ name: 'TestModel', path: 'models' });
    mockGetModelFileName.mockReturnValue('models/types.ts');
    mockIsEnum.mockReturnValue(false);
    mockIsReference.mockReturnValue(false);
    mockJsDocs.mockReturnValue([]);
  });

  describe('generate', () => {
    it('should return early when no schemas exist', () => {
      const context = createContext(mockLogger);
      (context.openAPI as any).components = undefined;

      const generator = new ModelGenerator(context);
      generator.generate();

      expect(mockLogger.info).toHaveBeenCalledWith(
        'No schemas found in OpenAPI specification',
      );
      expect(mockLogger.progress).not.toHaveBeenCalled();
    });

    it('should skip schemas starting with wow.', () => {
      const context = createContext(mockLogger);
      // Create a proper OpenAPI object with schemas
      context.openAPI = {
        ...context.openAPI,
        components: {
          schemas: {
            User: { type: 'object', properties: { id: { type: 'string' } } },
            'wow.Status': { type: 'string', enum: ['active'] },
            Product: {
              type: 'object',
              properties: { name: { type: 'string' } },
            },
          },
        },
      };
      const generator = new ModelGenerator(context);

      // Mock the generateKeyedSchema method
      const generateKeyedSchemaSpy = vi.spyOn(
        generator as any,
        'generateKeyedSchema',
      );
      generateKeyedSchemaSpy.mockReturnValue(mockSourceFile);

      generator.generate();

      expect(generateKeyedSchemaSpy).toHaveBeenCalledTimes(2);
      expect(generateKeyedSchemaSpy).toHaveBeenCalledWith(
        'User',
        expect.any(Object),
      );
      expect(generateKeyedSchemaSpy).toHaveBeenCalledWith(
        'Product',
        expect.any(Object),
      );
      expect(generateKeyedSchemaSpy).not.toHaveBeenCalledWith(
        'wow.Status',
        expect.any(Object),
      );
      expect(mockLogger.success).toHaveBeenCalledWith(
        'Model generation completed',
      );
    });
  });

  describe('generateKeyedSchema', () => {
    it('should generate schema and add JSDoc', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockNode = { name: 'TestModel' };
      const processSpy = vi.spyOn(generator as any, 'process');
      processSpy.mockReturnValue(mockNode);

      // Mock the private getOrCreateSourceFile method
      const getOrCreateSourceFileSpy = vi.spyOn(
        generator as any,
        'getOrCreateSourceFile',
      );
      getOrCreateSourceFileSpy.mockReturnValue(mockSourceFile);

      const schema: Schema = {
        type: 'object',
        title: 'Test Model',
        description: 'A test model',
      };

      const result = generator.generateKeyedSchema('TestModel', schema);

      expect(processSpy).toHaveBeenCalledWith(
        { name: 'TestModel', path: 'models' },
        mockSourceFile,
        schema,
      );
      expect(mockAddJSDoc).toHaveBeenCalledWith(
        mockNode,
        'Test Model',
        'A test model',
      );
      expect(result).toBe(mockSourceFile);
    });
  });

  describe('processEnum', () => {
    it('should return undefined for non-enum schemas', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      mockIsEnum.mockReturnValue(false);

      const schema: Schema = { type: 'object' };
      const result = (generator as any).processEnum(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBeUndefined();
      expect(mockSourceFile.addEnum).not.toHaveBeenCalled();
    });

    it('should create enum for valid enum schemas', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      mockIsEnum.mockReturnValue(true);
      const mockEnum = { name: 'Status' };
      mockSourceFile.addEnum.mockReturnValue(mockEnum);

      const schema: Schema = {
        type: 'string',
        enum: ['active', 'inactive', 'pending'],
      };

      const result = (generator as any).processEnum(
        { name: 'Status', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockSourceFile.addEnum).toHaveBeenCalledWith({
        name: 'Status',
        isExported: true,
        members: [
          { name: 'active', initializer: '\'active\'' },
          { name: 'inactive', initializer: '\'inactive\'' },
          { name: 'pending', initializer: '\'pending\'' },
        ],
      });
      expect(result).toBe(mockEnum);
    });

    it('should filter out non-string enum values', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      mockIsEnum.mockReturnValue(true);
      const mockEnum = { name: 'MixedEnum' };
      mockSourceFile.addEnum.mockReturnValue(mockEnum);

      const schema: Schema = {
        type: 'string',
        enum: ['valid', 123, '', null, 'another'],
      };

      const result = (generator as any).processEnum(
        { name: 'MixedEnum', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockSourceFile.addEnum).toHaveBeenCalledWith({
        name: 'MixedEnum',
        isExported: true,
        members: [
          { name: 'valid', initializer: '\'valid\'' },
          { name: 'another', initializer: '\'another\'' },
        ],
      });
      expect(result).toBe(mockEnum);
    });
  });

  describe('processObject', () => {
    it('should return undefined for non-object schemas', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema: Schema = { type: 'string' };
      const result = (generator as any).processObject(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBeUndefined();
      expect(mockSourceFile.addInterface).not.toHaveBeenCalled();
    });

    it('should create interface for valid object schemas', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockInterface = { name: 'User' };
      mockSourceFile.addInterface.mockReturnValue(mockInterface);

      const resolveTypeSpy = vi.spyOn(generator as any, 'resolveType');
      resolveTypeSpy
        .mockReturnValueOnce('string')
        .mockReturnValueOnce('string');

      const schema: Schema = {
        type: 'object',
        properties: {
          id: { type: 'string' },
          name: { type: 'string' },
        },
        required: ['id'],
      };

      const result = (generator as any).processObject(
        { name: 'User', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockSourceFile.addInterface).toHaveBeenCalledWith({
        name: 'User',
        isExported: true,
        properties: [
          { name: 'id', type: 'string' },
          { name: 'name', type: 'string | undefined' },
        ],
      });
      expect(result).toBe(mockInterface);
    });

    it('should handle schemas without required array', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockInterface = { name: 'Product' };
      mockSourceFile.addInterface.mockReturnValue(mockInterface);

      const resolveTypeSpy = vi.spyOn(generator as any, 'resolveType');
      resolveTypeSpy.mockReturnValue('string');

      const schema: Schema = {
        type: 'object',
        properties: {
          name: { type: 'string' },
        },
      };

      const result = (generator as any).processObject(
        { name: 'Product', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockSourceFile.addInterface).toHaveBeenCalledWith({
        name: 'Product',
        isExported: true,
        properties: [{ name: 'name', type: 'string | undefined' }],
      });
      expect(result).toBe(mockInterface);
    });
  });

  describe('processUnion', () => {
    it('should return undefined when no union properties exist', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema: Schema = { type: 'string' };
      const result = (generator as any).processUnion(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBeUndefined();
      expect(mockSourceFile.addTypeAlias).not.toHaveBeenCalled();
    });

    it('should create intersection type for allOf', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockTypeAlias = { name: 'Combined' };
      mockSourceFile.addTypeAlias.mockReturnValue(mockTypeAlias);

      const resolveTypeSpy = vi.spyOn(generator as any, 'resolveType');
      resolveTypeSpy.mockReturnValueOnce('TypeA').mockReturnValueOnce('TypeB');

      const schema: Schema = {
        allOf: [
          { $ref: '#/components/schemas/TypeA' },
          { $ref: '#/components/schemas/TypeB' },
        ],
      };

      const result = (generator as any).processUnion(
        { name: 'Combined', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockSourceFile.addTypeAlias).toHaveBeenCalledWith({
        name: 'Combined',
        type: 'TypeA & TypeB',
        isExported: true,
        docs: [],
      });
      expect(result).toBe(mockTypeAlias);
    });

    it('should create union type for anyOf', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockTypeAlias = { name: 'Either' };
      mockSourceFile.addTypeAlias.mockReturnValue(mockTypeAlias);

      const resolveTypeSpy = vi.spyOn(generator as any, 'resolveType');
      resolveTypeSpy
        .mockReturnValueOnce('string')
        .mockReturnValueOnce('number');

      const schema: Schema = {
        anyOf: [{ type: 'string' }, { type: 'number' }],
      };

      const result = (generator as any).processUnion(
        { name: 'Either', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockSourceFile.addTypeAlias).toHaveBeenCalledWith({
        name: 'Either',
        type: 'string | number',
        isExported: true,
        docs: [],
      });
      expect(result).toBe(mockTypeAlias);
    });

    it('should create union type for oneOf', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockTypeAlias = { name: 'OneOf' };
      mockSourceFile.addTypeAlias.mockReturnValue(mockTypeAlias);

      const resolveTypeSpy = vi.spyOn(generator as any, 'resolveType');
      resolveTypeSpy.mockReturnValueOnce('boolean').mockReturnValueOnce('null');

      const schema: Schema = {
        oneOf: [{ type: 'boolean' }, { type: 'null' }],
      };

      const result = (generator as any).processUnion(
        { name: 'OneOf', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockSourceFile.addTypeAlias).toHaveBeenCalledWith({
        name: 'OneOf',
        type: 'boolean | null',
        isExported: true,
        docs: [],
      });
      expect(result).toBe(mockTypeAlias);
    });
  });

  describe('processTypeAlias', () => {
    it('should create type alias for any schema', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockTypeAlias = { name: 'Alias' };
      mockSourceFile.addTypeAlias.mockReturnValue(mockTypeAlias);

      const resolveTypeSpy = vi.spyOn(generator as any, 'resolveType');
      resolveTypeSpy.mockReturnValue('string');

      const schema: Schema = { type: 'string' };

      const result = (generator as any).processTypeAlias(
        { name: 'Alias', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(resolveTypeSpy).toHaveBeenCalledWith(
        { name: 'Alias', path: 'models' },
        mockSourceFile,
        schema,
      );
      expect(mockSourceFile.addTypeAlias).toHaveBeenCalledWith({
        name: 'Alias',
        type: 'string',
        isExported: true,
        docs: [],
      });
      expect(result).toBe(mockTypeAlias);
    });
  });

  describe('resolveType', () => {
    it('should resolve reference types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      mockIsReference.mockReturnValue(true);
      const resolveReferenceSpy = vi.spyOn(
        generator as any,
        'resolveReference',
      );
      resolveReferenceSpy.mockReturnValue('ReferencedType');

      const ref = { $ref: '#/components/schemas/User' };
      const result = (generator as any).resolveType(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        ref,
      );

      expect(resolveReferenceSpy).toHaveBeenCalledWith(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        ref,
      );
      expect(result).toBe('ReferencedType');
    });

    it('should resolve array types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema: Schema = {
        type: 'array',
        items: { type: 'string' },
      };

      const result = (generator as any).resolveType(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBe('string[]');
    });

    it('should resolve array types with any items when no items specified', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema: Schema = {
        type: 'array',
      };

      const result = (generator as any).resolveType(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBe('any[]');
    });

    it('should resolve object types without properties as Record<string, any>', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema: Schema = {
        type: 'object',
      };

      const result = (generator as any).resolveType(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBe('Record<string, any>');
    });

    it('should resolve primitive types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema: Schema = { type: 'integer' };
      const result = (generator as any).resolveType(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBe('number');
    });

    it('should handle nullable types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema: Schema = {
        type: 'string',
        nullable: true,
      };

      const result = (generator as any).resolveType(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBe('string | null');
    });

    it('should return any for unknown types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema: Schema = {}; // no type, no nullable
      const result = (generator as any).resolveType(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBe('any');
    });
  });

  describe('resolveReference', () => {
    it('should resolve reference and add import', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      mockExtractComponentKey.mockReturnValue('User');
      mockResolveModelInfo.mockReturnValue({
        name: 'User',
        path: 'models/user',
      });

      const ref = { $ref: '#/components/schemas/User' };
      const result = (generator as any).resolveReference(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        ref,
      );

      expect(mockExtractComponentKey).toHaveBeenCalledWith(ref);
      expect(mockResolveModelInfo).toHaveBeenCalledWith('User');
      expect(mockAddImportModelInfo).toHaveBeenCalledWith(
        { name: 'Test', path: 'models' },
        mockSourceFile,
        '/tmp/test',
        { name: 'User', path: 'models/user' },
      );
      expect(result).toBe('User');
    });
  });
});
