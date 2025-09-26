#!/usr/bin/env node

/**
 * CLI entry point for the Fetcher OpenAPI code generator.
 * Sets up the commander program with generate command and handles execution.
 */

import { program } from 'commander';
import { generateAction } from './utils/clis';

program
  .name('fetcher-generator')
  .description('OpenAPI Specification TypeScript code generator for Wow')
  .version('1.0.0');

program
  .command('generate')
  .description('Generate TypeScript code from OpenAPI specification')
  .requiredOption(
    '-i, --input <path>',
    'Input OpenAPI specification file path or URL (http/https)',
  )
  .option('-o, --output <path>', 'Output directory path', 'src/generated')
  .option('-c, --config <file>', 'Configuration file path')
  .option('-v, --verbose', 'Enable verbose logging')
  .option('--dry-run', 'Show what would be generated without writing files')
  .action(generateAction);

program.parse();
