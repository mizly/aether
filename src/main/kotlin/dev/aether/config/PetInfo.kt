package dev.aether.config

class PetInfo(config: String) {
    var tag: String
    var name: String
    var maxLevel: Int
    var level1Price: Long
    var maxLevelPrice: Long
    var rarity: PetRarity

    init {
        val parts = config.split(":")
        if (parts.size >= 5) {
            tag = ""
            name = capitalizeWords(parts[0].trim())
            maxLevel = parts[1].trim().toIntOrNull() ?: 100
            level1Price = parts[2].trim().replace(",", "").toLongOrNull() ?: 0L
            maxLevelPrice = parts[3].trim().replace(",", "").toLongOrNull() ?: 0L
            rarity = parseRarity(parts[4])
        } else if (parts.size >= 4) {
            tag = parts[0].trim()
            name = capitalizeWords(parts[1].trim())
            maxLevel = parts[2].trim().toIntOrNull() ?: 100
            level1Price = 0L
            maxLevelPrice = 0L
            rarity = parseRarity(parts[3])
        } else {
            tag = "UNKNOWN"
            name = "Unknown Pet"
            maxLevel = 100
            rarity = PetRarity.LEGENDARY
            level1Price = 0L
            maxLevelPrice = 0L
        }
    }

    private fun capitalizeWords(input: String): String = input.split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase).drop(1).lowercase().let { rest -> it.replaceFirstChar(Char::uppercase) } }

    override fun toString(): String = "$name:$maxLevel:$level1Price:$maxLevelPrice:$rarity"

    private fun parseRarity(value: String): PetRarity =
        runCatching { PetRarity.valueOf(value.trim().uppercase()) }.getOrDefault(PetRarity.LEGENDARY)
}
