---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Configures one Fetcher for the service, passed to every generated client, instead of per-call headers
2. Applies @ahoo-wang/fetcher-cosec's CoSecConfigurer with the appId (CoSec-App-Id) and a token refresher on a separate Fetcher without the CoSec interceptors
3. Puts no tenant or owner in paths or bodies: the service reads the caller from the token, and Wow-Scope or Wow-Space only for a caller allowed to act elsewhere
4. Names the failure codes to handle: 401 wow.auth.unauthenticated and 403 wow.auth.forbidden
