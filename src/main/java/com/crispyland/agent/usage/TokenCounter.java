package com.crispyland.agent.usage;

import com.crispyland.agent.memory.Message;
import java.util.List;

/**
 * Prices text in tokens <em>before</em> it is sent.
 * <p>
 * The provider reports {@code usage} only in the response, which is too late to make a
 * decision with: by the time you know the prompt was 140k tokens, you have already been
 * billed for the rejection. Everything the agent wants to decide up front — will this fit,
 * how much of the window is history, should a turn be dropped — needs a local estimate.
 * <p>
 * An estimate is all it can ever be: the exact vocabulary is the provider's, not ours.
 * The contract is therefore "close enough to budget against", and every turn reconciles
 * the estimate with the authoritative {@code prompt_tokens} that comes back.
 */
public interface TokenCounter {

    /** Tokens in a bare string, with no chat framing. */
    long count(String text);

    /** Tokens for one message: its text plus the role tags the chat template wraps it in. */
    long count(Message message);

    /**
     * Tokens for a full message array as the provider will see it — every framed message
     * plus the once-per-request priming of the assistant's turn.
     */
    long count(List<Message> messages);
}
