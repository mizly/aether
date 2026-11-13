package dev.aether.modules.pest.helpers

import java.util.ArrayList
import java.util.regex.Pattern

internal class PestTrapTabParser private constructor() {
    companion object {
        private val TRAP_ID_PATTERN = Pattern.compile("#(\\d+)")

        @JvmStatic fun parseTrapIds(lines: List<String>, headingPattern: Pattern): List<Int> {
            val trapIds = ArrayList<Int>()
            for (line in lines) {
                val heading = headingPattern.matcher(line)
                if (!heading.find()) continue
                val idMatcher = TRAP_ID_PATTERN.matcher(heading.group(1))
                while (idMatcher.find()) trapIds.add(Integer.parseInt(idMatcher.group(1)))
            }
            return trapIds
        }
    }
}
