package com.crispyland.agent.memory;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sorts tagged extractor output into layers. Pure Java, no model involved.
 * <p>
 * This is where the day's requirement — <em>explicitly choose what is saved where</em> — is
 * actually met. The extractor proposes a label; this decides the lifetime, using the fixed table
 * in {@link MemoryTag}. That division matters because the two halves fail differently: a
 * mislabelled line is one wrong entry the user can delete, whereas a model that picked the
 * destination could put the same kind of line somewhere different every turn, and no amount of
 * reading the code would tell you where anything lives.
 * <p>
 * Nothing here calls a store. The router returns what <em>would</em> be written and lets the
 * agent commit it, so routing can be tested without a filesystem and so a half-applied turn
 * cannot leave working memory updated while the long-term write is still pending.
 */
public class MemoryRouter {

    private static final Logger log = LoggerFactory.getLogger(MemoryRouter.class);

    /**
     * One line the extractor produced, before it has been given a home.
     *
     * @param tag the raw label as emitted, not yet resolved — kept verbatim so an unrecognised
     *            one can be logged as the model actually wrote it
     */
    public record Line(String tag, String key, String value) {
    }

    /**
     * Where a turn's worth of extraction is going.
     *
     * @param working  lines destined for the task's key/value block, settled ones flagged
     * @param longTerm lines destined for the visitor's permanent record
     * @param dropped  lines whose tag was not recognised and which were therefore kept nowhere
     */
    public record Routed(List<Facts.Fact> working, List<LongTermMemory.Entry> longTerm,
                         List<Line> dropped) {

        public static final Routed NOTHING = new Routed(List.of(), List.of(), List.of());

        public Routed {
            working = List.copyOf(working);
            longTerm = List.copyOf(longTerm);
            dropped = List.copyOf(dropped);
        }

        public boolean isEmpty() {
            return working.isEmpty() && longTerm.isEmpty();
        }
    }

    /**
     * Applies the routing table to a turn's extraction.
     * <p>
     * An unrecognised tag is dropped and logged rather than guessed at or defaulted. Refusing to
     * store something costs one turn of forgetfulness and the next message will usually say it
     * again; storing it under the wrong lifetime is not self-correcting, because the layer it
     * landed in is the layer that will still be replaying it in a month.
     */
    public Routed route(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return Routed.NOTHING;
        }
        List<Facts.Fact> working = new ArrayList<>();
        List<LongTermMemory.Entry> longTerm = new ArrayList<>();
        List<Line> dropped = new ArrayList<>();

        for (Line line : lines) {
            MemoryTag tag = MemoryTag.from(line.tag());
            if (tag == null) {
                dropped.add(line);
                log.warn("Dropped an extracted line tagged '{}' ({}) — not one of the {} tags the "
                                + "router routes, and nothing is filed on a guess.",
                        line.tag(), line.key(), MemoryTag.values().length);
                continue;
            }
            // NONE is a real answer, not a failure: the model read the message and found nothing
            // worth keeping. It has no destination, so there is nothing to do and nothing to warn about.
            if (tag.destination() == null) {
                continue;
            }
            switch (tag.destination()) {
                case WORKING -> working.add(
                        new Facts.Fact(line.key(), line.value(), tag.heldUntilTaskCloses()));
                case LONG_TERM -> longTerm.add(
                        new LongTermMemory.Entry(tag.promoteAs(), line.key(), line.value()));
                // Short-term is the transcript itself. Nothing is ever *routed* into it — it is
                // written by the act of having the conversation, which is what makes it the one
                // layer with no extraction cost at all.
                case SHORT_TERM -> dropped.add(line);
            }
        }
        return new Routed(working, longTerm, dropped);
    }
}
