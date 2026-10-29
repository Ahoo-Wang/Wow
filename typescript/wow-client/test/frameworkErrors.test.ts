import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
// @ts-expect-error the generator script is plain JavaScript, without declarations
import {
  CATALOG,
  frameworkErrors,
  OUTPUT,
} from '../scripts/framework-errors.mjs';
import {
  DEFAULT_RETRY,
  FRAMEWORK_RETRIES,
  FrameworkErrorCodes,
} from '../src/index.js';

/**
 * The client's codes, what a retry of each may do and its default retry
 * policy are generated from the server's error catalog (FrameworkErrors of
 * :manifest, written as framework-errors.json by its test, decision D366 ②):
 * a change to the catalog fails here until
 * `node typescript/wow-client/scripts/framework-errors.mjs` runs.
 */
describe('the framework errors', () => {
  const catalog = JSON.parse(readFileSync(CATALOG, 'utf8'));

  it('should be what the error catalog generates', async () => {
    expect(readFileSync(OUTPUT, 'utf8')).toBe(await frameworkErrors(catalog));
  });

  it('should name every code of the catalog, with its status and retry', () => {
    expect(Object.values(FrameworkErrorCodes).sort()).toEqual(
      catalog.errors.map((error: { code: string }) => error.code).sort(),
    );
    expect(FRAMEWORK_RETRIES.get('wow.command.unknown')).toEqual({
      retry: 'same-request',
      retryAfterMs: 1000,
    });
    // A query that ran out of time would run as long again: no retry.
    expect(FRAMEWORK_RETRIES.has('wow.query.timeout')).toBe(false);
    expect(DEFAULT_RETRY.attempts).toBe(catalog.retry.attempts);
  });
});
