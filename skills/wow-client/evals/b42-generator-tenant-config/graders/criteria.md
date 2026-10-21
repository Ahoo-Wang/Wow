---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Explains that a Long, BigInteger or BigDecimal field (Manifest format int64, bigint, decimal) is typed string in the regenerated clients and read and written exactly through the aggregate's textNumbers, so regenerating from the current Manifest is the fix, not parsing with Number
2. Sends amount as a string ('19.90') through the generated InvoiceCommandClient, which writes it as an unquoted JSON number (bodyNumbers), and never converts it with Number or parseFloat
3. Displays and computes with a decimal library or string formatting, and keeps comparisons and sorts on the server
4. Notes that hand-written clients must pass bodyNumbers themselves
