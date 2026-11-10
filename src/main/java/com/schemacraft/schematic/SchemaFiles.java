package com.schemacraft.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Gestion du dossier ".minecraft/schemas" de l'instance. */
public final class SchemaFiles {
	public static final String EXTENSION = ".schema";

	private SchemaFiles() {}

	public static Path dir() throws IOException {
		Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("schemas");
		Files.createDirectories(dir);
		return dir;
	}

	/** Garde uniquement lettres, chiffres, espaces, tirets et underscores. */
	public static String sanitize(String name) {
		String cleaned = name.trim().replaceAll("[^\\p{L}\\p{N} _\\-]", "_");
		if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
		return cleaned;
	}

	public static Path fileFor(String name) throws IOException {
		return dir().resolve(sanitize(name) + EXTENSION);
	}

	public static boolean exists(String name) {
		try {
			return Files.exists(fileFor(name));
		} catch (IOException e) {
			return false;
		}
	}

	public static void save(Schematic schematic) throws IOException {
		NbtIo.writeCompressed(schematic.toNbt(), fileFor(schematic.name()));
	}

	/** Extensions importées (formats d'autres mods / outils), en plus des .schema. */
	private static final String[] IMPORT_EXTENSIONS = {
		LitematicReader.EXTENSION, SchematicReader.SCHEM, SchematicReader.SCHEMATIC
	};

	/** Dossiers (relatifs au dossier du jeu) parcourus pour les fichiers importés. */
	private static final String[] IMPORT_FOLDERS = {"schemas", "schematics", "config/worldedit/schematics"};

	/**
	 * Charge une entrée de {@link #list()} : soit le nom d'un .schema, soit le chemin
	 * (relatif au dossier du jeu) d'un .litematic, .schem ou .schematic.
	 */
	public static Schematic load(String entry) throws IOException {
		String ext = importExtension(entry);
		if (ext != null) {
			Path file = Minecraft.getInstance().gameDirectory.toPath().resolve(entry);
			if (ext.equals(LitematicReader.EXTENSION)) return LitematicReader.read(file, displayName(entry));
			return SchematicReader.read(file, displayName(entry));
		}
		CompoundTag tag = NbtIo.readCompressed(fileFor(entry), NbtAccounter.unlimitedHeap());
		return Schematic.fromNbt(tag, entry);
	}

	/** Extension d'un fichier importé (".litematic", ".schem", ".schematic"), ou null pour un .schema. */
	public static String importExtension(String entry) {
		String lower = entry.toLowerCase(java.util.Locale.ROOT);
		for (String ext : IMPORT_EXTENSIONS) {
			if (lower.endsWith(ext)) return ext;
		}
		return null;
	}

	public static boolean isLitematic(String entry) {
		return LitematicReader.EXTENSION.equals(importExtension(entry));
	}

	/** Nom affiché : nom du fichier sans dossier ni extension. */
	public static String displayName(String entry) {
		String ext = importExtension(entry);
		if (ext == null) return entry;
		String f = entry.substring(entry.lastIndexOf('/') + 1);
		return f.substring(0, f.length() - ext.length());
	}

	/**
	 * Schémas disponibles, triés alphabétiquement :
	 * les .schema de "schemas/" (par nom) puis les .litematic, .schem et .schematic de "schemas/",
	 * "schematics/" (dossier de Litematica) et "config/worldedit/schematics/" (WorldEdit),
	 * sous-dossiers compris, par chemin relatif.
	 */
	public static List<String> list() {
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir())) {
			files.filter(p -> p.getFileName().toString().endsWith(EXTENSION))
				.forEach(p -> {
					String f = p.getFileName().toString();
					names.add(f.substring(0, f.length() - EXTENSION.length()));
				});
		} catch (IOException ignored) {
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);

		List<String> imported = new ArrayList<>();
		Path game = Minecraft.getInstance().gameDirectory.toPath();
		for (String folder : IMPORT_FOLDERS) {
			Path dir = game.resolve(folder);
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> files = Files.walk(dir, 4)) {
				files.filter(p -> Files.isRegularFile(p) && importExtension(p.getFileName().toString()) != null)
					.forEach(p -> imported.add(game.relativize(p).toString().replace('\\', '/')));
			} catch (IOException ignored) {
			}
		}
		imported.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(displayName(a), displayName(b)));
		names.addAll(imported);
		return names;
	}
}
