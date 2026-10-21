---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Replaces the packages with the V10 @ahoo-wang/wow-generator (dev) and @ahoo-wang/wow-client with its peers @ahoo-wang/fetcher and @ahoo-wang/fetcher-eventstream, all on the service's V10 minor
2. Generates from the service's V10 Manifest (GET /wow/manifest, or its committed manifest/baseline.json), not from /v3/api-docs: V10 removed the OpenAPI-to-Wow path, the fetcher-generator alias and its configuration file
3. Regenerates into the output directory, commits .wow-generator.json, and adds a CI step with --strict that fails on a diff
4. Fixes the application by what tsc reports against the new clients (generated <Aggregate>CommandClient, SnapshotQueryClient, StateClient), then greps for what types cannot show: 9.x error code strings, hard-coded 9.x paths with /owner/{ownerId}/, zone-less relative-time filters
