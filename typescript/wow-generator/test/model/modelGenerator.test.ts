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
}));

vi.mock('../../src/model/modelInfo', () => ({
  resolveModelInfo: vi.fn(),
}));

vi.mock('../../src/utils', () => ({
  getModelFileName: vi.fn(),
  getOrCreateSourceFile: vi.fn(),
  addImportModelInfo: vi.fn(),
  addJSDoc: vi.fn(),
  extractComponentKey: vi.fn(),
  isEnum: vi.fn(),
  isReference: vi.fn(),
  isArray: vi.fn(),
  isPrimitive: vi.fn(),
  isComposition: vi.fn(),
  isUnion: vi.fn(),
  resolvePrimitiveType: vi.fn(),
  toArrayType: vi.fn(),
  pascalCase: vi.fn(),
  upperSnakeCase: vi.fn(value => value.toUpperCase()),
}));

vi.mock('../../src/baseCodeGenerator', () => ({
  BaseCodeGenerator: vi.fn(),
}));

// Import after mocking
import { resolveModelInfo } from '../../src/model/modelInfo';
import {
  getModelFileName,
  getOrCreateSourceFile,
  addImportModelInfo,
  addJSDoc,
  extractComponentKey,
  isEnum,
  isReference,
  isArray,
  isPrimitive,
  isComposition,
  isUnion,
  resolvePrimitiveType,
  toArrayType,
  pascalCase,
} from '../../src/utils';
import { BaseCodeGenerator } from '../../src/baseCodeGenerator';

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
    progressWithCount: vi.fn(),
  };

  const createContext = (logger?: any): GenerateContext => ({
    openAPI: mockOpenAPI,
    project: new Project(),
    outputDir: '/tmp/test',
    contextAggregates: new Map(),
    logger: logger || mockLogger,
  });

  let mockProject: any;
  let mockSourceFile: any;
  let mockResolveModelInfo: any;
  let mockGetModelFileName: any;
  let mockGetOrCreateSourceFile: any;
  let mockAddImportModelInfo: any;
  let mockAddJSDoc: any;
  let mockExtractComponentKey: any;
  let mockIsEnum: any;
  let mockIsReference: any;
  let mockIsArray: any;
  let mockIsPrimitive: any;
  let mockIsComposition: any;
  let mockIsUnion: any;
  let mockResolvePrimitiveType: any;
  let mockToArrayType: any;
  let mockPascalCase: any;

  beforeEach(() => {
    vi.clearAllMocks();

    mockProject = {
      getSourceFile: vi.fn(),
      createSourceFile: vi.fn(),
    };
    (Project as any).mockImplementation(() => mockProject);

    mockSourceFile = {
      addEnum: vi.fn(),
      addInterface: vi.fn(),
      addTypeAlias: vi.fn(),
    };

    mockResolveModelInfo = vi.mocked(resolveModelInfo);
    mockGetModelFileName = vi.mocked(getModelFileName);
    mockGetOrCreateSourceFile = vi.mocked(getOrCreateSourceFile);
    mockAddImportModelInfo = vi.mocked(addImportModelInfo);
    mockAddJSDoc = vi.mocked(addJSDoc);
    mockExtractComponentKey = vi.mocked(extractComponentKey);
    mockIsEnum = vi.mocked(isEnum);
    mockIsReference = vi.mocked(isReference);
    mockIsArray = vi.mocked(isArray);
    mockIsPrimitive = vi.mocked(isPrimitive);
    mockIsComposition = vi.mocked(isComposition);
    mockIsUnion = vi.mocked(isUnion);
    mockResolvePrimitiveType = vi.mocked(resolvePrimitiveType);
    mockToArrayType = vi.mocked(toArrayType);
    mockPascalCase = vi.mocked(pascalCase);
    mockPascalCase.mockReturnValue('PascalCase');

    // Setup default mocks
    mockGetOrCreateSourceFile.mockReturnValue(mockSourceFile);
    mockResolveModelInfo.mockReturnValue({ name: 'TestModel', path: 'models' });
    mockGetModelFileName.mockReturnValue('models/types.ts');
  });

  describe('constructor', () => {
    it('should create a ModelGenerator instance', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      expect(generator).toBeInstanceOf(ModelGenerator);
      expect(BaseCodeGenerator).toHaveBeenCalledWith(context);
    });
  });

  describe('generate', () => {
    it('should generate models for all schemas', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);
      (generator as any).openAPI = context.openAPI;
      (generator as any).logger = context.logger;

      const mockProcess = vi
        .spyOn(generator as any, 'generateKeyedSchema')
        .mockImplementation(() => {
        });

      generator.generate();

      expect(mockLogger.progress).toHaveBeenCalledWith(
        'Generating models for 2 schemas',
      );
      expect(mockLogger.progressWithCount).toHaveBeenCalledWith(
        1,
        2,
        'Processing schema: User',
        2,
      );
      expect(mockLogger.progressWithCount).toHaveBeenCalledWith(
        2,
        2,
        'Processing schema: Product',
        2,
      );
      expect(mockLogger.success).toHaveBeenCalledWith(
        'Model generation completed',
      );
      expect(mockProcess).toHaveBeenCalledTimes(2); // Should skip wow.Status
    });

    it('should skip wow schemas', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);
      (generator as any).openAPI = context.openAPI;
      (generator as any).logger = context.logger;

      const mockProcess = vi
        .spyOn(generator as any, 'generateKeyedSchema')
        .mockImplementation(() => {
        });

      generator.generate();

      expect(mockProcess).not.toHaveBeenCalledWith(
        expect.objectContaining({ key: 'wow.Status' }),
      );
    });

    it('should handle missing schemas', () => {
      const context = createContext();
      context.openAPI.components = undefined;
      const generator = new ModelGenerator(context);
      (generator as any).openAPI = context.openAPI;
      (generator as any).logger = context.logger;

      generator.generate();

      expect(mockLogger.info).toHaveBeenCalledWith(
        'No schemas found in OpenAPI specification',
      );
    });
  });

  describe('generateKeyedSchema', () => {
    it('should generate a model for a schema key', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockNode = { name: 'TestInterface' };
      const mockProcess = vi
        .spyOn(generator as any, 'process')
        .mockReturnValue(mockNode);

      const keySchema = {
        key: 'User',
        schema: { type: 'object' } as Schema,
      };

      (generator as any).generateKeyedSchema(keySchema);

      expect(mockResolveModelInfo).toHaveBeenCalledWith('User');
      expect(mockGetOrCreateSourceFile).toHaveBeenCalled();
      expect(mockProcess).toHaveBeenCalledWith(
        { name: 'TestModel', path: 'models' },
        mockSourceFile,
        keySchema.schema,
      );
      expect(mockAddJSDoc).toHaveBeenCalledWith(mockNode, undefined, undefined);
    });

    it('should not add JSDoc if no node returned', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockProcess = vi
        .spyOn(generator as any, 'process')
        .mockReturnValue(undefined);

      const keySchema = {
        key: 'User',
        schema: { type: 'object' } as Schema,
      };

      (generator as any).generateKeyedSchema(keySchema);

      expect(mockAddJSDoc).not.toHaveBeenCalled();
    });
  });

  describe('process', () => {
    it('should process enum schemas', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockEnum = { name: 'StatusEnum' };
      mockSourceFile.addEnum.mockReturnValue(mockEnum);
      mockIsEnum.mockReturnValue(true);

      const schema: Schema = {
        type: 'string',
        enum: ['active', 'inactive'],
      };

      const result = (generator as any).process(
        { name: 'Status', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockIsEnum).toHaveBeenCalledWith(schema);
      expect(mockSourceFile.addEnum).toHaveBeenCalledWith({
        name: 'Status',
        isExported: true,
        members: [
          { name: 'ACTIVE', initializer: '\'active\'' },
          { name: 'INACTIVE', initializer: '\'inactive\'' },
        ],
      });
      expect(result).toBe(mockEnum);
    });

    it('should process object schemas with properties', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockInterface = {
        name: 'User',
        addProperty: vi.fn(),
        getProperty: vi.fn().mockReturnValue(null),
      };
      mockSourceFile.addInterface.mockReturnValue(mockInterface);
      mockIsEnum.mockReturnValue(false);

      const mockProcessInterface = vi
        .spyOn(generator as any, 'processInterface')
        .mockReturnValue(mockInterface);

      const schema: Schema = {
        type: 'object',
        properties: {
          id: { type: 'string' },
          name: { type: 'string' },
        },
      };

      const result = (generator as any).process(
        { name: 'User', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockSourceFile.addInterface).toHaveBeenCalledWith({
        name: 'User',
        isExported: true,
      });
      expect(mockProcessInterface).toHaveBeenCalledWith(
        mockSourceFile,
        { name: 'User', path: 'models' },
        schema,
        mockInterface,
      );
      expect(result).toBe(mockInterface);
    });

    it('should process composition schemas', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);
      (generator as any).outputDir = context.outputDir;

      const mockInterface = {
        name: 'User',
        addExtends: vi.fn(),
      };
      mockSourceFile.addInterface.mockReturnValue(mockInterface);
      mockIsEnum.mockReturnValue(false);
      mockIsComposition.mockReturnValue(true);

      const mockProcessInterface = vi
        .spyOn(generator as any, 'processInterface')
        .mockImplementation(() => {
        });

      const schema: Schema = {
        allOf: [
          { $ref: '#/components/schemas/BaseUser' },
          {
            type: 'object',
            properties: { email: { type: 'string' } },
          },
        ],
      };

      mockIsReference.mockReturnValueOnce(true).mockReturnValueOnce(false);
      mockExtractComponentKey.mockReturnValue('BaseUser');
      mockResolveModelInfo.mockReturnValueOnce({
        name: 'BaseUser',
        path: 'models',
      });

      const result = (generator as any).process(
        { name: 'User', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(mockInterface.addExtends).toHaveBeenCalledWith('BaseUser');
      expect(mockAddImportModelInfo).toHaveBeenCalledWith(
        { name: 'User', path: 'models' },
        mockSourceFile,
        '/tmp/test',
        { name: 'BaseUser', path: 'models' },
      );
      expect(result).toBe(mockInterface);
    });
  });

  describe('processObject', () => {
    it('should create and process an interface for object schemas', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockInterface = { name: 'Product' };
      mockSourceFile.addInterface.mockReturnValue(mockInterface);

      const mockProcessInterface = vi
        .spyOn(generator as any, 'processInterface')
        .mockReturnValue(mockInterface);

      const schema: Schema = {
        type: 'object',
        properties: {
          id: { type: 'string' },
          name: { type: 'string' },
        },
      };

      const result = (generator as any).processObject(
        mockSourceFile,
        { name: 'Product', path: 'models' },
        schema,
      );

      expect(mockSourceFile.addInterface).toHaveBeenCalledWith({
        name: 'Product',
        isExported: true,
      });
      expect(mockProcessInterface).toHaveBeenCalledWith(
        mockSourceFile,
        { name: 'Product', path: 'models' },
        schema,
        mockInterface,
      );
      expect(result).toBe(mockInterface);
    });
  });

  describe('processInterface', () => {
    it('should add properties to interface', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockProperty = { name: 'id' };
      const mockInterface = {
        addProperty: vi.fn().mockReturnValue(mockProperty),
        getProperty: vi.fn().mockReturnValue(null),
      };

      const mockResolvePropertyType = vi
        .spyOn(generator as any, 'resolvePropertyType')
        .mockReturnValue('string');

      const schema: Schema = {
        type: 'object',
        properties: {
          id: { type: 'string', title: 'ID', description: 'User ID' },
          name: { type: 'string' },
        },
      };

      const result = (generator as any).processInterface(
        mockSourceFile,
        { name: 'User', path: 'models' },
        schema,
        mockInterface as any,
      );

      expect(mockResolvePropertyType).toHaveBeenCalledTimes(2);
      expect(mockInterface.addProperty).toHaveBeenCalledWith({
        name: 'id',
        type: 'string',
      });
      expect(mockInterface.addProperty).toHaveBeenCalledWith({
        name: 'name',
        type: 'string',
      });
      expect(mockAddJSDoc).toHaveBeenCalledWith(
        expect.any(Object),
        'ID',
        'User ID',
      );
      expect(result).toBe(mockInterface);
    });

    it('should update existing properties', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const mockProperty = {
        setType: vi.fn(),
      };

      const mockInterface = {
        addProperty: vi.fn(),
        getProperty: vi.fn().mockReturnValue(mockProperty),
      };

      const mockResolvePropertyType = vi
        .spyOn(generator as any, 'resolvePropertyType')
        .mockReturnValue('string');

      const schema: Schema = {
        type: 'object',
        properties: {
          id: { type: 'string' },
        },
      };

      (generator as any).processInterface(
        mockSourceFile,
        { name: 'User', path: 'models' },
        schema,
        mockInterface as any,
      );

      expect(mockProperty.setType).toHaveBeenCalledWith('string');
      expect(mockInterface.addProperty).not.toHaveBeenCalled();
    });
  });

  describe('resolvePropertyType', () => {
    it('should resolve reference types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);
      (generator as any).outputDir = context.outputDir;

      const propSchema = { $ref: '#/components/schemas/User' };
      mockIsReference.mockReturnValue(true);
      mockExtractComponentKey.mockReturnValue('User');
      mockResolveModelInfo.mockReturnValue({ name: 'User', path: 'models' });

      const result = (generator as any).resolvePropertyType(
        { name: 'Post', path: 'models' },
        mockSourceFile,
        'author',
        propSchema as any,
      );

      expect(mockExtractComponentKey).toHaveBeenCalledWith(propSchema);
      expect(mockAddImportModelInfo).toHaveBeenCalledWith(
        { name: 'Post', path: 'models' },
        mockSourceFile,
        '/tmp/test',
        { name: 'User', path: 'models' },
      );
      expect(result).toBe('User');
    });

    it('should resolve const values', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const propSchema = { const: 'active' };
      mockIsReference.mockReturnValue(false);

      const result = (generator as any).resolvePropertyType(
        { name: 'Status', path: 'models' },
        mockSourceFile,
        'value',
        propSchema as any,
      );

      expect(result).toBe('\'active\'');
    });

    it('should resolve array types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const arraySchema = {
        type: 'array',
        items: { type: 'string' },
      };
      mockIsReference.mockReturnValue(false);
      mockIsArray.mockReturnValueOnce(true).mockReturnValueOnce(false);
      mockIsPrimitive.mockReturnValue(true);
      mockIsComposition.mockReturnValue(false);
      mockResolvePrimitiveType.mockReset().mockReturnValue('string');
      mockToArrayType.mockReturnValue('string[]');

      const result = (generator as any).resolvePropertyType(
        { name: 'Tags', path: 'models' },
        mockSourceFile,
        'items',
        arraySchema as any,
      );

      expect(mockResolvePrimitiveType).toHaveBeenCalledWith('string');
      expect(mockToArrayType).toHaveBeenCalledWith('string');
      expect(result).toBe('string[]');
    });

    it('should resolve primitive types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const propSchema = { type: 'string' };
      mockIsReference.mockReturnValue(false);
      mockIsArray.mockReturnValue(false);
      mockIsPrimitive.mockReturnValue(true);
      mockResolvePrimitiveType.mockReturnValue('string');

      const result = (generator as any).resolvePropertyType(
        { name: 'User', path: 'models' },
        mockSourceFile,
        'name',
        propSchema as any,
      );

      expect(mockResolvePrimitiveType).toHaveBeenCalledWith('string');
      expect(result).toBe('string');
    });

    it('should resolve composition types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const propSchema = {
        anyOf: [{ type: 'string' }, { type: 'number' }],
      };

      mockIsComposition.mockReturnValue(true);

      const mockResolveComposition = vi
        .spyOn(generator as any, 'resolvePropertyCompositionType')
        .mockReturnValue('string | number');

      const result = (generator as any).resolvePropertyType(
        { name: 'Value', path: 'models' },
        mockSourceFile,
        'data',
        propSchema as any,
      );

      expect(mockResolveComposition).toHaveBeenCalledWith(
        { name: 'Value', path: 'models' },
        mockSourceFile,
        propSchema,
      );
      expect(result).toBe('string | number');
    });

    it('should resolve nested object types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const propSchema = {
        type: 'object',
        properties: {
          street: { type: 'string' },
          city: { type: 'string' },
        },
      };
      mockIsReference.mockReturnValue(false);
      mockIsArray.mockReturnValue(false);
      mockIsPrimitive.mockReturnValue(false);
      mockIsComposition.mockReturnValue(false);
      mockPascalCase.mockReturnValue('Address');

      const mockProcessObject = vi
        .spyOn(generator as any, 'processObject')
        .mockReturnValue({ name: 'ValueAddress' });

      const result = (generator as any).resolvePropertyType(
        { name: 'Value', path: 'models' },
        mockSourceFile,
        'address',
        propSchema as any,
      );

      expect(mockPascalCase).toHaveBeenCalledWith('address');
      expect(mockProcessObject).toHaveBeenCalledWith(
        mockSourceFile,
        { name: 'ValueAddress', path: 'models' },
        propSchema,
      );
      expect(mockAddJSDoc).toHaveBeenCalled();
      expect(result).toBe('ValueAddress');
    });

    it('should return any for unhandled types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const propSchema = { type: 'unknown' };
      mockIsReference.mockReturnValue(false);
      mockIsArray.mockReturnValue(false);
      mockIsPrimitive.mockReturnValue(false);
      mockIsComposition.mockReturnValue(false);

      const result = (generator as any).resolvePropertyType(
        { name: 'Unknown', path: 'models' },
        mockSourceFile,
        'data',
        propSchema as any,
      );

      expect(result).toBe('any');
    });
  });

  describe('resolvePropertyCompositionType', () => {
    it('should resolve anyOf composition types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema = {
        anyOf: [{ $ref: '#/components/schemas/User' }, { type: 'string' }],
      } as any;

      mockIsReference.mockReturnValueOnce(true).mockReturnValueOnce(false);
      mockExtractComponentKey.mockReturnValue('User');
      mockResolveModelInfo.mockReturnValue({ name: 'User', path: 'models' });
      mockResolvePrimitiveType.mockReturnValue('string');
      mockIsUnion.mockReturnValue(true);

      const result = (generator as any).resolvePropertyCompositionType(
        { name: 'Data', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBe('User|string');
    });

    it('should resolve allOf composition types', () => {
      const context = createContext();
      const generator = new ModelGenerator(context);

      const schema = {
        allOf: [{ $ref: '#/components/schemas/Base' }, { type: 'object' }],
      } as any;

      mockIsReference.mockReturnValueOnce(true).mockReturnValueOnce(false);
      mockExtractComponentKey.mockReturnValue('Base');
      mockResolveModelInfo.mockReturnValue({ name: 'Base', path: 'models' });
      mockResolvePrimitiveType.mockReturnValue('any');
      mockIsUnion.mockReturnValue(false);

      const result = (generator as any).resolvePropertyCompositionType(
        { name: 'Extended', path: 'models' },
        mockSourceFile,
        schema,
      );

      expect(result).toBe('Base&any');
    });
  });
});
