/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// The one version source is `version` in gradle.properties. Every package.json
// in the workspace follows it: the TypeScript packages (published or not), the
// ops console and the documentation site; and so do the Helm charts released
// with Wow, their `version` and `appVersion` (the image tag they default to).
//
//   node .github/scripts/project-version.mjs set <version>   (pnpm set-version)
//   node .github/scripts/project-version.mjs check           (pnpm check:versions)
//
// `check` also compares a release tag when RELEASE_TAG is set.

export const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const GRADLE_VERSION = /^version=(.*)$/m;
const EXTRA_PACKAGES = [
  'platform/ops/console',
  'platform/analytics/catalog/console',
  'documentation',
];

/** The Chart.yaml of each Helm chart released with Wow (chart-deploy.yml). */
export const CHARTS = ['deploy/helm/wow-service/Chart.yaml'];
/** A chart's top-level `version` or `appVersion`, unquoted. */
const CHART_FIELD = /^(version|appVersion):[ \t]*(\S+)[ \t]*$/gm;

const VERSION = /^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/;

/** Parses `x.y.z` or `x.y.z-pre`, with an optional leading `v`. */
export function parseVersion(text) {
  const match = VERSION.exec(text ?? '');
  if (!match) return undefined;
  const [, major, minor, patch, prerelease] = match;
  return {
    major: Number(major),
    minor: Number(minor),
    patch: Number(patch),
    prerelease,
  };
}

/** Orders two parsed versions; a prerelease sorts before its release. */
export function compareVersions(a, b) {
  return (
    a.major - b.major ||
    a.minor - b.minor ||
    a.patch - b.patch ||
    (a.prerelease === b.prerelease
      ? 0
      : a.prerelease === undefined
        ? 1
        : b.prerelease === undefined
          ? -1
          : a.prerelease < b.prerelease
            ? -1
            : 1)
  );
}

export function readProjectVersion(root = ROOT) {
  const match = GRADLE_VERSION.exec(
    readFileSync(join(root, 'gradle.properties'), 'utf8'),
  );
  if (!match) throw new Error('gradle.properties has no version property');
  return match[1].trim();
}

/** Every package.json that carries the project version, relative to root. */
export function versionedPackageFiles(root = ROOT) {
  const typescript = readdirSync(join(root, 'typescript'), {
    withFileTypes: true,
  })
    .filter(entry => entry.isDirectory())
    .map(entry => `typescript/${entry.name}/package.json`)
    .filter(file => existsSync(join(root, file)));
  return [
    ...typescript.sort(),
    ...EXTRA_PACKAGES.map(dir => `${dir}/package.json`),
  ];
}

/** The `version` and `appVersion` of a Chart.yaml's text, those it has. */
export function chartVersions(text) {
  const fields = {};
  for (const [, field, value] of text.matchAll(CHART_FIELD))
    fields[field] = value;
  return fields;
}

/** Lists every file whose version differs from the expected one. */
export function versionMismatches(root = ROOT, releaseTag) {
  const expected = readProjectVersion(root);
  const mismatches = [];
  if (releaseTag !== undefined && releaseTag !== `v${expected}`)
    mismatches.push(
      `release tag ${releaseTag} != v${expected} (gradle.properties)`,
    );
  for (const file of versionedPackageFiles(root)) {
    const { version } = JSON.parse(readFileSync(join(root, file), 'utf8'));
    if (version !== expected)
      mismatches.push(`${file}: ${version} != ${expected} (gradle.properties)`);
  }
  for (const file of CHARTS) {
    const fields = chartVersions(readFileSync(join(root, file), 'utf8'));
    for (const field of ['version', 'appVersion'])
      if (fields[field] !== expected)
        mismatches.push(
          `${file} ${field}: ${fields[field]} != ${expected} (gradle.properties)`,
        );
  }
  return mismatches;
}

/** Writes the version into gradle.properties and every versioned package.json. */
export function setProjectVersion(version, root = ROOT) {
  if (!parseVersion(version) || version.startsWith('v'))
    throw new Error(`not a version: ${version}`);
  const gradle = join(root, 'gradle.properties');
  const properties = readFileSync(gradle, 'utf8');
  if (!GRADLE_VERSION.test(properties))
    throw new Error('gradle.properties has no version property');
  writeFileSync(
    gradle,
    properties.replace(GRADLE_VERSION, `version=${version}`),
  );
  const changed = ['gradle.properties'];
  for (const file of versionedPackageFiles(root)) {
    const path = join(root, file);
    const text = readFileSync(path, 'utf8');
    // Edit the one field in place so key order and formatting survive.
    const next = text.replace(
      /^(\s*"version":\s*)"[^"]*"/m,
      (_, prefix) => `${prefix}"${version}"`,
    );
    if (JSON.parse(next).version !== version)
      throw new Error(`${file}: could not set the top-level version field`);
    if (next !== text) {
      writeFileSync(path, next);
      changed.push(file);
    }
  }
  for (const file of CHARTS) {
    const path = join(root, file);
    const text = readFileSync(path, 'utf8');
    const next = text.replace(
      CHART_FIELD,
      (_, field) => `${field}: ${version}`,
    );
    const fields = chartVersions(next);
    if (fields.version !== version || fields.appVersion !== version)
      throw new Error(`${file}: could not set its version and appVersion`);
    if (next !== text) {
      writeFileSync(path, next);
      changed.push(file);
    }
  }
  return changed;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const [command, version] = process.argv.slice(2);
  if (command === 'set' && version) {
    const previous = readProjectVersion();
    for (const file of setProjectVersion(version)) console.log(`set ${file}`);
    // The rest of a release bump is prose and snapshots; list what still
    // mentions the old version instead of rewriting it blindly.
    let rest = '';
    try {
      rest = execFileSync('git', ['grep', '-l', '-F', previous], {
        cwd: ROOT,
        encoding: 'utf8',
      });
    } catch {
      // git grep exits 1 when nothing matches.
    }
    if (rest.trim())
      console.log(
        `Still mentioning ${previous}; update the ones that track the release (see "Version Management" in AGENTS.md):\n${rest.trim()}`,
      );
  } else if (command === 'check') {
    const mismatches = versionMismatches(
      ROOT,
      process.env.RELEASE_TAG || undefined,
    );
    for (const mismatch of mismatches) console.error(mismatch);
    if (mismatches.length > 0) process.exit(1);
    console.log(
      `Versions match ${readProjectVersion()} across ${versionedPackageFiles().length} package.json files and ${CHARTS.length} chart`,
    );
  } else {
    console.error(
      'usage: project-version.mjs set <version> | project-version.mjs check',
    );
    process.exit(2);
  }
}
