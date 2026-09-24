/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  ROOT,
  compareVersions,
  parseVersion,
  readProjectVersion,
} from './project-version.mjs';

// Publishes the npm packages of a release. Run from the release commit after
// `pnpm build:typescript`:
//
//   node .github/scripts/publish-npm.mjs [--dry-run] [--no-provenance]
//
// - Only PUBLISHED packages go out; private packages never do.
// - A package whose version is already on npm is skipped, so a failed run can
//   be re-run after Maven succeeded.
// - The latest release line gets the `latest` dist-tag; a patch to an older
//   line gets `release-<major>.<minor>` so it does not move `latest`.
// - In CI, npm authenticates through OIDC trusted publishing and attaches
//   provenance; no token is involved.

/** Published to npm, in dependency order. */
export const PUBLISHED = [
  'typescript/wow-client',
  'typescript/wow-react',
  'typescript/wow-generator',
];
/** Public packages held back until they are stable (wow-view-engine, wow-view-store). */
export const HELD_BACK = [];
/** npm CLI version that supports OIDC trusted publishing. */
export const MIN_NPM = '11.5.1';

/**
 * The packages to publish, checked against the workspace: each is public and
 * on the project version, and no other public package exists unannounced.
 */
export function publishPlan(root = ROOT, version = readProjectVersion(root)) {
  const manifest = dir =>
    JSON.parse(readFileSync(join(root, dir, 'package.json'), 'utf8'));
  const problems = [];
  const plan = PUBLISHED.map(dir => {
    const { name, version: packageVersion, private: isPrivate } = manifest(dir);
    if (isPrivate)
      problems.push(`${dir}: private packages are never published`);
    if (packageVersion !== version)
      problems.push(`${dir}: version ${packageVersion} != ${version}`);
    return { dir, name, version };
  });
  for (const entry of readdirSync(join(root, 'typescript'), {
    withFileTypes: true,
  })) {
    const dir = `typescript/${entry.name}`;
    if (
      !entry.isDirectory() ||
      PUBLISHED.includes(dir) ||
      HELD_BACK.includes(dir)
    )
      continue;
    let isPrivate;
    try {
      isPrivate = manifest(dir).private;
    } catch {
      continue;
    }
    if (!isPrivate)
      problems.push(
        `${dir}: public but neither PUBLISHED nor HELD_BACK in publish-npm.mjs`,
      );
  }
  if (problems.length > 0) throw new Error(problems.join('\n'));
  return plan;
}

/**
 * The dist-tag for a version, given the repository's tags. The highest x.y.z
 * `v*` tag is the latest line; releasing below it is a patch to an older line.
 * npm refuses dist-tags that parse as semver ranges (`v9.0` does), hence the
 * `release-` prefix.
 */
export function distTag(version, tags) {
  const release = parseVersion(version);
  if (!release) throw new Error(`not a version: ${version}`);
  if (release.prerelease !== undefined) return 'next';
  const highest = tags
    .filter(tag => tag.startsWith('v'))
    .map(parseVersion)
    .filter(parsed => parsed && parsed.prerelease === undefined)
    .sort(compareVersions)
    .at(-1);
  return !highest || compareVersions(release, highest) >= 0
    ? 'latest'
    : `release-${release.major}.${release.minor}`;
}

/** Runs npm and returns stdout; a failure carries npm's stderr. */
function npm(args, options = {}) {
  return execFileSync('npm', args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    ...options,
  });
}

/** Whether name@version is already on the registry. */
export function isPublished(name, version, run = npm) {
  try {
    return run(['view', `${name}@${version}`, 'version']).trim() !== '';
  } catch (error) {
    // A package that was never published answers 404; anything else is real.
    if (/\bE404\b/.test(`${error.stderr ?? ''}${error.message ?? ''}`))
      return false;
    throw error;
  }
}

export function publishArgs(tarball, tag, { dryRun, provenance }) {
  return [
    'publish',
    tarball,
    '--access',
    'public',
    '--tag',
    tag,
    ...(provenance && !dryRun ? ['--provenance'] : []),
    ...(dryRun ? ['--dry-run'] : []),
  ];
}

/** Packs with pnpm, which rewrites `workspace:` and `catalog:` ranges. */
function pack(root, dir) {
  const destination = mkdtempSync(join(tmpdir(), 'wow-npm-'));
  execFileSync('pnpm', ['pack', '--pack-destination', destination], {
    cwd: join(root, dir),
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  const [tarball] = readdirSync(destination).filter(file =>
    file.endsWith('.tgz'),
  );
  if (!tarball) throw new Error(`${dir}: pnpm pack produced no tarball`);
  return join(destination, tarball);
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const args = process.argv.slice(2);
  const dryRun = args.includes('--dry-run');
  const provenance = !args.includes('--no-provenance');
  const version = readProjectVersion();
  const plan = publishPlan(ROOT, version);
  const tags = execFileSync('git', ['tag', '--list', 'v*'], {
    cwd: ROOT,
    encoding: 'utf8',
  })
    .split('\n')
    .filter(Boolean);
  const tag = distTag(version, tags);
  if (!dryRun) {
    const current = npm(['--version']).trim();
    if (compareVersions(parseVersion(current), parseVersion(MIN_NPM)) < 0)
      throw new Error(
        `npm ${current} < ${MIN_NPM}: no OIDC trusted publishing`,
      );
  }
  console.log(
    `${dryRun ? 'Dry run: ' : ''}publishing ${version} with dist-tag ${tag}`,
  );
  for (const { dir, name } of plan) {
    if (isPublished(name, version)) {
      console.log(`${name}@${version} is already on npm; skipped`);
      continue;
    }
    const tarball = pack(ROOT, dir);
    console.log(
      `npm ${publishArgs(tarball, tag, { dryRun, provenance }).join(' ')}`,
    );
    execFileSync('npm', publishArgs(tarball, tag, { dryRun, provenance }), {
      stdio: 'inherit',
    });
  }
}
