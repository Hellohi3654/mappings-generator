package org.geysermc.generator;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.MiningToolItem;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.DyeColor;
import org.geysermc.generator.state.StateMapper;
import org.geysermc.generator.state.StateRemapper;
import org.reflections.Reflections;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MappingsGenerator {

    public static final Map<String, BlockEntry> BLOCK_ENTRIES = new HashMap<>();
    public static final Map<String, ItemEntry> ITEM_ENTRIES = new HashMap<>();
    public static final Map<String, SoundEntry> SOUND_ENTRIES = new HashMap<>();
    public static final Map<String, Integer> RUNTIME_ITEM_IDS = new HashMap<>();
    public static final Map<String, List<String>> STATES = new HashMap<>();
    private static final List<MiningToolItem> MINING_TOOL_ITEMS = new ArrayList<>();
    private static final List<String> POTTABLE_BLOCK_IDENTIFIERS = Arrays.asList("minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid", "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip", "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley", "minecraft:wither_rose", "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling", "minecraft:jungle_sapling", "minecraft:acacia_sapling", "minecraft:dark_oak_sapling", "minecraft:red_mushroom", "minecraft:brown_mushroom", "minecraft:fern", "minecraft:dead_bush", "minecraft:cactus", "minecraft:bamboo", "minecraft:crimson_fungus", "minecraft:warped_fungus", "minecraft:crimson_roots", "minecraft:warped_roots");

    private static final Gson GSON = new Gson();

    private final Multimap<String, StateMapper<?>> stateMappers = HashMultimap.create();

    public void generateBlocks() {
        Reflections ref = new Reflections("org.geysermc.generator.state.type");
        for (Class<?> clazz : ref.getTypesAnnotatedWith(StateRemapper.class)) {
            try {
                StateMapper<?> stateMapper = (StateMapper<?>) clazz.newInstance();
                this.stateMappers.put(clazz.getAnnotation(StateRemapper.class).value(), stateMapper);
            } catch (InstantiationException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        try {
            File mappings = new File("mappings/blocks.json");
            File blockPalette = new File("palettes/runtime_block_states.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }
            if (!blockPalette.exists()) {
                System.out.println("Could not find item palette (runtime_block_states.json), please refer to the README in the palettes directory.");
                return;
            }

            try {
                Type mapType = new TypeToken<Map<String, BlockEntry>>() {}.getType();
                Map<String, BlockEntry> map = GSON.fromJson(new FileReader(mappings), mapType);
                BLOCK_ENTRIES.putAll(map);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            try {
                JsonArray stateArray = (JsonArray) new JsonParser().parse(readFile("palettes/runtime_block_states.json", StandardCharsets.UTF_8));
                stateArray.forEach(e -> {
                    JsonObject object = (JsonObject) e;
                    String identifier = object.get("name").getAsString();
                    if (!STATES.containsKey(identifier)) {
                        JsonObject states = object.getAsJsonObject("states");
                        List<String> stateKeys = states.entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toList());
                        // ignore some useless keys
                        stateKeys.remove("stone_slab_type");
                        STATES.put(identifier, stateKeys);
                    }
                });
                // Some State Corrections
                STATES.put("minecraft:attached_pumpkin_stem", Arrays.asList("growth", "facing_direction"));
                STATES.put("minecraft:attached_melon_stem", Arrays.asList("growth", "facing_direction"));
            } catch (IOException ex) {
                ex.printStackTrace();
            }

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            FileWriter writer = new FileWriter(mappings);
            JsonObject rootObject = new JsonObject();

            for (BlockState blockState : getFullBlockDataList()) {
                rootObject.add(blockStateToString(blockState), getRemapBlock(blockState, blockStateToString(blockState)));
            }

            builder.create().toJson(rootObject, writer);
            writer.close();
            System.out.println("Some block states need to be manually mapped, please search for MANUALMAP in blocks.json, if there are no occurrences you do not need to do anything.");
            System.out.println("Finished block writing process!");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void generateItems() {
        try {
            File mappings = new File("mappings/items.json");
            File itemPalette = new File("palettes/runtime_item_states.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }
            if (!itemPalette.exists()) {
                System.out.println("Could not find item palette (runtime_item_states.json), please refer to the README in the palettes directory.");
                return;
            }

            try {
                Type mapType = new TypeToken<Map<String, ItemEntry>>() {}.getType();
                Map<String, ItemEntry> map = GSON.fromJson(new FileReader(mappings), mapType);
                ITEM_ENTRIES.putAll(map);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            try {
                Type listType = new TypeToken<List<PaletteItemEntry>>(){}.getType();
                List<PaletteItemEntry> entries = GSON.fromJson(new FileReader(itemPalette), listType);
                entries.forEach(item -> RUNTIME_ITEM_IDS.put(item.getIdentifier(), item.getLegacy_id()));
                // Fix some discrepancies - identifier is the Java string and ID is the Bedrock number ID
                RUNTIME_ITEM_IDS.put("minecraft:grass", 31); // Conflicts with grass block
                RUNTIME_ITEM_IDS.put("minecraft:snow", 78); // Conflicts with snow block
                RUNTIME_ITEM_IDS.put("minecraft:melon", 103);
                RUNTIME_ITEM_IDS.put("minecraft:shulker_box", 205);
                RUNTIME_ITEM_IDS.put("minecraft:nether_brick", 405); // Conflicts with nether brick block
                RUNTIME_ITEM_IDS.put("minecraft:stone_stairs", -180); // Conflicts with cobblestone stairs
                RUNTIME_ITEM_IDS.put("minecraft:stonecutter", -197); // Conflicts with, surprisingly, the OLD MCPE stonecutter
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            FileWriter writer = new FileWriter(mappings);
            JsonObject rootObject = new JsonObject();

            for (Identifier key : Registry.ITEM.getIds()) {
                Optional<Item> item = Registry.ITEM.getOrEmpty(key);
                if (item.isPresent()) {
                    if (item.get() instanceof MiningToolItem) {
                        MiningToolItem miningToolItem = (MiningToolItem) item.get();
                        MINING_TOOL_ITEMS.add(miningToolItem);
                    }
                    rootObject.add(key.getNamespace() + ":" + key.getPath(), getRemapItem(key.getNamespace() + ":" + key.getPath(), Block.getBlockFromItem(item.get()) != Blocks.AIR));
                }
            }

            builder.create().toJson(rootObject, writer);
            writer.close();
            System.out.println("Finished item writing process!");

            // Check for duplicate mappings
            Set<JsonElement> itemDuplicateCheck = new HashSet<>();
            for (Map.Entry<String, JsonElement> object : rootObject.entrySet()) {
                if (!itemDuplicateCheck.add(object.getValue())) {
                    System.out.println("Possible duplicate item (" + object.getKey() + ") in mappings: " + object.getValue());
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void generateSounds() {
        try {
            File mappings = new File("mappings/sounds.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }

            try {
                Type mapType = new TypeToken<Map<String, SoundEntry>>() {}.getType();
                Map<String, SoundEntry> map = GSON.fromJson(new FileReader(mappings), mapType);
                SOUND_ENTRIES.putAll(map);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            FileWriter writer = new FileWriter(mappings);
            JsonObject rootObject = new JsonObject();

            for (Identifier key : Registry.SOUND_EVENT.getIds()) {
                Optional<SoundEvent> sound = Registry.SOUND_EVENT.getOrEmpty(key);
                sound.ifPresent(soundEvent -> {
                    SoundEntry soundEntry = SOUND_ENTRIES.get(soundEvent.getId().getPath());
                    if (soundEntry == null) {
                        soundEntry = new SoundEntry(soundEvent.getId().getPath(), "", -1, null, false);
                    }
                    JsonObject object = (JsonObject) GSON.toJsonTree(soundEntry);
                    if (soundEntry.getExtraData() <= 0 && !soundEvent.getId().getPath().equals("block.note_block.harp")) {
                        object.remove("extra_data");
                    }
                    if (soundEntry.getIdentifier() == null || soundEntry.getIdentifier().isEmpty()) {
                        object.remove("identifier");
                    }
                    if (!soundEntry.isLevelEvent()) {
                        object.remove("level_event");
                    }
                    rootObject.add(key.getPath(), object);
                });
            }

            builder.create().toJson(rootObject, writer);
            writer.close();
            System.out.println("Finished sound writing process!");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public JsonObject getRemapBlock(BlockState state, String identifier) {
        JsonObject object = new JsonObject();
        BlockEntry blockEntry = null;
        String trimmedIdentifier = identifier.split("\\[")[0];
        if (BLOCK_ENTRIES.containsKey(identifier)) {
            blockEntry = BLOCK_ENTRIES.get(identifier);
            // All walls before 1.16 use the same identifier (cobblestone_wall)
            if (trimmedIdentifier.endsWith("_wall") && !trimmedIdentifier.contains("blackstone")) {
                object.addProperty("bedrock_identifier", "minecraft:cobblestone_wall");
            } else {
                object.addProperty("bedrock_identifier", blockEntry.getBedrockIdentifier());
            }
            object.addProperty("block_hardness", state.getHardness(null, null));
            object.addProperty("piston_behavior", state.getPistonBehavior().toString().toLowerCase());

            object.addProperty("can_break_with_hand", !state.isToolRequired());
            MINING_TOOL_ITEMS.forEach(item -> {
                if (item.getMiningSpeedMultiplier(null, state) != 1.0f) {
                    String itemClassName = item.getClass().getName();
                    String toolType = itemClassName.substring(19, itemClassName.length() -4);
                    object.addProperty("tool_type", toolType.toLowerCase());
                }
            });
            // Removes nbt tags from identifier
            // Add tool type for blocks that use shears or sword
            if (trimmedIdentifier.contains("wool")) {
                object.addProperty("tool_type", "shears");
            } else if (trimmedIdentifier.contains("leaves")) {
                object.addProperty("tool_type", "shears");
            } else if (trimmedIdentifier.contains("cobweb")) {
                object.addProperty("tool_type", "sword");
            } else if (trimmedIdentifier.contains("_bed")) {
                String woolid = trimmedIdentifier.replace("minecraft:", "");
                woolid = woolid.split("_bed")[0].toUpperCase();
                object.addProperty("bed_color", DyeColor.valueOf(woolid).getId());
            } else if (trimmedIdentifier.contains("head") && !trimmedIdentifier.contains("piston") || trimmedIdentifier.contains("skull")) {
                if (!trimmedIdentifier.contains("wall")) {
                    int rotationId = Integer.parseInt(identifier.substring(identifier.indexOf("rotation=") + 9, identifier.indexOf("]")));
                    object.addProperty("skull_rotation", rotationId);
                }
                if (trimmedIdentifier.contains("wither_skeleton")) {
                    object.addProperty("variation", 1);
                } else if (trimmedIdentifier.contains("skeleton")) {
                    object.addProperty("variation", 0);
                } else if (trimmedIdentifier.contains("zombie")) {
                    object.addProperty("variation", 2);
                } else if (trimmedIdentifier.contains("player")) {
                    object.addProperty("variation", 3);
                } else if (trimmedIdentifier.contains("creeper")) {
                    object.addProperty("variation", 4);
                } else if (trimmedIdentifier.contains("dragon")) {
                    object.addProperty("variation", 5);
                }
            } else if (trimmedIdentifier.contains("_banner")) {
                String woolid = trimmedIdentifier.replace("minecraft:", "");
                woolid = woolid.split("_banner")[0].split("_wall")[0].toUpperCase();
                object.addProperty("banner_color", DyeColor.valueOf(woolid).getId());
            } else if (trimmedIdentifier.contains("note_block")) {
                int notepitch = Integer.parseInt(identifier.substring(identifier.indexOf("note=") + 5, identifier.indexOf(",powered")));
                object.addProperty("note_pitch", notepitch);
            } else if (trimmedIdentifier.contains("shulker_box")) {
                object.addProperty("shulker_direction", getDirectionInt(identifier.substring(identifier.indexOf("facing=") + 7, identifier.indexOf("]"))));
            } else if (trimmedIdentifier.contains("chest") && (identifier.contains("type="))) {
                if (identifier.contains("type=left")) {
                    object.addProperty("double_chest_position", "left");
                } else if (identifier.contains("type=right")) {
                    object.addProperty("double_chest_position", "right");
                }
                if (identifier.contains("north")) {
                    object.addProperty("z", false);
                } else if (identifier.contains("south")) {
                    object.addProperty("z", true);
                } else if (identifier.contains("east")) {
                    object.addProperty("x", true);
                } else if (identifier.contains("west")) {
                    object.addProperty("x", false);
                }
            } else if (trimmedIdentifier.endsWith("_slab") && identifier.contains("type=double") && !blockEntry.getBedrockIdentifier().contains("double")) {
                // Fixes 1.16 double slabs
                object.addProperty("bedrock_identifier", blockEntry.getBedrockIdentifier().replace("_slab", "_double_slab"));
            }
        } else {
            // All walls before 1.16 use the same identifier (cobblestone_wall)
            if (trimmedIdentifier.endsWith("_wall") && !trimmedIdentifier.contains("blackstone")) {
                object.addProperty("bedrock_identifier", "minecraft:cobblestone_wall");
            } else {
                object.addProperty("bedrock_identifier", trimmedIdentifier);
            }
        }

        JsonElement bedrockStates = blockEntry != null ? blockEntry.getBedrockStates() : null;
        if (bedrockStates == null) {
            bedrockStates = new JsonObject();
        }

        JsonObject statesObject = bedrockStates.getAsJsonObject();
        String[] states = identifier.substring(identifier.lastIndexOf("[") + 1).replace("]", "").split(",");
        for (String javaState : states) {
            String key = javaState.split("=")[0];
            if (!this.stateMappers.containsKey(key)) {
                continue;
            }
            Collection<StateMapper<?>> stateMappers = this.stateMappers.get(key);

            stateLoop:
            for (StateMapper<?> stateMapper : stateMappers) {
                String[] blockRegex = stateMapper.getClass().getAnnotation(StateRemapper.class).blockRegex();
                if (blockRegex.length != 0) {
                    for (String regex : blockRegex) {
                        if (!trimmedIdentifier.matches(regex)) {
                            continue stateLoop;
                        }
                    }
                }
                String value = javaState.split("=")[1];
                Pair<String, ?> bedrockState = stateMapper.translateState(identifier, value);
                if (statesObject.has(bedrockState.getKey())) {
                    if (!statesObject.get(bedrockState.getKey()).toString().equals("\"MANUALMAP\"")) {
                        continue;
                    }
                }
                if (bedrockState.getValue() instanceof Number) {
                    statesObject.addProperty(bedrockState.getKey(), StateMapper.asType(bedrockState, Number.class));
                }
                if (bedrockState.getValue() instanceof Boolean) {
                    statesObject.addProperty(bedrockState.getKey(), StateMapper.asType(bedrockState, Boolean.class));
                }
                if (bedrockState.getValue() instanceof String) {
                    statesObject.addProperty(bedrockState.getKey(), StateMapper.asType(bedrockState, String.class));
                }
            }
        }

        String stateIdentifier = trimmedIdentifier;
        if (trimmedIdentifier.endsWith("_wall") && !trimmedIdentifier.contains("blackstone")) {
            stateIdentifier = "minecraft:cobblestone_wall";
        }

        List<String> stateKeys = STATES.get(stateIdentifier);
        if (stateKeys != null) {
            stateKeys.forEach(key -> {
                if (trimmedIdentifier.contains("minecraft:shulker_box")) return;
                if (!statesObject.has(key)) {
                    statesObject.addProperty(key, "MANUALMAP");
                }
            });
        }

        // No more manual pottable because I'm angry I don't care how bad the list looks
        if (POTTABLE_BLOCK_IDENTIFIERS.contains(trimmedIdentifier)) {
            object.addProperty("pottable", true);
        }

        if (statesObject.entrySet().size() != 0) {
            if (statesObject.has("wall_block_type") && trimmedIdentifier.contains("blackstone")) {
                statesObject.getAsJsonObject().remove("wall_block_type");
            }
            object.add("bedrock_states", statesObject);
        }

        return object;
    }

    public JsonObject getRemapItem(String identifier, boolean isBlock) {
        JsonObject object = new JsonObject();
        if (ITEM_ENTRIES.containsKey(identifier)) {
            ItemEntry itemEntry = ITEM_ENTRIES.get(identifier);
            if (RUNTIME_ITEM_IDS.containsKey(identifier)) {
                object.addProperty("bedrock_id", RUNTIME_ITEM_IDS.get(identifier));
            } else {
                object.addProperty("bedrock_id", itemEntry.getBedrockId());
            }
            object.addProperty("bedrock_data", itemEntry.getBedrockData());
            object.addProperty("is_block", isBlock);
        } else {
            object.addProperty("bedrock_id", 248); // update block (missing mapping)
            object.addProperty("bedrock_data", 0);
        }
        String[] toolTypes = {"sword", "shovel", "pickaxe", "axe", "shears", "hoe"};
        String[] identifierSplit = identifier.split(":")[1].split("_");
        if (identifierSplit.length > 1) {
            Optional<String> optToolType = Arrays.stream(toolTypes).parallel().filter(identifierSplit[1]::equals).findAny();
            if (optToolType.isPresent()) {
                object.addProperty("tool_type", optToolType.get());
                object.addProperty("tool_tier", identifierSplit[0]);
            }
        } else {
            Optional<String> optToolType = Arrays.stream(toolTypes).parallel().filter(identifierSplit[0]::equals).findAny();
            optToolType.ifPresent(s -> object.addProperty("tool_type", s));
        }

        return object;
    }

    public List<BlockState> getFullBlockDataList() {
        List<BlockState> blockData = new ArrayList<>();
        Registry.BLOCK.forEach(material -> blockData.addAll(getBlockDataList(material)));
        return blockData.stream().sorted(Comparator.comparingInt(this::getID)).collect(Collectors.toList());
    }

    public List<BlockState> getBlockDataList(Block block) {
        return block.getStateManager().getStates();
    }

    public int getID(BlockState blockData) {
        return Block.getRawIdFromState(blockData);
    }

    private String blockStateToString(BlockState blockState) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(Registry.BLOCK.getId(blockState.getBlock()).toString());
        if (!blockState.getEntries().isEmpty()) {
            stringBuilder.append('[');
            stringBuilder.append(blockState.getEntries().entrySet().stream().map(PROPERTY_MAP_PRINTER).collect(Collectors.joining(",")));
            stringBuilder.append(']');
        }
        return stringBuilder.toString();
    }

    private static final Function<Map.Entry<Property<?>, Comparable<?>>, String> PROPERTY_MAP_PRINTER = new Function<Map.Entry<Property<?>, Comparable<?>>, String>() {

        public String apply(Map.Entry<Property<?>, Comparable<?>> entry) {
            if (entry == null) {
                return "<NULL>";
            } else {
                Property<?> lv = entry.getKey();
                return lv.getName() + "=" + this.nameValue(lv, entry.getValue());
            }
        }

        private <T extends Comparable<T>> String nameValue(Property<T> arg, Comparable<?> comparable) {
            return arg.name((T) comparable);
        }
    };

    /**
     * Converts a Java edition direction string to an byte for Bedrock edition
     * Designed for Shulker boxes, may work for other things
     *
     * @param direction The direction string
     * @return Converted direction byte
     */
    private static byte getDirectionInt(String direction) {
        switch (direction) {
            case "down":
                return 0;

            case "up":
                return 1;

            case "north":
                return 2;

            case "south":
                return 3;

            case "west":
                return 4;

            case "east":
                return 5;
        }

        return 1;
    }

    private static String readFile(String path, Charset encoding) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }
}
