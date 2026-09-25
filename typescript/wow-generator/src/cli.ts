#!/usr/bin/env node

/**
 * CLI entry point of wow-generator: runs the program `cli/program.ts` sets
 * up on the process's command line.
 */

import { runCLI } from './cli/program';

export { collect, runCLI, setupCLI } from './cli/program';

void runCLI();
