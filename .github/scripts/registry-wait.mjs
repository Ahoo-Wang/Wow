/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { isPublished } from './publish-npm.mjs';

// What the release's npm smoke (package-check.mjs --registry) waits for before
// it installs the version under test: the registry to serve each package's
// metadata, then each package's tarball, which npm's CDN can answer 404 for a
// few minutes more (10.0.0-beta.10's smoke failed in its install so).

/**
 * How long the smoke test waits for npm to serve a version it accepted:
 * up to 30 minutes. npm took about 13 minutes to serve wow-client 9.2.2
 * after accepting it, and with no approval step a smoke job that gives up
 * early only needs a re-run.
 */
const REGISTRY_ATTEMPTS = 120;
const REGISTRY_DELAY_MS = 15_000;

/**
 * Waits until the registry serves every name@version, asking at most
 * `attempts` times. A 404 and a registry error both mean "not yet"; the last
 * error of each package goes into the failure.
 */
export async function waitForRegistry(
  names,
  version,
  {
    attempts = REGISTRY_ATTEMPTS,
    delayMs = REGISTRY_DELAY_MS,
    published = isPublished,
    sleep = ms => new Promise(done => setTimeout(done, ms)),
    log = console.log,
  } = {},
) {
  let missing = [...names];
  const errors = new Map();
  for (let attempt = 1; ; attempt++) {
    missing = missing.filter(name => {
      try {
        return !published(name, version);
      } catch (error) {
        errors.set(name, `${error.stderr || error.message}`.trim());
        return true;
      }
    });
    if (missing.length === 0) {
      log(
        `registry: serves ${names.map(name => `${name}@${version}`).join(', ')}`,
      );
      return;
    }
    const specs = missing.map(name => `${name}@${version}`).join(', ');
    if (attempt >= attempts)
      throw new Error(
        `the registry does not serve ${specs} after ${attempts} attempts ${delayMs / 1000}s apart` +
          missing
            .filter(name => errors.has(name))
            .map(name => `\n  ${name}: ${errors.get(name).split('\n')[0]}`)
            .join(''),
      );
    log(`registry: waiting for ${specs} (attempt ${attempt}/${attempts})`);
    await sleep(delayMs);
  }
}

/**
 * How long the smoke test waits for the tarballs once the registry serves the
 * metadata: up to ten minutes, read every ten seconds.
 */
const TARBALL_TIMEOUT_MS = 600_000;
const TARBALL_INTERVAL_MS = 10_000;

/** The tarball URL the registry gives name@version, as npm install fetches it. */
export function tarballUrl(name, version) {
  return execFileSync('npm', ['view', `${name}@${version}`, 'dist.tarball'], {
    encoding: 'utf8',
  }).trim();
}

/**
 * Waits until each of `files` (`[name, url]` pairs) answers 200, reading
 * each one not yet there every `intervalMs` for at most `timeoutMs`. A 404, a
 * 5xx and a read that fails mean "not yet", as for waitForRegistry: a CDN that
 * has not caught up, or a reset on the way. A read that does not answer within
 * `intervalMs` fails, so none holds the wait past its bound. Any other answer,
 * a 401, 403 or 410 that no wait turns into a 200, is reported at once. At the
 * bound it fails, naming each file still missing with its last answer or
 * error. `kind` names one file and `registry` where it is read from, in the
 * log and the failure: npm's tarballs by default, Maven Central's POMs for
 * release-published.mjs.
 */
export async function waitForFiles(
  files,
  {
    timeoutMs = TARBALL_TIMEOUT_MS,
    intervalMs = TARBALL_INTERVAL_MS,
    kind = 'tarball',
    registry = 'the registry',
    fetch = globalThis.fetch,
    sleep = ms => new Promise(done => setTimeout(done, ms)),
    log = console.log,
  } = {},
) {
  const reads = Math.floor(timeoutMs / intervalMs) + 1;
  const last = new Map();
  let missing = [...files];
  for (let read = 1; ; read++) {
    const still = [];
    for (const [name, url] of missing) {
      let response;
      try {
        // A read that does not answer within the interval is abandoned and counts as failed, so no read holds the
        // loop past its bound.
        response = await fetch(url, {
          method: 'GET',
          cache: 'no-store',
          signal: AbortSignal.timeout(intervalMs),
        });
      } catch (error) {
        last.set(name, `could not be read: ${error.message}`);
        still.push([name, url]);
        continue;
      }
      // Only the status matters: the body is not downloaded.
      await response.body?.cancel();
      const answer = `${response.status} ${response.statusText}`.trim();
      if (response.status === 200) continue;
      if (response.status === 404 || response.status >= 500) {
        last.set(name, `answered ${answer}`);
        still.push([name, url]);
      } else
        throw new Error(`the ${kind} of ${name}, ${url}, answered ${answer}`);
    }
    missing = still;
    if (missing.length === 0) {
      log(
        `registry: serves the ${kind}s of ${files.map(([name]) => name).join(', ')}`,
      );
      return;
    }
    if (read >= reads)
      throw new Error(
        `${registry} does not serve the ${kind}s of ${missing.map(([name]) => name).join(', ')} after ${((read - 1) * intervalMs) / 1000}s (${read} reads ${intervalMs / 1000}s apart)` +
          missing
            .map(([name, url]) => `\n  ${name}: ${url} ${last.get(name)}`)
            .join(''),
      );
    log(
      `registry: waiting for the ${kind}s of ${missing.map(([name]) => name).join(', ')} (read ${read}/${reads})`,
    );
    await sleep(intervalMs);
  }
}
