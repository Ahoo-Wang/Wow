#!/usr/bin/env node

/**
 * CLI entry point of wow-generator.
 * Sets up the commander program with generate command and handles execution.
 */

import { program } from 'commander';
import packageJson from '../package.json';
import { DEFAULT_CONFIG_PATH } from './index';
import { DEFAULT_HTTP_TIMEOUT_MS, generateAction } from './utils';

function collect(value: string, previous: string[] = []): string[] {
  return [...previous, value];
}

/**
 * Sets up the CLI program with all commands and options.
 * @returns The configured commander program instance
 */
export function setupCLI() {
  // compat(fetcher): package.json also installs this CLI as `fetcher-generator`, the
  // name existing scripts call; drop that bin alias in v10.
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
    .option(
      '-H, --header <header>',
      'Request header for an http(s) input or configuration, as "Name: value"; repeatable',
      collect,
    )
    .option(
      '--timeout <ms>',
      `Milliseconds before fetching an http(s) input is abandoned (default: ${DEFAULT_HTTP_TIMEOUT_MS})`,
    )
    .option('--strict', 'Exit with code 4 when the run logs a warning')
    .option('--verbose', 'Log every step, and the stack trace of a failure')
    .option('--quiet', 'Log only warnings and errors')
    .action(generateAction);

  return program;
}

/**
 * Runs the CLI program by parsing command line arguments.
 * Only executes when this file is run directly (not imported).
 */
export function runCLI() {
  void setupCLI().parseAsync();
}

runCLI();
