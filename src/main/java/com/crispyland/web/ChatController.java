package com.crispyland.web;

import com.crispyland.agent.Agent;
import com.crispyland.agent.AgentConfig;
import com.crispyland.agent.AgentException;
import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.AgentResult;
import com.crispyland.agent.Branches;
import com.crispyland.agent.ContextOverflowException;
import com.crispyland.agent.memory.LongTermKind;
import com.crispyland.agent.memory.LongTermStore;
import com.crispyland.agent.memory.MemoryLayer;
import com.crispyland.agent.memory.MemoryScope;
import com.crispyland.agent.memory.Message;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Thin by design: render the page, turn form fields into an AgentConfig, call the agent,
 * put the result on the model. No HTTP client, no JSON, and no conversation state — the
 * browser only supplies the conversation id; the agent owns the messages.
 */
@Controller
public class ChatController {

    private final Agent agent;
    private final AgentProperties properties;
    private final ConversationIdResolver conversationIds;
    private final Branches branches;
    private final LongTermStore longTerm;

    public ChatController(Agent agent, AgentProperties properties,
                          ConversationIdResolver conversationIds, Branches branches,
                          LongTermStore longTerm) {
        this.agent = agent;
        this.properties = properties;
        this.conversationIds = conversationIds;
        this.branches = branches;
        this.longTerm = longTerm;
    }

    @GetMapping("/")
    public String chatPage(Model model, HttpServletRequest request, HttpServletResponse response) {
        // Resolving here also mints the cookie for a first-time visitor, before they send anything.
        MemoryScope scope = scope(request, response);
        addBranches(model, scope.visitor());
        AgentConfig config = properties.defaultConfig();
        model.addAttribute("form", ChatForm.of("", config));
        addMemory(model, scope, agent.transcript(scope.conversation()));
        model.addAttribute("budget", agent.budget(scope, config));
        addOptions(model, config);
        return "chat";
    }

    @PostMapping("/")
    public String ask(@ModelAttribute("form") ChatForm form, BindingResult binding, Model model,
                      HttpServletRequest request, HttpServletResponse response) {
        MemoryScope scope = scope(request, response);
        addBranches(model, scope.visitor());
        addOptions(model, form.toAgentConfig().withFallback(properties.defaultConfig()));

        if (binding.hasErrors()) {
            model.addAttribute("error", "Some parameters could not be read — check the numeric fields.");
            addMemory(model, scope, agent.transcript(scope.conversation()));
            model.addAttribute("budget", agent.budget(scope, properties.defaultConfig()));
            return "chat";
        }

        try {
            AgentResult result = agent.handle(scope, form.userInput(), form.toAgentConfig());
            model.addAttribute("result", result);
            addMemory(model, scope, result.transcript());
            model.addAttribute("budget", agent.budget(scope, result.effectiveConfig()));
            // Keep the settings the agent actually used, but clear the box for the next turn.
            model.addAttribute("form", ChatForm.of("", result.effectiveConfig()));
        } catch (ContextOverflowException e) {
            // Show the budget that caused the refusal, not the one the dialogue merely sits at.
            model.addAttribute("error", e.getMessage());
            model.addAttribute("budget", e.budget());
            addMemory(model, scope, agent.transcript(scope.conversation()));
        } catch (AgentException e) {
            model.addAttribute("error", e.getMessage());
            addMemory(model, scope, agent.transcript(scope.conversation()));
            model.addAttribute("budget", agent.budget(scope, form.toAgentConfig()));
        }
        return "chat";
    }

    /**
     * Discards the messages but keeps the cookie — same visitor, fresh dialogue.
     * <p>
     * Long-term memory is deliberately untouched. That is the observable difference between the
     * layers: reset and the agent still greets you by name, because the name was never part of
     * this conversation in the first place.
     */
    @PostMapping("/reset")
    public String reset(HttpServletRequest request, HttpServletResponse response) {
        // Every branch, not just the open one: a half-reset conversation with forks still
        // hanging off the transcript it no longer has is worse than either outcome.
        branches.reset(conversationIds.resolve(request, response));
        return "redirect:/";
    }

    /** Drops one long-term entry by its id, leaving the rest of the layer alone. */
    @PostMapping("/memory/forget")
    public String forget(@RequestParam String entry,
                         HttpServletRequest request, HttpServletResponse response) {
        longTerm.forget(conversationIds.resolve(request, response), entry);
        return "redirect:/";
    }

    /**
     * Erases the visitor: everything remembered about them, every branch they opened, and then
     * the id itself.
     * <p>
     * The order is the point and it is not interchangeable. Delete the data first while the id
     * still addresses it, and rotate last — rotate first and the deletes go looking under an id
     * that owns nothing, leaving the real record intact and permanently unreachable. That outcome
     * is worse than having no button, because it looks like the button worked.
     */
    @PostMapping("/memory/forget-all")
    public String forgetAll(HttpServletRequest request, HttpServletResponse response) {
        String visitor = conversationIds.resolve(request, response);
        longTerm.forgetAll(visitor);
        branches.reset(visitor);
        conversationIds.rotate(response);
        return "redirect:/";
    }

    /**
     * Ends the task in hand and keeps what it agreed: every line working memory had marked
     * {@code [agreed]} moves to long-term, and the rest of the block is discarded.
     * <p>
     * A button rather than something the model infers. The agent could be asked "is this task
     * over?" on every turn, but the answer decides whether a decision is written somewhere every
     * branch can see, and a wrong guess there is not visible and not undone by the next message.
     * Asking the person who actually knows costs one click.
     */
    @PostMapping("/task/finish")
    public String finishTask(HttpServletRequest request, HttpServletResponse response) {
        agent.finishTask(scope(request, response));
        return "redirect:/";
    }

    /**
     * Starts a fresh task, discarding working memory without promoting any of it.
     * <p>
     * The other half of the boundary, and it has to exist: a task that went nowhere must be able
     * to end without writing its dead ends into permanent memory. Without this the only way to
     * abandon an exploration is to also keep what it concluded.
     */
    @PostMapping("/task/new")
    public String newTask(HttpServletRequest request, HttpServletResponse response) {
        agent.newTask(scope(request, response));
        return "redirect:/";
    }

    /**
     * Copies the conversation up to {@code at} onto a new branch and continues there. The parent
     * is untouched, which is the whole point: fork twice from the same message and you have two
     * futures of one past, comparable because everything before the split is identical.
     */
    @PostMapping("/branch/fork")
    public String fork(@RequestParam(required = false) String name,
                       @RequestParam(required = false, defaultValue = "-1") int at,
                       HttpServletRequest request, HttpServletResponse response) {
        branches.fork(conversationIds.resolve(request, response), name, at);
        return "redirect:/";
    }

    /** Switching is free and lossless — the other branch's messages were never touched. */
    @PostMapping("/branch/switch")
    public String switchBranch(@RequestParam(required = false) String branch,
                               HttpServletRequest request, HttpServletResponse response) {
        branches.switchTo(conversationIds.resolve(request, response), branch);
        return "redirect:/";
    }

    /** Who is asking and which of their branches is open — resolved once per request. */
    private MemoryScope scope(HttpServletRequest request, HttpServletResponse response) {
        // Resolving here also mints the cookie for a first-time visitor, before they send anything.
        String visitor = conversationIds.resolve(request, response);
        return new MemoryScope(visitor, branches.activeKey(visitor));
    }

    private void addBranches(Model model, String visitor) {
        model.addAttribute("branches", branches.all(visitor));
        model.addAttribute("branch", branches.active(visitor));
    }

    /**
     * Every layer on the page, so the claim that they are separate is something the user can see
     * rather than something the code asserts. The turn-by-turn cost series is only ever a view
     * over the transcript — never stored twice.
     */
    private void addMemory(Model model, MemoryScope scope, List<Message> transcript) {
        model.addAttribute("transcript", transcript);
        model.addAttribute("turns", TurnCost.series(transcript));
        // The head of the dialogue that no longer exists as messages — rendered above them so
        // the page shows the whole conversation, compressed part included.
        model.addAttribute("summary", agent.summary(scope.conversation()));
        model.addAttribute("facts", agent.facts(scope.conversation()));
        model.addAttribute("longTerm", agent.recall(scope.visitor()));
    }

    /** Dropdown contents come from application.yml, not from the template. */
    private void addOptions(Model model, AgentConfig config) {
        model.addAttribute("models", properties.availableModels());
        model.addAttribute("reasoningEfforts", properties.reasoningEfforts());
        model.addAttribute("layers", MemoryLayer.values());
        model.addAttribute("longTermKinds", LongTermKind.values());
        model.addAttribute("keepRecentMessages", properties.keepRecentMessages());
        model.addAttribute("maxFacts", properties.facts().maxFacts());
    }
}
