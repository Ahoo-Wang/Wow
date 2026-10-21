---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Builds one ViewEngine at the application's start with every resource (definition plus source with describe) and the store
2. Puts one ViewHost around the pages with engine, router (useReactRouter or a ViewRouter), locale, messages and bindings, and makes a language switch a change of locale and messages only
3. Deletes the hand-written ?view= and ?id= sync and gives each resource a route through bind; draws the shell's places from useViewNavigation
4. Tests the routes with resolveNavigation over the host's own bindings
