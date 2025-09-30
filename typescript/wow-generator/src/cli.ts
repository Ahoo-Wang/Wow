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
    .name('fetcher-generator')
    .description('OpenAPI Specification TypeScript code generator for Wow')
    .version(packageJson.version);

  program
    .command('generate')
    .description('Generate TypeScript code from OpenAPI specification')
    .requiredOption(
      '-i, --input <file>',
      'Input OpenAPI specification file path or URL (http/https)',
    )
    .option('-o, --output <path>', 'Output directory path', 'src/generated')
    .option('-c, --config <file>', 'Configuration file path', DEFAULT_CONFIG_PATH)
    .option('-t, --ts-config-file-path <file>', 'TypeScript configuration file path')
    .option('-v, --verbose', 'Enable verbose logging')
    .option('--dry-run', 'Show what would be generated without writing files')
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
