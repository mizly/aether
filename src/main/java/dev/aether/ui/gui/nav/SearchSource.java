package dev.aether.ui.gui.nav;

import dev.aether.ui.gui.Icon;

import java.util.List;
import java.util.function.Consumer;

// feeds search candidates from live objects; called again for every query, so it must be cheap and must
// skip anything not visible right now
@FunctionalInterface
public interface SearchSource {
    void collect(NavModel model, Consumer<Candidate> out);

    // terms are matched like the title (raw english names, aliases); description only scores as DESCRIPTION.
    // mirrorOf is the canonical page id when this is a copy of a knob owned elsewhere; rawName pairs the
    // copy with its canonical result
    record Candidate(SearchResult.Kind kind, String title, List<String> terms, String description, List<String> path,
                     Icon icon, Runnable action, Object key, String pageId, String rawName, String mirrorOf) {
        public Candidate {
            terms = List.copyOf(terms);
            path = List.copyOf(path);
        }
    }
}
