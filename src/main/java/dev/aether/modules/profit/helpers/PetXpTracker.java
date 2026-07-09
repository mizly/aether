package dev.aether.modules.profit.helpers;

import dev.aether.config.PetRarity;
import dev.aether.config.PetInfo;
import dev.aether.config.AetherConfig;
import dev.aether.util.TablistUtils;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.aether.modules.profit.ProfitManager;

/**
 * Tracks Pet XP from the tab list based on configured pet name and rarity.
 */
public class PetXpTracker {
    private record TrackedPet(PetInfo info, Pattern pattern, long[] xpTable) {}

    private static final long[] XP_1_100_COMMON = {
            0L,
            0L, 100L, 210L, 330L, 460L, 605L, 765L, 940L, 1130L, 1340L,
            1570L, 1820L, 2095L, 2395L, 2725L, 3085L, 3485L, 3925L, 4415L, 4955L,
            5555L, 6215L, 6945L, 7745L, 8625L, 9585L, 10635L, 11785L, 13045L, 14425L,
            15935L, 17585L, 19385L, 21345L, 23475L, 25785L, 28285L, 30985L, 33905L, 37065L,
            40485L, 44185L, 48185L, 52535L, 57285L, 62485L, 68185L, 74485L, 81485L, 89285L,
            97985L, 107685L, 118485L, 130485L, 143785L, 158485L, 174685L, 192485L, 211985L, 233285L,
            256485L, 281685L, 309085L, 338885L, 371285L, 406485L, 444685L, 486085L, 530885L, 579285L,
            631485L, 687685L, 748085L, 812885L, 882285L, 956485L, 1035685L, 1120385L, 1211085L, 1308285L,
            1412485L, 1524185L, 1643885L, 1772085L, 1909285L, 2055985L, 2212685L, 2380385L, 2560085L, 2752785L,
            2959485L, 3181185L, 3418885L, 3673585L, 3946285L, 4237985L, 4549685L, 4883385L, 5241085L, 5624785L,
    };

    private static final long[] XP_1_100_UNCOMMON = {
            0L,
            0L, 175L, 365L, 575L, 805L, 1055L, 1330L, 1630L, 1960L, 2320L,
            2720L, 3160L, 3650L, 4190L, 4790L, 5450L, 6180L, 6980L, 7860L, 8820L,
            9870L, 11020L, 12280L, 13660L, 15170L, 16820L, 18620L, 20580L, 22710L, 25020L,
            27520L, 30220L, 33140L, 36300L, 39720L, 43420L, 47420L, 51770L, 56520L, 61720L,
            67420L, 73720L, 80720L, 88520L, 97220L, 106920L, 117720L, 129720L, 143020L, 157720L,
            173920L, 191720L, 211220L, 232520L, 255720L, 280920L, 308320L, 338120L, 370520L, 405720L,
            443920L, 485320L, 530120L, 578520L, 630720L, 686920L, 747320L, 812120L, 881520L, 955720L,
            1034920L, 1119620L, 1210320L, 1307520L, 1411720L, 1523420L, 1643120L, 1771320L, 1908520L, 2055220L,
            2211920L, 2379620L, 2559320L, 2752020L, 2958720L, 3180420L, 3418120L, 3672820L, 3945520L, 4237220L,
            4548920L, 4882620L, 5240320L, 5624020L, 6035720L, 6477420L, 6954120L, 7470820L, 8032520L, 8644220L,
    };

    private static final long[] XP_1_100_RARE = {
            0L,
            0L, 275L, 575L, 905L, 1265L, 1665L, 2105L, 2595L, 3135L, 3735L,
            4395L, 5125L, 5925L, 6805L, 7765L, 8815L, 9965L, 11225L, 12605L, 14115L,
            15765L, 17565L, 19525L, 21655L, 23965L, 26465L, 29165L, 32085L, 35245L, 38665L,
            42365L, 46365L, 50715L, 55465L, 60665L, 66365L, 72665L, 79665L, 87465L, 96165L,
            105865L, 116665L, 128665L, 141965L, 156665L, 172865L, 190665L, 210165L, 231465L, 254665L,
            279865L, 307265L, 337065L, 369465L, 404665L, 442865L, 484265L, 529065L, 577465L, 629665L,
            685865L, 746265L, 811065L, 880465L, 954665L, 1033865L, 1118565L, 1209265L, 1306465L, 1410665L,
            1522365L, 1642065L, 1770265L, 1907465L, 2054165L, 2210865L, 2378565L, 2558265L, 2750965L, 2957665L,
            3179365L, 3417065L, 3671765L, 3944465L, 4236165L, 4547865L, 4881565L, 5239265L, 5622965L, 6034665L,
            6476365L, 6953065L, 7469765L, 8031465L, 8643165L, 9309865L, 10036565L, 10828265L, 11689965L, 12626665L,
    };

    private static final long[] XP_1_100_EPIC = {
            0L,
            0L, 440L, 930L, 1470L, 2070L, 2730L, 3460L, 4260L, 5140L, 6100L,
            7150L, 8300L, 9560L, 10940L, 12450L, 14100L, 15900L, 17860L, 19990L, 22300L,
            24800L, 27500L, 30420L, 33580L, 37000L, 40700L, 44700L, 49050L, 53800L, 59000L,
            64700L, 71000L, 78000L, 85800L, 94500L, 104200L, 115000L, 127000L, 140300L, 155000L,
            171200L, 189000L, 208500L, 229800L, 253000L, 278200L, 305600L, 335400L, 367800L, 403000L,
            441200L, 482600L, 527400L, 575800L, 628000L, 684200L, 744600L, 809400L, 878800L, 953000L,
            1032200L, 1116900L, 1207600L, 1304800L, 1409000L, 1520700L, 1640400L, 1768600L, 1905800L, 2052500L,
            2209200L, 2376900L, 2556600L, 2749300L, 2956000L, 3177700L, 3415400L, 3670100L, 3942800L, 4234500L,
            4546200L, 4879900L, 5237600L, 5621300L, 6033000L, 6474700L, 6951400L, 7468100L, 8029800L, 8641500L,
            9308200L, 10034900L, 10826600L, 11688300L, 12625000L, 13641700L, 14743400L, 15935100L, 17221800L, 18608500L,
    };

    private static final long[] XP_1_200_LEGENDARY = {
            0L,
            0L, 660L, 1390L, 2190L, 3070L, 4030L, 5080L, 6230L, 7490L, 8870L,
            10380L, 12030L, 13830L, 15790L, 17920L, 20230L, 22730L, 25430L, 28350L, 31510L,
            34930L, 38630L, 42630L, 46980L, 51730L, 56930L, 62630L, 68930L, 75930L, 83730L,
            92430L, 102130L, 112930L, 124930L, 138230L, 152930L, 169130L, 186930L, 206430L, 227730L,
            250930L, 276130L, 303530L, 333330L, 365730L, 400930L, 439130L, 480530L, 525330L, 573730L,
            625930L, 682130L, 742530L, 807330L, 876730L, 950930L, 1030130L, 1114830L, 1205530L, 1302730L,
            1406930L, 1518630L, 1638330L, 1766530L, 1903730L, 2050430L, 2207130L, 2374830L, 2554530L, 2747230L,
            2953930L, 3175630L, 3413330L, 3668030L, 3940730L, 4232430L, 4544130L, 4877830L, 5235530L, 5619230L,
            6030930L, 6472630L, 6949330L, 7466030L, 8027730L, 8639430L, 9306130L, 10032830L, 10824530L, 11686230L,
            12622930L, 13639630L, 14741330L, 15933030L, 17219730L, 18606430L, 20103130L, 21719830L, 23466530L,
            25353230L,
            25353230L, 25358785L, 27245485L, 29132185L, 31018885L, 32905585L, 34792285L, 36678985L, 38565685L,
            40452385L,
            42339085L, 44225785L, 46112485L, 47999185L, 49885885L, 51772585L, 53659285L, 55545985L, 57432685L,
            59319385L,
            61206085L, 63092785L, 64979485L, 66866185L, 68752885L, 70639585L, 72526285L, 74412985L, 76299685L,
            78186385L,
            80073085L, 81959785L, 83846485L, 85733185L, 87619885L, 89506585L, 91393285L, 93279985L, 95166685L,
            97053385L,
            98940085L, 100826785L, 102713485L, 104600185L, 106486885L, 108373585L, 110260285L, 112146985L, 114033685L,
            115920385L,
            117807085L, 119693785L, 121580485L, 123467185L, 125353885L, 127240585L, 129127285L, 131013985L, 132900685L,
            134787385L,
            136674085L, 138560785L, 140447485L, 142334185L, 144220885L, 146107585L, 147994285L, 149880985L, 151767685L,
            153654385L,
            155541085L, 157427785L, 159314485L, 161201185L, 163087885L, 164974585L, 166861285L, 168747985L, 170634685L,
            172521385L,
            174408085L, 176294785L, 178181485L, 180068185L, 181954885L, 183841585L, 185728285L, 187614985L, 189501685L,
            191388385L,
            193275085L, 195161785L, 197048485L, 198935185L, 200821885L, 202708585L, 204595285L, 206481985L, 208368685L,
            210255385L
    };

    private static final Pattern MAX_LEVEL_PATTERN = Pattern.compile("MAX\\s*LEVEL", Pattern.CASE_INSENSITIVE);
    private static final Pattern XP_VALUE_PATTERN = Pattern.compile("([\\d,]+(?:\\.\\d+)?)\\s*/[\\d,.]+[KkMmBb]?\\s*XP",
            Pattern.CASE_INSENSITIVE);

    private static java.util.Map<String, Long> lastPetXp = new java.util.HashMap<>();
    private static List<String> cachedPetTrackerConfig = List.of();
    private static List<TrackedPet> trackedPets = List.of();

    public static void reset() {
        lastPetXp.clear();
    }

    public static boolean hasTrackedPetsConfigured() {
        refreshTrackedPets();
        return !trackedPets.isEmpty();
    }

    public static long[] getXpTable(PetRarity rarity, int maxLevel) {
        long[] base;
        switch (rarity) {
            case COMMON:
                base = XP_1_100_COMMON;
                break;
            case UNCOMMON:
                base = XP_1_100_UNCOMMON;
                break;
            case RARE:
                base = XP_1_100_RARE;
                break;
            case EPIC:
                base = XP_1_100_EPIC;
                break;
            case LEGENDARY:
            case MYTHIC:
            default:
                base = XP_1_200_LEGENDARY;
                break;
        }

        if (maxLevel <= 100 && base.length >= 101) {
            long[] table = new long[maxLevel + 1];
            System.arraycopy(base, 0, table, 0, Math.min(base.length, maxLevel + 1));
            return table;
        }

        if (maxLevel > 100) {
            long[] table = new long[maxLevel + 1];
            // Fill 1-100 from rarity base
            System.arraycopy(base, 0, table, 0, 101);
            // Fill 101-maxLevel using Legendary curve delta
            long base100 = base[100];
            long leg100 = XP_1_200_LEGENDARY[100];
            for (int i = 101; i <= maxLevel; i++) {
                if (i < XP_1_200_LEGENDARY.length) {
                    table[i] = base100 + (XP_1_200_LEGENDARY[i] - leg100);
                } else {
                    table[i] = table[i - 1]; // Cap if out of bounds
                }
            }
            return table;
        }

        return base;
    }

    public static void update(Minecraft client) {
        if (client.getConnection() == null)
            return;

        refreshTrackedPets();
        if (trackedPets.isEmpty()) {
            return;
        }

        List<String> tabLines = TablistUtils.getTabLines(client);

        PetInfo activePet = null;
        long[] activeXpTable = null;
        int detectedLevel = -1;
        int petLineIndex = -1;

        // Iterate through all tracked pets to find which one is active in tab list
        for (TrackedPet trackedPet : trackedPets) {
            for (int i = 0; i < tabLines.size(); i++) {
                Matcher m = trackedPet.pattern().matcher(tabLines.get(i));
                if (m.find()) {
                    detectedLevel = Integer.parseInt(m.group(1));
                    petLineIndex = i;
                    activePet = trackedPet.info();
                    activeXpTable = trackedPet.xpTable();
                    break;
                }
            }
            if (activePet != null)
                break;
        }

        if (activePet == null || detectedLevel < 1 || detectedLevel > activePet.maxLevel)
            return;

        for (int i = petLineIndex + 1; i < tabLines.size(); i++) {
            if (MAX_LEVEL_PATTERN.matcher(tabLines.get(i)).find()) {
                // Ignore max level pets
                return;
            }
        }

        double currentXpInLevel = -1;
        for (String line : tabLines) {
            Matcher m = XP_VALUE_PATTERN.matcher(line);
            if (m.find()) {
                try {
                    currentXpInLevel = Double.parseDouble(m.group(1).replace(",", ""));
                    break;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (currentXpInLevel < 0)
            return;

        long absoluteXp = activeXpTable[detectedLevel] + (long) currentXpInLevel;
        long previousXp = lastPetXp.getOrDefault(activePet.name, -1L);

        if (previousXp >= 0 && absoluteXp > previousXp) {
            long delta = absoluteXp - previousXp;
            if (delta < 2_000_000L) {
                ProfitManager.addPetXp(activePet.name, delta);
            }
        }
        lastPetXp.put(activePet.name, absoluteXp);
    }

    private static void refreshTrackedPets() {
        List<String> config = new ArrayList<>(AetherConfig.PET_TRACKER_LIST.get());
        if (config.equals(cachedPetTrackerConfig)) {
            return;
        }

        List<TrackedPet> rebuilt = new ArrayList<>(config.size());
        for (String petConfig : config) {
            PetInfo info = new PetInfo(petConfig);
            rebuilt.add(new TrackedPet(
                    info,
                    Pattern.compile("\\[Lvl\\s*(\\d+)]\\s*" + Pattern.quote(info.name), Pattern.CASE_INSENSITIVE),
                    getXpTable(info.rarity, info.maxLevel)));
        }

        cachedPetTrackerConfig = List.copyOf(config);
        trackedPets = List.copyOf(rebuilt);
    }
}
