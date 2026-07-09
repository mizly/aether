package dev.aether.config;

public class PetInfo {
    public String tag;
    public String name;
    public int maxLevel;
    public long level1Price;
    public long maxLevelPrice;
    public PetRarity rarity;

    public PetInfo(String config) {
        String[] parts = config.split(":");
        if (parts.length >= 5) {
            this.tag = "";
            this.name = capitalizeWords(parts[0].trim());
            try { this.maxLevel = Integer.parseInt(parts[1].trim()); }
            catch (NumberFormatException e) { this.maxLevel = 100; }
            try { this.level1Price = Long.parseLong(parts[2].trim().replace(",", "")); }
            catch (NumberFormatException e) { this.level1Price = 0L; }
            try { this.maxLevelPrice = Long.parseLong(parts[3].trim().replace(",", "")); }
            catch (NumberFormatException e) { this.maxLevelPrice = 0L; }
            try { this.rarity = PetRarity.valueOf(parts[4].trim().toUpperCase()); }
            catch (IllegalArgumentException e) { this.rarity = PetRarity.LEGENDARY; }
        } else if (parts.length >= 4) {
            this.tag = parts[0].trim();
            this.name = capitalizeWords(parts[1].trim());
            try { this.maxLevel = Integer.parseInt(parts[2].trim()); }
            catch (NumberFormatException e) { this.maxLevel = 100; }
            this.level1Price = 0L;
            this.maxLevelPrice = 0L;
            try { this.rarity = PetRarity.valueOf(parts[3].trim().toUpperCase()); }
            catch (IllegalArgumentException e) { this.rarity = PetRarity.LEGENDARY; }
        } else {
            this.tag = "UNKNOWN"; this.name = "Unknown Pet";
            this.maxLevel = 100; this.rarity = PetRarity.LEGENDARY;
            this.level1Price = 0L;
            this.maxLevelPrice = 0L;
        }
    }

    private String capitalizeWords(String input) {
        if (input == null || input.isEmpty()) return input;
        String[] words = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1).toLowerCase());
                sb.append(" ");
            }
        }
        return sb.toString().trim();
    }

    @Override public String toString() {
        return name + ":" + maxLevel + ":" + level1Price + ":" + maxLevelPrice + ":" + rarity;
    }
}
