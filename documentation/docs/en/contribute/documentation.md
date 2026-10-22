---
title: Documentation Governance
description: Keep Wow documentation authoritative, discoverable, reproducible, and owned by one canonical location.
outline: deep
---

# Documentation Governance

Wow keeps each kind of documentation in one place, chosen by who reads it. This page states the principles, the resulting locations, and the lifecycle each kind of document follows.

## Principles

1. **A document has one reader and one job.** Its location follows from the reader: users read the site, maintainers of a module read beside the module, agents read `AGENTS.md`.
2. **One fact, one owner.** Every current fact has exactly one authoritative page. Other documents summarize it in a sentence and link to it.
3. **The present lives in place; the past lives in Git.** Update the owning page instead of adding a dated successor. Plans, reviews and superseded drafts are not kept: the pull request and Git history are the archive.
4. **A claim needs evidence someone else can rerun.** Keep measurements only with the commit, command, environment and raw results that reproduce them.
5. **Generated contracts are not prose.** ABI dumps, API reports, surface lists and generated clients are checked artifacts; they are regenerated, never edited by hand.

## Canonical Locations

| Content | Reader | Canonical location |
| --- | --- | --- |
| Project introduction | Evaluators | `README.md`, `README.zh-CN.md` |
| Repository policy | Contributors | `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md` |
| Agent instructions | Coding agents | Root `AGENTS.md` and a scoped `AGENTS.md` in a subtree that needs its own rules (`typescript/`, each TypeScript package); `CLAUDE.md` only points to `AGENTS.md` |
| Product, architecture, migration, operations and reference documentation | Users | `documentation/docs/{en,zh}/`, the only long-term documentation site; public pages stay structurally bilingual |
| Static documentation assets | Users | `documentation/docs/public/` |
| Diagram sources Mermaid cannot express | Maintainers | `documentation/diagrams/` (created with its first file) |
| Design records spanning JVM modules or the repository | Maintainers | `documentation/designs/` |
| Design of one TypeScript package or application | Maintainers of that package | `<package>/docs/design/` |
| Ledgers of decided work | Maintainers | `docs/todo.md` (JVM), `typescript/TODO.md` (wow-client, wow-react, wow-generator), `typescript/wow-view-engine/docs/design/todo.md` (view engine and view store), `example/eshop/DX-GAPS.md` (V10 developer-experience gaps the eShop found, to close before M7) |
| Release runbooks | Release maintainers | `AGENTS.md` (versioning, compatibility, CI), `typescript/RELEASING.md`, `typescript/RELEASE_NOTES_TEMPLATE.md`; a minor release's notes draft in `typescript/release-notes/v<version>.md`, deleted once the release is published |
| Reproducible performance evidence | Maintainers | `test/benchmarks/results/` |
| Agent capabilities | Coding agents | `skills/`; vendored third-party skills in `.claude/skills/` |
| Published module usage | Module users | `<module>/README.md` and `README.zh-CN.md` |
| Storybook's scenario catalog | Storybook maintainers | `typescript/storybook/docs/scenarios.md` |
| Third-party notices | Package users | `typescript/<package>/THIRD_PARTY_NOTICES.md` |
| Issue and pull request templates | Contributors | `.github/ISSUE_TEMPLATE/`, `.github/PULL_REQUEST_TEMPLATE` |
| Generated contracts | Reviewers | `<module>/api/*.api`, `typescript/*/test/api/*.api.md`, `typescript/*/test/surface/*.txt` |

Do not track files under `document/` or `docs/superpowers/`. `docs/superpowers/` remains an ignored local agent workspace; do not force-add its specs, plans, reports or review packages.

## Lifecycle

### Durable documentation

Durable documentation defines a current contract, architecture boundary, supported migration, operational procedure, or exact reference. Update its canonical page in place in the same pull request that changes the behavior. Public documentation changes keep the English and Chinese structure and technical meaning aligned.

### Co-located documentation

Module READMEs, package design documents and Skill instructions are not competing documentation sites. They explain a local artifact or an executable agent capability and link to VitePress for broader concepts. Do not copy complete architecture, migration, or configuration guidance into them.

### Design records

A design record explains why the code is shaped the way it is: the problem, constraints, compatibility boundary, decision and rejected alternatives. Records that span JVM modules go to `documentation/designs/` as `YYYY-MM-DD-<topic>-design.md`; the design of a single TypeScript package or application stays in its `docs/design/`. A major version redesigned as a whole keeps its records together in `documentation/designs/v<major>/`: a `README.md` index, the architecture overview and its topic records, a `roadmap.md` of milestones, and a `decisions.md` ledger of open and settled decisions. That directory is flat.

- The header states the date, the source baseline and the status: proposed, accepted, implementing, implemented, or superseded.
- When the implementation lands, update the status, and move every user-visible contract to its site page. The site may link a record for rationale; it never depends on one for the contract itself.
- When a newer record replaces an older one, mark the older one superseded at the top and link its successor. Delete it once nothing links to it and it adds no rationale the successor lacks.
- Step-by-step implementation plans and review notes are working material (see below). A plan stays only while a test or source comment cites it as the specification it encodes.

### Ledgers

A ledger lists decided but unfinished work, each entry with its reason, completion criterion and location. Delete an entry when the work is done; do not tick it off or archive it. Open findings from a review belong in the matching ledger, not in the review.

### Reproducible evidence

Keep experimental evidence only when it records all of the following:

1. the tested commit or immutable source revision;
2. the exact command and parameters;
3. the relevant runtime, dependency, and environment facts;
4. raw or derived results sufficient to verify the stated conclusion;
5. checksums or another integrity mechanism when artifacts are bundled.

Retained performance evidence lives in `test/benchmarks/results/`, next to the benchmarks that can rerun it. Remove duplicated artifacts, unverifiable experiments, and evidence for conclusions no current document or benchmark report uses.

### Ephemeral work

Specs before acceptance, implementation plans, review notes, temporary QA comparisons, and completed rewrite plans are working material. Keep them in ignored local workspaces or in the pull request while the work is active. Once the durable conclusion is merged into its canonical page or design record, delete the working material.

## When Documentation Is Expired

A document is expired when any of these conditions apply:

- a canonical page already owns the same conclusion;
- its API, version, type name, route, configuration, or architecture has been removed;
- it describes how a completed change was implemented but defines no current contract or rationale;
- its evidence cannot be reproduced or verified;
- a newer source owns the same topic and the older source adds no supported distinction.

Do not delete by date alone. Before deletion, search inbound links and compare the content with current source, tests, and canonical documentation. If the file contains unique current knowledge, migrate that knowledge first and delete the old file in the same pull request.

## Earlier Versions

The site documents the current major version only. The pages of an earlier major stay on its maintenance branch (`v9` for Wow 9.x), which the site builds and serves under `/9.x/`; they are not copied into the current tree.

An old URL that has a direct successor on the site keeps working through the redirect table, `documentation/docs/.vitepress/redirects.mjs`: the build writes a static page for each entry that sends the reader to its successor. The table is bounded and expires: it names the version it is deleted in, and `documentation/test/redirects.test.mjs` fails once the project reaches that version, as it does when an entry's source is a page again or its target is not one. Any other old URL reaches the 404 page, which points to `/9.x/`.

## Diagrams and Assets

- Use fenced Mermaid for every diagram Mermaid supports.
- Do not commit a generated SVG beside Mermaid source.
- Keep PlantUML only for unsupported kinds such as use-case diagrams, under `documentation/diagrams/`.
- Keep a rendered asset only when the documentation runtime cannot render its canonical source.
- Remove unreferenced screenshots and duplicate assets; keep logos, badges, and current product screenshots in `documentation/docs/public/`.

## Cleanup

A cleanup follows the same procedure, one reviewable pull request per batch:

1. inventory tracked documentation, assets, links, and owners;
2. classify each item as keep, migrate, relocate, or delete;
3. migrate unique current knowledge before deleting its source;
4. relocate reproducible evidence to its owning module;
5. remove stale links, navigation entries, duplicate assets, and expired pages;
6. list migrated and deleted paths in the pull request body instead of committing another cleanup report;
7. build and review each batch before starting the next.

## Enforcement

`.github/scripts/docs-layout.mjs` (`pnpm check:docs-layout`) runs on every pull request as the `docs-layout` job of `ci.yml`, part of `ci-gate`. It reads only the tracked files and fails when:

- a file is tracked under `document/` or `docs/superpowers/`;
- Markdown is added outside the locations in [Canonical Locations](#canonical-locations);
- PlantUML is added outside `documentation/diagrams/`;
- an SVG sits beside a `.mmd` or PlantUML source of the same name.

`.github/scripts/docs-samples.mjs` (`pnpm check:docs-samples`) runs in the same job. A fenced block after `<!-- sample: <path>[#<region>] -->` must equal that file of the repository, without its license header, or that `// region` of it; the module that owns the file compiles and tests it. A Kotlin or Java block with a `package` or `import` line must be such a sample, or say `<!-- sample: fragment -->`. A block that shows 9.x code, as a migration does, says `<!-- sample: 9.x -->` instead. In a Kotlin or Java block that is no sample, each `import me.ahoo.wow.…` must name a declaration of a published module's ABI dump (`<module>/api/*.api`). It reads the site's pages, the root READMEs and the Skills.

`.github/scripts/docs-consistency.mjs` (`pnpm check:docs-consistency`) runs there too. Every English page has a Chinese page and the other way round, except a generated page, which may be English only; the two share their headings by level and their code blocks by language. Every `wow.*` key that a YAML or properties block sets is a key of the Spring Boot configuration metadata, compared as Spring binds it.

The reference pages of the configuration keys, the error codes, the annotations, the Manifest format, the published modules and the API levels (`reference/{configuration,error-codes,annotations,manifest,modules,api-levels}.md`) are generated from their sources by `.github/scripts/docs-reference.mjs`; the same job fails when a committed page is not what the sources give. Regenerate them with `pnpm docs:reference` in the change that changes a source.

The Documentation job of the same workflow builds the VitePress site with the locked dependency graph whenever the site or its samples change. The check is structural: whether a document is current, has one owner, or is working material in disguise remains a review question.

## Review Checklist

- Is there exactly one authoritative current page for the topic, updated in the same pull request as the behavior?
- Are English and Chinese public pages structurally aligned?
- Do local READMEs, package designs and Skills link instead of copying broad guidance?
- Does a new or changed design record state its status, and has every user-visible contract reached the site?
- Is every retained experiment reproducible and owned by `test/benchmarks`?
- Are deleted paths free of inbound links?
- Does the pull request avoid tracked plans, review notes, generated build output, and local agent state?
- Do the documentation layout check, the VitePress build and `git diff --check` pass?
