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
import { Project } from 'ts-morph';
import { ApiClientGenerator } from '../../src/client';
import { GenerateContext } from '../../src/generateContext';
import { GenerateContextInit } from '../../src/types';
import { Tag } from '@ahoo-wang/fetcher-openapi';

// Mock the dependencies
vi.mock('../../src/client/decorators', () => ({
  addImportDecorator: vi.fn(),
  createDecoratorClass: vi.fn(() => ({ addMethod: vi.fn() })),
  addApiMetadataCtor: vi.fn(),
  STREAM_RESULT_EXTRACTOR_METADATA: 'STREAM_METADATA',
  DEFAULT_RETURN_TYPE: {
    type: 'Promise<Response>',
    metadata: '{resultExtractor: ResultExtractors.Response }',
  },
  STRING_RETURN_TYPE: {
    type: 'Promise<string>',
    metadata: '{resultExtractor: ResultExtractors.Text }',
  },
}));

vi.mock('../../src/utils', () => ({
  addImportRefModel: vi.fn(),
  camelCase: vi.fn(str => (Array.isArray(str) ? str.join('.') : str)),
  extractOkResponse: vi.fn(),
  extractResponseJsonSchema: vi.fn(),
  extractOperations: vi.fn(() => []),
  extractRequestBody: vi.fn(),
  isPrimitive: vi.fn(type => type === 'string'),
  isReference: vi.fn(obj => obj && typeof obj === 'object' && '$ref' in obj),
  resolvePrimitiveType: vi.fn(type => type),
  extractResponseEventStreamSchema: vi.fn(),
  extractSchema: vi.fn(),
  isArray: vi.fn(),
  extractResponseWildcardSchema: vi.fn(),
  addJSDoc: vi.fn(),
  methodToDecorator: vi.fn(() => 'get'),
  getOrCreateSourceFile: vi.fn(() => ({ addImportDeclaration: vi.fn() })),
}));

vi.mock('../../src/model/modelInfo', () => ({
  resolveModelInfo: vi.fn(() => ({ name: 'TestModel', path: '/test' })),
  resolveReferenceModelInfo: vi.fn(() => ({ name: 'RefModel', path: '/ref' })),
}));

vi.mock('@ahoo-wang/fetcher', async importOriginal => {
  const actual = await importOriginal();
  return {
    ...(actual as any),
    combineURLs: vi.fn((...urls) => urls.join('/')),
  };
});

describe('ApiClientGenerator', () => {
  let mockContext: GenerateContext;
  let mockLogger: any;
  let mockProject: Project;

  beforeEach(() => {
    vi.clearAllMocks();

    mockLogger = {
      info: vi.fn(),
      success: vi.fn(),
      progressWithCount: vi.fn(),
    };

    mockProject = new Project();

    const contextInit: GenerateContextInit = {
      openAPI: {
        openapi: '3.0.0',
        info: { title: 'Test API', 'x-wow-context-alias': 'test-context' },
        paths: {
          '/users': {
            get: { operationId: 'user.get', tags: ['User'], responses: {} },
          },
        },
        tags: [
          { name: 'User', description: 'User operations' },
          { name: 'wow', description: 'System tag' },
          { name: 'Actuator', description: 'Actuator tag' },
        ],
        components: {},
      },
      project: mockProject,
      outputDir: '/output',
      contextAggregates: new Map(),
      logger: mockLogger,
      config: {},
    };

    mockContext = new GenerateContext(contextInit);
  });

  describe('constructor', () => {
    it('should create instance with context alias initializer', () => {
      const generator = new ApiClientGenerator(mockContext);
      expect(generator.context).toBe(mockContext);
    });

    it('should create instance without context alias', () => {
      const contextInit: GenerateContextInit = {
        openAPI: {
          openapi: '3.0.0',
          info: { title: 'Test API' },
          paths: {},
          components: {},
        },
        project: mockProject,
        outputDir: '/output',
        contextAggregates: new Map(),
        logger: mockLogger,
        config: {},
      };
      const contextWithoutAlias = new GenerateContext(contextInit);
      const generator = new ApiClientGenerator(contextWithoutAlias);
      expect(generator.context).toBe(contextWithoutAlias);
    });
  });

  describe('generate', () => {
    it('should generate API clients for valid tags', () => {
      const generator = new ApiClientGenerator(mockContext);

      // Mock the private methods
      vi.spyOn(generator as any, 'resolveApiTags').mockReturnValue(
        new Map([
          ['User', { name: 'User', description: 'User operations' } as Tag],
        ]),
      );
      vi.spyOn(generator as any, 'groupOperations').mockReturnValue(new Map());
      vi.spyOn(generator as any, 'generateApiClients').mockImplementation(
        () => {},
      );

      generator.generate();

      expect(mockLogger.info).toHaveBeenCalledWith(
        'Starting API client generation',
      );
      expect(mockLogger.success).toHaveBeenCalledWith(
        'API client generation completed',
      );
    });
  });

  describe('resolveApiTags', () => {
    it('should filter out system and aggregate tags', () => {
      const generator = new ApiClientGenerator(mockContext);
      const result = (generator as any).resolveApiTags();

      expect(result.has('User')).toBe(true);
      expect(result.has('wow')).toBe(false);
      expect(result.has('Actuator')).toBe(false);
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Resolving API client tags from OpenAPI specification',
      );
    });

    it('should handle empty tags array', () => {
      const contextInit: GenerateContextInit = {
        openAPI: {
          openapi: '3.0.0',
          info: { title: 'Test API' },
          paths: {},
          components: {},
        },
        project: mockProject,
        outputDir: '/output',
        contextAggregates: new Map(),
        logger: mockLogger,
        config: {},
      };
      const contextWithoutTags = new GenerateContext(contextInit);
      const generator = new ApiClientGenerator(contextWithoutTags);
      const result = (generator as any).resolveApiTags();

      expect(result.size).toBe(0);
    });
  });

  describe('createApiClientFile', () => {
    it('should create source file with correct path', () => {
      const generator = new ApiClientGenerator(mockContext);
      const modelInfo = { name: 'TestModel', path: '/test' };
      const spy = vi.spyOn(mockContext, 'getOrCreateSourceFile');

      const result = (generator as any).createApiClientFile(modelInfo);

      expect(spy).toHaveBeenCalledWith(
        'test-context//test/TestModelApiClient.ts',
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Creating API client file: test-context//test/TestModelApiClient.ts',
      );
    });

    it('should create source file without context alias', () => {
      const contextInit: GenerateContextInit = {
        openAPI: {
          openapi: '3.0.0',
          info: { title: 'Test API' },
          paths: {},
          components: {},
        },
        project: mockProject,
        outputDir: '/output',
        contextAggregates: new Map(),
        logger: mockLogger,
        config: {},
      };
      const contextWithoutAlias = new GenerateContext(contextInit);
      const generator = new ApiClientGenerator(contextWithoutAlias);
      const modelInfo = { name: 'TestModel', path: '/test' };
      const spy = vi.spyOn(contextWithoutAlias, 'getOrCreateSourceFile');

      (generator as any).createApiClientFile(modelInfo);

      expect(spy).toHaveBeenCalledWith('/test/TestModelApiClient.ts');
    });
  });

  describe('getMethodName', () => {
    it('should generate method name from operationId', () => {
      const generator = new ApiClientGenerator(mockContext);
      const mockClass = { getMethod: vi.fn(() => false) };
      const operation = { operationId: 'user.getProfile' };

      const result = (generator as any).getMethodName(mockClass, operation);

      expect(result).toBe('getProfile');
    });

    it('should handle existing method names', () => {
      const generator = new ApiClientGenerator(mockContext);
      const mockClass = { getMethod: vi.fn(name => name === 'getProfile') };
      const operation = { operationId: 'user.getProfile' };

      const result = (generator as any).getMethodName(mockClass, operation);

      expect(result).toBe('user.getProfile');
    });

    it('should return camelCase of all parts when no unique method found', () => {
      const generator = new ApiClientGenerator(mockContext);
      const mockClass = { getMethod: vi.fn(() => true) }; // Always returns true, so no unique name found
      const operation = { operationId: 'user.get.profile' };

      const result = (generator as any).getMethodName(mockClass, operation);

      expect(result).toBe('user.get.profile');
    });
  });

  describe('resolveRequestType', () => {
    it('should return default type when no request body', () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = { operationId: 'test.op' };

      const result = (generator as any).resolveRequestType({}, operation);

      expect(result).toBe('ParameterRequest');
      expect(mockLogger.info).toHaveBeenCalledWith(
        'No request body found for operation test.op, using default: ParameterRequest',
      );
    });

    it('should handle multipart form data', () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = {
        operationId: 'test.op',
        requestBody: { content: { 'multipart/form-data': {} } },
      };

      const result = (generator as any).resolveRequestType({}, operation);

      expect(result).toBe('ParameterRequest<FormData>');
    });

    it('should handle JSON request body with reference', () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = {
        operationId: 'test.op',
        requestBody: {
          content: {
            'application/json': {
              schema: { $ref: '#/components/schemas/Test' },
            },
          },
        },
      };

      const result = (generator as any).resolveRequestType({}, operation);

      expect(result).toBe('ParameterRequest<RefModel>');
    });

    it('should handle reference request body that fails extraction', async () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = {
        operationId: 'test.op',
        requestBody: { $ref: '#/components/requestBodies/Test' },
      };

      // Mock extractRequestBody to return undefined (extraction fails)
      vi.mocked(
        await import('../../src/utils'),
      ).extractRequestBody.mockReturnValueOnce(undefined);

      const result = (generator as any).resolveRequestType({}, operation);

      expect(result).toBe('ParameterRequest');
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Extracting request body from reference for operation: test.op',
      );
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Request body extraction failed for operation test.op, using default: ParameterRequest',
      );
    });

    it('should return default type when no matching content type', () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = {
        operationId: 'test.op',
        requestBody: {
          content: {
            'application/xml': { schema: { type: 'string' } },
          },
        },
      };

      const result = (generator as any).resolveRequestType({}, operation);

      expect(result).toBe('ParameterRequest');
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Using default request type for operation test.op: ParameterRequest',
      );
    });
  });

  describe('resolveSchemaReturnType', () => {
    it('should resolve reference schema', () => {
      const generator = new ApiClientGenerator(mockContext);
      const schema = { $ref: '#/components/schemas/Test' };

      const result = (generator as any).resolveSchemaReturnType({}, schema);

      expect(result).toBe('Promise<RefModel>');
    });

    it('should resolve primitive schema', () => {
      const generator = new ApiClientGenerator(mockContext);
      const schema = { type: 'string' };

      const result = (generator as any).resolveSchemaReturnType({}, schema);

      expect(result).toBe('Promise<string>');
    });

    it('should handle schema with no type', () => {
      const generator = new ApiClientGenerator(mockContext);
      const schema = { description: 'test schema' }; // No type property

      const result = (generator as any).resolveSchemaReturnType({}, schema);

      expect(result).toBe('Promise<any>');
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Schema has no type, using default return type: Promise<any>',
      );
    });

    it('should handle non-primitive schema type', () => {
      const generator = new ApiClientGenerator(mockContext);
      const schema = { type: 'object' }; // Non-primitive type

      const result = (generator as any).resolveSchemaReturnType({}, schema);

      expect(result).toBe('Promise<any>');
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Using default return type: Promise<any>',
      );
    });
  });

  describe('resolveReturnType', () => {
    it('should return default type when no OK response', () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = { operationId: 'test.op' };

      const result = (generator as any).resolveReturnType({}, operation);

      expect(result).toEqual({
        type: 'Promise<Response>',
        metadata: '{resultExtractor: ResultExtractors.Response }',
      });
    });

    it('should handle JSON response schema', async () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = { operationId: 'test.op' };

      // Mock extractOkResponse to return a response
      vi.mocked(
        await import('../../src/utils'),
      ).extractOkResponse.mockReturnValueOnce({
        content: {
          'application/json': { schema: { type: 'string' } },
        },
      });

      // Mock extractResponseJsonSchema to return the schema
      vi.mocked(
        await import('../../src/utils'),
      ).extractResponseJsonSchema.mockReturnValueOnce({ type: 'string' });

      const result = (generator as any).resolveReturnType({}, operation);

      expect(result).toEqual({
        type: 'Promise<string>',
        metadata: '{resultExtractor: ResultExtractors.Text }',
      });
    });

    it('should handle event stream with array reference schema', async () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = { operationId: 'test.op' };

      // Mock extractOkResponse to return a response
      vi.mocked(
        await import('../../src/utils'),
      ).extractOkResponse.mockReturnValueOnce({});

      // Mock extractResponseEventStreamSchema to return a reference
      vi.mocked(
        await import('../../src/utils'),
      ).extractResponseEventStreamSchema.mockReturnValueOnce({
        $ref: '#/components/schemas/TestEvent',
      });

      // Mock extractSchema to return an array schema
      vi.mocked(
        await import('../../src/utils'),
      ).extractSchema.mockReturnValueOnce({
        type: 'array',
        items: { $ref: '#/components/schemas/TestItem' },
      });

      // Mock isArray to return true
      vi.mocked(await import('../../src/utils')).isArray.mockReturnValueOnce(
        true,
      );

      const result = (generator as any).resolveReturnType({}, operation);

      expect(result).toEqual({
        type: 'Promise<JsonServerSentEventStream<RefModel>>',
        metadata: 'STREAM_METADATA',
      });
    });

    it('should handle event stream with non-array schema', async () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = { operationId: 'test.op' };

      // Mock extractOkResponse to return a response
      vi.mocked(
        await import('../../src/utils'),
      ).extractOkResponse.mockReturnValueOnce({});

      // Mock extractResponseEventStreamSchema to return a schema
      vi.mocked(
        await import('../../src/utils'),
      ).extractResponseEventStreamSchema.mockReturnValueOnce({
        type: 'string',
      });

      const result = (generator as any).resolveReturnType({}, operation);

      expect(result).toEqual({
        type: 'Promise<JsonServerSentEventStream<any>>',
        metadata: 'STREAM_METADATA',
      });
    });

    it('should handle event stream with array reference schema', async () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = { operationId: 'test.op' };

      // Mock extractOkResponse to return a response
      vi.mocked(
        await import('../../src/utils'),
      ).extractOkResponse.mockReturnValueOnce({});

      // Mock extractResponseJsonSchema to return undefined
      vi.mocked(
        await import('../../src/utils'),
      ).extractResponseJsonSchema.mockReturnValueOnce(undefined);

      // Mock extractResponseEventStreamSchema to return a reference
      vi.mocked(
        await import('../../src/utils'),
      ).extractResponseEventStreamSchema.mockReturnValueOnce({
        $ref: '#/components/schemas/TestEvent',
      });

      // Mock extractSchema to return an array schema
      vi.mocked(
        await import('../../src/utils'),
      ).extractSchema.mockReturnValueOnce({
        type: 'array',
        items: { $ref: '#/components/schemas/TestItem' },
      });

      // Mock isArray to return true
      vi.mocked(await import('../../src/utils')).isArray.mockReturnValueOnce(
        true,
      );

      const result = (generator as any).resolveReturnType({}, operation);

      expect(result).toEqual({
        type: 'Promise<JsonServerSentEventStream<RefModel>>',
        metadata: 'STREAM_METADATA',
      });
    });

    it('should handle event stream with non-array schema', async () => {
      const generator = new ApiClientGenerator(mockContext);
      const operation = { operationId: 'test.op' };

      // Mock extractOkResponse to return a response
      vi.mocked(
        await import('../../src/utils'),
      ).extractOkResponse.mockReturnValueOnce({});

      // Mock extractResponseEventStreamSchema to return a schema
      vi.mocked(
        await import('../../src/utils'),
      ).extractResponseEventStreamSchema.mockReturnValueOnce({
        type: 'string',
      });

      const result = (generator as any).resolveReturnType({}, operation);

      expect(result).toEqual({
        type: 'Promise<JsonServerSentEventStream<any>>',
        metadata: 'STREAM_METADATA',
      });
    });
  });

  describe('groupOperations', () => {
    it('should group operations by tags', async () => {
      const generator = new ApiClientGenerator(mockContext);
      const apiClientTags = new Map([['User', { name: 'User' } as Tag]]);

      // Mock extractOperations to return operations
      vi.mocked(
        await import('../../src/utils'),
      ).extractOperations.mockReturnValue([
        {
          method: 'get',
          operation: { operationId: 'user.get', tags: ['User'], responses: {} },
        },
      ]);

      const result = (generator as any).groupOperations(apiClientTags);

      expect(result.size).toBe(1);
      expect(mockLogger.info).toHaveBeenCalledWith(
        'Grouping operations by API client tags',
      );
    });

    it('should filter out operations with invalid tags', async () => {
      const generator = new ApiClientGenerator(mockContext);
      const apiClientTags = new Map([['User', { name: 'User' } as Tag]]);

      // Mock extractOperations to return operations, one with valid tag, one with invalid
      vi.mocked(
        await import('../../src/utils'),
      ).extractOperations.mockReturnValue([
        {
          method: 'get',
          operation: { operationId: 'user.get', tags: ['User'], responses: {} },
        },
        {
          method: 'post',
          operation: {
            operationId: 'admin.create',
            tags: ['Admin'],
            responses: {},
          },
        },
      ]);

      const result = (generator as any).groupOperations(apiClientTags);

      expect(result.size).toBe(1);
      expect(result.has('User')).toBe(true);
      expect(result.has('Admin')).toBe(false);
    });
  });
});
