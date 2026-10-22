/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { ROOT, compareVersions, parseVersion } from './project-version.mjs';
import { distTag } from './publish-npm.mjs';

// What a release of a v* tag publishes, decided once from the tag and every
// v* tag of the repository, for package-deploy.yml and image-deploy.yml:
//
//   REF=refs/tags/v<version> node .github/scripts/release-plan.mjs
//
// It prints the plan as key=value lines and appends them to $GITHUB_OUTPUT.
//
// - The ref is a `v<x.y.z>` or `v<x.y.z-pre>` tag. A tag on an older major
//   than the highest v* tag is refused: an older major releases from its
//   maintenance branch (`v<major>`), with its own copy of the workflows.
// - A pre-release (`-alpha.n`, `-beta.n`, `-rc.n`) keeps its own npm
//   dist-tag, pushes its images under the version alone and is a GitHub
//   pre-release, never the latest release.
// - A stable release also pushes the images under `<major>.<minor>`. When it
//   is the latest line (the highest stable v* tag, publish-npm.mjs distTag),
//   npm publishes it under `latest`, the images get `latest` and its GitHub
//   release is marked latest; a patch to an older line moves none of them.

/** The GitHub release notes template of each kind of release. */
export const NOTES = {
  prerelease: '.github/release-notes/prerelease.md',
  stable: '.github/release-notes/stable.md',
};

/** The release a ref publishes, or an Error saying why it publishes nothing. */
export function releasePlan(ref, tags) {
  const tag = ref?.startsWith('refs/tags/v')
    ? ref.slice('refs/tags/'.length)
    : undefined;
  if (!tag)
    throw new Error(
      `Releases run from a v* tag, not ${ref}. Choose the tag under 'Use workflow from'.`,
    );
  const release = parseVersion(tag);
  if (!release)
    throw new Error(
      `${tag} is not a release tag (v<x.y.z> or v<x.y.z-pre>, such as v10.0.0 or v10.1.0-rc.1)`,
    );
  const highest = tags
    .filter(other => other.startsWith('v'))
    .map(parseVersion)
    .filter(Boolean)
    .sort(compareVersions)
    .at(-1);
  if (highest && release.major < highest.major)
    throw new Error(
      `${tag} is on an older major line than v${highest.major}; ${release.major}.x releases from the v${release.major} branch.`,
    );
  const version = tag.slice(1);
  const prerelease = release.prerelease !== undefined;
  const npmDistTag = distTag(version, tags);
  const latest = !prerelease && npmDistTag === 'latest';
  const line = `${release.major}.${release.minor}`;
  return {
    tag,
    version,
    major: release.major,
    line,
    prerelease,
    latest,
    npmDistTag,
    imageTags: [
      version,
      ...(prerelease ? [] : [line]),
      ...(latest ? ['latest'] : []),
    ],
    notes: prerelease ? NOTES.prerelease : NOTES.stable,
  };
}

/** The plan as the `key=value` lines of a step's outputs. */
export function outputLines(plan) {
  return [
    `tag=${plan.tag}`,
    `version=${plan.version}`,
    `major=${plan.major}`,
    `line=${plan.line}`,
    `prerelease=${plan.prerelease}`,
    `latest=${plan.latest}`,
    `npm-dist-tag=${plan.npmDistTag}`,
    `image-tags=${plan.imageTags.join(',')}`,
    `notes=${plan.notes}`,
  ];
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const tags = execFileSync('git', ['tag', '--list', 'v*'], {
    cwd: ROOT,
    encoding: 'utf8',
  })
    .split('\n')
    .filter(Boolean);
  let plan;
  try {
    plan = releasePlan(process.env.REF, tags);
  } catch (error) {
    console.error(`::error::${error.message}`);
    process.exit(1);
  }
  const lines = outputLines(plan);
  console.log(lines.join('\n'));
  if (process.env.GITHUB_OUTPUT)
    appendFileSync(process.env.GITHUB_OUTPUT, `${lines.join('\n')}\n`);
}
