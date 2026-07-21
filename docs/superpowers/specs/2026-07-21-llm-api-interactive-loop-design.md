# LLM API Interactive Loop Design

## Goal

Complete `section1.LLMapiTest` with a command-window loop that keeps one conversation history across prompts and exits on `exit`, `quit`, or EOF.

## Scope

Only `src/test/java/section1/LLMapiTest.java` is changed. Production classes and configuration remain untouched.

## Behavior

- Read one line at a time from standard input.
- Ignore blank lines.
- Stop on EOF or a case-insensitive `exit`/`quit` command.
- Add each non-empty prompt to one `ConversationManager`.
- Stream `TextDelta` events immediately and append the complete text as an assistant message.
- Report stream errors without adding a fabricated assistant message.

## Verification

An in-file scripted-client test verifies that two prompts observe history sizes one and three, and that streamed text is written to the supplied output. The interactive JUnit entry point uses the configured provider for manual command-window runs.
