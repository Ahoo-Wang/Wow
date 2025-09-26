#!/usr/bin/env node

/**
 * CLI entry point for the Fetcher OpenAPI code generator.
 * Sets up the commander program with generate command and handles execution.
 */

import { program } from 'commander';
import { Project } from 'ts-morph';
import { GeneratorOptions } from './types';
import { CodeGenerator } from './index';
import { ConsoleLogger } from './utils/logger';

program
  .name('fetcher-generator')
  .description('OpenAPI Specification TypeScript code generator for Wow')
  .version('1.0.0');

/**
 * Validates the input path or URL.
 * @param input - Input path or URL
 * @returns true if valid
 */
function validateInput(input: string): boolean {
  if (!input) return false;

  // Check if it's a URL
  try {
    const url = new URL(input);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    // Not a URL, check if it's a file path
    // For file paths, we'll let parseOpenAPI handle it
    return input.length > 0;
  }
}

/**
 * Action handler for the generate command.
 * @param options - Command options
 */
export async function generateAction(options: {
  input: string;
  output: string;
  config?: string;
  verbose?: boolean;
  dryRun?: boolean;
}) {
  const logger = new ConsoleLogger();

  // Handle signals
  process.on('SIGINT', () => {
    logger.error('Generation interrupted by user');
    process.exit(130);
  });

  // Validate input
  if (!validateInput(options.input)) {
    logger.error('Invalid input: must be a valid file path or HTTP/HTTPS URL');
    process.exit(2);
  }

  try {
    logger.info('Starting code generation...');
    const project = new Project();
    const generatorOptions: GeneratorOptions = {
      inputPath: options.input,
      outputDir: options.output,
      project,
      logger,
    };
    const codeGenerator = new CodeGenerator(generatorOptions);
    await codeGenerator.generate();
    logger.success(
      `Code generation completed successfully! Files generated in: ${options.output}`,
    );
  } catch (error) {
    logger.error(`Error during code generation: ${error}`);
    process.exit(1);
  }
}

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
