/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { basename, dirname, extname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Holds the tracked files to the canonical locations of the documentation
// governance page (documentation/docs/en/contribute/documentation.md):
// nothing under the retired document/ and docs/superpowers/ trees, Markdown
// only where its reader looks for it, PlantUML only under documentation/diagrams/,
// and no rendered SVG beside the diagram source it was generated from.

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const GOVERNANCE = 'documentation/docs/en/contribute/documentation.md';

/** Trees that must hold no tracked file at all. */
export const RETIRED = ['document/', 'docs/superpowers/'];

/** Where Markdown may live, one entry per row of the governance page. */
export const MARKDOWN_LOCATIONS = [
  // Project introduction, repository policy and agent instructions.
  /^(?:CONTRIBUTING|SECURITY|CODE_OF_CONDUCT)\.md$/,
  /(?:^|\/)(?:AGENTS|CLAUDE)\.md$/,
  // Project introduction and module or package usage.
  /(?:^|\/)README(?:\.zh-CN)?\.md$/,
  // The documentation site and design records spanning JVM modules.
  /^documentation\/docs\//,
  /^documentation\/designs\/(?:v\d+\/)?[^/]+\.md$/,
  // The design of one TypeScript package or application.
  /^typescript\/[^/]+\/docs\/design\//,
  // Storybook's scenario catalog, which the stories cite by section.
  /^typescript\/storybook\/docs\/scenarios\.md$/,
  // Ledgers and release runbooks.
  /^docs\/todo\.md$/,
  // The eShop's developer-experience gaps, to close before M7.
  /^example\/eshop\/DX-GAPS\.md$/,
  /^typescript\/(?:TODO|MIGRATION|RELEASING|RELEASE_NOTES_TEMPLATE)\.md$/,
  // A minor release's notes draft, deleted once the release is published.
  /^typescript\/release-notes\/v[^/]+\.md$/,
  // Reproducible performance evidence.
  /^test\/benchmarks\//,
  // Agent capabilities, the project's own and vendored ones.
  /^skills\//,
  /^\.claude\/skills\//,
  // Generated API reports and third-party notices beside a package.
  /^typescript\/[^/]+\/test\/api\/[^/]+\.api\.md$/,
  /^typescript\/[^/]+\/THIRD_PARTY_NOTICES\.md$/,
  // GitHub issue and pull request templates.
  /^\.github\/(?:ISSUE_TEMPLATE\/[^/]+|PULL_REQUEST_TEMPLATE(?:\/[^/]+)?)\.md$/,
  // Release notes templates the release workflow fills (package-deploy.yml).
  /^\.github\/release-notes\/[^/]+\.md$/,
];

const PLANTUML = new Set(['.puml', '.plantuml', '.pu', '.iuml']);
const DIAGRAM_SOURCE = new Set([...PLANTUML, '.mmd']);
const PLANTUML_HOME = 'documentation/diagrams/';

const stem = path => `${dirname(path)}/${basename(path, extname(path))}`;

/** Every layout violation among the given repository-relative paths. */
export function layoutProblems(paths) {
  const problems = [];
  const sources = new Set(
    paths
      .filter(path => DIAGRAM_SOURCE.has(extname(path).toLowerCase()))
      .map(stem),
  );
  for (const path of paths) {
    const extension = extname(path).toLowerCase();
    const retired = RETIRED.find(tree => path.startsWith(tree));
    if (retired) {
      problems.push(`${path}: ${retired} is retired; track nothing under it`);
      continue;
    }
    if (
      extension === '.md' &&
      !MARKDOWN_LOCATIONS.some(location => location.test(path))
    )
      problems.push(
        `${path}: Markdown outside the canonical locations of ${GOVERNANCE}`,
      );
    if (PLANTUML.has(extension) && !path.startsWith(PLANTUML_HOME))
      problems.push(
        `${path}: PlantUML belongs under ${PLANTUML_HOME}, and only for diagrams Mermaid cannot express`,
      );
    if (extension === '.svg' && sources.has(stem(path)))
      problems.push(
        `${path}: rendered beside its diagram source; commit only the source`,
      );
  }
  return problems;
}

/** The tracked files of the repository at `root`. */
export function trackedFiles(root = ROOT) {
  return execFileSync('git', ['ls-files', '-z'], {
    cwd: root,
    encoding: 'utf8',
  })
    .split('\0')
    .filter(Boolean);
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const problems = layoutProblems(trackedFiles());
  for (const problem of problems) console.error(problem);
  if (problems.length > 0) process.exit(1);
  console.log('Documentation layout follows the governance page');
}
