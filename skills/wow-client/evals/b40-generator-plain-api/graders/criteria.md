---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Installs @ahoo-wang/wow-generator as a dev dependency and runs wow-generator generate with -i pointing at the spec and -o src/generated/petstore
2. Generated TypeScript types are properly defined in types.ts
3. PetsApiClient class is generated for the pets endpoints (tag `pets` resolves to name `Pets`)
4. Generated code compiles without errors and follows the standard output directory structure
