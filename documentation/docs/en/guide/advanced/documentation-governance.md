---
title: Documentation Governance
description: Keep Wow documentation authoritative, discoverable, reproducible, and owned by one canonical location.
outline: deep
---

# Documentation Governance

Wow keeps current documentation in the smallest set of authoritative locations. Implementation plans are not long-term documentation, Git history is the archive, and evidence stays only when another contributor can reproduce or verify it.

Legacy tracked files under `document/` and `docs/superpowers/` are migration candidates, not sources of truth. Cleanup PRs remove them after any unique current knowledge or reproducible evidence has moved to its canonical owner.

## Canonical Locations

| Content | Canonical location | Rule |
| --- | --- | --- |
| Project introduction | `README.md`, `README.zh-CN.md` | Keep the shortest useful project entry and link to the documentation site |
| Governance | Root `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, `AGENTS.md`, `CLAUDE.md` | Keep only repository-wide policy and agent instructions |
| Product, architecture, migration, operations, and reference documentation | `documentation/docs/{en,zh}/` | This is the only long-term documentation site; public pages remain structurally bilingual |
| Static documentation assets | `documentation/docs/public/` | Keep only assets referenced by current documentation |
| Diagram sources Mermaid cannot express | `documentation/diagrams/` | PlantUML is limited to unsupported diagram kinds such as use cases |
| Reproducible performance evidence | `wow-benchmarks/results/` | Evidence belongs to the benchmark module that can interpret it |
| Agent capabilities | `skills/` | Skill instructions and their references remain executable, co-located assets |
| Published module usage | `<module>/README*` | A module README stays beside the artifact it explains |

Do not track long-term documentation in `document/` or `docs/superpowers/`. `docs/superpowers/` may remain ignored as a local agent workspace, but do not force-add its specs, plans, reports, or review packages.

## Lifecycle

### Durable documentation

Durable documentation defines a current contract, architecture boundary, supported migration, operational procedure, or exact reference. Update its canonical page in place rather than creating a dated successor. Public documentation changes must keep the English and Chinese structure and technical meaning aligned.

### Co-located documentation

Module READMEs and Skill instructions are not competing documentation sites. They explain a local artifact or executable agent capability and link to VitePress for broader concepts. Do not copy complete architecture, migration, or configuration guidance into them.

### Reproducible evidence

Keep experimental evidence only when it records all of the following:

1. the tested commit or immutable source revision;
2. the exact command and parameters;
3. the relevant runtime, dependency, and environment facts;
4. raw or derived results sufficient to verify the stated conclusion;
5. checksums or another integrity mechanism when artifacts are bundled.

Move retained performance evidence to `wow-benchmarks/results/`. Remove duplicated artifacts, unverifiable experiments, and evidence for conclusions no current document or benchmark report uses.

### Ephemeral work

Specs, implementation plans, review notes, temporary QA comparisons, and completed rewrite plans are working material. Keep them in ignored local workspaces while work is active. After the durable conclusion is merged into its canonical page, delete the working material; Git history and the pull request retain the implementation record.

## When Documentation Is Expired

A document is expired when any of these conditions apply:

- a canonical VitePress page already owns the same conclusion;
- its API, version, type name, route, configuration, or architecture has been removed;
- it describes how a completed change was implemented but defines no current contract;
- its evidence cannot be reproduced or verified;
- a newer source owns the same topic and the older source adds no supported distinction.

Do not delete by date alone. Before deletion, search inbound links and compare the content with current source, tests, and canonical documentation. If the file contains unique current knowledge, migrate that knowledge first and delete the old file in the same PR.

## Migration Documentation

The current site keeps only the direct path to the current major version and current operational migrations. For Wow V9, retain the V8-to-V9 path and current runtime or traditional-architecture migrations. Older version chains belong to their release tags; do not keep V6-to-V8 material in the V9 site.

## Diagrams and Assets

- Use fenced Mermaid for every diagram Mermaid supports.
- Do not commit a generated SVG beside Mermaid source.
- Keep PlantUML only for unsupported kinds such as use-case diagrams, under `documentation/diagrams/`.
- Keep a rendered asset only when the documentation runtime cannot render its canonical source.
- Remove unreferenced screenshots and duplicate assets; keep logos, badges, and current product screenshots in `documentation/docs/public/`.

## Cleanup Workflow

Clean documentation in independently reviewable PRs:

1. inventory tracked documentation, assets, links, and owners;
2. classify each item as keep, migrate, relocate, or delete;
3. migrate unique current knowledge before deleting its source;
4. relocate reproducible evidence to its owning module;
5. remove stale links, navigation entries, duplicate assets, and expired migration pages;
6. list migrated and deleted paths in the PR body instead of committing another cleanup report;
7. build and review each batch before starting the next.

The final state removes `document/` and all tracked `docs/superpowers/` files.

## Enforcement

The documentation layout check runs on relevant pull requests and fails when:

- tracked files appear under `document/` or `docs/superpowers/`;
- Markdown is added outside approved root governance files, `documentation/docs/`, `skills/`, `wow-benchmarks/`, or a module README;
- a Mermaid-supported diagram adds PlantUML or a generated diagram SVG;
- the VitePress site cannot build with the locked dependency graph.

The check uses repository scripts and existing toolchains; it does not add a documentation framework or another archive directory.

## Review Checklist

- Is there exactly one authoritative current page for the topic?
- Are English and Chinese public pages structurally aligned?
- Do local READMEs and Skills link instead of copying broad guidance?
- Is every retained experiment reproducible and owned by `wow-benchmarks`?
- Are deleted paths free of inbound links?
- Does the PR avoid tracked plans, generated build output, and local agent state?
- Do the layout check, VitePress build, and `git diff --check` pass?
