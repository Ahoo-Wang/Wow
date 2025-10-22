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
import { CommandClientGenerator } from '../../src/client';
import { AggregateDefinition } from '../../src/aggregate';
import { SilentLogger } from '../../src/utils/logger';
import { GenerateContext } from '../../src/generateContext';
import { GenerateContextInit } from '../../src/types';

// Mock the dependencies
vi.mock('../../src/utils');
vi.mock('../../src/model');
vi.mock('../../src/client/utils');

describe('CommandClientGenerator', () => {
  const mockOpenAPI = {
    openapi: '3.0.0',
    info: { title: 'Test API', version: '1.0.0' },
    paths: {},
  };

  const mockCommand = {
    name: 'createUser',
    method: 'post' as const,
    path: '/users',
    pathParameters: [],
    summary: 'Create a user',
    description: 'Creates a new user',
    schema: { key: 'createUserCommand', schema: {} as any },
    operation: {} as any,
  };

  const mockAggregate: AggregateDefinition = {
    aggregate: {
      aggregateName: 'user',
      contextAlias: 'context1',
      tag: { name: 'context1.user' } as any,
    } as any,
    commands: new Map([['createUser', mockCommand]]),
    events: new Map(),
    state: { key: 'userState', schema: {} as any },
    fields: { key: 'userFields', schema: {} as any },
  };

  const mockContextAggregates = new Map<string, Set<AggregateDefinition>>([
    ['context1', new Set([mockAggregate])],
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

  let mockSourceFile: any;

  beforeEach(async () => {
    vi.clearAllMocks();
    const {
      getOrCreateSourceFile: mockGetOrCreateSourceFile,
      addImportRefModel: mockAddImportRefModel,
      addImport: mockAddImport,
      addJSDoc: mockAddJSDoc,
    } = vi.mocked(await import('../../src/utils'));
    const { resolveModelInfo: mockResolveModelInfo } = vi.mocked(
      await import('../../src/model'),
    );
    const {
      createClientFilePath: mockCreateClientFilePath,
      getClientName: mockGetClientName,
    } = vi.mocked(await import('../../src/client/utils'));

    mockSourceFile = {
      addImportDeclaration: vi.fn(),
      addVariableStatement: vi.fn(),
      addEnum: vi.fn(() => ({ addMember: vi.fn() })),
      addClass: vi.fn(() => ({
        addImplements: vi.fn(),
        addConstructor: vi.fn(),
        addMethod: vi.fn(() => ({ addJSDoc: vi.fn() })),
      })),
      addConstructor: vi.fn(),
      addMethod: vi.fn(),
    };

    mockGetOrCreateSourceFile.mockReturnValue(mockSourceFile as any);
    mockCreateClientFilePath.mockReturnValue(mockSourceFile as any);
    mockAddImportRefModel.mockImplementation(() => {});
    mockResolveModelInfo.mockReturnValue({
      name: 'CreateUserCommand',
      path: 'createUserCommand',
    });
    mockAddImport.mockImplementation(() => {});
    mockAddJSDoc.mockImplementation(() => {});
    mockGetClientName.mockImplementation(
      (aggregate, suffix) => `User${suffix}`,
    );
  });

  it('should initialize with provided context', () => {
    const context = createContext(mockLogger);
    const generator = new CommandClientGenerator(context);

    expect(generator.context).toBe(context);
  });

  it('should initialize without logger', () => {
    const context = createContext(undefined);
    const generator = new CommandClientGenerator(context);

    expect(generator.context.logger).toBe(mockLogger);
  });

  it('should generate command clients for all aggregates', () => {
    const context = createContext(mockLogger);
    const generator = new CommandClientGenerator(context);

    generator.generate();
  });

  it('should process aggregate and create command client', () => {
    const context = createContext(mockLogger);
    const generator = new CommandClientGenerator(context);

    generator.processAggregate(mockAggregate);

    // Verify that the source file methods were called
    expect(mockSourceFile.addImportDeclaration).toHaveBeenCalled();
    expect(mockSourceFile.addVariableStatement).toHaveBeenCalled();
    expect(mockSourceFile.addEnum).toHaveBeenCalled();
    expect(mockSourceFile.addClass).toHaveBeenCalled();
  });

  it('should process command endpoint paths', () => {
    const context = createContext(mockLogger);
    const generator = new CommandClientGenerator(context);

    const mockEnum = {
      addMember: vi.fn(),
    };
    mockSourceFile.addEnum.mockReturnValue(mockEnum);

    generator.processCommandEndpointPaths(mockSourceFile, mockAggregate);

    expect(mockSourceFile.addEnum).toHaveBeenCalledWith({
      name: 'COMMAND_ENDPOINT_PATHS',
    });
    expect(mockEnum.addMember).toHaveBeenCalledWith({
      name: 'CREATEUSER',
      initializer: `'${mockCommand.path}'`,
    });
  });

  it('should get endpoint path for command', () => {
    const context = createContext(mockLogger);
    const generator = new CommandClientGenerator(context);

    const result = generator.getEndpointPath(mockCommand);

    expect(result).toBe('COMMAND_ENDPOINT_PATHS.CREATEUSER');
  });

  it('should process command client without stream', () => {
    const context = createContext(mockLogger);
    const generator = new CommandClientGenerator(context);

    const mockClass = {
      addImplements: vi.fn(),
      addConstructor: vi.fn(),
      addMethod: vi.fn(),
    };
    mockSourceFile.addClass.mockReturnValue(mockClass);

    generator.processCommandClient(mockSourceFile, mockAggregate, false);

    expect(mockSourceFile.addClass).toHaveBeenCalledWith({
      name: 'UserCommandClient',
      isExported: true,
      typeParameters: ['R = CommandResult'],
      decorators: [
        {
          name: 'api',
          arguments: [],
        },
      ],
    });
    expect(mockClass.addImplements).toHaveBeenCalledWith('ApiMetadataCapable');
    expect(mockClass.addConstructor).toHaveBeenCalled();
    expect(mockClass.addMethod).toHaveBeenCalled();
  });

  it('should process command client with stream', () => {
    const context = createContext(mockLogger);
    const generator = new CommandClientGenerator(context);

    const mockClass = {
      addImplements: vi.fn(),
      addConstructor: vi.fn(),
      addMethod: vi.fn(),
    };
    mockSourceFile.addClass.mockReturnValue(mockClass);

    generator.processCommandClient(mockSourceFile, mockAggregate, true);

    expect(mockSourceFile.addClass).toHaveBeenCalledWith(
      expect.objectContaining({
        name: expect.stringContaining('CommandClient'),
        isExported: true,
        decorators: expect.any(Array),
      }),
    );
    expect(mockClass.addImplements).toHaveBeenCalledWith('ApiMetadataCapable');
  });

  it('should handle empty context aggregates', () => {
    const emptyContextAggregates = new Map<string, Set<AggregateDefinition>>();
    const context = {
      ...createContext(mockLogger),
      contextAggregates: emptyContextAggregates,
    };
    const generator = new CommandClientGenerator(context);

    generator.generate();
  });
});
