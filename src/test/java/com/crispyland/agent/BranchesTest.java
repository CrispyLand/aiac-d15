package com.crispyland.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.crispyland.agent.memory.Branch;
import com.crispyland.agent.memory.ConversationStore;
import com.crispyland.agent.memory.Facts;
import com.crispyland.agent.memory.InMemoryBranchStore;
import com.crispyland.agent.memory.InMemoryConversationStore;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.memory.Summary;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Branching is a storage-topology change rather than a prompt-assembly one, so what has to be
 * proved here is about keys and copies: that a fork continues somewhere else, and that the
 * conversation it came from does not notice.
 */
class BranchesTest {

    private final ConversationStore conversations = new InMemoryConversationStore(0);
    private final Branches branches = new Branches(new InMemoryBranchStore(), conversations);

    @Test
    void aConversationThatNeverForksStoresWhatItAlwaysDid() {
        // The trunk keeps the bare id on purpose: adding this feature must not move anybody's
        // existing dialogue to a new key, and an unused branch costs nothing.
        assertThat(branches.activeKey("c1")).isEqualTo("c1");
        assertThat(branches.all("c1")).containsExactly(Branch.MAIN);
    }

    @Test
    void aForkContinuesOnItsOwnKeyAndLeavesTheParentAloneAfterwards() {
        seed("c1", 4);
        branches.fork("c1", "option b", -1);

        conversations.append(branches.activeKey("c1"), List.of(Message.user("only on the fork")));

        assertThat(branches.activeKey("c1")).isEqualTo("c1/option-b");
        assertThat(contentOf("c1/option-b")).endsWith("only on the fork").hasSize(5);
        assertThat(contentOf("c1")).hasSize(4);
    }

    @Test
    void twoBranchesOffOneCheckpointAreTwoFuturesOfTheSamePastRatherThanEachOthers() {
        seed("c1", 4);

        branches.fork("c1", "postgres", -1);
        conversations.append("c1/postgres", List.of(Message.user("use Postgres")));

        branches.switchTo("c1", Branch.TRUNK);
        branches.fork("c1", "mongo", -1);
        conversations.append("c1/mongo", List.of(Message.user("use Mongo")));

        assertThat(contentOf("c1/postgres")).endsWith("use Postgres").hasSize(5);
        assertThat(contentOf("c1/mongo")).endsWith("use Mongo").hasSize(5);
        // Identical up to the split, which is what makes the two answers comparable at all.
        assertThat(contentOf("c1/postgres").subList(0, 4)).isEqualTo(contentOf("c1/mongo").subList(0, 4));
    }

    @Test
    void aBranchRecordsWhereItReallyStartsAndNotWhereItWasAskedTo() {
        seed("c1", 6);

        assertThat(branches.fork("c1", "whole", -1).forkedAt()).isEqualTo(6);
        branches.switchTo("c1", Branch.TRUNK);
        assertThat(branches.fork("c1", "snapped", 3).forkedAt()).isEqualTo(2);
    }

    @Test
    void forkingAtAMessageLeavesEverythingBelowItBehind() {
        seed("c1", 6);

        branches.fork("c1", "rewind", 2);

        assertThat(contentOf("c1/rewind")).containsExactly("u1", "a1");
        assertThat(contentOf("c1")).hasSize(6);
    }

    @Test
    void aCutIsNeverLeftOnAnUnansweredQuestion() {
        // Cutting between a user message and its reply would hand the branch a question the
        // model can see it already declined to answer.
        seed("c1", 6);

        branches.fork("c1", "rewind", 3);

        assertThat(contentOf("c1/rewind")).containsExactly("u1", "a1");
    }

    @Test
    void aBranchInheritsTheMemoriesDerivedFromTheMessagesItCopied() {
        // Without this a fork of a compressed conversation would start with the folded head
        // missing entirely — the summary is the only remaining record of it.
        seed("c1", 2);
        conversations.compact("c1", new Summary("notes", 1, 4, 120, 60), 0);
        conversations.saveFacts("c1", Facts.EMPTY.updatedWith(
                List.of(new Facts.Fact("database", "Postgres 16")), 12, 90));

        branches.fork("c1", "b", -1);

        assertThat(conversations.summary("c1/b").text()).isEqualTo("notes");
        assertThat(conversations.facts("c1/b").entries())
                .containsExactly(new Facts.Fact("database", "Postgres 16"));
    }

    @Test
    void aRewindDoesNotCarryFactsItLearnedAfterThePointItRewoundTo() {
        // The block has no message addresses, so it cannot be rewound four messages. Starting
        // empty is honest; starting with a decision the branch has not made yet is not.
        seed("c1", 6);
        conversations.compact("c1", new Summary("notes", 1, 2, 120, 60), 0);
        conversations.saveFacts("c1", Facts.EMPTY.updatedWith(
                List.of(new Facts.Fact("database", "Postgres 16")), 12, 90));

        branches.fork("c1", "rewind", 2);

        assertThat(conversations.facts("c1/rewind").isPresent()).isFalse();
        // The summary still comes along: it only ever covers messages older than any cut.
        assertThat(conversations.summary("c1/rewind").text()).isEqualTo("notes");
    }

    @Test
    void switchingBackAndForthChangesNothingButWhichKeyIsAnswered() {
        seed("c1", 2);
        branches.fork("c1", "b", -1);
        conversations.append("c1/b", List.of(Message.user("on b")));

        branches.switchTo("c1", Branch.TRUNK);
        assertThat(branches.activeKey("c1")).isEqualTo("c1");
        branches.switchTo("c1", "b");
        assertThat(contentOf("c1/b")).endsWith("on b");
    }

    @Test
    void aBranchIdThatIsNotOnThisConversationIsIgnoredRatherThanOpened() {
        seed("c1", 2);
        branches.switchTo("c1", "../somebody-elses-conversation");

        assertThat(branches.activeKey("c1")).isEqualTo("c1");
    }

    @Test
    void forkingTheSameNameTwiceDoesNotSilentlyContinueTheFirstOne() {
        seed("c1", 2);
        branches.fork("c1", "option b", -1);
        branches.switchTo("c1", Branch.TRUNK);
        branches.fork("c1", "option b", -1);

        assertThat(branches.all("c1")).extracting(Branch::id)
                .containsExactly("main", "option-b", "option-b-2");
    }

    @Test
    void aForkRemembersWhichBranchItCameOutOf() {
        seed("c1", 4);
        branches.fork("c1", "b", -1);
        branches.fork("c1", "c", 2);

        // The second fork came off the first, not off the trunk — forking is relative to
        // whatever is open, the same way `git branch` is.
        assertThat(branches.all("c1")).extracting(Branch::id, Branch::parentId)
                .containsExactly(tuple("main", null), tuple("b", "main"), tuple("c", "b"));
    }

    @Test
    void resettingClearsEveryBranchBecauseAHalfResetConversationIsWorseThanEither() {
        seed("c1", 2);
        branches.fork("c1", "b", -1);
        conversations.append("c1/b", List.of(Message.user("on b")));

        branches.reset("c1");

        assertThat(conversations.history("c1")).isEmpty();
        assertThat(conversations.history("c1/b")).isEmpty();
        assertThat(branches.all("c1")).containsExactly(Branch.MAIN);
        assertThat(branches.activeKey("c1")).isEqualTo("c1");
    }

    /** {@code count} messages alternating user/assistant, numbered so a cut is easy to read. */
    private void seed(String conversationId, int count) {
        for (int i = 1; i <= count / 2; i++) {
            conversations.append(conversationId,
                    List.of(Message.user("u" + i), Message.assistant("a" + i)));
        }
    }

    private List<String> contentOf(String conversationId) {
        return conversations.history(conversationId).stream().map(Message::content).toList();
    }
}
