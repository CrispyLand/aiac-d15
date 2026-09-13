package com.crispyland.agent.usage;

import com.crispyland.agent.memory.Message;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.List;

/**
 * Counts with a real byte-pair encoder (jtokkit, the JVM port of tiktoken) rather than a
 * characters-per-token rule of thumb.
 * <p>
 * Why it matters: {@code chars / 4} is calibrated on English prose. Cyrillic, code, JSON and
 * long identifiers all tokenize far worse than that, so the heuristic under-counts by 10–30%
 * exactly where the prompt is biggest — which is the worst possible place for a budget to be
 * optimistic. BPE gets within a couple of percent.
 * <p>
 * Caveat, stated plainly: {@code o200k_base} is OpenAI's vocabulary. The {@code gpt-oss}
 * models on Groq use the harmony encoding built on that same vocabulary, so the numbers are
 * near-exact there; Qwen ships its own tokenizer, so its counts are an approximation of the
 * right order of magnitude. This is why the agent treats the estimate as a planning figure
 * and reconciles it against the provider's reported usage on every turn.
 */
public class BpeTokenCounter implements TokenCounter {

    /**
     * Role tags and delimiters wrapped around every message by the chat template.
     * OpenAI documents this as ~4 tokens per message for the ChatML family.
     */
    private static final int TOKENS_PER_MESSAGE = 4;

    /** The template also primes the assistant turn once per request. */
    public static final int TOKENS_PER_REPLY = 3;

    private final Encoding encoding;

    public BpeTokenCounter() {
        this(Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE));
    }

    public BpeTokenCounter(Encoding encoding) {
        this.encoding = encoding;
    }

    @Override
    public long count(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        return encoding.countTokens(text);
    }

    @Override
    public long count(Message message) {
        return (message == null) ? 0L : TOKENS_PER_MESSAGE + count(message.content());
    }

    @Override
    public long count(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0L;
        }
        long total = TOKENS_PER_REPLY;
        for (Message message : messages) {
            total += count(message);
        }
        return total;
    }
}
