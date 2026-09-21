package com.crispyland.agent;

import com.crispyland.agent.invariant.Invariant;
import com.crispyland.agent.invariant.InvariantGuard;
import com.crispyland.agent.invariant.InvariantStore;
import com.crispyland.agent.invariant.Invariants;
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
import com.crispyland.agent.task.PausedTurn;
import com.crispyland.agent.task.TaskStage;
import com.crispyland.agent.task.TaskState;
import com.crispyland.agent.usage.ContextBudget;
import com.crispyland.agent.usage.TemplateOverhead;
import com.crispyland.agent.usage.TokenUsage;
import com.crispyland.agent.usage.TokenUsageTracker;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private final InvariantStore invariants;
    private final InvariantGuard guard;
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
                 InvariantStore invariants,
                 InvariantGuard guard,
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
        this.invariants = invariants;
        this.guard = guard;
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
        return handle(scope, persona, userInput, config, PausedTurn.REFUSE);
    }

    /**
     * The same turn, with a say in what a paused task does to it.
     *
     * @param whenPaused {@link PausedTurn#ASIDE} lets a message unrelated to the task through
     *        while leaving the task frozen. It is a separate argument rather than something read
     *        off the message because only the person sending it knows whether it is an aside.
     */
    public AgentResult handle(MemoryScope scope, Persona persona, String userInput,
                              AgentConfig config, PausedTurn whenPaused) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        Persona who = (persona == null) ? Persona.NONE : persona;
        String id = where.conversation();
        AgentConfig effective = effectiveConfig(who, config);

        String prompt = inputPolicy.apply(userInput);

        // Ahead of every call, including the two this turn would make before the answer: a paused
        // task refuses the turn outright rather than answering and silently declining to move.
        // Deciding per message would cost the very calls the pause exists to stop. An aside is
        // let through here and frozen further in, by the refusal in TaskState.apply.
        TaskState paused = conversations.task(id);
        boolean aside = paused.isPresent() && paused.paused();
        if (aside && whenPaused != PausedTurn.ASIDE) {
            throw new TaskPausedException(paused);
        }
        if (aside) {
            log.info("Aside on a task paused in {} — answered, but the task does not move.",
                    paused.stage().id());
        }

        // Ahead of the memory writes as well as the calls, and the order is the point: a message
        // that asks for something forbidden must not first be filed as an established fact. Left
        // until after extraction, a refused request would still teach the agent what was asked
        // for, and every later turn would answer against it.
        Invariants rules = invariants.held(where.visitor());
        InvariantGuard.Ruling ruling = guard.check(prompt, rules);
        if (ruling.breached()) {
            return refuse(where, who, effective, prompt, rules, ruling);
        }

        // Before pricing, not after: the whole point is that this turn is the one that gets
        // cheaper, and the budget the caller is shown has to be the budget that was spent.
        int compacted = compressIfDue(id);
        updateMemory(where, prompt);
        MemoryState memory = new MemoryState(longTerm.recall(where.visitor()),
                conversations.summary(id), conversations.facts(id), conversations.task(id),
                conversations.history(id));
        List<Message> history = memory.recent();

        // Priced before a byte leaves the process: an oversized prompt is billed as a
        // rejection, so the cheapest place to find out it will not fit is here.
        ContextPlanner.ContextPlan plan = contextPlanner.plan(effective, who, rules, memory, prompt);
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
                response.finishReason(), latencyMillis, verdict, conversations.history(id),
                compacted, InvariantGuard.Ruling.CLEAR);
    }

    /**
     * Turns a breach into the turn's answer, and commits it to the transcript like any other.
     * <p>
     * This is where invariants part company with the other refusals in here. An overflow or a
     * paused task throws, because nothing was produced and the message is best left in the box for
     * the user to edit or resend. A breach produces something worth keeping: the rule, the reason
     * it exists, and what can be done instead — which is an answer to the question, just not the
     * one that was asked for.
     * <p>
     * Writing both halves into the transcript is what stops the same refusal happening twice.
     * Dropped instead, the exchange leaves no trace the model can read, so two turns later it
     * cheerfully proposes the forbidden thing again and the user gets the same paragraph back.
     * Kept, the refusal is in the history and the next turn is answered in light of it.
     * <p>
     * Nothing is extracted from a refused message and the task does not move, both for the same
     * reason: what was asked for is not going to happen, so filing it as established fact or as
     * progress would record a thing that never took place.
     */
    private AgentResult refuse(MemoryScope where, Persona who, AgentConfig effective, String prompt,
                               Invariants rules, InvariantGuard.Ruling ruling) {
        String redirect = ruling.redirect();
        conversations.append(where.conversation(),
                List.of(Message.user(prompt), Message.assistant(redirect)));

        // The guard's own call is the only thing this turn cost, and on the forbid tier not even
        // that. Billed like any other call so a refusal is never free-looking when it was not.
        TokenUsage usage = new TokenUsage(0, 0, ruling.costTokens());
        log.info("Refused by {} — {}. The answer call was never made{}.",
                ruling.broken().label(),
                ruling.settledInJava() ? "matched on " + ruling.terms() : "ruled on by the guard",
                ruling.costTokens() == 0 ? ", and nothing was charged"
                        : ", at a cost of " + ruling.costTokens() + " token(s)");

        return new AgentResult(redirect, effective, usage, usageTracker.record(usage),
                contextPlanner.budget(effective, who, rules, memory(where)),
                "invariant", 0L, Verdict.NOT_SCORED, conversations.history(where.conversation()), 0,
                ruling);
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
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        return contextPlanner.budget(effectiveConfig(who, config), who,
                invariants.held(where.visitor()), memory(where));
    }

    /** The standing rules for a visitor, for rendering the page. */
    public Invariants invariants(String visitorId) {
        return invariants.held(visitorId);
    }

    /**
     * Declares a rule, or amends the one already held under the same id.
     * <p>
     * Public on the agent and reachable only from the controller — which is to say, only from a
     * form somebody filled in. Nothing on the extraction path can get here, and that is the whole
     * of what makes an invariant different from a remembered fact: a model that can mint its own
     * constraints can mint the one that permits what it wanted to do, and the mechanism becomes
     * decoration.
     */
    public Invariants declareInvariant(MemoryScope scope, Invariant invariant) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        Invariants held = invariants.declare(where.visitor(), invariant);
        log.info("Invariants rev {} — {} rule(s) now binding on this visitor.",
                held.revision(), held.binding().size());
        return held;
    }

    /** Stops a rule binding, keeping it and the reason on the record. */
    public Invariants retireInvariant(MemoryScope scope, String id, String reason) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        Invariants held = invariants.retire(where.visitor(), id, reason);
        log.info("Invariant {} retired — {}. {} rule(s) still binding.",
                id, blankAsDash(reason), held.binding().size());
        return held;
    }

    /** Puts a retired rule back in force. */
    public Invariants restoreInvariant(MemoryScope scope, String id) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        return invariants.restore(where.visitor(), id);
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
                conversations.facts(id), conversations.task(id), conversations.history(id));
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
     * <p>
     * The same call also carries the task's own stage, which is why this method has stopped
     * returning just the fact block. One reading of the message serves both questions — what did
     * it establish, and did it move the job on — and splitting them into two calls would double
     * the per-turn request count to re-read text this one already has.
     */
    private void updateMemory(MemoryScope where, String prompt) {
        String id = where.conversation();
        Facts current = conversations.facts(id);

        MemoryExtractor.Extraction extraction;
        try {
            extraction = extractor.extract(prompt,
                    keysInUse(current, longTerm.recall(where.visitor())), conversations.task(id));
        } catch (AgentException e) {
            log.warn("Memory extraction failed ({}) — continuing with the layers as they stand.",
                    e.getMessage());
            return;
        }
        if (extraction.isEmpty()) {
            return;
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

        applyProposedMove(id, TaskState.Proposal.from(routed.stage()), TaskState.Authority.MODEL);
    }

    /**
     * Puts a proposed move to the machine and saves it if it is allowed.
     * <p>
     * A refusal is logged at WARN and nothing else happens. That is the loudest the disagreement
     * should get: the model's idea of where the task is has drifted from the machine's, which is
     * worth knowing about and is not worth failing a user's turn over. The machine stays where it
     * was, which is the safe side of the disagreement — an unmoved task under-claims progress,
     * whereas an illegally moved one claims work that was never done.
     */
    private TaskState applyProposedMove(String id, TaskState.Proposal proposal,
                                        TaskState.Authority by) {
        TaskState current = conversations.task(id);
        TaskState.Transition transition = current.apply(proposal, by);

        if (transition.refused()) {
            log.warn("Refused a {} task transition — {}. The task stays in {}.",
                    by.name().toLowerCase(Locale.ROOT), transition.why(), current.stage().id());
            return current;
        }
        if (!transition.moved()) {
            return current;
        }
        conversations.saveTask(id, transition.state());
        log.info("Task rev {} — {} (step: {}, next: {} from the {})", transition.state().revision(),
                transition.why(), blankAsDash(transition.state().step()),
                blankAsDash(transition.state().next()), transition.state().awaiting().id());
        return transition.state();
    }

    private static String blankAsDash(String value) {
        return value.isEmpty() ? "—" : value;
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
     * What closing the task did, or why it did not.
     *
     * @param closed   false when the state machine refused the move to {@code done}, in which case
     *                 nothing was promoted and nothing was cleared
     * @param why      the refusal, or what was promoted — the page shows this either way
     * @param longTerm the long-term block as it now stands
     */
    public record TaskClosure(boolean closed, String why, LongTermMemory longTerm) {
    }

    /**
     * Closes the task in hand: everything working memory had marked as agreed graduates to
     * long-term, the rest of the block is thrown away, and the state machine is reset.
     * <p>
     * This is the one moment a decision crosses a lifetime boundary, and it is deliberately a
     * thing the user does rather than a thing the model infers. Long-term memory is shared by
     * every branch of every conversation; promoting a decision while the task that produced it is
     * still open leaks it into the forks that exist precisely to disagree with it, and a model
     * guessing at "is this task finished?" would do exactly that on the turn it guessed wrong.
     * <p>
     * <strong>This button is the {@code → done} transition</strong>, which is what gives the
     * transition table something to actually govern. A task sitting in {@code planning} cannot be
     * closed, because nothing has been built and nothing has been checked, and the promotion would
     * write "agreed" lines about work that never happened into memory every later branch reads.
     * <p>
     * A conversation that never started a task is exempt, and that is not a loophole: the machine
     * governs tasks that have a state, and one with no state is the pre-Day-13 behaviour, where
     * closing was simply a way to file what the dialogue had agreed.
     */
    public TaskClosure finishTask(MemoryScope scope) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        String id = where.conversation();
        TaskState task = conversations.task(id);

        if (task.isPresent()) {
            TaskState.Transition closing = task.apply(
                    TaskState.Proposal.toStage(TaskStage.DONE), TaskState.Authority.HUMAN);
            if (closing.refused()) {
                log.info("Refused to close the task — {}. Nothing promoted.", closing.why());
                return new TaskClosure(false, closing.why(), longTerm.recall(where.visitor()));
            }
        }

        List<Facts.Fact> settled = conversations.facts(id).settled();
        conversations.saveFacts(id, Facts.EMPTY);
        conversations.saveTask(id, TaskState.EMPTY);

        if (settled.isEmpty()) {
            log.info("Task closed with nothing agreed — working memory cleared, long-term untouched.");
            return new TaskClosure(true, "Task closed; nothing had been agreed to keep.",
                    longTerm.recall(where.visitor()));
        }
        LongTermMemory kept = longTerm.remember(where.visitor(),
                LongTermMemory.promoted(LongTermKind.DECISION, settled));
        log.info("Task closed — promoted {} agreed fact(s) to long-term memory (rev {}) and cleared "
                + "the rest of working memory.", settled.size(), kept.revision());
        return new TaskClosure(true,
                "Task closed; %d agreed fact(s) kept.".formatted(settled.size()), kept);
    }

    /**
     * Starts a fresh task: working memory and the state machine, leaving the dialogue and the
     * visitor alone.
     * <p>
     * The other half of the boundary, and unlike closing it is not gated by the transition table.
     * Abandoning a task is not a move within the machine — it is throwing the machine away — so a
     * task stuck anywhere at all must be able to end this way. Refusing to abandon would be the
     * one refusal with no escape from it.
     */
    public void newTask(MemoryScope scope) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        conversations.saveFacts(where.conversation(), Facts.EMPTY);
        conversations.saveTask(where.conversation(), TaskState.EMPTY);
        log.info("New task — working memory and task state cleared; short-term and long-term are "
                + "untouched.");
    }

    /** Where the task in hand has got to, for rendering the page. */
    public TaskState task(String conversationId) {
        return conversations.task(conversationId);
    }

    /**
     * A person moving the task by hand.
     * <p>
     * Bound by the same transition table as the model. The button overrides <em>authority</em>,
     * not legality — if it overrode both, the table would only describe what the model does and
     * the machine would have two sets of rules, which is one more than a machine can have.
     */
    public TaskState moveTask(MemoryScope scope, TaskStage stage) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        return applyProposedMove(where.conversation(), TaskState.Proposal.toStage(stage),
                TaskState.Authority.HUMAN);
    }

    /**
     * Stops the machine where it stands, and with it the turns. While a task is paused a new
     * message is refused before any call is made, rather than answered with the state change
     * quietly dropped.
     * <p>
     * Pausing a conversation that never started a task does nothing. Left to
     * {@link TaskState#pause()} alone it would bump the revision and so bring a task into
     * existence — a pause that creates the thing it is pausing, and worse, one that then locks a
     * dialogue which never had a machine to begin with.
     */
    public TaskState pauseTask(MemoryScope scope) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        TaskState current = conversations.task(where.conversation());
        if (!current.isPresent()) {
            log.info("Nothing to pause — this conversation has no task.");
            return current;
        }
        TaskState paused = current.pause();
        conversations.saveTask(where.conversation(), paused);
        log.info("Task paused in {} — the state is on disk; it survives a restart.",
                paused.stage().id());
        return paused;
    }

    /** Lets the turns through again. Harmless on a task that was never paused, or never started. */
    public TaskState resumeTask(MemoryScope scope) {
        MemoryScope where = (scope == null) ? MemoryScope.of(null) : scope;
        TaskState current = conversations.task(where.conversation());
        if (!current.paused()) {
            return current;
        }
        TaskState resumed = current.resume();
        conversations.saveTask(where.conversation(), resumed);
        log.info("Task resumed in {}.", resumed.stage().id());
        return resumed;
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
