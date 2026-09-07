package com.crispyland.web;

import com.crispyland.agent.Agent;
import com.crispyland.agent.AgentException;
import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.AgentResult;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Thin by design: render the page, turn form fields into an AgentConfig, call the agent,
 * put the result on the model. No HTTP client, no JSON, and no conversation state — the
 * browser session only supplies the conversation id; the agent owns the messages.
 */
@Controller
public class ChatController {

    private final Agent agent;
    private final AgentProperties properties;

    public ChatController(Agent agent, AgentProperties properties) {
        this.agent = agent;
        this.properties = properties;
    }

    @GetMapping("/")
    public String chatPage(Model model, HttpSession session) {
        model.addAttribute("form", ChatForm.of("", properties.defaults().toConfig()));
        model.addAttribute("transcript", agent.transcript(session.getId()));
        addOptions(model);
        return "chat";
    }

    @PostMapping("/")
    public String ask(@ModelAttribute("form") ChatForm form, BindingResult binding,
                      Model model, HttpSession session) {
        addOptions(model);

        if (binding.hasErrors()) {
            model.addAttribute("error", "Some parameters could not be read — check the numeric fields.");
            model.addAttribute("transcript", agent.transcript(session.getId()));
            return "chat";
        }

        try {
            AgentResult result = agent.handle(session.getId(), form.userInput(), form.toAgentConfig());
            model.addAttribute("result", result);
            model.addAttribute("transcript", result.transcript());
            // Keep the settings the agent actually used, but clear the box for the next turn.
            model.addAttribute("form", ChatForm.of("", result.effectiveConfig()));
        } catch (AgentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("transcript", agent.transcript(session.getId()));
        }
        return "chat";
    }

    @PostMapping("/reset")
    public String reset(HttpSession session) {
        agent.reset(session.getId());
        return "redirect:/";
    }

    /** Dropdown contents come from application.yml, not from the template. */
    private void addOptions(Model model) {
        model.addAttribute("models", properties.availableModels());
        model.addAttribute("reasoningEfforts", properties.reasoningEfforts());
    }
}
