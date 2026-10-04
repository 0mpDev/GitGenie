package gitGenie.backend.services.ai;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import gitGenie.backend.dto.ChatMessageResponse;
import gitGenie.backend.dto.CitationDto;
import gitGenie.backend.entity.ChatMessage;
import gitGenie.backend.entity.MessageRole;
import gitGenie.backend.repository.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

/**
 * Streams the LLM answer to the browser as Server-Sent Events.
 * Event names must match client/lib/stream-chat.ts:
 * user_message, token, assistant_message, done.
 */
@Component
@Slf4j
public class ChatStreamHandler {

    private final ChatClient chatClient;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    private final ObjectMapper objectMapper;

    public ChatStreamHandler(
            ChatClient.Builder chatClientBuilder,
            ChatMessageRepository chatMessageRepository,
            CitationMapper citationMapper,
            ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.chatMessageRepository = chatMessageRepository;
        this.citationMapper = citationMapper;
        this.objectMapper = objectMapper;
    }

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse userMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        try {
            send(emitter, "user_message", userMessage);
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        StringBuilder answer = new StringBuilder();

        chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        token -> {
                            answer.append(token);
                            if (closed.get()) {
                                return;
                            }
                            try {
                                send(emitter, "token", token);
                            } catch (IOException e) {
                                closed.set(true);
                            }
                        },
                        error -> {
                            log.error("LLM streaming failed", error);
                            if (!closed.getAndSet(true)) {
                                // Tell the browser what went wrong instead of aborting the stream
                                // (aborting shows up as ERR_INCOMPLETE_CHUNKED_ENCODING).
                                try {
                                    send(emitter, "error", rootMessage(error));
                                } catch (IOException ignored) {
                                    // client already gone
                                }
                                emitter.complete();
                            }
                        },
                        () -> {
                            try {
                                ChatMessage saved = chatMessageRepository.save(ChatMessage.builder()
                                        .sessionId(sessionId)
                                        .role(MessageRole.ASSISTANT)
                                        .content(answer.toString())
                                        .citations(citationMapper.toJson(citations))
                                        .build());
                                if (!closed.get()) {
                                    send(emitter, "assistant_message", new ChatMessageResponse(
                                            saved.getId(),
                                            saved.getRole(),
                                            saved.getContent(),
                                            citations,
                                            saved.getCreatedAt()));
                                    send(emitter, "done", "done");
                                }
                                emitter.complete();
                            } catch (Exception e) {
                                log.error("Failed to finish chat stream", e);
                                emitter.completeWithError(e);
                            }
                        });

        return emitter;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() != null ? root.getMessage() : error.getMessage();
        return message != null ? message : "AI request failed";
    }

    private void send(SseEmitter emitter, String event, Object payload) throws IOException {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
        synchronized (emitter) {
            emitter.send(SseEmitter.event().name(event).data(json));
        }
    }
}