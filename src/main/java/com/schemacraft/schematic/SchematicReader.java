package com.schemacraft.schematic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Lecture des fichiers .schem et .schematic (NBT compressé). Le format est détecté
 * d'après le contenu, pas l'extension (certains outils écrivent du Sponge en .schematic) :
 * <ul>
 * <li><b>Sponge v1/v2</b> (WorldEdit 7+, FAWE...) : Width/Height/Length,
 *     Palette { "minecraft:bloc[props]": id }, BlockData = varints.</li>
 * <li><b>Sponge v3</b> : racine { Schematic: { ..., Blocks: { Palette, Data } } }.</li>
 * <li><b>MCEdit / Schematica / WorldEdit &lt; 1.13</b> : Blocks (byte[]), Data (byte[], métadonnée),
 *     AddBlocks optionnel (4 bits de poids fort de l'id). Les ids numériques sont convertis avec
 *     la table {@code assets/schemacraft/legacy_blocks.txt}.</li>
 * </ul>
 * Dans tous les cas l'ordre des index est (y * length + z) * width + x, comme {@link Schematic}.
 * Les blocs-entités (contenu des coffres, texte des panneaux...) et entités sont ignorés.
 */
public final class SchematicReader {
	public static final String SCHEM = ".schem";
	public static final String SCHEMATIC = ".schematic";

	private static final long MAX_VOLUME = 64L * 1024 * 1024;

	/** Blocs renommés depuis 1.13 : utilisés seulement si l'ancien nom n'existe plus. */
	private static final Map<String, String> RENAMED = Map.of(
		"minecraft:grass", "minecraft:short_grass",
		"minecraft:grass_path", "minecraft:dirt_path",
		"minecraft:sign", "minecraft:oak_sign",
		"minecraft:wall_sign", "minecraft:oak_wall_sign",
		"minecraft:chain", "minecraft:iron_chain");

	private static Map<Integer, String> legacyTable;

	private SchematicReader() {}

	public static Schematic read(Path file, String name) throws IOException {
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		// Sponge v3 enveloppe tout dans "Schematic" ; en v1/v2 c'est le nom de la racine (déjà retiré).
		CompoundTag tag = root.getCompound("Schematic").orElse(root);

		int width = unsignedShort(tag.getIntOr("Width", 0));
		int height = unsignedShort(tag.getIntOr("Height", 0));
		int length = unsignedShort(tag.getIntOr("Length", 0));
		if (width == 0 || height == 0 || length == 0) throw new IOException("taille manquante ou nulle");
		long volume = (long) width * height * length;
		if (volume > MAX_VOLUME) throw new IOException("schéma trop grand (" + width + "x" + height + "x" + length + ")");

		if (tag.contains("Blocks") && tag.getCompound("Blocks").isPresent()) {
			// Sponge v3
			CompoundTag blocks = tag.getCompoundOrEmpty("Blocks");
			return readSponge(name, width, height, length, tag.getIntOr("DataVersion", 0),
				blocks.getCompoundOrEmpty("Palette"), blocks.getByteArray("Data").orElse(new byte[0]));
		}
		if (tag.contains("Palette")) {
			// Sponge v1 / v2
			return readSponge(name, width, height, length, tag.getIntOr("DataVersion", 0),
				tag.getCompoundOrEmpty("Palette"), tag.getByteArray("BlockData").orElse(new byte[0]));
		}
		if (tag.contains("Blocks")) {
			String materials = tag.getStringOr("Materials", "Alpha");
			if (!materials.equals("Alpha")) throw new IOException("format \"" + materials + "\" non pris en charge (seul Alpha / Java l'est)");
			return readLegacy(name, width, height, length, tag);
		}
		throw new IOException("format de schéma non reconnu");
	}

	// ================================================================ Sponge

	private static Schematic readSponge(String name, int width, int height, int length, int dataVersion,
			CompoundTag paletteTag, byte[] data) throws IOException {
		if (paletteTag.keySet().isEmpty()) throw new IOException("palette vide");

		// Palette du fichier (id -> nom) puis conversion vers la palette du Schematic (0 = air).
		int maxId = 0;
		for (String key : paletteTag.keySet()) maxId = Math.max(maxId, paletteTag.getIntOr(key, 0));
		if (maxId > 1_000_000) throw new IOException("palette invalide");

		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> ids = new HashMap<>();
		BlockState air = Blocks.AIR.defaultBlockState();
		palette.add(air);
		ids.put(air, 0);
		int[] map = new int[maxId + 1];
		for (String key : paletteTag.keySet()) {
			int fileId = paletteTag.getIntOr(key, -1);
			if (fileId < 0) continue;
			map[fileId] = idOf(parseState(key, dataVersion), palette, ids);
		}

		int[] blocks = new int[width * height * length];
		int pos = 0;
		for (int i = 0; i < blocks.length; i++) {
			// Varint : 7 bits par octet, bit de poids fort = « il reste des octets ».
			int value = 0, shift = 0;
			byte b;
			do {
				if (pos >= data.length) throw new IOException("données de blocs tronquées");
				b = data[pos++];
				value |= (b & 0x7F) << shift;
				shift += 7;
				if (shift > 35) throw new IOException("données de blocs invalides");
			} while ((b & 0x80) != 0);
			blocks[i] = value >= 0 && value < map.length ? map[value] : 0;
		}
		return Schematic.of(name, width, height, length, palette, blocks);
	}

	/** "minecraft:oak_stairs[facing=east,half=bottom]" -> BlockState (air si inconnu). */
	static BlockState parseState(String text, int dataVersion) {
		String blockName = text;
		CompoundTag props = new CompoundTag();
		int bracket = text.indexOf('[');
		if (bracket >= 0) {
			blockName = text.substring(0, bracket);
			int end = text.lastIndexOf(']');
			String inner = text.substring(bracket + 1, end > bracket ? end : text.length());
			for (String pair : inner.split(",")) {
				int eq = pair.indexOf('=');
				if (eq > 0) props.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
			}
		}
		blockName = blockName.trim();
		if (!blockName.contains(":")) blockName = "minecraft:" + blockName;
		// En 1.13 "stone_slab" était la dalle de pierre lisse (renommée en 1.14, data version 1901+).
		if (dataVersion > 0 && dataVersion < 1901 && blockName.equals("minecraft:stone_slab")) {
			blockName = "minecraft:smooth_stone_slab";
		}

		BlockState state = readState(blockName, props);
		if (state.isAir() && !blockName.endsWith("air")) {
			String renamed = RENAMED.get(blockName);
			if (renamed != null) state = readState(renamed, props);
		}
		return state;
	}

	private static BlockState readState(String blockName, CompoundTag props) {
		CompoundTag tag = new CompoundTag();
		tag.putString("Name", blockName);
		if (!props.keySet().isEmpty()) tag.put("Properties", props.copy());
		return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
	}

	// ================================================================ MCEdit (avant 1.13)

	private static Schematic readLegacy(String name, int width, int height, int length, CompoundTag tag) throws IOException {
		int volume = width * height * length;
		byte[] ids = tag.getByteArray("Blocks").orElse(new byte[0]);
		byte[] meta = tag.getByteArray("Data").orElse(new byte[0]);
		byte[] add = tag.getByteArray("AddBlocks").orElse(new byte[0]);
		if (ids.length < volume) throw new IOException("données de blocs tronquées");

		Map<Integer, String> table = legacyTable();
		Map<Integer, BlockState> cache = new HashMap<>();
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> paletteIds = new HashMap<>();
		BlockState air = Blocks.AIR.defaultBlockState();
		palette.add(air);
		paletteIds.put(air, 0);

		int[] blocks = new int[volume];
		for (int i = 0; i < volume; i++) {
			int id = ids[i] & 0xFF;
			if ((i >> 1) < add.length) {
				// AddBlocks : un demi-octet par bloc (index pair = 4 bits de poids faible).
				int high = (i & 1) == 0 ? add[i >> 1] & 0x0F : (add[i >> 1] >> 4) & 0x0F;
				id |= high << 8;
			}
			if (id == 0) continue;
			int data = i < meta.length ? meta[i] & 0x0F : 0;
			int key = id << 4 | data;
			BlockState state = cache.get(key);
			if (state == null) {
				String text = table.get(key);
				if (text == null) text = table.get(id << 4); // méta inconnue -> variante par défaut
				state = text == null ? air : parseState(text, 0);
				cache.put(key, state);
			}
			blocks[i] = idOf(state, palette, paletteIds);
		}
		fixDoubleBlocks(width, height, length, palette, paletteIds, blocks);
		return Schematic.of(name, width, height, length, palette, blocks);
	}

	/**
	 * Avant 1.13, la moitié haute d'une porte ne stockait que charnière + alimentation, et celle
	 * des grandes plantes rien du tout : on complète avec la moitié basse (et inversement).
	 */
	private static void fixDoubleBlocks(int width, int height, int length,
			List<BlockState> palette, Map<BlockState, Integer> ids, int[] blocks) {
		int layer = width * length;
		for (int i = layer; i < blocks.length; i++) {
			BlockState upper = palette.get(blocks[i]);
			BlockState lower = palette.get(blocks[i - layer]);
			if (!upper.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				|| !lower.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				|| upper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER
				|| lower.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) continue;

			if (upper.getBlock() instanceof DoorBlock && upper.getBlock() == lower.getBlock()) {
				BlockState newUpper = upper.setValue(DoorBlock.FACING, lower.getValue(DoorBlock.FACING))
					.setValue(DoorBlock.OPEN, lower.getValue(DoorBlock.OPEN));
				BlockState newLower = lower.setValue(DoorBlock.HINGE, upper.getValue(DoorBlock.HINGE))
					.setValue(DoorBlock.POWERED, upper.getValue(DoorBlock.POWERED));
				blocks[i] = idOf(newUpper, palette, ids);
				blocks[i - layer] = idOf(newLower, palette, ids);
			} else if (upper.getBlock() instanceof DoublePlantBlock && lower.getBlock() instanceof DoublePlantBlock) {
				blocks[i] = idOf(lower.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), palette, ids);
			}
		}
	}

	private static synchronized Map<Integer, String> legacyTable() throws IOException {
		if (legacyTable != null) return legacyTable;
		Map<Integer, String> table = new HashMap<>();
		try (InputStream in = SchematicReader.class.getResourceAsStream("/assets/schemacraft/legacy_blocks.txt")) {
			if (in == null) throw new IOException("table des anciens blocs introuvable");
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				int colon = line.indexOf(':'), eq = line.indexOf('=');
				if (colon < 0 || eq < colon) continue;
				int id = Integer.parseInt(line.substring(0, colon));
				int data = Integer.parseInt(line.substring(colon + 1, eq));
				table.put(id << 4 | data, line.substring(eq + 1));
			}
		}
		legacyTable = table;
		return table;
	}

	// ================================================================ Outils

	private static int idOf(BlockState state, List<BlockState> palette, Map<BlockState, Integer> ids) {
		if (state.isAir()) return 0;
		Integer id = ids.get(state);
		if (id == null) {
			id = palette.size();
			palette.add(state);
			ids.put(state, id);
		}
		return id;
	}

	/** Width/Height/Length sont des shorts non signés. */
	private static int unsignedShort(int value) {
		return value < 0 ? value & 0xFFFF : value;
	}
}
