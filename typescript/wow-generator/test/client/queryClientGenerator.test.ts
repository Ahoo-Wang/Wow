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
import { Project, StructureKind } from 'ts-morph';
import { QueryClientGenerator } from '../../src/client';
import { GenerateContext } from '../../src/generateContext';
import type { GenerateContextInit } from '../../src/generateContext';
import { AggregateDefinition } from '../../src/aggregate';
import { SilentLogger } from '../../src/api/logger';

// Mock the dependencies
vi.mock('../../src/emit/imports');
vi.mock('../../src/naming/naming');
vi.mock('../../src/output/generatedFiles');
vi.mock('../../src/model');
vi.mock('../../src/client/utils');

import { addImport } from '../../src/emit/imports';

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
          resourceName: 'user',
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
          resourceName: 'product',
          commands: new Map(),
          events: new Map(),
          state: { key: 'productState', schema: {} as any },
          fields: { key: 'productFields', schema: {} as any },
        },
      ]),
    ],
  ]);

  const mockLogger = new SilentLogger();

  const createContext = (logger?: any): GenerateContext => {
    const contextInit: GenerateContextInit = {
      openAPI: mockOpenAPI,
      project: new Project(),
      outputDir: '/tmp/test',
      contextAggregates: mockContextAggregates,
      logger: logger || mockLogger,
      config: {},
    };
    const context = new GenerateContext(contextInit);
    vi.spyOn(context, 'module').mockReturnValue(mockModule);
    return context;
  };

  let mockModule: any;

  beforeEach(async () => {
    vi.clearAllMocks();
    const { resolveModelInfo: mockResolveModelInfo } = vi.mocked(
      await import('../../src/model'),
    );
    const { clientModulePath: mockClientModulePath } = vi.mocked(
      await import('../../src/client/utils'),
    );

    mockModule = { add: vi.fn(statement => statement) };
    mockClientModulePath.mockImplementation(
      (aggregate, fileName) =>
        `${aggregate.contextAlias}/${aggregate.aggregateName}/${fileName}.ts`,
    );

    mockResolveModelInfo.mockImplementation((key: string) => ({
      name: key.charAt(0).toUpperCase() + key.slice(1),
      path: key,
      key,
    }));
  });

  it('should initialize with provided context', () => {
    const context = createContext(mockLogger);
    const generator = new QueryClientGenerator(context);

    expect(generator.context.project).toBe(context.project);
    expect(generator.context.openAPI).toBe(context.openAPI);
    expect(generator.context.outputDir).toBe(context.outputDir);
    expect(generator.context.contextAggregates).toBe(context.contextAggregates);
    expect(generator.context.logger).toBe(context.logger);
  });

  it('should initialize without logger', () => {
    const context = createContext();
    const generator = new QueryClientGenerator(context);

    expect(generator.context.logger).toBe(mockLogger);
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
      .next().value as AggregateDefinition;

    generator.processQueryClient(aggregate);

    // Verify that the source file methods were called
    expect(vi.mocked(addImport)).toHaveBeenCalledWith(
      mockModule,
      '@ahoo-wang/wow-client',
      [
        'QueryClientFactory',
        'QueryClientOptions',
        'ResourceAttributionPathSpec',
      ],
    );
    expect(mockModule.add).toHaveBeenCalledWith(
      expect.objectContaining({ kind: StructureKind.VariableStatement }),
    );
    expect(mockModule.add).toHaveBeenCalledWith(
      expect.objectContaining({ kind: StructureKind.TypeAlias }),
    );
    expect(mockModule.add).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: StructureKind.Enum,
        members: [expect.any(Object), expect.any(Object)],
      }),
    );
  });

  it('should use never when the aggregate has no domain events', () => {
    const context = createContext(mockLogger);
    const generator = new QueryClientGenerator(context);
    const aggregates = Array.from(mockContextAggregates.values())[0];
    const aggregate = Array.from(aggregates)[1];

    generator.processQueryClient(aggregate);

    expect(mockModule.add).toHaveBeenCalledWith(
      expect.objectContaining({ kind: StructureKind.TypeAlias, type: 'never' }),
    );
  });

  it('writes a client into the module of its aggregate', async () => {
    const context = createContext(mockLogger);
    const generator = new QueryClientGenerator(context);

    const aggregate = {
      aggregateName: 'testAggregate',
      contextAlias: 'testContext',
      tag: { name: 'testContext.testAggregate' } as any,
    } as any;

    const result = generator.clientModule(aggregate, 'queryClient');

    const { clientModulePath } = vi.mocked(
      await import('../../src/client/utils'),
    );
    expect(clientModulePath).toHaveBeenCalledWith(aggregate, 'queryClient');
    expect(context.module).toHaveBeenCalledWith(
      'testContext/testAggregate/queryClient.ts',
    );
    expect(result).toBe(mockModule);
  });

  it('should handle empty context aggregates', () => {
    const emptyContextAggregates = new Map<string, Set<AggregateDefinition>>();
    const contextInit: GenerateContextInit = {
      openAPI: mockOpenAPI,
      project: new Project(),
      outputDir: '/tmp/test',
      contextAggregates: emptyContextAggregates,
      logger: mockLogger,
      config: {},
    };
    const context = new GenerateContext(contextInit);
    const generator = new QueryClientGenerator(context);

    generator.generate();
  });
});
