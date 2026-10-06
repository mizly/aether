package dev.aether.ui.settings;

// button that runs a runnable when clicked
public class ActionSetting extends AbstractSetting<ActionSetting> {

    private final Runnable action;

    public ActionSetting(String name, Runnable action) {
        super(name);
        this.action = action;
    }

    public void execute() {
        if (action != null) action.run();
    }

    @Override public SettingType getType() { return SettingType.ACTION; }
}
