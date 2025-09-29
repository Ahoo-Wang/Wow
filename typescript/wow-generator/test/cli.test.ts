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

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Mock commander
vi.mock('commander', () => ({
  program: {
    name: vi.fn().mockReturnThis(),
    description: vi.fn().mockReturnThis(),
    version: vi.fn().mockReturnThis(),
    command: vi.fn().mockReturnThis(),
    requiredOption: vi.fn().mockReturnThis(),
    option: vi.fn().mockReturnThis(),
    action: vi.fn().mockReturnThis(),
    parse: vi.fn(),
  },
}));

// Mock generateAction
vi.mock('../../src/utils', () => ({
  generateAction: vi.fn(),
}));

// Mock package.json import
vi.mock('../package.json', () => ({
  default: {
    version: '2.1.2',
    name: '@ahoo-wang/fetcher-generator',
  },
}));

import { setupCLI, runCLI } from '../src/cli';
import { generateAction } from '../src/utils';
import packageJson from '../package.json';

describe('CLI setup', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('should setup CLI program with correct configuration', () => {
    const result = setupCLI();

    expect(result.name).toHaveBeenCalledWith('fetcher-generator');
    expect(result.description).toHaveBeenCalledWith(
      'OpenAPI Specification TypeScript code generator for Wow',
    );
    expect(result.version).toHaveBeenCalledWith(packageJson.version);

    expect(result.command).toHaveBeenCalledWith('generate');
    expect(result.requiredOption).toHaveBeenCalledWith(
      '-i, --input <path>',
      'Input OpenAPI specification file path or URL (http/https)',
    );
    expect(result.option).toHaveBeenCalledWith(
      '-o, --output <path>',
      'Output directory path',
      'src/generated',
    );
    expect(result.option).toHaveBeenCalledWith(
      '-c, --config <file>',
      'Configuration file path',
      './fetcher-generator.config.json',
    );
    expect(result.option).toHaveBeenCalledWith(
      '-v, --verbose',
      'Enable verbose logging',
    );
    expect(result.option).toHaveBeenCalledWith(
      '--dry-run',
      'Show what would be generated without writing files',
    );
    expect(result.action).toHaveBeenCalledWith(generateAction);
  });

  it('should return the configured program instance', () => {
    const result = setupCLI();
    expect(result).toBeDefined();
  });
});

describe('runCLI', () => {
  let originalRequireMain: any;

  beforeEach(() => {
    vi.clearAllMocks();
    originalRequireMain = require.main;
  });

  afterEach(() => {
    require.main = originalRequireMain;
  });

  it('should execute without error when run as main module', () => {
    // Mock require.main to simulate running as main module
    Object.defineProperty(require, 'main', {
      value: module,
      writable: true,
    });

    // Should not throw an error
    expect(() => runCLI()).not.toThrow();
  });

  it('should execute without error when imported as module', () => {
    // Mock require.main to simulate being imported
    Object.defineProperty(require, 'main', {
      value: { filename: 'other.js' },
      writable: true,
    });

    // Should not throw an error
    expect(() => runCLI()).not.toThrow();
  });
});
