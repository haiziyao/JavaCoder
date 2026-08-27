package com.jcoder.command;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandInvocationTest {

    @Test
    void nonCommandAndEmptySlashReturnEmpty() {
        assertTrue(CommandInvocation.parse(null).isEmpty());
        assertTrue(CommandInvocation.parse("").isEmpty());
        assertTrue(CommandInvocation.parse("plain text").isEmpty());
        assertTrue(CommandInvocation.parse("/").isEmpty());
        assertTrue(CommandInvocation.parse("  /   ").isEmpty());
    }

    @Test
    void normalizesCommandNameToLowerCase() {
        CommandInvocation invocation = CommandInvocation
                .parse("  /HeLP  ")
                .orElseThrow();

        assertEquals("help", invocation.name());
        assertEquals("", invocation.arguments());
    }

    @Test
    void trimsArgumentsButPreservesTheirInternalText() {
        Optional<CommandInvocation> parsed = CommandInvocation.parse(
                "  /CoMpAcT   alpha  beta\t--force   "
        );

        CommandInvocation invocation = parsed.orElseThrow();
        assertEquals("compact", invocation.name());
        assertEquals("alpha  beta\t--force", invocation.arguments());
    }
}
