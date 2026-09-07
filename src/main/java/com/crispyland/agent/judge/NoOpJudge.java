package com.crispyland.agent.judge;

import com.crispyland.agent.AgentConfig;

/** Default judge: keeps the hook in the pipeline without spending a token on it. */
public class NoOpJudge implements Judge {

    @Override
    public Verdict judge(String userInput, String answer, AgentConfig config) {
        return Verdict.NOT_SCORED;
    }
}
