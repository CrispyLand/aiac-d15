package com.crispyland.agent.judge;

/** Optional quality assessment of an answer. {@code scored=false} means nobody judged it. */
public record Verdict(boolean scored, double score, String notes) {

    public static final Verdict NOT_SCORED = new Verdict(false, 0.0, "not scored");

    public static Verdict of(double score, String notes) {
        return new Verdict(true, score, notes);
    }
}
