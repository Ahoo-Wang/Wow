#!/usr/bin/env node

/**
 * CLI entry point for the Fetcher OpenAPI code generator.
 * Sets up the commander program with generate command and handles execution.
 */

import { program } from 'commander';
import packageJson from '../package.json';
import { DEFAULT_CONFIG_PATH } from './index';
import { generateAction } from './utils';

/**
 * Sets up the CLI program with all commands and options.
 * @returns The configured commander program instance
 */
export function setupCLI() {
  program
    .name('wow-generator')
    .description('OpenAPI Specification TypeScript code generator for Wow')
    .version(packageJson.version, '-v, --version');

  program
    .command('generate')
    .description('Generate TypeScript code from OpenAPI specification')
    .requiredOption(
      '-i, --input <file>',
      'Input OpenAPI specification file path or URL (http/https)',
    )
    .option('-o, --output <path>', 'Output directory path', 'src/generated')
    // No commander default: an omitted -c falls back to DEFAULT_CONFIG_PATH,
    // which may be absent, while a path the user named has to exist.
    .option(
      '-c, --config <file>',
      `Configuration file path (default: ${DEFAULT_CONFIG_PATH})`,
    )
    .option(
      '-t, --ts-config-file-path <file>',
      'TypeScript configuration file path',
    )
    .action(generateAction);

  return program;
}

/**
 * Runs the CLI program by parsing command line arguments.
 * Only executes when this file is run directly (not imported).
 */
export function runCLI() {
  setupCLI().parse();
}

runCLI();
