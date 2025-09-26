#!/usr/bin/env node

import { program } from 'commander';
import { CodeGenerator } from '@/index.ts';
import { GeneratorOptions } from '@/types.ts';
import { Project } from 'ts-morph';

program
  .name('fetcher-openapi-generator')
  .description('OpenAPI Specification TypeScript code generator for Fetcher')
  .version('1.0.0');

program
  .command('generate')
  .description('Generate TypeScript code from OpenAPI specification')
  .requiredOption('-i, --input <path>', 'Input OpenAPI specification file path')
  .requiredOption('-o, --output <path>', 'Output directory path')
  .action(async options => {
    try {
      const project = new Project();
      const generatorOptions: GeneratorOptions = {
        inputPath: options.input,
        outputDir: options.output,
        project,
      };
      const codeGenerator = new CodeGenerator(generatorOptions);
      await codeGenerator.generate();
      console.log('Code generation completed successfully!');
    } catch (error) {
      console.error('Error during code generation:', error);
      process.exit(1);
    }
  });

program.parse();
