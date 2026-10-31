package de.tpahunter;

import de.tpahunter.Config.Criterion;
import de.tpahunter.Config.Mode;
import de.tpahunter.ScanManager.Entry;
import de.tpahunter.ScanManager.Status;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Roter Look: Alle Buttons sind unsichtbare Vanilla-ButtonWidgets (Klick/Hover/Sound),
 * gezeichnet wird alles selbst in paint().
 */
public class TpaScreen extends Screen {
    private enum Tab { FILTER, RESULTS }

    private static Tab tab = Tab.FILTER;
    private static String lastSearch = "";

    private static final int W = 400, H = 236;
    private static final int COLS = 9, GRID_ROWS = 6, CELL = 18, PER_PAGE = COLS * GRID_ROWS;
    private static final int CRIT_ROWS = 6, CRIT_H = 20;
    private static final int RES_ROWS = 8, RES_H = 18;

    private static final int BORDER = 0xFFCC1111, BG = 0xF0180000, HEADER = 0xFF7A0A0A, STATUS_BG = 0xFF2A0505;
    private static final int SLOT = 0xFF3A0B0B, SLOT_HOVER = 0xFF8A1A1A, ROW = 0xFF2A0808, ROW_HIT = 0xFF5A1010;
    private static final int BTN = 0xFF8B1010, BTN_HOVER = 0xFFC01818, BTN_HOT = 0xFFD42626, BTN_EDGE = 0xFF300505;
    private static final int WHITE = 0xFFFFFFFF, DIM = 0xFFC49A9A, ACCENT = 0xFFFF4040, SOFT = 0xFFFFD0D0;

    private record Preset(String label, List<String> items) {}

    private static final List<Preset> PRESETS = List.of(
            new Preset("Nether-Rüstung", List.of("netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots")),
            new Preset("Diamant-Rüstung", List.of("diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots")),
            new Preset("Elytra+Raketen", List.of("elytra", "firework_rocket")),
            new Preset("Totems", List.of("totem_of_undying")),
            new Preset("End-Crystal-Kit", List.of("end_crystal", "obsidian")),
            new Preset("Gapples", List.of("enchanted_golden_apple"))
    );

    // alle Items (einmalig aufgebaut)
    private static List<ItemStack> ALL;
    private static List<String> ALL_IDS;
    private static List<String> ALL_KEYS;

    private static void ensureItems() {
        if (ALL != null) return;
        ALL = new ArrayList<>();
        ALL_IDS = new ArrayList<>();
        ALL_KEYS = new ArrayList<>();
        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) continue;
            ItemStack st = item.getDefaultStack();
            if (st.isEmpty()) continue;
            String id = Registries.ITEM.getId(item).toString();
            ALL.add(st);
            ALL_IDS.add(id);
            ALL_KEYS.add((st.getName().getString() + " " + id).toLowerCase(Locale.ROOT));
        }
    }

    private static final class Btn {
        final ButtonWidget w;
        final Supplier<String> label;
        final BooleanSupplier shown;
        final BooleanSupplier hot;

        Btn(ButtonWidget w, Supplier<String> label, BooleanSupplier shown, BooleanSupplier hot) {
            this.w = w;
            this.label = label;
            this.shown = shown;
            this.hot = hot;
        }
    }

    private int x0, y0;
    private final List<Btn> btns = new ArrayList<>();
    private final List<Integer> visible = new ArrayList<>();
    private int page, critPage, resPage;
    private boolean syncing;
    private TextFieldWidget search;
    private ButtonWidget[] grid;
    private TextFieldWidget[] amountFields;
    private List<Entry> shown = new ArrayList<>();

    public TpaScreen() {
        super(Text.literal("TPA Hunter"));
    }

    // ------------------------------------------------------------------ Aufbau

    private Btn btn(int x, int y, int w, int h, Supplier<String> label, Runnable action,
                    BooleanSupplier shownIf, BooleanSupplier hot) {
        ButtonWidget bw = ButtonWidget.builder(Text.empty(), b -> action.run()).dimensions(x, y, w, h).build();
        bw.setAlpha(0f);
        addDrawableChild(bw);
        Btn res = new Btn(bw, label, shownIf, hot);
        btns.add(res);
        return res;
    }

    private static boolean no() { return false; }

    private boolean inFilter() { return tab == Tab.FILTER; }

    private boolean inResults() { return tab == Tab.RESULTS; }

    @Override
    protected void init() {
        ensureItems();
        x0 = (width - W) / 2;
        y0 = Math.max(0, (height - H) / 2);
        btns.clear();

        // ---- Header
        btn(x0 + 120, y0 + 2, 70, 14, () -> "Filter", () -> switchTab(Tab.FILTER), () -> true, () -> tab == Tab.FILTER);
        btn(x0 + 194, y0 + 2, 110, 14, () -> "Ergebnisse (" + ScanManager.matchCount() + ")",
                () -> switchTab(Tab.RESULTS), () -> true, () -> tab == Tab.RESULTS);
        btn(x0 + W - 18, y0 + 2, 14, 14, () -> "X", this::close, () -> true, TpaScreen::no);

        // ---- FILTER: Suche + Grid
        search = new TextFieldWidget(textRenderer, x0 + 8, y0 + 22, CELL * COLS, 14, Text.literal("Suche"));
        search.setPlaceholder(Text.literal("Item suchen..."));
        search.setText(lastSearch);
        search.setChangedListener(s -> refilter());
        addDrawableChild(search);

        grid = new ButtonWidget[PER_PAGE];
        for (int i = 0; i < PER_PAGE; i++) {
            final int idx = i;
            int gx = x0 + 8 + (i % COLS) * CELL;
            int gy = y0 + 38 + (i / COLS) * CELL;
            ButtonWidget b = ButtonWidget.builder(Text.empty(), bb -> onGridClick(idx))
                    .dimensions(gx, gy, CELL, CELL).build();
            b.setAlpha(0f);
            addDrawableChild(b);
            grid[i] = b;
        }

        btn(x0 + 8, y0 + 148, 18, 14, () -> "<", () -> page = Math.max(0, page - 1), this::inFilter, TpaScreen::no);
        btn(x0 + 8 + CELL * COLS - 18, y0 + 148, 18, 14, () -> ">",
                () -> page = Math.min(maxPage(), page + 1), this::inFilter, TpaScreen::no);

        // ---- FILTER: Kombis
        for (int i = 0; i < PRESETS.size(); i++) {
            final Preset p = PRESETS.get(i);
            int px = x0 + 8 + (i % 2) * 90;
            int py = y0 + 176 + (i / 2) * 16;
            btn(px, py, 86, 14, p::label, () -> applyPreset(p), this::inFilter, TpaScreen::no);
        }

        // ---- FILTER: Kriterien-Liste (rechts)
        btn(x0 + 188, y0 + 22, 84, 14, () -> Config.get().matchAll ? "UND: alle" : "ODER: eins",
                () -> { Config.get().matchAll = !Config.get().matchAll; Config.save(); }, this::inFilter, TpaScreen::no);
        btn(x0 + 276, y0 + 22, 70, 14, () -> "Leeren",
                () -> { Config.get().criteria.clear(); afterCritChange(); }, this::inFilter, TpaScreen::no);

        amountFields = new TextFieldWidget[CRIT_ROWS];
        for (int r = 0; r < CRIT_ROWS; r++) {
            final int rr = r;
            int ry = y0 + 42 + r * CRIT_H;
            btn(x0 + 282, ry + 3, 44, 14,
                    () -> { Criterion c = critAt(rr); return c == null ? "" : c.mode.label; },
                    () -> {
                        Criterion c = critAt(rr);
                        if (c != null) {
                            c.mode = c.mode.next();
                            Config.save();
                        }
                    },
                    () -> inFilter() && critAt(rr) != null, TpaScreen::no);

            TextFieldWidget tf = new TextFieldWidget(textRenderer, x0 + 330, ry + 3, 36, 14, Text.empty());
            tf.setMaxLength(4);
            tf.setTextPredicate(s -> s.matches("\\d{0,4}"));
            tf.setChangedListener(s -> {
                if (syncing) return;
                Criterion c = critAt(rr);
                if (c != null) {
                    try {
                        c.amount = s.isEmpty() ? 0 : Integer.parseInt(s);
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            addDrawableChild(tf);
            amountFields[r] = tf;

            btn(x0 + 370, ry + 3, 14, 14, () -> "x",
                    () -> {
                        Criterion c = critAt(rr);
                        if (c != null) {
                            Config.get().criteria.remove(c);
                            afterCritChange();
                        }
                    },
                    () -> inFilter() && critAt(rr) != null, TpaScreen::no);
        }
        btn(x0 + 188, y0 + 166, 18, 14, () -> "<", () -> { critPage = Math.max(0, critPage - 1); syncRows(); },
                this::inFilter, TpaScreen::no);
        btn(x0 + 208, y0 + 166, 18, 14, () -> ">", () -> { critPage = Math.min(maxCritPage(), critPage + 1); syncRows(); },
                this::inFilter, TpaScreen::no);

        // ---- ERGEBNISSE
        btn(x0 + 8, y0 + 22, 100, 14, () -> ScanManager.isBusy() ? "STOPP" : "START",
                ScanManager::startStop, this::inResults, ScanManager::isBusy);
        btn(x0 + 112, y0 + 22, 90, 14, () -> "Alle scannen", ScanManager::scanAll, this::inResults, TpaScreen::no);
        btn(x0 + 206, y0 + 22, 60, 14, () -> "Leeren", ScanManager::clearAll, this::inResults, TpaScreen::no);
        btn(x0 + 8, y0 + 38, 100, 14,
                () -> "Auto-Scan: " + (Config.get().autoScan ? "AN" : "AUS"),
                () -> { Config.get().autoScan = !Config.get().autoScan; Config.save(); },
                this::inResults, () -> Config.get().autoScan);
        btn(x0 + 112, y0 + 38, 100, 14,
                () -> "Nur Treffer: " + (Config.get().onlyMatches ? "AN" : "AUS"),
                () -> { Config.get().onlyMatches = !Config.get().onlyMatches; Config.save(); },
                this::inResults, () -> Config.get().onlyMatches);
        btn(x0 + 216, y0 + 38, 100, 14,
                () -> "Chat-Meldung: " + (Config.get().notifyChat ? "AN" : "AUS"),
                () -> { Config.get().notifyChat = !Config.get().notifyChat; Config.save(); },
                this::inResults, () -> Config.get().notifyChat);

        for (int r = 0; r < RES_ROWS; r++) {
            final int rr = r;
            int ry = y0 + 58 + r * RES_H + 2;
            btn(x0 + 314, ry, 34, 14, () -> "Scan",
                    () -> { Entry e = resAt(rr); if (e != null) ScanManager.enqueue(e, true); },
                    () -> inResults() && resAt(rr) != null, TpaScreen::no);
            btn(x0 + 352, ry, 40, 14, () -> "Ansehen",
                    () -> { Entry e = resAt(rr); if (e != null) ScanManager.requestView(e.name); },
                    () -> inResults() && resAt(rr) != null, TpaScreen::no);
        }
        btn(x0 + 8, y0 + 204, 18, 14, () -> "<", () -> resPage = Math.max(0, resPage - 1), this::inResults, TpaScreen::no);
        btn(x0 + 28, y0 + 204, 18, 14, () -> ">", () -> resPage = Math.min(maxResPage(), resPage + 1),
                this::inResults, TpaScreen::no);

        refilter();
        syncRows();
    }

    // ------------------------------------------------------------------ Logik

    private void switchTab(Tab t) {
        tab = t;
        setFocused(null);
        resPage = 0;
    }

    private void refilter() {
        lastSearch = search.getText();
        String q = lastSearch.toLowerCase(Locale.ROOT).trim();
        visible.clear();
        for (int i = 0; i < ALL.size(); i++) {
            if (q.isEmpty() || ALL_KEYS.get(i).contains(q)) visible.add(i);
        }
        page = 0;
    }

    private int maxPage() { return Math.max(0, (visible.size() - 1) / PER_PAGE); }

    private int maxCritPage() { return Math.max(0, (Config.get().criteria.size() - 1) / CRIT_ROWS); }

    private int maxResPage() { return Math.max(0, (shown.size() - 1) / RES_ROWS); }

    private Criterion critAt(int r) {
        List<Criterion> cs = Config.get().criteria;
        int i = critPage * CRIT_ROWS + r;
        return i < cs.size() ? cs.get(i) : null;
    }

    private Entry resAt(int r) {
        int i = resPage * RES_ROWS + r;
        return i < shown.size() ? shown.get(i) : null;
    }

    private void onGridClick(int i) {
        int idx = page * PER_PAGE + i;
        if (idx >= visible.size()) return;
        String id = ALL_IDS.get(visible.get(idx));
        List<Criterion> cs = Config.get().criteria;
        if (!cs.removeIf(c -> c.item.equals(id))) cs.add(new Criterion(id));
        afterCritChange();
    }

    private void applyPreset(Preset p) {
        List<Criterion> cs = Config.get().criteria;
        for (String id : p.items()) {
            String full = "minecraft:" + id;
            boolean exists = false;
            for (Criterion c : cs) if (c.item.equals(full)) exists = true;
            if (!exists) cs.add(new Criterion(full));
        }
        afterCritChange();
    }

    private void afterCritChange() {
        critPage = Math.min(critPage, maxCritPage());
        syncRows();
        Config.save();
    }

    private void syncRows() {
        syncing = true;
        for (int r = 0; r < CRIT_ROWS; r++) {
            Criterion c = critAt(r);
            amountFields[r].setText(c == null ? "" : String.valueOf(c.amount));
        }
        syncing = false;
    }

    // ------------------------------------------------------------------ Eingaben

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int dir = verticalAmount > 0 ? -1 : 1;
        if (tab == Tab.FILTER && mouseX < x0 + 182) {
            page = Math.max(0, Math.min(maxPage(), page + dir));
            return true;
        }
        if (tab == Tab.RESULTS) {
            resPage = Math.max(0, Math.min(maxResPage(), resPage + dir));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void removed() {
        Config.save();
        super.removed();
    }

    // ------------------------------------------------------------------ Rendering

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(x0 - 1, y0 - 1, x0 + W + 1, y0 + H + 1, BORDER);
        ctx.fill(x0, y0, x0 + W, y0 + H, BG);
        ctx.fill(x0, y0, x0 + W, y0 + 18, HEADER);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        shown = ScanManager.shownEntries();
        resPage = Math.min(resPage, maxResPage());

        boolean f = tab == Tab.FILTER;
        search.visible = f;
        for (int i = 0; i < PER_PAGE; i++) grid[i].visible = f && page * PER_PAGE + i < visible.size();
        for (int r = 0; r < CRIT_ROWS; r++) {
            Criterion c = critAt(r);
            amountFields[r].visible = f && c != null && c.mode != Mode.EGAL;
        }
        for (Btn b : btns) b.w.visible = b.shown.getAsBoolean();

        super.render(ctx, mouseX, mouseY, delta);
        paint(ctx, mouseX, mouseY);
    }

    private void paint(DrawContext ctx, int mx, int my) {
        ctx.drawText(textRenderer, "TPA-HUNTER", x0 + 8, y0 + 5, WHITE, true);
        ctx.drawText(textRenderer, ScanManager.running ? "[AN]" : "[AUS]", x0 + 74, y0 + 5,
                ScanManager.running ? 0xFF66FF66 : DIM, true);
        String status = tab == Tab.FILTER ? paintFilter(ctx, mx, my) : paintResults(ctx, mx, my);

        for (Btn b : btns) paintBtn(ctx, b);

        ctx.fill(x0, y0 + H - 14, x0 + W, y0 + H, STATUS_BG);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(status, W - 12), x0 + 6, y0 + H - 11, DIM, false);
    }

    private void paintBtn(DrawContext ctx, Btn b) {
        if (!b.w.visible) return;
        int x = b.w.getX(), y = b.w.getY(), w = b.w.getWidth(), h = b.w.getHeight();
        int col = b.hot.getAsBoolean() ? BTN_HOT : (b.w.isHovered() ? BTN_HOVER : BTN);
        ctx.fill(x, y, x + w, y + h, BTN_EDGE);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, col);
        String s = textRenderer.trimToWidth(b.label.get(), w - 2);
        ctx.drawText(textRenderer, s, x + (w - textRenderer.getWidth(s)) / 2, y + (h - 8) / 2 + 1, WHITE, true);
    }

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String paintFilter(DrawContext ctx, int mx, int my) {
        String status = "Klick auf ein Item = zum Filter hinzufügen / entfernen";
        Set<String> selected = new HashSet<>();
        for (Criterion c : Config.get().criteria) selected.add(c.item);

        int gx = x0 + 8, gy = y0 + 38;
        for (int i = 0; i < PER_PAGE; i++) {
            int cx = gx + (i % COLS) * CELL, cy = gy + (i / COLS) * CELL;
            int idx = page * PER_PAGE + i;
            boolean has = idx < visible.size();
            boolean hov = has && in(mx, my, cx, cy, CELL, CELL);
            ctx.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, hov ? SLOT_HOVER : SLOT);
            if (!has) continue;
            int all = visible.get(idx);
            ctx.drawItem(ALL.get(all), cx + 1, cy + 1);
            if (selected.contains(ALL_IDS.get(all))) {
                ctx.fill(cx, cy, cx + CELL - 1, cy + 1, ACCENT);
                ctx.fill(cx, cy + CELL - 2, cx + CELL - 1, cy + CELL - 1, ACCENT);
                ctx.fill(cx, cy, cx + 1, cy + CELL - 1, ACCENT);
                ctx.fill(cx + CELL - 2, cy, cx + CELL - 1, cy + CELL - 1, ACCENT);
            }
            if (hov) status = ALL.get(all).getName().getString() + "  (" + ALL_IDS.get(all) + ")";
        }

        String pt = (page + 1) + "/" + (maxPage() + 1) + "  (" + visible.size() + ")";
        ctx.drawText(textRenderer, pt, gx + (COLS * CELL - textRenderer.getWidth(pt)) / 2, y0 + 151, DIM, false);
        ctx.drawText(textRenderer, "Kombis (zum Filter):", gx, y0 + 165, SOFT, false);

        // rechte Seite
        int rx = x0 + 188;
        List<Criterion> cs = Config.get().criteria;
        if (cs.isEmpty()) {
            ctx.drawText(textRenderer, "Kein Filter aktiv.", rx + 2, y0 + 46, SOFT, false);
            ctx.drawText(textRenderer, "Alle gescannten Spieler", rx + 2, y0 + 58, DIM, false);
            ctx.drawText(textRenderer, "zählen dann als Treffer.", rx + 2, y0 + 69, DIM, false);
        }
        for (int r = 0; r < CRIT_ROWS; r++) {
            Criterion c = critAt(r);
            if (c == null) break;
            int ry = y0 + 42 + r * CRIT_H;
            boolean hov = in(mx, my, rx, ry, x0 + W - 6 - rx, CRIT_H - 2);
            ctx.fill(rx, ry, x0 + W - 6, ry + CRIT_H - 2, hov ? ROW_HIT : ROW);
            ctx.drawItem(ItemUtil.stack(c.item), rx + 2, ry + 1);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(ItemUtil.name(c.item), 62), rx + 22, ry + 6, WHITE, true);
            if (hov) {
                status = ItemUtil.name(c.item) + "  (" + c.item + ")  -  "
                        + (c.mode == Mode.EGAL ? "Menge egal (mind. 1)" : c.mode.label + " " + c.amount);
            }
        }
        if (maxCritPage() > 0) {
            ctx.drawText(textRenderer, "Seite " + (critPage + 1) + "/" + (maxCritPage() + 1), x0 + 232, y0 + 169, DIM, false);
        }
        ctx.drawText(textRenderer, "Modus: egal = mind. 1 (Menge egal)", rx, y0 + 188, DIM, false);
        ctx.drawText(textRenderer, "genau = X | größer = >X | kleiner = <X", rx, y0 + 199, DIM, false);
        ctx.drawText(textRenderer, "kleiner 1 = Item darf NICHT dabei sein", rx, y0 + 210, DIM, false);
        return status;
    }

    private String paintResults(DrawContext ctx, int mx, int my) {
        String status = "START = Chat überwachen + scannen. Ohne dein Zutun wird nichts gescannt.";
        ctx.drawText(textRenderer, "Queue " + ScanManager.queueSize() + " | " + ScanManager.ENTRIES.size() + " Spieler",
                x0 + 272, y0 + 26, DIM, false);

        if (shown.isEmpty()) {
            ctx.drawText(textRenderer, "Noch niemand mit TPA-Nachricht im Chat gesehen.", x0 + 14, y0 + 70, SOFT, false);
            ctx.drawText(textRenderer, "Drücke START - dann werden TPA-Nachrichten erfasst.", x0 + 14, y0 + 82, DIM, false);
        }

        for (int r = 0; r < RES_ROWS; r++) {
            Entry e = resAt(r);
            if (e == null) break;
            int ry = y0 + 58 + r * RES_H;
            boolean match = ScanManager.matches(e);
            ctx.fill(x0 + 6, ry, x0 + W - 6, ry + RES_H - 2, match ? ROW_HIT : ROW);
            if (match) ctx.fill(x0 + 6, ry, x0 + 9, ry + RES_H - 2, ACCENT);

            ctx.drawText(textRenderer, textRenderer.trimToWidth(e.name, 90), x0 + 13, ry + 5, WHITE, true);

            String st;
            int stCol = DIM;
            switch (e.status) {
                case NEW -> st = "neu";
                case QUEUED -> st = "wartet";
                case SCANNING -> { st = "scannt..."; stCol = SOFT; }
                case FAILED -> { st = "Fehler"; stCol = 0xFFFF8080; }
                default -> {
                    st = match ? "TREFFER" : "kein Treffer";
                    stCol = match ? ACCENT : DIM;
                }
            }
            ctx.drawText(textRenderer, st, x0 + 106, ry + 5, stCol, false);

            if (e.status == Status.DONE) {
                ctx.drawText(textRenderer, textRenderer.trimToWidth(ScanManager.describe(e), 132), x0 + 176, ry + 5, SOFT, false);
            }
            if (in(mx, my, x0 + 6, ry, 304, RES_H - 2)) status = "Chat: " + e.lastMessage;
        }
        if (maxResPage() > 0) {
            ctx.drawText(textRenderer, "Seite " + (resPage + 1) + "/" + (maxResPage() + 1), x0 + 52, y0 + 207, DIM, false);
        }
        return status;
    }
}
