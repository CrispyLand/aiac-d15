package com.crispyland.web;

import com.crispyland.agent.Agent;
import com.crispyland.agent.AgentException;
import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.AgentResult;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Thin by design: render the page, turn form fields into an AgentConfig, call
 * {@code agent.handle(...)}, put the result on the model. No HTTP client, no JSON.
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
    public String chatPage(Model model) {
        model.addAttribute("form", ChatForm.of("", properties.defaults().toConfig()));
        model.addAttribute("models", properties.availableModels());
        return "chat";
    }

    @PostMapping("/")
    public String ask(@ModelAttribute("form") ChatForm form, BindingResult binding, Model model) {
        model.addAttribute("models", properties.availableModels());

        if (binding.hasErrors()) {
            model.addAttribute("error", "Some parameters could not be read — check the numeric fields.");
            return "chat";
        }

        try {
            AgentResult result = agent.handle(form.userInput(), form.toAgentConfig());
            model.addAttribute("result", result);
            // Show back exactly the settings the agent actually used.
            model.addAttribute("form", ChatForm.of(form.userInput(), result.effectiveConfig()));
        } catch (AgentException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "chat";
    }
}
