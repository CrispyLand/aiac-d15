package com.crispyland.agent.llm;

import com.crispyland.agent.memory.Message;
import com.crispyland.agent.usage.TokenUsage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The single class in the whole application that performs HTTP and touches JSON.
 * Speaks the OpenAI-compatible chat-completions dialect that Groq exposes.
 */
public class GroqLlmClient implements LlmClient {

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final String apiKey;

    public GroqLlmClient(RestClient restClient, ObjectMapper mapper, String endpoint, String apiKey) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("GROQ_API_KEY is not set — export it and restart the app.");
        }

        String payload = mapper.writeValueAsString(toJson(request));
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    // Disable the default throw-on-error so we can surface status + body ourselves.
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);
        } catch (RestClientException e) {
            throw new LlmException("Could not reach Groq at " + endpoint + ": " + e.getMessage(), e);
        }

        String body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new LlmException("Groq returned HTTP %s: %s"
                    .formatted(response.getStatusCode().value(), summarize(body)));
        }
        return parse(body);
    }

    private ObjectNode toJson(ChatRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.model());

        ArrayNode messages = root.putArray("messages");
        for (Message message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
        }

        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.maxCompletionTokens() != null) {
            root.put("max_completion_tokens", request.maxCompletionTokens());
        }
        if (request.reasoningEffort() != null && !request.reasoningEffort().isBlank()) {
            root.put("reasoning_effort", request.reasoningEffort());
        }
        if (!request.stopSequences().isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            request.stopSequences().forEach(stop::add);
        }
        if (request.responseSchema() != null && !request.responseSchema().isBlank()) {
            ObjectNode format = root.putObject("response_format");
            format.put("type", "json_schema");
            ObjectNode schema = format.putObject("json_schema");
            schema.put("name", "response");
            schema.put("strict", true);
            try {
                schema.set("schema", mapper.readTree(request.responseSchema()));
            } catch (JacksonException e) {
                throw new LlmException("Response JSON schema is not valid JSON: " + e.getOriginalMessage(), e);
            }
        }
        return root;
    }

    private ChatResponse parse(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException e) {
            throw new LlmException("Groq returned a body that is not JSON: " + summarize(body), e);
        }

        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new LlmException("Groq response contained no choices: " + summarize(body));
        }
        JsonNode message = choice.path("message");
        String content = text(message.path("content"));
        if (content.isBlank()) {
            // gpt-oss models can spend the whole budget on reasoning and return empty content.
            content = text(message.path("reasoning"));
        }

        JsonNode usage = root.path("usage");
        TokenUsage tokenUsage = new TokenUsage(
                number(usage.path("prompt_tokens")),
                number(usage.path("completion_tokens")),
                number(usage.path("total_tokens")));

        return new ChatResponse(content, text(root.path("model")), text(choice.path("finish_reason")), tokenUsage);
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.stringValue() : "";
    }

    private static long number(JsonNode node) {
        return node.isNumber() ? node.longValue() : 0L;
    }

    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String trimmed = body.strip();
        return trimmed.length() > 600 ? trimmed.substring(0, 600) + "…" : trimmed;
    }
}
