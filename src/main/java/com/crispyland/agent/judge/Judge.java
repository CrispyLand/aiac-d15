package com.crispyland.agent.judge;

import com.crispyland.agent.AgentConfig;

/**
 * Seam for scoring an answer (self-critique, a second model, heuristics, evals...).
 * The default implementation does nothing; swap the bean in to make it real.
 */
public interface Judge {

    Verdict judge(String userInput, String answer, AgentConfig config);
}
