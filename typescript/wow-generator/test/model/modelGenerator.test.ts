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
import { ModelGenerator } from '../../src/model/modelGenerator';
import { TypeGenerator } from '../../src/model/typeGenerator';
import { KeySchema } from '../../src/utils';

// Mock dependencies
vi.mock('../../src/model/typeGenerator');
vi.mock('../../src/utils/sourceFiles', () => ({
  getOrCreateSourceFile: vi.fn(),
  getModelFileName: vi.fn().mockReturnValue('TestModel.ts'),
}));

describe('ModelGenerator', () => {
  const mockLogger = {
    info: vi.fn(),
    progress: vi.fn(),
    progressWithCount: vi.fn(),
    success: vi.fn(),
    error: vi.fn(),
  };

  const mockOpenAPI = {
    openapi: '3.0.0',
    info: { title: 'Test', version: '1.0.0' },
    paths: {},
    components: {
      schemas: {
        TestModel: { type: 'object', properties: { id: { type: 'string' } } },
        'wow.TestWow': { type: 'string' },
        TestEnum: { type: 'string', enum: ['a', 'b'] },
      },
    },
  };

  const mockContextAggregates = new Map();

  const mockGetOrCreateSourceFile = vi.fn();

  const mockContext = {
    logger: mockLogger,
    openAPI: mockOpenAPI as any,
    outputDir: '/output',
    contextAggregates: mockContextAggregates,
    getOrCreateSourceFile: mockGetOrCreateSourceFile,
    defaultIgnorePathParameters: [],
    isIgnoreApiClientPathParameters: vi.fn(),
    isIgnoreCommandClientPathParameters: vi.fn(),
    project: {} as any,
    config: {},
    currentContextAlias: undefined,
  };

  describe('generate', () => {
    it('should generate models for all non-wow schemas', () => {
      const generator = new ModelGenerator(mockContext as any);
      const mockSourceFile = {};
      mockGetOrCreateSourceFile.mockReturnValue(mockSourceFile);

      // Mock TypeGenerator
      const mockTypeGenerator = { generate: vi.fn() };
      vi.mocked(TypeGenerator).mockReturnValue(mockTypeGenerator as any);

      generator.generate();

      expect(mockLogger.progress).toHaveBeenCalledWith(
        'Generating models for 2 schemas',
      );
      expect(mockLogger.progressWithCount).toHaveBeenCalledTimes(2);
      expect(mockLogger.success).toHaveBeenCalledWith(
        'Model generation completed',
      );
    });

    it('should log info when no schemas found', () => {
      const contextWithoutSchemas = {
        ...mockContext,
        openAPI: {
          openapi: '3.0.0',
          info: { title: 'Test', version: '1.0.0' },
          paths: {},
          components: {},
        },
      };
      const generator = new ModelGenerator(contextWithoutSchemas as any);

      generator.generate();

      expect(mockLogger.info).toHaveBeenCalledWith(
        'No schemas found in OpenAPI specification',
      );
    });
  });

  describe('filterSchemas', () => {
    it('should filter out wow schemas', () => {
      const generator = new ModelGenerator(mockContext as any);
      const schemas = {
        TestModel: { type: 'object' },
        'wow.TestWow': { type: 'string' },
        TestEnum: { type: 'string', enum: ['a', 'b'] },
      };
      const aggregatedTypeNames = new Set<string>();

      const result = (generator as any).filterSchemas(
        schemas,
        aggregatedTypeNames,
      ) as KeySchema[];

      expect(result).toHaveLength(2);
      expect(result.map(r => r.key)).toEqual(['TestModel', 'TestEnum']);
    });
  });

  describe('isWowSchema', () => {
    it('should return true for wow schemas', () => {
      const generator = new ModelGenerator(mockContext as any);
      const aggregatedTypeNames = new Set<string>();

      expect(
        (generator as any).isWowSchema('wow.Test', aggregatedTypeNames),
      ).toBe(true);
      expect(
        (generator as any).isWowSchema(
          'TestAggregatedCondition',
          aggregatedTypeNames,
        ),
      ).toBe(true);
    });

    it('should return false for non-wow schemas', () => {
      const generator = new ModelGenerator(mockContext as any);
      const aggregatedTypeNames = new Set<string>();

      expect(
        (generator as any).isWowSchema('TestModel', aggregatedTypeNames),
      ).toBe(false);
    });
  });

  describe('generateKeyedSchema', () => {
    it('should create TypeGenerator and call generate', () => {
      const generator = new ModelGenerator(mockContext as any);
      const mockSourceFile = {};
      mockGetOrCreateSourceFile.mockReturnValue(mockSourceFile);

      const keySchema: KeySchema = { key: 'TestModel', schema: { type: 'object' } };

      generator.generateKeyedSchema(keySchema);

      expect(mockGetOrCreateSourceFile).toHaveBeenCalled();
    });
  });
});
