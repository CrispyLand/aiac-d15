package com.crispyland.agent;

import com.crispyland.agent.judge.Judge;
import com.crispyland.agent.judge.Verdict;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.ConversationStore;
import com.crispyland.agent.memory.Facts;
import com.crispyland.agent.memory.HistoryCompressor;
import com.crispyland.agent.memory.LongTermKind;
import com.crispyland.agent.memory.LongTermMemory;
import com.crispyland.agent.memory.LongTermStore;
import com.crispyland.agent.memory.MemoryExtractor;
import com.crispyland.agent.memory.MemoryRouter;
import com.crispyland.agent.memory.MemoryScope;
import com.crispyland.agent.profile.Persona;
import com.crispyland.agent.memory.MemoryState;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.memory.MessageStats;
import com.crispyland.agent.memory.Summary;
import com.crispyland.agent.policy.InputPolicy;
import com.crispyland.agent.policy.OutputPolicy;
import com.crispyland.agent.usage.ContextBudget;
import com.crispyland.agent.usage.TemplateOverhead;
import com.crispyland.agent.usage.TokenUsage;
import com.crispyland.agent.usage.TokenUsageTracker;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The box. One pipeline:
 * <pre>
 *   input policy -> merge config with defaults -> load history -> compress the backlog
 *       -> price the context -> LlmClient -> record token usage -> output policy -> judge
 *       -> append turn to history -> AgentResult
 * </pre>
 * The conversation is the agent's own state, not the web layer's: callers pass a
 * conversation id and the agent decides what history to send, how to window it, and when
 * to commit a turn. Knows nothing about HTTP servlets, Thymeleaf, or forms.
 */
@Service
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final LlmClient llmClient;
    private final InputPolicy inputPolicy;
    private final OutputPolicy outputPolicy;
    private final Judge judge;
    private final TokenUsageTracker usageTracker;
    private final ConversationStore conversations;
    private final LongTermStore longTerm;
    private final ContextPlanner contextPlanner;
    private final HistoryCompressor compressor;
    private final MemoryExtractor extractor;
    private final TemplateOverhead templateOverhead;
    private final AgentConfig defaults;
    private final int maxFacts;

    /**
     * Not injected: the router is a pure function of the table in {@code MemoryTag}, holds nothing,
     * and has nothing to configure. Making it a bean would suggest it could be swapped for one
     * that files things elsewhere, which is the exact property this design exists to deny.
     */
    private final MemoryRouter router = new MemoryRouter();

    public Agent(LlmClient llmClient,
                 InputPolicy inputPolicy,
                 OutputPolicy outputPolicy,
                 Judge judge,
                 TokenUsageTracker usageTracker,
                 ConversationStore conversations,
                 LongTermStore longTerm,
                 ContextPlanner contextPlanner,
                 HistoryCompressor compressor,
                 MemoryExtractor extractor,
                 TemplateOverhead templateOverhead,
                 AgentProperties properties) {
        this.llmClient = llmClient;
        this.inputPolicy = inputPolicy;
        this.outputPolicy = outputPolicy;
        this.judge = judge;
        this.usageTracker = usageTracker;
        this.conversations = conversations;
        this.longTerm = longTerm;
        this.contextPlanner = contextPlanner;
        this.compressor = compressor;
        this.extractor = extractor;
        this.templateOverhead = templateOverhead;
        this.defaults = properties.defaultConfig();
        this.maxFacts = properties.facts().maxFacts();
    }

    /**
     * Handles one turn of a continuing dialogue. Prior messages for {@code scope} are replayed to
     * the model, and the new user/assistant pair is appended on success. Any config value left
     * null falls back to the application.yml defaults.
     *
     * @throws AgentException if a policy rejects the exchange, the context window cannot
     *         hold the call, or the provider fails
     */
    public AgentResult handle(MemoryScope scope, Persona persona, String userInput, AgentConfig config) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        Persona who = (persona == null) ? Persona.NONE : persona;
        String id = where.conversation();
        AgentConfig effective = effectiveConfig(who, config);

        String prompt = inputPolicy.apply(userInput);

        // Before pricing, not after: the whole point is that this turn is the one that gets
        // cheaper, and the budget the caller is shown has to be the budget that was spent.
        int compacted = compressIfDue(id);
        Facts working = updateMemory(where, prompt);
        MemoryState memory = new MemoryState(longTerm.recall(where.visitor()),
                conversations.summary(id), working, conversations.history(id));
        List<Message> history = memory.recent();

        // Priced before a byte leaves the process: an oversized prompt is billed as a
        // rejection, so the cheapest place to find out it will not fit is here.
        ContextPlanner.ContextPlan plan = contextPlanner.plan(effective, who, memory, prompt);
        ContextBudget budget = plan.budget();
        if (budget.trimmed()) {
            log.info("Context trim: dropped {} oldest message(s) to fit {} of {} tokens",
                    budget.droppedMessages(), budget.projectedTokens(), budget.contextWindow());
        }

        ChatRequest request = buildRequest(plan.messages(), effective);

        long startedAt = System.nanoTime();
        ChatResponse response = llmClient.complete(request);
        long latencyMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        TokenUsage usage = response.usage();
        TokenUsage cumulative = usageTracker.record(usage);

        // The response is the only place the truth is ever stated. Feeding it back is what
        // keeps the next turn's pre-flight estimate honest.
        templateOverhead.observe(effective.model(), budget.countedTokens(), usage.promptTokens());

        log.info("Turn: prompt est {} / actual {} ({} history msgs, summary {} tok replacing {}), "
                        + "completion {}, window {}% used",
                budget.promptTokens(), usage.promptTokens(), history.size(),
                budget.summaryTokens(), budget.replacedTokens(),
                usage.completionTokens(), budget.usedPercent());

        String answer = outputPolicy.apply(response.content());
        Verdict verdict = judge.judge(prompt, answer, effective);

        // Committed only after the reply survives the output policy, so a failed turn
        // never poisons the history. Each message keeps the share of the turn it earned.
        conversations.append(id, List.of(
                Message.user(prompt).withStats(
                        MessageStats.forPrompt(usage.promptTokens(), effective.model())),
                Message.assistant(answer).withStats(
                        MessageStats.forCompletion(usage.completionTokens(), usage.totalTokens(),
                                latencyMillis, effective.model(), response.finishReason()))));

        return new AgentResult(answer, effective, usage, cumulative, budget,
                response.finishReason(), latencyMillis, verdict, conversations.history(id), compacted);
    }

    /** Read-only view of the message stack, for rendering an existing dialogue. */
    public List<Message> transcript(String conversationId) {
        return conversations.history(conversationId);
    }

    /** The notes standing in for whatever has already been compressed out of the transcript. */
    public Summary summary(String conversationId) {
        return conversations.summary(conversationId);
    }

    /** Working memory: what the task in hand has settled, as key/value. */
    public Facts facts(String conversationId) {
        return conversations.facts(conversationId);
    }

    /** Long-term memory: what is known about the visitor, whichever conversation they are in. */
    public LongTermMemory recall(String visitorId) {
        return longTerm.recall(visitorId);
    }

    /**
     * What the dialogue already costs, before anything new is typed. Lets the window be
     * watched as it fills rather than only at the turn that breaks it.
     */
    public ContextBudget budget(MemoryScope scope, Persona persona, AgentConfig config) {
        Persona who = (persona == null) ? Persona.NONE : persona;
        return contextPlanner.budget(effectiveConfig(who, config), who, memory(scope));
    }

    /**
     * What a request would actually be made with: three tiers, and the order is the whole of what
     * "enforced in code" means here.
     * <p>
     * What the caller set explicitly is someone overriding this one request on purpose and wins;
     * what the profile asks for is a standing preference and fills the gaps; the yml is what to do
     * when nobody said. Resolving against the defaults first would fill every gap before the
     * profile was consulted, leaving it nothing to apply and making it prose-only again.
     * <p>
     * Public because the page has to prefill its settings boxes with these numbers rather than the
     * raw defaults. Showing 1,024 in a box while sending 600 would be a lie, and worse, the box is
     * posted back — so the form would hand back the default as an explicit override and quietly
     * beat the profile on every single turn. That is not a display bug; it silently disables the
     * enforced half of the feature.
     */
    public AgentConfig effectiveConfig(Persona persona, AgentConfig config) {
        Persona who = (persona == null) ? Persona.NONE : persona;
        return who.applyTo((config == null) ? AgentConfig.builder().build() : config)
                .withFallback(defaults);
    }

    /** Every layer at once, gathered here so what is priced is what would have been sent. */
    private MemoryState memory(MemoryScope scope) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        String id = where.conversation();
        return new MemoryState(longTerm.recall(where.visitor()), conversations.summary(id),
                conversations.facts(id), conversations.history(id));
    }

    /**
     * Extracts what the message about to be sent establishes, routes it, and commits both halves
     * before the turn is priced — so this turn already answers against what it just taught the
     * agent, rather than the next one.
     * <p>
     * Extraction and routing are two steps on purpose. The model labels; {@link MemoryRouter}
     * decides the lifetime from a fixed table. Nothing between here and the stores consults the
     * model about where a line belongs.
     * <p>
     * Same bargain as compression: an extraction failure costs one turn of forgetfulness, never
     * the turn itself. A user asking a question does not deserve an error because a background
     * bookkeeping call timed out.
     *
     * @return the working block as it now stands, which is the one the prompt will carry
     */
    private Facts updateMemory(MemoryScope where, String prompt) {
        String id = where.conversation();
        Facts current = conversations.facts(id);

        MemoryExtractor.Extraction extraction;
        try {
            extraction = extractor.extract(prompt, keysInUse(current, longTerm.recall(where.visitor())));
        } catch (AgentException e) {
            log.warn("Memory extraction failed ({}) — continuing with the layers as they stand.",
                    e.getMessage());
            return current;
        }
        if (extraction.isEmpty()) {
            return current;
        }

        MemoryRouter.Routed routed = router.route(extraction.lines());

        // Working memory carries the running bill for the whole extraction even when the routed
        // lines all went elsewhere, because it is the only layer counting, and a call nobody is
        // charged for is a call nobody notices the cost of.
        Facts updated = current.updatedWith(routed.working(), maxFacts, extraction.costTokens());
        if (updated.entries().equals(current.entries())) {
            updated = current.billed(extraction.costTokens());
        } else {
            log.info("Working memory rev {} — {} fact(s) held for the task in hand, {} of them agreed",
                    updated.revision(), updated.size(), updated.settled().size());
        }
        conversations.saveFacts(id, updated);

        if (!routed.longTerm().isEmpty()) {
            LongTermMemory kept = longTerm.remember(where.visitor(), routed.longTerm());
            log.info("Long-term memory rev {} — {} entry(ies) now known about this visitor",
                    kept.revision(), kept.size());
        }
        return updated;
    }

    /**
     * Every key the layers are already using, across both of them.
     * <p>
     * Deduplicated and flattened, because the extractor is being told what a subject is
     * <em>called</em>, not where it lives. A key held in long-term that turns up again in a task
     * message should land on the same key and be re-routed by its new tag; qualifying the list by
     * layer would instead teach the model to keep it where it was.
     */
    private static List<String> keysInUse(Facts working, LongTermMemory known) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        working.entries().forEach(fact -> keys.add(fact.key()));
        known.entries().forEach(entry -> keys.add(entry.key()));
        return List.copyOf(keys);
    }

    /**
     * Closes the task in hand: everything working memory had marked as agreed graduates to
     * long-term, and the rest of the block is thrown away.
     * <p>
     * This is the one moment a decision crosses a lifetime boundary, and it is deliberately a
     * thing the user does rather than a thing the model infers. Long-term memory is shared by
     * every branch of every conversation; promoting a decision while the task that produced it is
     * still open leaks it into the forks that exist precisely to disagree with it, and a model
     * guessing at "is this task finished?" would do exactly that on the turn it guessed wrong.
     *
     * @return the long-term block after the promotion, for rendering the result of the button
     */
    public LongTermMemory finishTask(MemoryScope scope) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        String id = where.conversation();
        List<Facts.Fact> settled = conversations.facts(id).settled();

        conversations.saveFacts(id, Facts.EMPTY);
        if (settled.isEmpty()) {
            log.info("Task closed with nothing agreed — working memory cleared, long-term untouched.");
            return longTerm.recall(where.visitor());
        }
        LongTermMemory kept = longTerm.remember(where.visitor(),
                LongTermMemory.promoted(LongTermKind.DECISION, settled));
        log.info("Task closed — promoted {} agreed fact(s) to long-term memory (rev {}) and cleared "
                + "the rest of working memory.", settled.size(), kept.revision());
        return kept;
    }

    /** Starts a fresh task: working memory only, leaving the dialogue and the visitor alone. */
    public void newTask(MemoryScope scope) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        conversations.saveFacts(where.conversation(), Facts.EMPTY);
        log.info("New task — working memory cleared; short-term and long-term are untouched.");
    }

    /**
     * Folds the backlog into the summary when there is enough of it to be worth a call.
     * <p>
     * A summarization failure must not take the user's turn down with it. The worst case of
     * skipping it is a prompt that stays large for one more turn, which the overflow policy is
     * already there to catch; the worst case of propagating it is an agent that stops answering
     * because a background housekeeping call timed out.
     *
     * @return how many messages were folded, 0 if none
     */
    private int compressIfDue(String id) {
        try {
            return compressor.compact(conversations.summary(id), conversations.history(id))
                    .map(compaction -> {
                        conversations.compact(id, compaction.summary(), compaction.foldedMessages());
                        log.info("Compressed {} message(s) worth {} tokens into summary rev {} — "
                                        + "every later turn now replays notes instead",
                                compaction.foldedMessages(), compaction.foldedTokens(),
                                compaction.summary().revision());
                        return compaction.foldedMessages();
                    })
                    .orElse(0);
        } catch (AgentException e) {
            log.warn("History compression failed ({}) — continuing with the full transcript.",
                    e.getMessage());
            return 0;
        }
    }

    /** Starts a new dialogue, discarding the message stack. */
    public void reset(String conversationId) {
        conversations.clear(conversationId);
    }

    private ChatRequest buildRequest(List<Message> messages, AgentConfig config) {
        return new ChatRequest(
                config.model(),
                messages,
                config.temperature(),
                config.maxCompletionTokens(),
                config.hasReasoningEffort() ? config.reasoningEffort() : null,
                config.stopSequencesOrEmpty(),
                config.hasResponseSchema() ? config.responseSchema() : null);
    }
}
