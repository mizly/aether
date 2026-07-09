package dev.aether.modules.profit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProfitPersistence {
    private static final File LIFETIME_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("aether_profit_lifetime.json").toFile();
    private static final File DAILY_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("aether_profit_daily.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type COUNTS_TYPE = new TypeToken<Map<String, Long>>() {
    }.getType();

    public ProfitPersistence() {
    }

    public void saveLifetime(Map<String, Long> lifetimeCounts) {
        try (FileWriter writer = new FileWriter(LIFETIME_FILE)) {
            GSON.toJson(lifetimeCounts, writer);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    public void loadLifetime(Map<String, Long> lifetimeCounts, Runnable onLoaded) {
        if (!LIFETIME_FILE.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(LIFETIME_FILE)) {
            Map<String, Long> data = GSON.fromJson(reader, COUNTS_TYPE);
            if (data != null) {
                lifetimeCounts.clear();
                lifetimeCounts.putAll(data);
                onLoaded.run();
            }
        } catch (Exception e) {
            System.err.println("[Aether] Failed to load lifetime profit data: " + e.getMessage());
        }
    }

    public void saveDaily(Map<String, Long> dailyCounts, long sprayQuantity, String resetDate) {
        try (FileWriter writer = new FileWriter(DAILY_FILE)) {
            JsonObject data = new JsonObject();
            JsonObject countsObject = new JsonObject();
            for (Map.Entry<String, Long> entry : dailyCounts.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    countsObject.addProperty(entry.getKey(), entry.getValue());
                }
            }
            data.add("counts", countsObject);
            data.addProperty("sprayQuantity", sprayQuantity);
            data.addProperty("resetDate", resetDate);
            GSON.toJson(data, writer);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    public DailySnapshot loadDaily() {
        if (!DAILY_FILE.exists()) {
            return null;
        }

        try (FileReader reader = new FileReader(DAILY_FILE)) {
            JsonObject data = GSON.fromJson(reader, JsonObject.class);
            if (data == null) {
                return null;
            }

            Map<String, Long> counts = new LinkedHashMap<>();
            JsonElement countsElement = data.get("counts");
            if (countsElement != null && countsElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : countsElement.getAsJsonObject().entrySet()) {
                    if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                        try {
                            counts.put(entry.getKey(), entry.getValue().getAsLong());
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            long sprayQuantity = 0L;
            JsonElement sprayElement = data.get("sprayQuantity");
            if (sprayElement != null && !sprayElement.isJsonNull()) {
                try {
                    sprayQuantity = sprayElement.getAsLong();
                } catch (Exception ignored) {
                }
            }

            String resetDate = getCurrentDateString();
            JsonElement resetDateElement = data.get("resetDate");
            if (resetDateElement != null && !resetDateElement.isJsonNull()) {
                try {
                    resetDate = resetDateElement.getAsString();
                } catch (Exception ignored) {
                }
            }

            return new DailySnapshot(
                    counts,
                    sprayQuantity,
                    resetDate);
        } catch (Exception e) {
            System.err.println("[Aether] Failed to load daily profit data: " + e.getMessage());
            return null;
        }
    }

    public String getCurrentDateString() {
        return LocalDate.now().toString();
    }

    public static record DailySnapshot(Map<String, Long> counts, long sprayQuantity, String resetDate) {
    }

}
