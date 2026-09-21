package com.crispyland.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one box on the invariants form that is parsed rather than stored verbatim.
 * <p>
 * Worth its own test because the first version used {@code [,\R]}, and {@code \R} is a line-break
 * <em>sequence</em> that is illegal inside a character class. Java compiles a split pattern on first
 * use, so it passed every compile and threw a 500 on the first rule anybody tried to declare.
 */
class WatchedTermsTest {

    @Test
    @DisplayName("nothing to watch for is not an error — the rule just falls back to the model")
    void nothingToWatchForIsNotAnError() {
        assertThat(ChatController.terms(null)).isEmpty();
        assertThat(ChatController.terms("   ")).isEmpty();
        assertThat(ChatController.terms(" , ,, ")).isEmpty();
    }

    @Test
    @DisplayName("terms split on commas or newlines, whichever the person happened to type")
    void termsSplitOnCommasOrNewlines() {
        assertThat(ChatController.terms("mongo, mongodb ,dynamodb"))
                .containsExactly("mongo", "mongodb", "dynamodb");
        assertThat(ChatController.terms("mongo\nmongodb\r\ndynamodb"))
                .containsExactly("mongo", "mongodb", "dynamodb");
    }

    @Test
    @DisplayName("a term keeps its own punctuation — node.js and c++ are things people write")
    void aTermKeepsItsOwnPunctuation() {
        assertThat(ChatController.terms("node.js, c++")).containsExactly("node.js", "c++");
    }
}
