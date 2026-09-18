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
import com.crispyland.agent.profile.Persona;
import com.crispyland.agent.profile.PersonaSelector;
import com.crispyland.agent.profile.Profiles;
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
    private final Profiles profiles;
    private final PersonaSelector personas;
    private final ActiveProfile activeProfile;

    public ChatController(Agent agent, AgentProperties properties,
                          ConversationIdResolver conversationIds, Branches branches,
                          LongTermStore longTerm, Profiles profiles, PersonaSelector personas,
                          ActiveProfile activeProfile) {
        this.agent = agent;
        this.properties = properties;
        this.conversationIds = conversationIds;
        this.branches = branches;
        this.longTerm = longTerm;
        this.profiles = profiles;
        this.personas = personas;
        this.activeProfile = activeProfile;
    }

    @GetMapping("/")
    public String chatPage(Model model, HttpServletRequest request, HttpServletResponse response) {
        // Resolving here also mints the cookie for a first-time visitor, before they send anything.
        MemoryScope scope = scope(request, response);
        addBranches(model, scope.visitor());
        // No message yet, so nothing for the keyword rule to match on — this shows what the
        // visitor's default would give them, which is what the page should be honest about.
        PersonaSelector.Selection selection = select(request, null, null);
        // The profile's numbers, not the raw yml ones. The boxes are posted straight back on the
        // next turn, so prefilling them with the defaults would hand every request an explicit
        // override and silently cancel the profile's limits.
        AgentConfig config = agent.effectiveConfig(selection.persona(), null);
        model.addAttribute("form", ChatForm.of("", config, lensIdOf(selection)));
        addMemory(model, scope, agent.transcript(scope.conversation()));
        addPersona(model, request, selection);
        model.addAttribute("budget", agent.budget(scope, selection.persona(), config));
        addOptions(model, config);
        return "chat";
    }

    @PostMapping("/")
    public String ask(@ModelAttribute("form") ChatForm form, BindingResult binding, Model model,
                      HttpServletRequest request, HttpServletResponse response) {
        MemoryScope scope = scope(request, response);
        addBranches(model, scope.visitor());

        // Selected once and reused for the answer, the budget and the page: choosing twice would
        // let the keyword rule pick one lens for the call and a different one for the label.
        PersonaSelector.Selection selection = select(request, form.selectedLens(), form.userInput());
        Persona persona = selection.persona();
        addPersona(model, request, selection);
        // Resolved through the persona so the dropdowns show the model and effort the turn would
        // actually use, for the same reason the numeric boxes are.
        addOptions(model, agent.effectiveConfig(persona, form.toAgentConfig()));

        if (binding.hasErrors()) {
            model.addAttribute("error", "Some parameters could not be read — check the numeric fields.");
            addMemory(model, scope, agent.transcript(scope.conversation()));
            model.addAttribute("budget", agent.budget(scope, persona, properties.defaultConfig()));
            return "chat";
        }

        try {
            AgentResult result = agent.handle(scope, persona, form.userInput(), form.toAgentConfig());
            model.addAttribute("result", result);
            addMemory(model, scope, result.transcript());
            model.addAttribute("budget", agent.budget(scope, persona, result.effectiveConfig()));
            // Keep the settings the agent actually used, but clear the box for the next turn.
            model.addAttribute("form", ChatForm.of("", result.effectiveConfig(), form.selectedLens()));
        } catch (ContextOverflowException e) {
            // Show the budget that caused the refusal, not the one the dialogue merely sits at.
            model.addAttribute("error", e.getMessage());
            model.addAttribute("budget", e.budget());
            addMemory(model, scope, agent.transcript(scope.conversation()));
        } catch (AgentException e) {
            model.addAttribute("error", e.getMessage());
            addMemory(model, scope, agent.transcript(scope.conversation()));
            model.addAttribute("budget", agent.budget(scope, persona, form.toAgentConfig()));
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

    /**
     * Answers as somebody else from the next message on.
     * <p>
     * Memory is deliberately untouched — not reset, not partitioned. That is the demonstration:
     * the same transcript and the same remembered facts, asked the same question, come back
     * formatted completely differently. Clearing memory here would make the switch look like it
     * worked for the wrong reason.
     */
    @PostMapping("/profile/switch")
    public String switchProfile(@RequestParam(required = false) String profile,
                                HttpServletResponse response) {
        activeProfile.switchTo(profile, response);
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

    /** Resolves the persona for one request: who they are, plus whichever lens applies. */
    private PersonaSelector.Selection select(HttpServletRequest request, String lens, String message) {
        return personas.select(activeProfile.current(request), lens, message);
    }

    /**
     * The profile as the page shows it — including the reason it was chosen.
     * <p>
     * The reason is on screen rather than only in the logs because a lens changes what the agent
     * refuses to do. "It would not answer that" is a bug report; "it would not answer that,
     * wearing the psychologist lens, matched on 'тревог'" is a fixable one.
     */
    private void addPersona(Model model, HttpServletRequest request,
                            PersonaSelector.Selection selection) {
        model.addAttribute("persona", selection.persona());
        model.addAttribute("personaWhy", selection.why());
        model.addAttribute("profiles", profiles.users());
        model.addAttribute("lenses", profiles.lenses());
        model.addAttribute("activeProfile", activeProfile.current(request));
    }

    private static String lensIdOf(PersonaSelector.Selection selection) {
        return selection.persona().hasLens() ? selection.lens().id() : null;
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
