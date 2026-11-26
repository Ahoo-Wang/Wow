import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  CATALOG,
  type FrameworkErrorsCatalog,
  frameworkErrors,
  frameworkTitles,
  OUTPUT,
  TITLES_OUTPUT,
} from '../scripts/framework-errors.mjs';
import { DEFAULT_RETRY } from '../src/frameworkErrors.js';
import { FRAMEWORK_RETRIES, FrameworkErrorCodes } from '../src/index.js';

/**
 * The client's codes, what a retry of each may do and its default retry
 * policy are generated from the server's error catalog (FrameworkErrors of
 * :manifest, written as framework-errors.json by its test, decision D366 ②):
 * a change to the catalog fails here until
 * `node typescript/wow-client/scripts/framework-errors.mjs` runs.
 */
describe('the framework errors', () => {
  const catalog: FrameworkErrorsCatalog = JSON.parse(
    readFileSync(CATALOG, 'utf8'),
  );

  it('should be what the error catalog generates', async () => {
    expect(readFileSync(OUTPUT, 'utf8')).toBe(await frameworkErrors(catalog));
    expect(readFileSync(TITLES_OUTPUT, 'utf8')).toBe(
      await frameworkTitles(catalog),
    );
  });

  it('should name every code of the catalog, with its status and retry', () => {
    expect(Object.values(FrameworkErrorCodes).sort()).toEqual(
      catalog.errors.map(error => error.code).sort(),
    );
    expect(FRAMEWORK_RETRIES.get('wow.command.unknown')).toEqual({
      retry: 'same-request',
      retryAfterMs: 1000,
    });
    // A query that ran out of time would run as long again: no retry.
    expect(FRAMEWORK_RETRIES.has('wow.query.timeout')).toBe(false);
    expect(DEFAULT_RETRY.maxAttempts).toBe(catalog.retry.attempts);
  });
});
