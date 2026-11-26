/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { ROOT } from './project-version.mjs';

// The workspace develops on the newest fetcher (the default catalog), while
// the published packages accept every fetcher the `peers` catalog names. The
// floor of that range is held by ci.yml's `fetcher-floor` job:
//
//   node .github/scripts/fetcher-floor.mjs pin     # overrides every fetcher to the floor
//   pnpm install --no-frozen-lockfile
//   node .github/scripts/fetcher-floor.mjs verify  # the packages resolve the floor
//
// then the unit tests and `package-check.mjs --fetcher-floor`. The floor is
// read from the `peers` catalog, so raising it there (only in an x.Y.0
// release) moves the job with it.
//
// `pin <version>` and `verify <version>` do the same for another fetcher the
// range admits, such as a release candidate the default catalog does not
// develop on yet (`8.0.0-rc.1`, D490), with `package-check.mjs --fetcher
// <version>`: the run a maintainer repeats locally before such a fetcher
// becomes the default.

const FETCHER = /^'?(@ahoo-wang\/fetcher[\w-]*)'?: (.+)$/;

/** The entries of a catalog block: `catalog:` or `peers:` under `catalogs:`. */
function catalogEntries(yaml, header, indent) {
  const lines = yaml.split('\n');
  const start = lines.indexOf(header);
  assert.notEqual(start, -1, `pnpm-workspace.yaml has no ${header.trim()}`);
  const entries = {};
  for (const line of lines.slice(start + 1)) {
    if (line.trim() === '' || line.trimStart().startsWith('#')) continue;
    if (!line.startsWith(indent)) break;
    const match = FETCHER.exec(line.slice(indent.length));
    if (match) entries[match[1]] = match[2].replace(/^'|'$/g, '');
  }
  return entries;
}

/** `{ name: range }` of the fetcher packages in the `peers` catalog. */
export function peerFetchers(yaml) {
  return catalogEntries(yaml, '  peers:', '    ');
}

/** `{ name: range }` of the fetcher packages in the default catalog. */
export function devFetchers(yaml) {
  return catalogEntries(yaml, 'catalog:', '  ');
}

/**
 * The lowest fetcher the published packages accept: the lower bound of the
 * first alternative of each peer range (`^5.1.5 || ^6.0.0` → 5.1.5), the
 * same for every fetcher peer.
 */
export function fetcherFloor(yaml) {
  const floors = new Set(
    Object.entries(peerFetchers(yaml)).map(([name, range]) => {
      const floor = /^[\^~]?(\d+\.\d+\.\d+)$/.exec(
        range.split('||')[0].trim(),
      )?.[1];
      assert.ok(floor, `${name}: no lower bound in ${range}`);
      return floor;
    }),
  );
  assert.equal(
    floors.size,
    1,
    `The fetcher peers disagree on the floor: ${[...floors].join(', ')}`,
  );
  return [...floors][0];
}

/**
 * The fetcher packages pnpm-lock.yaml resolves, whichever package pulls them
 * in (CoSec's client brings fetcher-storage and fetcher-eventbus as peers).
 * Only `@ahoo-wang/fetcher*`: CoSec's client, `@ahoo-wang/cosec-fetcher`, is
 * versioned with CoSec and stays as the lockfile has it.
 */
export function lockedFetchers(lockfile) {
  return [
    ...new Set(
      [...lockfile.matchAll(/^ {2}'(@ahoo-wang\/fetcher[\w-]*)@/gm)].map(
        match => match[1],
      ),
    ),
  ];
}

/**
 * pnpm-workspace.yaml with every fetcher package, of the default catalog and
 * of the lockfile, overridden to the floor, or to `version`.
 */
export function withFloorOverrides(
  yaml,
  lockfile = '',
  version = fetcherFloor(yaml),
) {
  const names = new Set([
    ...Object.keys(devFetchers(yaml)),
    ...lockedFetchers(lockfile),
  ]);
  const lines = [...names].sort().map(name => `  '${name}': '${version}'`);
  if (!/^overrides:$/m.test(yaml))
    return `${yaml.replace(/\n*$/, '\n')}\noverrides:\n${lines.join('\n')}\n`;
  // Merge into the workspace's own overrides (security pins), which must
  // leave the fetcher packages to this job.
  const block = yaml.split(/^overrides:$/m)[1].split(/^\S/m)[0];
  assert.doesNotMatch(
    block,
    /^ {2}'?@ahoo-wang\/fetcher/m,
    'The workspace overrides a fetcher package; the floor job owns those',
  );
  return yaml.replace(/^overrides:$/m, `overrides:\n${lines.join('\n')}`);
}

/**
 * Fetcher packages a workspace package resolves to another version than
 * `floor`, as `dir: name is version`.
 */
export function unpinned(root, floor) {
  const problems = [];
  const dir = join(root, 'typescript');
  for (const name of readdirSync(dir).sort()) {
    const manifest = join(dir, name, 'package.json');
    if (!existsSync(manifest)) continue;
    const { dependencies, devDependencies, peerDependencies } = JSON.parse(
      readFileSync(manifest, 'utf8'),
    );
    const fetchers = Object.keys({
      ...dependencies,
      ...devDependencies,
      ...peerDependencies,
    }).filter(dependency => dependency.startsWith('@ahoo-wang/fetcher'));
    for (const fetcher of fetchers) {
      const installed = join(
        dir,
        name,
        'node_modules',
        ...fetcher.split('/'),
        'package.json',
      );
      const version = existsSync(installed)
        ? JSON.parse(readFileSync(installed, 'utf8')).version
        : 'not installed';
      if (version !== floor)
        problems.push(`typescript/${name}: ${fetcher} is ${version}`);
    }
  }
  return problems;
}

/** A fetcher version as `pin`, `verify` and `--fetcher` take it: exact, never a range or a tag. */
export const FETCHER_VERSION = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;

/**
 * The command and the fetcher version of `fetcher-floor.mjs pin|verify
 * [version]`: the floor of the `peers` range when no version is given, which
 * is how the `fetcher-floor` job runs it.
 */
export function floorCommand(args, yaml) {
  const [command, given, ...rest] = args;
  if (
    (command !== 'pin' && command !== 'verify') ||
    rest.length > 0 ||
    (given !== undefined && !FETCHER_VERSION.test(given))
  )
    throw new Error('Usage: fetcher-floor.mjs pin|verify [version]');
  return { command, version: given ?? fetcherFloor(yaml) };
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const file = join(ROOT, 'pnpm-workspace.yaml');
  const yaml = readFileSync(file, 'utf8');
  const { command, version } = floorCommand(process.argv.slice(2), yaml);
  if (command === 'pin') {
    const lockfile = readFileSync(join(ROOT, 'pnpm-lock.yaml'), 'utf8');
    writeFileSync(file, withFloorOverrides(yaml, lockfile, version));
    console.log(`fetcher pinned to ${version}`);
  } else {
    const problems = unpinned(ROOT, version);
    if (problems.length > 0)
      throw new Error(`Not on fetcher ${version}:\n  ${problems.join('\n  ')}`);
    console.log(`every TypeScript package resolves fetcher ${version}`);
  }
}
