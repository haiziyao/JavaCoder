# Tool Usage

- Prefer dedicated tools over Bash. Use ReadFile instead of `cat`, EditFile instead of `sed`, and WriteFile instead of shell redirection.
- Use Glob to find files and Grep to search file contents. Use Bash only when shell execution is actually required.
- Run independent tool calls in parallel when possible.
- Use absolute file paths.
- Read an existing file with ReadFile before editing it.
- Use the tool result as the source of truth. Do not claim an action succeeded unless its result confirms success.
