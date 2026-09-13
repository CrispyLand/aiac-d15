package com.crispyland.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.agent.memory.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class BpeTokenCounterTest {

    private final BpeTokenCounter counter = new BpeTokenCounter();

    @Test
    void emptyTextCostsNothing() {
        assertThat(counter.count("")).isZero();
        assertThat(counter.count((String) null)).isZero();
        assertThat(counter.count(List.of())).isZero();
    }

    @Test
    void aMessageCostsItsTextPlusTheRoleFraming() {
        String text = "What is the capital of France?";

        assertThat(counter.count(Message.user(text))).isEqualTo(counter.count(text) + 4);
    }

    @Test
    void aRequestCostsEveryMessagePlusOneReplyPriming() {
        List<Message> messages = List.of(Message.system("be brief"), Message.user("hello"));

        long expected = counter.count(messages.get(0)) + counter.count(messages.get(1)) + 3;
        assertThat(counter.count(messages)).isEqualTo(expected);
    }

    @Test
    void theCharsOverFourHeuristicUnderCountsCyrillic() {
        // The reason this is a real tokenizer and not a division. English lands near the rule
        // of thumb; Cyrillic costs far more per character, and a budget built on chars/4 would
        // wave through a prompt that does not fit.
        String english = "The quick brown fox jumps over the lazy dog and keeps on running.";
        String russian = "Быстрая коричневая лиса прыгает через ленивую собаку и бежит дальше.";

        double englishRatio = (double) english.length() / counter.count(english);
        double russianRatio = (double) russian.length() / counter.count(russian);

        System.out.printf("  chars per token — english %.2f, russian %.2f%n", englishRatio, russianRatio);
        assertThat(englishRatio).isGreaterThan(3.0);
        assertThat(russianRatio).isLessThan(englishRatio);
    }

    @Test
    void longerTextCostsMore() {
        assertThat(counter.count("hello world hello world"))
                .isGreaterThan(counter.count("hello world"));
    }
}
