package dev.aether.ui.gui.preview;

import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.LaunchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// one scripted gui state to render: a name for the png, a layout size in units (one pixel each) and the
// steps that lead there, e.g. Scenario.of("search", 1280, 800).key(ctrlF).type("pest").advance(300)
public record Scenario(String name, int width, int height, List<Consumer<PreviewSession>> steps) {
    public Scenario {
        steps = List.copyOf(steps);
    }

    public static Scenario of(String name, int width, int height) {
        return new Scenario(name, width, height, List.of());
    }

    public Scenario then(Consumer<PreviewSession> step) {
        List<Consumer<PreviewSession>> next = new ArrayList<>(steps);
        next.add(step);
        return new Scenario(name, width, height, next);
    }

    public Scenario navigate(LaunchRequest launch) {
        return then(session -> session.navigate(launch));
    }

    public Scenario hover(float x, float y) {
        return then(session -> session.hover(x, y));
    }

    public Scenario click(float x, float y) {
        return then(session -> session.click(x, y));
    }

    public Scenario drag(float fromX, float fromY, float toX, float toY) {
        return then(session -> session.drag(fromX, fromY, toX, toY));
    }

    public Scenario scroll(float x, float y, double dy) {
        return then(session -> session.scroll(x, y, dy));
    }

    public Scenario key(KeyInput key) {
        return then(session -> session.key(key));
    }

    public Scenario type(String text) {
        return then(session -> session.type(text));
    }

    public Scenario advance(long millis) {
        return then(session -> session.advance(millis));
    }
}
