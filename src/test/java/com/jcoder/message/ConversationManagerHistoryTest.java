package com.jcoder.message;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationManagerHistoryTest {

    @Test
    void replaceHistoryReplacesExistingMessagesInOrder() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("old");
        Message first = new Message("user", "first");
        Message second = new Message("assistant", "second");

        conversation.replaceHistory(List.of(first, second));

        assertEquals(2, conversation.size());
        assertSame(first, conversation.getHistoryCopy().get(0));
        assertSame(second, conversation.getHistoryCopy().get(1));
    }

    @Test
    void replaceHistoryAcceptsCopyOfItsCurrentHistory() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("one");
        conversation.addAssistantMsg("two");
        List<Message> snapshot = conversation.getHistoryCopy();

        conversation.replaceHistory(snapshot);

        assertEquals(2, conversation.size());
        assertEquals(List.of("one", "two"), conversation.getHistoryCopy().stream()
                .map(Message::getContent)
                .toList());
    }

    @Test
    void replaceHistoryAlsoHandlesInternalMutableListWithoutLosingData() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("one");
        conversation.addAssistantMsg("two");

        conversation.replaceHistory(conversation.getHistoryMut());

        assertEquals(2, conversation.size());
        assertEquals("one", conversation.getHistoryCopy().getFirst().getContent());
        assertEquals("two", conversation.getHistoryCopy().getLast().getContent());
    }

    @Test
    void replaceHistoryRejectsNullListWithoutChangingHistory() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("keep");

        assertThrows(NullPointerException.class,
                () -> conversation.replaceHistory(null));

        assertEquals(1, conversation.size());
        assertEquals("keep", conversation.getHistoryCopy().getFirst().getContent());
    }

    @Test
    void replaceHistoryRejectsNullElementWithoutChangingHistory() {
        ConversationManager conversation = new ConversationManager();
        Message original = new Message("user", "keep");
        conversation.addMessage(original);
        List<Message> invalid = Arrays.asList(
                new Message("assistant", "replacement"), null
        );

        assertThrows(NullPointerException.class,
                () -> conversation.replaceHistory(invalid));

        assertEquals(1, conversation.size());
        assertSame(original, conversation.getHistoryCopy().getFirst());
    }
}
