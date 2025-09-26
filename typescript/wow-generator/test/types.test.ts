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

import { describe, it, expect } from 'vitest';
import { GeneratorOptions, GenerateContext, Logger } from '../src/types';

describe('Types', () => {
  describe('GeneratorOptions', () => {
    it('should define required properties', () => {
      const options: GeneratorOptions = {
        project: {} as any,
        inputPath: 'test.json',
        outputDir: '/tmp/output',
      };

      expect(options.project).toBeDefined();
      expect(options.inputPath).toBe('test.json');
      expect(options.outputDir).toBe('/tmp/output');
    });

    it('should allow optional logger property', () => {
      const mockLogger: Logger = {
        info: () => {
        },
        success: () => {
        },
        error: () => {
        },
        progress: () => {
        },
      };

      const options: GeneratorOptions = {
        project: {} as any,
        inputPath: 'test.json',
        outputDir: '/tmp/output',
        logger: mockLogger,
      };

      expect(options.logger).toBe(mockLogger);
    });
  });

  describe('GenerateContext', () => {
    it('should define required properties', () => {
      const context: GenerateContext = {
        openAPI: { openapi: '3.0.0' } as any,
        project: {} as any,
        outputDir: '/tmp/output',
        contextAggregates: new Map(),
      };

      expect(context.openAPI).toBeDefined();
      expect(context.project).toBeDefined();
      expect(context.outputDir).toBe('/tmp/output');
      expect(context.contextAggregates).toBeInstanceOf(Map);
    });

    it('should allow optional logger property', () => {
      const mockLogger: Logger = {
        info: () => {
        },
        success: () => {
        },
        error: () => {
        },
        progress: () => {
        },
      };

      const context: GenerateContext = {
        openAPI: { openapi: '3.0.0' } as any,
        project: {} as any,
        outputDir: '/tmp/output',
        contextAggregates: new Map(),
        logger: mockLogger,
      };

      expect(context.logger).toBe(mockLogger);
    });
  });

  describe('Logger interface', () => {
    it('should define all required methods', () => {
      const logger: Logger = {
        info: (message: string) => `info: ${message}`,
        success: (message: string) => `success: ${message}`,
        error: (message: string) => `error: ${message}`,
        progress: (message: string) => `progress: ${message}`,
      };

      expect(typeof logger.info).toBe('function');
      expect(typeof logger.success).toBe('function');
      expect(typeof logger.error).toBe('function');
      expect(typeof logger.progress).toBe('function');

      expect(logger.info('test')).toBe('info: test');
      expect(logger.success('test')).toBe('success: test');
      expect(logger.error('test')).toBe('error: test');
      expect(logger.progress('test')).toBe('progress: test');
    });
  });
});
