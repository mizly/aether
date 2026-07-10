package dev.aether.modules.pest.helpers;

import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;

import java.util.regex.Pattern;

public class PestBonusManager {
    private static final Pattern PHILLIP_REACTIVATION_PATTERN = Pattern.compile(
            "^\\[NPC\\] Pesthunter Phillip: Thanks for the \\S+ Pests, [A-Za-z0-9_]{1,16}!$");
    private static final Pattern BONUS_INACTIVE_PATTERN = Pattern.compile("(?i)\\bbonus\\b.*\\binactive\\b");
    private static final Pattern BONUS_ACTIVE_PATTERN = Pattern.compile("(?i)\\bbonus\\b.*\\bactive\\b");

    public static volatile boolean isBonusInactive = false;
    public static volatile boolean isReactivatingBonus = false;

    public static void resetState() {
        isBonusInactive = false;
        isReactivatingBonus = false;
    }

    public static void updateFromTab() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getConnection() == null) {
            return;
        }

        Boolean bonusInactive = readBonusState(client);
        if (bonusInactive != null) {
            isBonusInactive = bonusInactive;
        }
    }

    public static Boolean parseBonusState(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String normalized = normalizeLine(text);
        if (BONUS_INACTIVE_PATTERN.matcher(normalized).find()) {
            return Boolean.TRUE;
        }
        if (BONUS_ACTIVE_PATTERN.matcher(normalized).find()) {
            return Boolean.FALSE;
        }
        return null;
    }

    public static void handlePhillipMessage(Minecraft client, String text, String currentInfestedPlot) {
        if (!isReactivatingBonus || client.player == null) {
            return;
        }

        String plain = text
                .replaceAll("(?i)\u00A7[0-9a-fk-or]", "")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (!PHILLIP_REACTIVATION_PATTERN.matcher(plain).matches()) {
            return;
        }

        ClientUtils.sendMessage("\u00A7aPhillip reactivation detected. Returning to plot \u00A7e" + currentInfestedPlot + "...",
                true);
        MacroWorkerThread.getInstance().submit("PhillipReactivation", () -> {
            try {
                if (MacroWorkerThread.shouldAbortTask(client)) {
                    return;
                }
                ClientUtils.sendDebugMessage("Disabling farming macro: Phillip reactivation detected");
                client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));
                MacroWorkerThread.sleep(ConfigHelpers.getRandomizedDelay(250));
                if (MacroWorkerThread.shouldAbortTask(client)) {
                    return;
                }
                ClientUtils.sendDebugMessage("PestDestroyer: starting after Phillip reactivation");
                client.execute(() -> PestDestroyer.start(client));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isReactivatingBonus = false;
            }
        });
    }

    private static Boolean readBonusState(Minecraft client) {
        for (String line : TablistUtils.getRawTabLines(client)) {
            Boolean parsed = parseBonusState(line);
            if (parsed != null) {
                return parsed;
            }
        }

        for (String line : TablistUtils.getTabLines(client)) {
            Boolean parsed = parseBonusState(line);
            if (parsed != null) {
                return parsed;
            }
        }

        return null;
    }

    private static String normalizeLine(String text) {
        return TablistUtils.stripColors(text)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
