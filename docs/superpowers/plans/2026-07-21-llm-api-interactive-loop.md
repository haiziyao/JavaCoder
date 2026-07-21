# LLM API Interactive Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a tested, continuous command-line LLM loop to `LLMapiTest` without changing production code.

**Architecture:** Keep the interactive entry point in the JUnit test class and delegate to a package-private static loop that accepts reader, output, client, and conversation dependencies. A scripted fake client tests the loop without network access; the real entry point constructs the existing configured client.

**Tech Stack:** Java 21, JUnit 5, existing `LLMClient`, `ConversationManager`, and `StreamEvent` APIs.

---

### Task 1: Lock down continuous conversation behavior

**Files:**
- Modify: `src/test/java/section1/LLMapiTest.java`

- [ ] Add a JUnit test with scripted input `hello`, `world`, `exit` and a fake streaming client.
- [ ] Assert the fake client sees history sizes 1 and 3, streamed replies are printed, and the final history contains both assistant messages.
- [ ] Run the focused test and observe failure because the loop helper is not implemented.

### Task 2: Implement the minimal loop and manual entry point

**Files:**
- Modify: `src/test/java/section1/LLMapiTest.java`

- [ ] Implement the loop with EOF/blank/exit handling, `TextDelta`, `StreamEnd`, and `Error` handling.
- [ ] Add an interactive `@Test` method and a `main` convenience entry point that create the existing configured client.
- [ ] Run the focused test and then the project test command.

### Task 3: Review scope and report limitations

**Files:**
- No additional source changes.

- [ ] Confirm only `LLMapiTest.java` contains implementation changes.
- [ ] Record the JDK 17 versus Java 21 build limitation and the existing empty request-body/config limitations in the final report.
