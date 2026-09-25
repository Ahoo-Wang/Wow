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
  CommanderError: class CommanderError extends Error {},
  program: {
    exitOverride: vi.fn().mockReturnThis(),
    name: vi.fn().mockReturnThis(),
    description: vi.fn().mockReturnThis(),
    version: vi.fn().mockReturnThis(),
    command: vi.fn().mockReturnThis(),
    requiredOption: vi.fn().mockReturnThis(),
    option: vi.fn().mockReturnThis(),
    action: vi.fn().mockReturnThis(),
    parse: vi.fn(),
    parseAsync: vi.fn().mockResolvedValue(undefined),
  },
}));

// Mock generateAction
vi.mock('../../src/cli/runGenerate', () => ({
  generateAction: vi.fn(),
}));

// Mock the version the build injects
vi.mock('../../src/version', () => ({ VERSION: '2.1.2' }));

import { program } from 'commander';
import { collect, setupCLI, runCLI } from '../../src/cli';
import { generateAction } from '../../src/cli/runGenerate';

describe('CLI setup', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('should setup CLI program with correct configuration', () => {
    const result = setupCLI();

    expect(result.exitOverride).toHaveBeenCalledWith();
    expect(result.name).toHaveBeenCalledWith('wow-generator');
    expect(result.description).toHaveBeenNthCalledWith(
      1,
      'OpenAPI Specification TypeScript code generator for Wow',
    );
    expect(result.version).toHaveBeenCalledWith('2.1.2', '-v, --version');

    expect(result.command).toHaveBeenCalledWith('generate');
    expect(result.description).toHaveBeenNthCalledWith(
      2,
      'Generate TypeScript code from OpenAPI specification',
    );
    expect(result.requiredOption).toHaveBeenCalledWith(
      '-i, --input <file>',
      'Input OpenAPI specification file path or URL (http/https)',
    );
    expect(result.option).toHaveBeenCalledWith(
      '-o, --output <path>',
      'Output directory path',
      'src/generated',
    );
    // No commander default: CodeGenerator owns the fallback, so an omitted
    // -c stays undefined and a named path is treated as required.
    expect(result.option).toHaveBeenCalledWith(
      '-c, --config <file>',
      'Configuration file path (default: ./wow-generator.config.json)',
    );
    expect(result.option).toHaveBeenCalledWith(
      '-t, --ts-config-file-path <file>',
      'TypeScript configuration file path',
    );
    expect(result.option).toHaveBeenCalledWith(
      '-H, --header <header>',
      'Request header for an http(s) input or configuration, as "Name: value"; repeatable',
      expect.any(Function),
    );
    expect(result.option).toHaveBeenCalledWith(
      '--timeout <ms>',
      'Milliseconds before fetching an http(s) input is abandoned (default: 30000)',
    );
    expect(result.option).toHaveBeenCalledWith(
      '--schema-docs <mode>',
      'What model doc comments carry: "summary" (title, description, constraints) or "full" (also the JSON schema)',
      'summary',
    );
    expect(result.option).toHaveBeenCalledWith(
      '--strict',
      'Exit with code 4 when the run logs a warning',
    );
    expect(result.option).toHaveBeenCalledWith(
      '--verbose',
      'Log every step, and the stack trace of a failure',
    );
    expect(result.option).toHaveBeenCalledWith(
      '--quiet',
      'Log only warnings and errors',
    );
    expect(result.action).toHaveBeenCalledWith(generateAction);
  });

  it('collects repeated --header values', () => {
    setupCLI();
    expect(program.option).toHaveBeenCalledWith(
      '-H, --header <header>',
      expect.any(String),
      collect,
    );
    expect(collect('A: 1', undefined)).toEqual(['A: 1']);
    expect(collect('B: 2', ['A: 1'])).toEqual(['A: 1', 'B: 2']);
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
