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
import { Project, SourceFile, VariableDeclarationKind } from 'ts-morph';
import { QueryClientGenerator } from '../../src/client/queryClientGenerator';
import { GenerateContext } from '../../src/types';
import { AggregateDefinition } from '../../src/aggregate';
import { SilentLogger } from '../../src/utils/logger';

// Mock the dependencies
vi.mock('../../src/utils');
vi.mock('../../src/model');
vi.mock('../../src/client/utils');

describe('QueryClientGenerator', () => {
  const mockOpenAPI = {
    openapi: '3.0.0',
    info: { title: 'Test API', version: '1.0.0' },
    paths: {},
  };

  const mockContextAggregates = new Map<string, Set<AggregateDefinition>>([
    [
      'context1',
      new Set([
        {
          aggregate: {
            aggregateName: 'user',
            contextAlias: 'context1',
            tag: { name: 'context1.user' } as any,
          } as any,
          commands: new Map(),
          events: new Map([
            ['UserCreated', { schema: { key: 'userCreatedEvent' } } as any],
            ['UserUpdated', { schema: { key: 'userUpdatedEvent' } } as any],
          ]),
          state: { key: 'userState', schema: {} as any },
          fields: { key: 'userFields', schema: {} as any },
        },
        {
          aggregate: {
            aggregateName: 'product',
            contextAlias: 'context1',
            tag: { name: 'context1.product' } as any,
          } as any,
          commands: new Map(),
          events: new Map(),
          state: { key: 'productState', schema: {} as any },
          fields: { key: 'productFields', schema: {} as any },
        },
      ]),
    ],
  ]);

  const mockLogger = new SilentLogger();

  const createContext = (logger?: any): GenerateContext => ({
    openAPI: mockOpenAPI,
    project: new Project(),
    outputDir: '/tmp/test',
    contextAggregates: mockContextAggregates,
    logger,
  });

  let mockSourceFile: any;

  beforeEach(async () => {
    vi.clearAllMocks();
    const { getOrCreateSourceFile: mockGetOrCreateSourceFile } = vi.mocked(
      await import('../../src/utils'),
    );
    const { resolveModelInfo: mockResolveModelInfo } = vi.mocked(
      await import('../../src/model'),
    );
    const { createClientFilePath: mockCreateClientFilePath } = vi.mocked(
      await import('../../src/client/utils'),
    );

    mockSourceFile = {
      addImportDeclaration: vi.fn(),
      addVariableStatement: vi.fn(),
      addTypeAlias: vi.fn(),
    };

    mockGetOrCreateSourceFile.mockReturnValue(mockSourceFile as any);
    mockCreateClientFilePath.mockReturnValue(mockSourceFile as any);

    mockResolveModelInfo.mockImplementation((key: string) => ({
      name: key.charAt(0).toUpperCase() + key.slice(1),
      path: key,
      key,
    }));
  });

  it('should initialize with provided context', () => {
    const context = createContext(mockLogger);
    const generator = new QueryClientGenerator(context);

    expect(generator.project).toBe(context.project);
    expect(generator.openAPI).toBe(context.openAPI);
    expect(generator.outputDir).toBe(context.outputDir);
    expect(generator.contextAggregates).toBe(context.contextAggregates);
    expect(generator.logger).toBe(context.logger);
  });

  it('should initialize without logger', () => {
    const context = createContext();
    const generator = new QueryClientGenerator(context);

    expect(generator.logger).toBeUndefined();
  });

  it('should generate query clients for all aggregates', () => {
    const context = createContext(mockLogger);
    const generator = new QueryClientGenerator(context);

    generator.generate();
  });

  it('should process query client with events', () => {
    const context = createContext(mockLogger);
    const generator = new QueryClientGenerator(context);

    const aggregate = Array.from(mockContextAggregates.values())[0]
      .values()
      .next().value;

    generator.processQueryClient(aggregate);

    // Verify that the source file methods were called
    expect(mockSourceFile.addImportDeclaration).toHaveBeenCalled();
    expect(mockSourceFile.addVariableStatement).toHaveBeenCalled();
    expect(mockSourceFile.addTypeAlias).toHaveBeenCalled();
  });

  it('should create client file path correctly', async () => {
    const context = createContext(mockLogger);
    const generator = new QueryClientGenerator(context);

    const aggregate = {
      aggregateName: 'testAggregate',
      contextAlias: 'testContext',
      tag: { name: 'testContext.testAggregate' } as any,
    } as any;

    const result = generator.createClientFilePath(aggregate, 'queryClient');

    const { createClientFilePath } = vi.mocked(
      await import('../../src/client/utils'),
    );
    expect(createClientFilePath).toHaveBeenCalledWith(
      context.project,
      context.outputDir,
      aggregate,
      'queryClient',
    );
    expect(result).toBe(mockSourceFile);
  });

  it('should handle empty context aggregates', () => {
    const emptyContextAggregates = new Map<string, Set<AggregateDefinition>>();
    const context = {
      ...createContext(mockLogger),
      contextAggregates: emptyContextAggregates,
    };
    const generator = new QueryClientGenerator(context);

    generator.generate();
  });
});
