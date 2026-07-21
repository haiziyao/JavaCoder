package section1;

import com.hzy.conversation.ConversationManager;
import com.hzy.config.ConfigManager;
import com.hzy.config.ProviderConfig;
import com.hzy.llm.LLMClient;
import com.hzy.llm.LLMRequestBody;
import com.hzy.llm.OpenAIClient;
import com.hzy.llm.StreamEvent;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMapiTest {

    @Test
    void interactiveLoopKeepsConversationHistory() throws Exception {
        var input = new StringReader("hello\nworld\nexit\n");
        var output = new StringWriter();
        var conversation = new ConversationManager();
        var historySizes = new ArrayList<Integer>();

        LLMClient client = new LLMClient() {
            private int responseNumber;

            @Override
            public void doStream(LLMRequestBody requestBody, BlockingQueue<StreamEvent> queue) {
                throw new UnsupportedOperationException("the scripted stream is supplied by stream()");
            }

            @Override
            public BlockingQueue<StreamEvent> stream(LLMRequestBody requestBody) {
                historySizes.add(requestBody.conversationManager().getHistoryCopy().size());
                var queue = new LinkedBlockingQueue<StreamEvent>();
                queue.add(new StreamEvent.TextDelta("reply" + (++responseNumber)));
                queue.add(new StreamEvent.StreamEnd("end_turn", 0, 1));
                return queue;
            }
        };

        runInteractive(input, output, client, conversation, "system", 1000);

        assertEquals(List.of(1, 3), historySizes);
        assertTrue(output.toString().contains("reply1"));
        assertTrue(output.toString().contains("reply2"));
        assertEquals(List.of("hello", "reply1", "world", "reply2"),
                conversation.getHistoryCopy().stream().map(message -> message.getContent()).toList());
    }

    /**
     * Manual command-window entry point. Run this single JUnit method when a
     * configured provider is available, or run {@link #main(String[])}.
     */
    @Test
    void interactiveApiTest() throws Exception {
        runConfiguredInteractive();
    }

    public static void main(String[] args) throws Exception {
        runConfiguredInteractive();
    }

    private static void runConfiguredInteractive() throws Exception {
        if (ConfigManager.appConfig == null || ConfigManager.appConfig.providers() == null
                || ConfigManager.appConfig.providers().isEmpty()) {
            throw new IllegalStateException("application.json must contain at least one provider");
        }

        ProviderConfig provider = ConfigManager.appConfig.providers().get(0);
        LLMClient client = createConfiguredClient(provider);
        int maxOutputTokens = provider.maxOutputTokens() == null ? 4096 : provider.maxOutputTokens();

        runInteractive(
                new InputStreamReader(System.in, StandardCharsets.UTF_8),
                System.out,
                client,
                new ConversationManager(),
                "",
                maxOutputTokens
        );
    }

    private static LLMClient createConfiguredClient(ProviderConfig provider) {
        var httpClient = HttpClient.newHttpClient();
        if (provider.protocol() == null || provider.protocol().isBlank()) {
            // The sample application.json predates the protocol field and is
            // OpenAI-shaped, so keep this test usable without changing config.
            return new OpenAIClient(httpClient, provider, "");
        }
        return LLMClient.create(httpClient, provider, "");
    }

    static void runInteractive(
            Reader input,
            Appendable output,
            LLMClient client,
            ConversationManager conversation,
            String systemPrompt,
            int maxOutputTokens
    ) throws IOException {
        var reader = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);

        while (true) {
            output.append("> ");
            flushIfPossible(output);

            String line = reader.readLine();
            if (line == null) {
                return;
            }

            String prompt = line.trim();
            if (prompt.equalsIgnoreCase("exit") || prompt.equalsIgnoreCase("quit")) {
                output.append("Bye!\n");
                flushIfPossible(output);
                return;
            }
            if (prompt.isEmpty()) {
                continue;
            }

            conversation.addUserMessage(prompt);
            output.append("assistant: ");
            flushIfPossible(output);

            var requestBody = new LLMRequestBody(
                    systemPrompt,
                    conversation,
                    List.of(),
                    maxOutputTokens,
                    false
            );
            var answer = new StringBuilder();
            boolean completed = consumeStream(client.stream(requestBody), output, answer);
            if (completed) {
                conversation.addAssistantMessage(answer.toString());
            }
            output.append('\n');
            flushIfPossible(output);
        }
    }

    private static boolean consumeStream(
            BlockingQueue<StreamEvent> events,
            Appendable output,
            StringBuilder answer
    ) throws IOException {
        while (true) {
            final StreamEvent event;
            try {
                event = events.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                output.append("\nError: stream interrupted");
                return false;
            }

            if (event instanceof StreamEvent.TextDelta textDelta) {
                answer.append(textDelta.text());
                output.append(textDelta.text());
                flushIfPossible(output);
            } else if (event instanceof StreamEvent.Error error) {
                output.append("\nError: ").append(String.valueOf(error.message()));
                return false;
            } else if (event instanceof StreamEvent.StreamEnd) {
                return true;
            }
        }
    }

    private static void flushIfPossible(Appendable output) throws IOException {
        if (output instanceof java.io.Flushable flushable) {
            flushable.flush();
        }
    }
}
