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

import { describe, it, expect, vi } from 'vitest';
import { Project } from 'ts-morph';
import { GenerateContext } from '../src/generateContext';
import { GenerateContext } from '../src/types';
import { BoundedContextAggregates } from '../src/aggregate';

// Test implementation of BaseCodeGenerator
class TestCodeGenerator extends GenerateContext {
  public constructor(context: GenerateContext) {
    super(context);
  }

  generate(): void {
    // Test implementation
  }
}

describe('BaseCodeGenerator', () => {
  const mockOpenAPI = {
    openapi: '3.0.0',
    info: { title: 'Test API', version: '1.0.0' },
    paths: {},
  };

  const mockContextAggregates = new Map();

  const mockLogger = {
    info: vi.fn(),
    success: vi.fn(),
    error: vi.fn(),
    progress: vi.fn(),
  };

  const createContext = (logger?: any): GenerateContext => ({
    openAPI: mockOpenAPI,
    project: new Project(),
    outputDir: '/tmp/test',
    contextAggregates: mockContextAggregates,
    logger,
  });

  it('should initialize with provided context', () => {
    const context = createContext(mockLogger);
    const generator = new TestCodeGenerator(context);

    expect(generator.project).toBe(context.project);
    expect(generator.openAPI).toBe(context.openAPI);
    expect(generator.outputDir).toBe(context.outputDir);
    expect(generator.contextAggregates).toBe(context.contextAggregates);
    expect(generator.logger).toBe(context.logger);
  });

  it('should initialize without logger', () => {
    const context = createContext();
    const generator = new TestCodeGenerator(context);

    expect(generator.logger).toBeUndefined();
  });

  it('should have abstract generate method', () => {
    const context = createContext();
    const generator = new TestCodeGenerator(context);

    expect(typeof generator.generate).toBe('function');
  });

  it('should implement GenerateContext interface', () => {
    const context = createContext(mockLogger);
    const generator = new TestCodeGenerator(context);

    // Check that it implements the interface properties
    expect(generator).toHaveProperty('openAPI');
    expect(generator).toHaveProperty('project');
    expect(generator).toHaveProperty('outputDir');
    expect(generator).toHaveProperty('contextAggregates');
    expect(generator).toHaveProperty('logger');
  });
});
