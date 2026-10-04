---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Replaces the dev dependency with @ahoo-wang/wow-generator and the runtime dependency with @ahoo-wang/wow-client, keeping @ahoo-wang/fetcher and @ahoo-wang/fetcher-decorator under their existing names
2. Changes the script to wow-generator generate with the same flags, noting fetcher-generator stays an alias until v10
3. Renames fetcher-generator.config.json to wow-generator.config.json (the old name is still read with a deprecation warning until v10) and commits the .wow-generator.json manifest that replaces .fetcher-generator.json
4. Regenerates the output so generated imports reference @ahoo-wang/wow-client, then runs the project's type check
