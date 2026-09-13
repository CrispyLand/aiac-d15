package com.crispyland.web;

import com.crispyland.agent.Agent;
import com.crispyland.agent.AgentConfig;
import com.crispyland.agent.AgentException;
import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.AgentResult;
import com.crispyland.agent.ContextOverflowException;
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

    public ChatController(Agent agent, AgentProperties properties, ConversationIdResolver conversationIds) {
        this.agent = agent;
        this.properties = properties;
        this.conversationIds = conversationIds;
    }

    @GetMapping("/")
    public String chatPage(Model model, HttpServletRequest request, HttpServletResponse response) {
        // Resolving here also mints the cookie for a first-time visitor, before they send anything.
        String conversationId = conversationIds.resolve(request, response);
        AgentConfig defaults = properties.defaultConfig();
        model.addAttribute("form", ChatForm.of("", defaults));
        addTranscript(model, conversationId, agent.transcript(conversationId));
        model.addAttribute("budget", agent.budget(conversationId, defaults));
        addOptions(model);
        return "chat";
    }

    @PostMapping("/")
    public String ask(@ModelAttribute("form") ChatForm form, BindingResult binding, Model model,
                      HttpServletRequest request, HttpServletResponse response) {
        String conversationId = conversationIds.resolve(request, response);
        addOptions(model);

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

    /** Discards the messages but keeps the cookie — same visitor, fresh dialogue. */
    @PostMapping("/reset")
    public String reset(HttpServletRequest request, HttpServletResponse response) {
        agent.reset(conversationIds.resolve(request, response));
        return "redirect:/";
    }

    /** The turn-by-turn cost series is only ever a view over the transcript — never stored twice. */
    private void addTranscript(Model model, String conversationId, List<Message> transcript) {
        model.addAttribute("transcript", transcript);
        model.addAttribute("turns", TurnCost.series(transcript));
        // The head of the dialogue that no longer exists as messages — rendered above them so
        // the page shows the whole conversation, compressed part included.
        model.addAttribute("summary", agent.summary(conversationId));
    }

    /** Dropdown contents come from application.yml, not from the template. */
    private void addOptions(Model model) {
        model.addAttribute("models", properties.availableModels());
        model.addAttribute("reasoningEfforts", properties.reasoningEfforts());
    }
}
