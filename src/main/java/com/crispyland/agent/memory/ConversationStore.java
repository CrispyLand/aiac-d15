package com.crispyland.agent.memory;

import com.crispyland.agent.task.TaskState;
import java.util.List;

/**
 * Where the message stack lives. Swappable: the default keeps it in memory, but a Redis or
 * JDBC implementation would let the agent run as several stateless microservice instances.
 */
public interface ConversationStore {

    /** Prior messages, oldest first. Never null; empty for an unknown conversation. */
    List<Message> history(String conversationId);

    /**
     * The notes standing in for everything older than {@link #history}. Never null;
     * {@link Summary#EMPTY} until the dialogue has been compressed at least once.
     */
    Summary summary(String conversationId);

    /**
     * What the dialogue has settled, as key/value. Never null; {@link Facts#EMPTY} until the
     * sticky-facts strategy has run at least once.
     */
    Facts facts(String conversationId);

    /**
     * Replaces the fact block wholesale. Unlike {@link #compact}, this touches no messages:
     * facts are a <em>view</em> of a transcript that is still there in full, which is why
     * leaving the facts tab costs nothing and loses nothing.
     */
    void saveFacts(String conversationId, Facts facts);

    /**
     * Where the task in hand has got to. Never null; {@link TaskState#EMPTY} until one starts.
     * <p>
     * Kept beside the facts rather than in a store of its own because it has working memory's
     * scope <em>and</em> its lifetime: same branch, same task, discarded at the same moment. What
     * makes it a separate value rather than another fact is that it answers "where are we" instead
     * of "what do we know", and only one of those has legal moves.
     */
    TaskState task(String conversationId);

    /** Replaces the task state wholesale. Touches no messages and no facts. */
    void saveTask(String conversationId, TaskState task);

    /** Appends messages to the stack, applying whatever retention policy the store has. */
    void append(String conversationId, List<Message> messages);

    /**
     * Swaps the {@code foldedMessages} oldest messages for {@code summary}, in one step so the
     * conversation is never briefly missing both. The messages are gone afterwards: the point of
     * compression is that the summary is what remains of them, here and in the prompt alike.
     */
    void compact(String conversationId, Summary summary, int foldedMessages);

    /**
     * Copies the first {@code messages} messages of one conversation onto another id, together
     * with the memories derived from them — a checkpoint, and the only primitive branching needs.
     * <p>
     * The summary always comes along: folding only ever removes the <em>oldest</em> messages, so
     * whatever it covers is behind any cut point by construction. The fact block only comes along
     * when the copy is of the whole conversation. A block is keyed, not message-addressable, so
     * there is no way to rewind it by four messages — and a branch that starts before a decision
     * while still holding that decision as an established fact is a worse answer than one that
     * starts empty and learns again.
     *
     * @param messages how many of the oldest messages to keep; {@code < 0} or beyond the end
     *                 means the whole stack
     * @return how many were actually copied, which is what the caller should record — the cut
     *         moves back a message rather than separate a question from its answer
     */
    int copy(String fromConversationId, String toConversationId, int messages);

    void clear(String conversationId);
}
