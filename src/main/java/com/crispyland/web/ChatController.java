package com.crispyland.web;

import com.crispyland.agent.Agent;
import com.crispyland.agent.AgentConfig;
import com.crispyland.agent.AgentException;
import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.AgentResult;
import com.crispyland.agent.Branches;
import com.crispyland.agent.ContextOverflowException;
import com.crispyland.agent.ContextStrategy;
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

    public ChatController(Agent agent, AgentProperties properties,
                          ConversationIdResolver conversationIds, Branches branches) {
        this.agent = agent;
        this.properties = properties;
        this.conversationIds = conversationIds;
        this.branches = branches;
    }

    /**
     * @param strategy which tab is open. It lives in the URL rather than in the session so a
     *                 tab is a link — shareable, back-button-able, and reloadable into the same
     *                 view of the same conversation.
     */
    @GetMapping("/")
    public String chatPage(@RequestParam(required = false) String strategy, Model model,
                           HttpServletRequest request, HttpServletResponse response) {
        // Resolving here also mints the cookie for a first-time visitor, before they send anything.
        String visitor = conversationIds.resolve(request, response);
        String conversationId = branches.activeKey(visitor);
        addBranches(model, visitor);
        AgentConfig config = properties.defaultConfig().toBuilder()
                .contextStrategy(ContextStrategy.from(strategy))
                .build()
                .withFallback(properties.defaultConfig());
        model.addAttribute("form", ChatForm.of("", config));
        addTranscript(model, conversationId, agent.transcript(conversationId));
        model.addAttribute("budget", agent.budget(conversationId, config));
        addOptions(model, config);
        return "chat";
    }

    @PostMapping("/")
    public String ask(@ModelAttribute("form") ChatForm form, BindingResult binding, Model model,
                      HttpServletRequest request, HttpServletResponse response) {
        String visitor = conversationIds.resolve(request, response);
        String conversationId = branches.activeKey(visitor);
        addBranches(model, visitor);
        addOptions(model, form.toAgentConfig().withFallback(properties.defaultConfig()));

        if (binding.hasErrors()) {
            model.addAttribute("error", "Some parameters could not be read — check the numeric fields.");
            addTranscript(model, conversationId, agent.transcript(conversationId));
            model.addAttribute("budget", agent.budget(conversationId, properties.defaultConfig()));
            return "chat";
        }

        try {
            AgentResult result = agent.handle(conversationId, form.userInput(), form.toAgentConfig());
            model.addAttribute("result", result);
            addTranscript(model, conversationId, result.transcript());
            model.addAttribute("budget", agent.budget(conversationId, result.effectiveConfig()));
            // Keep the settings the agent actually used, but clear the box for the next turn.
            model.addAttribute("form", ChatForm.of("", result.effectiveConfig()));
        } catch (ContextOverflowException e) {
            // Show the budget that caused the refusal, not the one the dialogue merely sits at.
            model.addAttribute("error", e.getMessage());
            model.addAttribute("budget", e.budget());
            addTranscript(model, conversationId, agent.transcript(conversationId));
        } catch (AgentException e) {
            model.addAttribute("error", e.getMessage());
            addTranscript(model, conversationId, agent.transcript(conversationId));
            model.addAttribute("budget", agent.budget(conversationId, form.toAgentConfig()));
        }
        return "chat";
    }

    /** Discards the messages but keeps the cookie — same visitor, fresh dialogue, same tab. */
    @PostMapping("/reset")
    public String reset(@RequestParam(required = false) String strategy,
                        HttpServletRequest request, HttpServletResponse response) {
        // Every branch, not just the open one: a half-reset conversation with forks still
        // hanging off the transcript it no longer has is worse than either outcome.
        branches.reset(conversationIds.resolve(request, response));
        return redirect(strategy);
    }

    /**
     * Copies the conversation up to {@code at} onto a new branch and continues there. The parent
     * is untouched, which is the whole point: fork twice from the same message and you have two
     * futures of one past, comparable because everything before the split is identical.
     */
    @PostMapping("/branch/fork")
    public String fork(@RequestParam(required = false) String strategy,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false, defaultValue = "-1") int at,
                       HttpServletRequest request, HttpServletResponse response) {
        branches.fork(conversationIds.resolve(request, response), name, at);
        return redirect(strategy);
    }

    /** Switching is free and lossless — the other branch's messages were never touched. */
    @PostMapping("/branch/switch")
    public String switchBranch(@RequestParam(required = false) String strategy,
                               @RequestParam(required = false) String branch,
                               HttpServletRequest request, HttpServletResponse response) {
        branches.switchTo(conversationIds.resolve(request, response), branch);
        return redirect(strategy);
    }

    /** Post/redirect/get, keeping whichever tab was open. */
    private static String redirect(String strategy) {
        ContextStrategy open = ContextStrategy.from(strategy);
        return (open == null) ? "redirect:/" : "redirect:/?strategy=" + open.id();
    }

    private void addBranches(Model model, String visitor) {
        model.addAttribute("branches", branches.all(visitor));
        model.addAttribute("branch", branches.active(visitor));
    }

    /** The turn-by-turn cost series is only ever a view over the transcript — never stored twice. */
    private void addTranscript(Model model, String conversationId, List<Message> transcript) {
        model.addAttribute("transcript", transcript);
        model.addAttribute("turns", TurnCost.series(transcript));
        // The head of the dialogue that no longer exists as messages — rendered above them so
        // the page shows the whole conversation, compressed part included.
        model.addAttribute("summary", agent.summary(conversationId));
        model.addAttribute("facts", agent.facts(conversationId));
    }

    /** Dropdown contents come from application.yml, not from the template. */
    private void addOptions(Model model, AgentConfig config) {
        model.addAttribute("models", properties.availableModels());
        model.addAttribute("reasoningEfforts", properties.reasoningEfforts());
        model.addAttribute("strategies", ContextStrategy.values());
        model.addAttribute("strategy", config.contextStrategyOrDefault());
        model.addAttribute("windowMessages", properties.windowMessages());
        model.addAttribute("maxFacts", properties.facts().maxFacts());
    }
}
