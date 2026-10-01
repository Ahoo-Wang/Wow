/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { distTag } from './publish-npm.mjs';

// Whether an image workflow (example-, compensation- and view-store-deploy.yml)
// moves the Docker `latest` tag:
//
//   node .github/scripts/docker-latest.mjs     (GITHUB_REF, GITHUB_OUTPUT)
//
// It writes `latest=true` only for a release tag of the highest stable line,
// the rule npm's `latest` dist-tag follows (publish-npm.mjs distTag): a patch
// to an older line (v9.1.7 after v9.2.0) keeps its `X.Y.Z` and `X.Y` tags but
// must not move `latest` back. Every other ref (main, the daily run) writes
// `latest=false`. The tags come from the remote, so a shallow checkout works.

const TAG_REF = /^refs\/tags\/(v\d+\.\d+\.\d+)$/;

/** True when `ref` is a stable release tag at or above every stable `tags`. */
export function movesLatest(ref, tags) {
  const tag = TAG_REF.exec(ref ?? '')?.[1];
  return tag !== undefined && distTag(tag, tags) === 'latest';
}

/** The `v*` tag names of `git ls-remote --tags --refs` output. */
export function remoteTags(output) {
  return output
    .split('\n')
    .map(line => line.split('\t')[1])
    .filter(ref => ref?.startsWith('refs/tags/v'))
    .map(ref => ref.slice('refs/tags/'.length));
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const { GITHUB_REF, GITHUB_OUTPUT } = process.env;
  const latest =
    TAG_REF.test(GITHUB_REF ?? '') &&
    movesLatest(
      GITHUB_REF,
      remoteTags(
        execFileSync(
          'git',
          ['ls-remote', '--tags', '--refs', 'origin', 'refs/tags/v*'],
          { encoding: 'utf8' },
        ),
      ),
    );
  console.log(`${GITHUB_REF}: latest=${latest}`);
  appendFileSync(GITHUB_OUTPUT, `latest=${latest}\n`);
}
