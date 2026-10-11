#!/usr/bin/env node

/**
 * The `fetcher-generator` bin: the CLI under the name it had in fetcher. It
 * says on stderr that the name is deprecated, then runs as `wow-generator`;
 * stdout stays the same, so `fetcher-generator --version` still prints only
 * the version.
 */

import { DEPRECATED_BIN_NOTICE, runCLI } from './cli/program';

// compat(fetcher): the `fetcher-generator` bin of package.json; delete this
// file and that bin entry in v10.
process.stderr.write(`${DEPRECATED_BIN_NOTICE}\n`);
void runCLI();
