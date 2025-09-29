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
import { ClientGenerator } from '../../src/client';
import { GenerateContext } from '../../src/generateContext';
import { GenerateContextInit } from '../../src/types';
import { AggregateDefinition } from '../../src/aggregate';
import { SilentLogger } from '../../src/utils/logger';

// Mock the dependencies
vi.mock('../../src/client/queryClientGenerator', () => ({
  QueryClientGenerator: vi.fn(),
}));

vi.mock('../../src/client/commandClientGenerator', () => ({
  CommandClientGenerator: vi.fn(),
}));

vi.mock('../../src/utils', () => ({
  getOrCreateSourceFile: vi.fn(),
}));

describe('ClientGenerator', () => {
  const mockOpenAPI = {
    openapi: '3.0.0',
    info: { title: 'Test API', version: '1.0.0' },
    paths: {},
  };

  const mockAggregate1: AggregateDefinition = {
    aggregate: {
      aggregateName: 'agg1',
      contextAlias: 'context1',
      tag: { name: 'context1.agg1' } as any,
    } as any,
    commands: new Map(),
    events: new Map(),
    state: { key: 'state1', schema: {} as any },
    fields: { key: 'fields1', schema: {} as any },
  };

  const mockAggregate2: AggregateDefinition = {
    aggregate: {
      aggregateName: 'agg2',
      contextAlias: 'context2',
      tag: { name: 'context2.agg2' } as any,
    } as any,
    commands: new Map(),
    events: new Map(),
    state: { key: 'state2', schema: {} as any },
    fields: { key: 'fields2', schema: {} as any },
  };

  const mockContextAggregates = new Map<string, Set<AggregateDefinition>>([
    ['context1', new Set([mockAggregate1])],
    ['context2', new Set([mockAggregate2])],
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
    return new GenerateContext(contextInit);
  };

  let mockQueryClientGenerator: any;
  let mockCommandClientGenerator: any;

  beforeEach(async () => {
    vi.clearAllMocks();
    const { QueryClientGenerator: QueryClientGeneratorMock } = vi.mocked(
      await import('../../src/client/queryClientGenerator'),
    );
    const { CommandClientGenerator: CommandClientGeneratorMock } = vi.mocked(
      await import('../../src/client/commandClientGenerator'),
    );
    const { getOrCreateSourceFile: getOrCreateSourceFileMock } = vi.mocked(
      await import('../../src/utils'),
    );

    mockQueryClientGenerator = {
      generate: vi.fn(),
    };
    mockCommandClientGenerator = {
      generate: vi.fn(),
    };

    (QueryClientGeneratorMock as any).mockImplementation(
      () => mockQueryClientGenerator,
    );
    (CommandClientGeneratorMock as any).mockImplementation(
      () => mockCommandClientGenerator,
    );
    getOrCreateSourceFileMock.mockReturnValue({
      addStatements: vi.fn(),
    } as any);
  });

  it('should initialize with provided context and create child generators', () => {
    const context = createContext(mockLogger);
    const generator = new ClientGenerator(context);

    expect(generator.context.project).toBe(context.project);
    expect(generator.context.openAPI).toBe(context.openAPI);
    expect(generator.context.outputDir).toBe(context.outputDir);
    expect(generator.context.contextAggregates).toBe(context.contextAggregates);
    expect(generator.context.logger).toBe(context.logger);
  });

  it('should generate clients for all bounded contexts and call child generators', () => {
    const context = createContext(mockLogger);
    const generator = new ClientGenerator(context);

    generator.generate();

    expect(mockQueryClientGenerator.generate).toHaveBeenCalledTimes(1);
    expect(mockCommandClientGenerator.generate).toHaveBeenCalledTimes(1);
  });

  it('should generate clients without logger', () => {
    const context = createContext();
    const generator = new ClientGenerator(context);

    generator.generate();

    expect(mockQueryClientGenerator.generate).toHaveBeenCalledTimes(1);
    expect(mockCommandClientGenerator.generate).toHaveBeenCalledTimes(1);
  });

  it('should process bounded context by creating boundedContext.ts file', async () => {
    const context = createContext(mockLogger);
    const generator = new ClientGenerator(context);

    const { getOrCreateSourceFile: mockGetOrCreateSourceFile } = vi.mocked(
      await import('../../src/utils'),
    );
    const mockFile = {
      addStatements: vi.fn(),
    };
    mockGetOrCreateSourceFile.mockReturnValue(mockFile as any);

    generator.processBoundedContext('test-context');

    expect(mockGetOrCreateSourceFile).toHaveBeenCalledWith(
      context.project,
      context.outputDir,
      'test-context/boundedContext.ts',
    );
    expect(mockFile.addStatements).toHaveBeenCalledWith(
      'export const BOUNDED_CONTEXT_ALIAS = \'test-context\';',
    );
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
    const generator = new ClientGenerator(context);

    generator.generate();

    expect(mockQueryClientGenerator.generate).toHaveBeenCalledTimes(1);
    expect(mockCommandClientGenerator.generate).toHaveBeenCalledTimes(1);
  });
});
