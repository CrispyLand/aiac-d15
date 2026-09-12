package com.crispyland.web;

import com.crispyland.agent.Agent;
import com.crispyland.agent.AgentException;
import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.AgentResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
        model.addAttribute("form", ChatForm.of("", properties.defaults().toConfig()));
        model.addAttribute("transcript", agent.transcript(conversationId));
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
            model.addAttribute("transcript", agent.transcript(conversationId));
            return "chat";
        }

        try {
            AgentResult result = agent.handle(conversationId, form.userInput(), form.toAgentConfig());
            model.addAttribute("result", result);
            model.addAttribute("transcript", result.transcript());
            // Keep the settings the agent actually used, but clear the box for the next turn.
            model.addAttribute("form", ChatForm.of("", result.effectiveConfig()));
        } catch (AgentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("transcript", agent.transcript(conversationId));
        }
        return "chat";
    }

    /** Discards the messages but keeps the cookie — same visitor, fresh dialogue. */
    @PostMapping("/reset")
    public String reset(HttpServletRequest request, HttpServletResponse response) {
        agent.reset(conversationIds.resolve(request, response));
        return "redirect:/";
    }

    /** Dropdown contents come from application.yml, not from the template. */
    private void addOptions(Model model) {
        model.addAttribute("models", properties.availableModels());
        model.addAttribute("reasoningEfforts", properties.reasoningEfforts());
    }
}
