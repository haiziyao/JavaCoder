package com.jcoder.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMetadataTest {

    @Test
    void acceptsValidMetadataAndStripsOuterWhitespace() {
        SkillMetadata metadata = new SkillMetadata(
                "  java-review  ",
                "  Review Java source code.  "
        );

        assertEquals("java-review", metadata.name());
        assertEquals("Review Java source code.", metadata.description());
    }

    @Test
    void acceptsBoundaryLengthNameAndDescription() {
        SkillMetadata metadata = new SkillMetadata(
                "a" + "1".repeat(63),
                "d".repeat(500)
        );

        assertEquals(64, metadata.name().length());
        assertEquals(500, metadata.description().length());
    }

    @Test
    void rejectsMissingNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillMetadata(null, "description"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillMetadata("   ", "description"));
    }

    @Test
    void rejectsInvalidNames() {
        for (String name : new String[]{
                "Java-review",
                "-java-review",
                "java review",
                "java/review",
                "java.review",
                "a".repeat(65)
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SkillMetadata(name, "description"),
                    name
            );
            assertTrue(error.getMessage().contains("invalid skill name"));
        }
    }

    @Test
    void rejectsMissingOrOversizedDescriptions() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillMetadata("java-review", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillMetadata("java-review", " \n\t "));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new SkillMetadata("java-review", "d".repeat(501))
        );
        assertTrue(error.getMessage().contains("too long"));
    }
}
