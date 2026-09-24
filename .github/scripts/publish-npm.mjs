/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
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
//                                        [--pack <dir> | --tarballs <dir>]
//
// - A real publish refuses a dirty working tree and a HEAD that is not the
//   commit of `v<version>`: what goes out is exactly the tagged commit.
// - `--pack <dir>` packs the packages and stops; `--tarballs <dir>` publishes
//   tarballs packed earlier, so the release workflow packs, checks and
//   publishes the same files.
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
export const HELD_BACK = ['typescript/wow-view-engine'];
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

/** The file name `pnpm pack` gives a package: `@scope/name` → `scope-name-<version>.tgz`. */
export function tarballName(name, version) {
  return `${name.replace(/^@/, '').replace('/', '-')}-${version}.tgz`;
}

/**
 * Packs a package with pnpm, which rewrites `workspace:` and `catalog:` ranges,
 * into `destination` and returns the tarball's path.
 */
export function pack(root, dir, destination) {
  execFileSync('pnpm', ['pack', '--pack-destination', destination], {
    cwd: join(root, dir),
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  const { name, version } = JSON.parse(
    readFileSync(join(root, dir, 'package.json'), 'utf8'),
  );
  const tarball = join(destination, tarballName(name, version));
  if (!existsSync(tarball))
    throw new Error(
      `${dir}: pnpm pack produced no ${tarballName(name, version)}`,
    );
  return tarball;
}

/**
 * Why a real publish must not run from this checkout, or undefined. A publish
 * cannot be taken back, so it ships exactly the tagged commit: the working
 * tree is clean and HEAD is the commit of `v<version>`.
 */
export function publishRefusal({ status, head, tagCommit, version }) {
  if (status.trim() !== '')
    return `the working tree is not clean; publish from a fresh clone of v${version}:\n${status.trimEnd()}`;
  if (!tagCommit) return `tag v${version} does not exist in this clone`;
  if (head !== tagCommit)
    return `HEAD ${head.slice(0, 9)} is not the commit of v${version} (${tagCommit.slice(0, 9)}); run: git checkout --detach v${version}`;
  return undefined;
}

function git(args) {
  return execFileSync('git', args, {
    cwd: ROOT,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function tagCommit(version) {
  try {
    return git([
      'rev-parse',
      '--verify',
      '--quiet',
      `v${version}^{commit}`,
    ]).trim();
  } catch {
    return undefined;
  }
}

function option(args, name) {
  const index = args.indexOf(name);
  if (index === -1) return undefined;
  const value = args[index + 1];
  if (!value || value.startsWith('--'))
    throw new Error(`${name} needs a directory`);
  return resolve(value);
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const args = process.argv.slice(2);
  const dryRun = args.includes('--dry-run');
  const provenance = !args.includes('--no-provenance');
  // --pack <dir>: only pack the packages into <dir>, for the package check and
  // the publish job. --tarballs <dir>: publish those tarballs, not fresh packs.
  const packInto = option(args, '--pack');
  const tarballs = option(args, '--tarballs');
  const version = readProjectVersion();
  const plan = publishPlan(ROOT, version);

  if (packInto) {
    mkdirSync(packInto, { recursive: true });
    for (const { dir } of plan) console.log(pack(ROOT, dir, packInto));
    process.exit(0);
  }

  const tags = git(['tag', '--list', 'v*']).split('\n').filter(Boolean);
  const tag = distTag(version, tags);
  if (!dryRun) {
    const refusal = publishRefusal({
      status: git(['status', '--porcelain']),
      head: git(['rev-parse', 'HEAD']).trim(),
      tagCommit: tagCommit(version),
      version,
    });
    if (refusal) throw new Error(`refusing to publish: ${refusal}`);
    const current = npm(['--version']).trim();
    if (compareVersions(parseVersion(current), parseVersion(MIN_NPM)) < 0)
      throw new Error(
        `npm ${current} < ${MIN_NPM}: no OIDC trusted publishing`,
      );
  }
  console.log(
    `${dryRun ? 'Dry run: ' : ''}publishing ${version} with dist-tag ${tag}`,
  );
  const scratch = tarballs
    ? undefined
    : mkdtempSync(join(tmpdir(), 'wow-npm-'));
  try {
    for (const { dir, name } of plan) {
      if (isPublished(name, version)) {
        console.log(`${name}@${version} is already on npm; skipped`);
        continue;
      }
      const tarball = tarballs
        ? join(tarballs, tarballName(name, version))
        : pack(ROOT, dir, scratch);
      if (!existsSync(tarball)) throw new Error(`${tarball} does not exist`);
      const publish = publishArgs(tarball, tag, { dryRun, provenance });
      console.log(`npm ${publish.join(' ')}`);
      execFileSync('npm', publish, { stdio: 'inherit' });
    }
  } finally {
    if (scratch) rmSync(scratch, { recursive: true, force: true });
  }
}
