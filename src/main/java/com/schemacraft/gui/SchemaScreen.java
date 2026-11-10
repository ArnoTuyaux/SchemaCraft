package com.schemacraft.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.schemacraft.SchemaCraftClient;
import com.schemacraft.SchemaState;
import com.schemacraft.Settings;
import com.schemacraft.schematic.Materials;
import com.schemacraft.schematic.SchemaFiles;
import com.schemacraft.schematic.Schematic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Menu principal (touche ","), organisé en onglets :
 *  Sélection | Schémas | Placement | Paramètres
 */
public class SchemaScreen extends Screen {
	private enum Tab {
		SELECTION("Sélection"), SCHEMAS("Schémas"), PLACEMENT("Placement"), RESOURCES("Ressources"), SETTINGS("Paramètres");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final int W = 300;
	private static final int ROW = 21;
	private static final int FILES_PER_PAGE = 6;
	private static final int RES_PER_PAGE = 7;
	private static final int RES_ROW = 17;
	private static final long MAX_BLOCKS = 4_000_000L;

	private static final int GRAY = 0xFFAAAAAA;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int DARK = 0xFF777777;

	private static Tab tab = Tab.SELECTION;
	private static int page;
	private static int resPage;
	private static boolean resOnlyMissing;
	private static String lastName = "";

	/** Texte affiché à une position donnée, recalculé à chaque image. */
	private record Label(Supplier<String> text, int x, int y, int color, boolean centered) {}

	private final List<Label> labels = new ArrayList<>();
	private List<String> files = List.of();
	/** Entrée (nom ou chemin .litematic) du schéma chargé, pour le surligner dans la liste. */
	private static String loadedEntry;
	private String status = "";
	private int statusColor = WHITE;
	private String pendingOverwrite;
	private boolean updatingFields;

	private EditBox nameBox;
	private EditBox[] originBoxes;

	/** Lignes de ressources visibles (onglet Ressources) et position de la liste. */
	private List<Materials.Entry> resources = List.of();
	private List<Materials.Entry> resourcesShown = List.of();
	private int resListX;
	private int resListY;

	public SchemaScreen() {
		super(Component.literal("SchemaCraft"));
	}

	/** Ouvre directement le menu sur l'onglet Ressources (touche rapide). */
	public static SchemaScreen openResources() {
		tab = Tab.RESOURCES;
		return new SchemaScreen();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int cx() {
		return width / 2 - W / 2;
	}

	private int top() {
		return Math.max(6, height / 2 - 112);
	}

	@Override
	protected void init() {
		labels.clear();
		int x = cx();
		int y0 = top();

		label(() -> "SchemaCraft", width / 2, y0, 0xFFFFD000, true);

		// Onglets
		Tab[] tabs = Tab.values();
		int tw = (W - (tabs.length - 1) * 2) / tabs.length;
		for (int i = 0; i < tabs.length; i++) {
			Tab t = tabs[i];
			Button b = addRenderableWidget(Button.builder(Component.literal(t.label), btn -> {
				tab = t;
				rebuildWidgets();
			}).bounds(x + i * (tw + 2), y0 + 12, tw, 20).build());
			b.active = tab != t;
		}

		int c = y0 + 40;
		switch (tab) {
			case SELECTION -> initSelection(x, c);
			case SCHEMAS -> initSchemas(x, c);
			case PLACEMENT -> initPlacement(x, c);
			case RESOURCES -> initResources(x, c);
			case SETTINGS -> initSettings(x, c);
		}
	}

	// ================================================================ Sélection

	private void initSelection(int x, int c) {
		label(() -> "Bâton : clic droit = Pos 1, shift = Pos 2", x, c, GRAY, false);

		int y = c + 12;
		label(() -> "Pos 1", x, y + 6, 0xFFFF6060, false);
		coordRow(x + 36, y, SchemaState.pos1, p -> SchemaState.pos1 = p);
		addRenderableWidget(Button.builder(Component.literal("Ici"), b -> {
			SchemaState.pos1 = playerPos();
			rebuildWidgets();
		}).bounds(x + 36 + 3 * 54, y, W - 36 - 3 * 54, 20).build());

		y += ROW;
		label(() -> "Pos 2", x, y + 6, 0xFF6090FF, false);
		coordRow(x + 36, y, SchemaState.pos2, p -> SchemaState.pos2 = p);
		addRenderableWidget(Button.builder(Component.literal("Ici"), b -> {
			SchemaState.pos2 = playerPos();
			rebuildWidgets();
		}).bounds(x + 36 + 3 * 54, y, W - 36 - 3 * 54, 20).build());

		y += ROW + 4;
		label(SchemaScreen::sizeText, x, y, WHITE, false);

		y += 12;
		addRenderableWidget(Button.builder(Component.literal("Effacer la sélection"), b -> {
			SchemaState.pos1 = null;
			SchemaState.pos2 = null;
			rebuildWidgets();
		}).bounds(x, y, W, 20).build());

		y += ROW + 6;
		label(() -> "Sauvegarder (dossier schemas/)", x, y, GRAY, false);
		y += 12;
		nameBox = new EditBox(font, x, y, 170, 20, Component.literal("Nom"));
		nameBox.setMaxLength(64);
		nameBox.setHint(Component.literal("nom du schéma"));
		nameBox.setValue(lastName);
		nameBox.setResponder(s -> {
			lastName = s;
			pendingOverwrite = null;
		});
		addRenderableWidget(nameBox);
		addRenderableWidget(Button.builder(Component.literal("Sauvegarder"), b -> save())
			.bounds(x + 174, y, W - 174, 20).build());
	}

	private static String sizeText() {
		if (!SchemaState.hasSelection()) return "Taille : -";
		BlockPos a = SchemaState.pos1, b = SchemaState.pos2;
		int sx = Math.abs(a.getX() - b.getX()) + 1;
		int sy = Math.abs(a.getY() - b.getY()) + 1;
		int sz = Math.abs(a.getZ() - b.getZ()) + 1;
		return "Taille : " + sx + " x " + sy + " x " + sz + " = " + ((long) sx * sy * sz) + " blocs";
	}

	// ================================================================ Schémas

	private void initSchemas(int x, int c) {
		files = SchemaFiles.list();
		int pages = Math.max(1, (files.size() + FILES_PER_PAGE - 1) / FILES_PER_PAGE);
		if (page >= pages) page = pages - 1;

		label(() -> "Schémas (" + files.size() + ") : clic pour charger", x, c, GRAY, false);
		int y = c + 12;
		if (files.isEmpty()) {
			label(() -> "Aucun schéma (dossiers schemas/ et schematics/).", x, y + 6, WHITE, false);
		}
		int start = page * FILES_PER_PAGE;
		for (int i = 0; i < FILES_PER_PAGE && start + i < files.size(); i++) {
			String name = files.get(start + i);
			String ext = SchemaFiles.importExtension(name);
			String shown = SchemaFiles.displayName(name) + (ext != null ? " [" + ext.substring(1) + "]" : "");
			boolean current = SchemaState.loaded != null && name.equals(loadedEntry);
			addRenderableWidget(Button.builder(Component.literal(current ? "» " + shown + " «" : shown), b -> load(name))
				.bounds(x, y + i * ROW, W, 20).build());
		}

		y += FILES_PER_PAGE * ROW + 2;
		Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			page--;
			rebuildWidgets();
		}).bounds(x, y, 24, 20).build());
		prev.active = page > 0;
		final int shownPages = pages;
		label(() -> (page + 1) + " / " + shownPages, x + 24 + 25, y + 6, WHITE, true);
		Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			page++;
			rebuildWidgets();
		}).bounds(x + 74, y, 24, 20).build());
		next.active = page < pages - 1;
		addRenderableWidget(Button.builder(Component.literal("Rafraîchir"), b -> rebuildWidgets())
			.bounds(x + 102, y, W - 102, 20).build());
	}

	// ================================================================ Placement

	private void initPlacement(int x, int c) {
		label(() -> {
			Schematic s = SchemaState.loaded;
			if (s == null) return "Aucun schéma chargé (onglet Schémas)";
			return "Schéma : " + s.name() + "  (" + s.sizeX() + " x " + s.sizeY() + " x " + s.sizeZ() + ")";
		}, x, c, SchemaState.loaded == null ? GRAY : 0xFF55FFFF, false);

		boolean loaded = SchemaState.loaded != null;
		boolean placed = SchemaState.isPlaced();

		int y = c + 12;
		label(() -> "Coin", x, y + 6, 0xFF55FFFF, false);
		originBoxes = coordRow(x + 36, y, SchemaState.origin, p -> {
			if (SchemaState.loaded != null) SchemaState.setOrigin(p);
		});
		for (EditBox b : originBoxes) b.setEditable(loaded);
		Button here = addRenderableWidget(Button.builder(Component.literal("Ici"), b -> {
			SchemaState.setOrigin(playerPos());
			rebuildWidgets();
		}).bounds(x + 36 + 3 * 54, y, W - 36 - 3 * 54, 20).build());
		here.active = loaded;

		y += ROW;
		String[] names = {"X-", "X+", "Y-", "Y+", "Z-", "Z+"};
		int[][] moves = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
		int bw = (W - 5 * 2) / 6;
		for (int i = 0; i < 6; i++) {
			int[] m = moves[i];
			Button mb = addRenderableWidget(Button.builder(Component.literal(names[i]), b -> {
				int step = Minecraft.getInstance().hasShiftDown() ? 10 : 1;
				SchemaState.moveOrigin(m[0] * step, m[1] * step, m[2] * step);
				refreshOriginFields();
			}).bounds(x + i * (bw + 2), y, bw, 20).build());
			mb.active = placed;
		}
		y += ROW + 2;
		label(() -> "Shift = 10 blocs. Épée en bois = poser.", x, y, DARK, false);

		// --- Rotation / miroir ---
		y += 14;
		String[] tNames = {"-90°", "+90°", "Miroir X", "Miroir Z", "Réinit."};
		Runnable[] tActions = {
			() -> SchemaState.rotate(false),
			() -> SchemaState.rotate(true),
			() -> SchemaState.toggleMirror(net.minecraft.world.level.block.Mirror.FRONT_BACK),
			() -> SchemaState.toggleMirror(net.minecraft.world.level.block.Mirror.LEFT_RIGHT),
			SchemaState::resetTransform,
		};
		int tw = (W - 4 * 2) / 5;
		for (int i = 0; i < tNames.length; i++) {
			Runnable action = tActions[i];
			Button tb = addRenderableWidget(Button.builder(Component.literal(tNames[i]), b -> {
				action.run();
				rebuildWidgets();
			}).bounds(x + i * (tw + 2), y, tw, 20).build());
			tb.active = loaded;
		}
		y += ROW + 1;
		label(() -> "Rotation : " + SchemaState.rotationText() + "   Miroir : " + SchemaState.mirrorText(), x, y, 0xFFFFD000, false);

		y += 12;
		addRenderableWidget(Button.builder(Component.literal(SchemaState.layerMode ? "Affichage : couche par couche" : "Affichage : tout le schéma"), b -> {
			SchemaState.toggleLayerMode();
			rebuildWidgets();
		}).bounds(x, y, W, 20).build());

		y += ROW;
		int half = (W - 4) / 2;
		Button down = addRenderableWidget(Button.builder(Component.literal("Couche -"), b -> SchemaState.changeLayer(-1))
			.bounds(x, y, half - 30, 20).build());
		label(() -> SchemaState.layerMode && SchemaState.isPlaced()
			? SchemaState.layerIndex() + "/" + SchemaState.loaded.sizeY() : "-", width / 2, y + 6, 0xFFFFD000, true);
		Button up = addRenderableWidget(Button.builder(Component.literal("Couche +"), b -> SchemaState.changeLayer(1))
			.bounds(x + W - (half - 30), y, half - 30, 20).build());
		down.active = up.active = SchemaState.layerMode && placed;

		y += ROW + 4;
		Button remove = addRenderableWidget(Button.builder(Component.literal("Retirer le schéma"), b -> {
			SchemaState.unload();
			rebuildWidgets();
		}).bounds(x, y, W, 20).build());
		remove.active = loaded;
	}

	// ================================================================ Ressources

	private void initResources(int x, int c) {
		resListX = x;
		resListY = -1;
		Schematic s = SchemaState.loaded;
		if (s == null) {
			resources = List.of();
			resourcesShown = List.of();
			label(() -> "Aucun schéma chargé (onglet Schémas)", x, c, GRAY, false);
			return;
		}

		resources = Materials.compute(s, SchemaState.origin, minecraft.level, minecraft.player);
		List<Materials.Entry> shown = new ArrayList<>();
		int total = 0, missing = 0, toFetch = 0;
		for (Materials.Entry e : resources) {
			total += e.total();
			missing += e.missing();
			toFetch += e.toFetch();
			if (!resOnlyMissing || e.toFetch() > 0) shown.add(e);
		}
		resourcesShown = shown;
		int pages = Math.max(1, (shown.size() + RES_PER_PAGE - 1) / RES_PER_PAGE);
		if (resPage >= pages) resPage = pages - 1;

		final int fTotal = total, fMissing = missing, fFetch = toFetch;
		label(() -> s.name() + " : " + fTotal + " objets, " + fMissing + " à poser, " + fFetch + " à chercher",
			x, c, 0xFF55FFFF, false);

		int y = c + 12;
		addRenderableWidget(Button.builder(Component.literal(resOnlyMissing ? "Afficher : à chercher" : "Afficher : tout"), b -> {
			resOnlyMissing = !resOnlyMissing;
			resPage = 0;
			rebuildWidgets();
		}).bounds(x, y, 190, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Rafraîchir"), b -> rebuildWidgets())
			.bounds(x + 194, y, W - 194, 20).build());

		y += ROW + 3;
		label(() -> "Objet", x + 20, y, GRAY, false);
		label(() -> "Total", x + 190, y, GRAY, true);
		label(() -> "À poser", x + 232, y, GRAY, true);
		label(() -> "Inv.", x + 276, y, GRAY, true);
		resListY = y + 11;
		if (shown.isEmpty()) {
			label(() -> resOnlyMissing ? "Tu as tout ce qu'il faut !" : "Schéma vide.", x, resListY + 4, 0xFF55FF55, false);
		}

		y = resListY + RES_PER_PAGE * RES_ROW + 2;
		Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			resPage--;
			rebuildWidgets();
		}).bounds(x, y, 24, 20).build());
		prev.active = resPage > 0;
		final int shownPages = pages;
		label(() -> (resPage + 1) + " / " + shownPages, x + 49, y + 6, WHITE, true);
		Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			resPage++;
			rebuildWidgets();
		}).bounds(x + 74, y, 24, 20).build());
		next.active = resPage < pages - 1;
		label(() -> SchemaState.isPlaced() ? "À poser = pas encore construit" : "Non posé : à poser = total",
			x + 104, y + 6, DARK, false);
	}

	private void renderResources(GuiGraphicsExtractor g) {
		if (resListY < 0 || resourcesShown.isEmpty()) return;
		int x = resListX;
		int start = resPage * RES_PER_PAGE;
		for (int i = 0; i < RES_PER_PAGE && start + i < resourcesShown.size(); i++) {
			Materials.Entry e = resourcesShown.get(start + i);
			int y = resListY + i * RES_ROW;
			if (i % 2 == 0) g.fill(x, y - 1, x + W, y + RES_ROW - 1, 0x30FFFFFF);
			ItemStack stack = new ItemStack(e.item());
			g.item(stack, x + 1, y);
			String name = trim(stack.getHoverName().getString(), 140);
			int ty = y + 4;
			g.text(font, name, x + 20, ty, WHITE, true);
			centered(g, String.valueOf(e.total()), x + 190, ty, WHITE);
			centered(g, String.valueOf(e.missing()), x + 232, ty, e.missing() == 0 ? 0xFF55FF55 : 0xFFFFD000);
			int invColor = e.missing() == 0 || e.inInventory() >= e.missing() ? 0xFF55FF55 : 0xFFFF5555;
			centered(g, String.valueOf(e.inInventory()), x + 276, ty, invColor);
		}
	}

	private void centered(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
		g.text(font, s, cx - font.width(s) / 2, y, color, true);
	}

	private String trim(String s, int maxWidth) {
		if (font.width(s) <= maxWidth) return s;
		while (s.length() > 1 && font.width(s + "…") > maxWidth) s = s.substring(0, s.length() - 1);
		return s + "…";
	}

	// ================================================================ Paramètres

	private void initSettings(int x, int c) {
		label(() -> "Printer : pose auto des blocs manquants", x, c, GRAY, false);

		int y = c + 12;
		addRenderableWidget(Button.builder(Component.literal("Printer : " + (Settings.printerEnabled ? "§aACTIVÉ" : "§cDÉSACTIVÉ")), b -> {
			Settings.printerEnabled = !Settings.printerEnabled;
			Settings.save();
			rebuildWidgets();
		}).bounds(x, y, W, 20).build());

		y += ROW + 2;
		stepper(x, y, "Délai entre poses", () -> Settings.printerDelay + " ticks", () -> {
			Settings.printerDelay = Math.max(Settings.DELAY_MIN, Settings.printerDelay - 1);
		}, () -> {
			Settings.printerDelay = Math.min(Settings.DELAY_MAX, Settings.printerDelay + 1);
		});
		y += ROW;
		stepper(x, y, "Blocs par pose", () -> String.valueOf(Settings.printerBlocksPerAction), () -> {
			Settings.printerBlocksPerAction = Math.max(Settings.BLOCKS_MIN, Settings.printerBlocksPerAction - 1);
		}, () -> {
			Settings.printerBlocksPerAction = Math.min(Settings.BLOCKS_MAX, Settings.printerBlocksPerAction + 1);
		});
		y += ROW;
		stepper(x, y, "Portée", () -> String.format("%.1f blocs", Settings.printerRange), () -> {
			Settings.printerRange = Math.max(Settings.RANGE_MIN, Math.round((Settings.printerRange - 0.5) * 2) / 2.0);
		}, () -> {
			Settings.printerRange = Math.min(Settings.RANGE_MAX, Math.round((Settings.printerRange + 0.5) * 2) / 2.0);
		});

		y += ROW + 2;
		addRenderableWidget(Button.builder(Component.literal("En mode couche : " + (Settings.printerOnlyVisibleLayer ? "couche affichée seulement" : "tout le schéma")), b -> {
			Settings.printerOnlyVisibleLayer = !Settings.printerOnlyVisibleLayer;
			Settings.save();
			rebuildWidgets();
		}).bounds(x, y, W, 20).build());

		y += ROW + 4;
		label(() -> "Prend les blocs de ton inventaire.", x, y, DARK, false);
		label(() -> "En créatif, les blocs sont créés.", x, y + 11, DARK, false);
	}

	/** Ligne "Libellé      [-] valeur [+]". */
	private void stepper(int x, int y, String name, Supplier<String> value, Runnable minus, Runnable plus) {
		label(() -> name, x, y + 6, WHITE, false);
		addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			minus.run();
			Settings.save();
		}).bounds(x + W - 130, y, 20, 20).build());
		label(value, x + W - 65, y + 6, 0xFFFFD000, true);
		addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			plus.run();
			Settings.save();
		}).bounds(x + W - 20, y, 20, 20).build());
	}

	// ================================================================ outils

	private void label(Supplier<String> text, int x, int y, int color, boolean centered) {
		labels.add(new Label(text, x, y, color, centered));
	}

	/** 3 champs X/Y/Z ; quand les 3 sont des entiers valides, on appelle setter. */
	private EditBox[] coordRow(int x, int y, BlockPos initial, Consumer<BlockPos> setter) {
		EditBox[] boxes = new EditBox[3];
		String[] hints = {"x", "y", "z"};
		for (int i = 0; i < 3; i++) {
			EditBox box = new EditBox(font, x + i * 54, y, 50, 20, Component.literal(hints[i]));
			box.setMaxLength(9);
			box.setHint(Component.literal(hints[i]));
			if (initial != null) {
				box.setValue(String.valueOf(i == 0 ? initial.getX() : i == 1 ? initial.getY() : initial.getZ()));
			}
			boxes[i] = box;
			addRenderableWidget(box);
		}
		for (EditBox box : boxes) {
			box.setResponder(s -> {
				if (updatingFields) return;
				BlockPos p = parse(boxes);
				if (p != null) setter.accept(p);
			});
		}
		return boxes;
	}

	private static BlockPos parse(EditBox[] boxes) {
		try {
			return new BlockPos(
				Integer.parseInt(boxes[0].getValue().trim()),
				Integer.parseInt(boxes[1].getValue().trim()),
				Integer.parseInt(boxes[2].getValue().trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void refreshOriginFields() {
		BlockPos o = SchemaState.origin;
		if (o == null || originBoxes == null) return;
		updatingFields = true;
		originBoxes[0].setValue(String.valueOf(o.getX()));
		originBoxes[1].setValue(String.valueOf(o.getY()));
		originBoxes[2].setValue(String.valueOf(o.getZ()));
		updatingFields = false;
	}

	private BlockPos playerPos() {
		return minecraft.player != null ? minecraft.player.blockPosition() : BlockPos.ZERO;
	}

	// ================================================================ actions

	private void save() {
		if (!SchemaState.hasSelection()) {
			setStatus("Il faut une position 1 et une position 2.", 0xFFFF5555);
			return;
		}
		String name = SchemaFiles.sanitize(nameBox.getValue());
		if (name.isEmpty()) {
			setStatus("Donne un nom au schéma.", 0xFFFF5555);
			return;
		}
		BlockPos a = SchemaState.pos1, b = SchemaState.pos2;
		long volume = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
		if (volume > MAX_BLOCKS) {
			setStatus("Sélection trop grande (" + volume + " blocs, max " + MAX_BLOCKS + ").", 0xFFFF5555);
			return;
		}
		if (SchemaFiles.exists(name) && !name.equals(pendingOverwrite)) {
			pendingOverwrite = name;
			setStatus("« " + name + " » existe déjà : clique encore pour l'écraser.", 0xFFFFAA00);
			return;
		}
		pendingOverwrite = null;
		try {
			Schematic schem = Schematic.capture(minecraft.level, a, b, name);
			SchemaFiles.save(schem);
			setStatus("Sauvegardé : " + name + " (" + schem.nonAirCount() + " blocs)", 0xFF55FF55);
		} catch (IOException | RuntimeException e) {
			setStatus("Erreur de sauvegarde : " + e.getMessage(), 0xFFFF5555);
		}
	}

	private void load(String name) {
		try {
			Schematic schem = SchemaFiles.load(name);
			SchemaState.setLoaded(schem);
			loadedEntry = name;
			setStatus("Chargé : " + schem.name() + " (" + schem.sizeX() + "x" + schem.sizeY() + "x" + schem.sizeZ() + "). Clic droit au sol avec l'épée en bois pour le poser.", 0xFF55FFFF);
			rebuildWidgets();
		} catch (IOException | RuntimeException e) {
			setStatus("Impossible de charger " + name + " : " + e.getMessage(), 0xFFFF5555);
		}
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	// ================================================================ rendu

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		if (tab == Tab.RESOURCES) renderResources(g);
		for (Label l : labels) {
			String text = l.text().get();
			int x = l.centered() ? l.x() - font.width(text) / 2 : l.x();
			g.text(font, text, x, l.y(), l.color(), true);
		}
		if (!status.isEmpty()) {
			g.text(font, status, width / 2 - font.width(status) / 2, height - 12, statusColor, true);
		}
	}

	@Override
	public void onClose() {
		SchemaCraftClient.message(Component.empty());
		super.onClose();
	}
}
