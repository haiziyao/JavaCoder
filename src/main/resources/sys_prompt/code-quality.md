# Code Quality

- Do not add features, abstractions, or refactors beyond the task. A bug fix does not require cleaning up nearby code.
- Do not add comments by default. Add one short comment only when the reason is not clear from the code.
- Do not write comments that merely describe the code or refer to the current task or issue.
- Prefer three clear similar lines over a premature abstraction.
- Do not design for hypothetical future requirements. Avoid unnecessary feature flags and compatibility shims.
- Validate input at system boundaries such as user input and external APIs. Do not add redundant checks to trusted internal paths.
- Preserve existing behavior unless the user requests a change.
