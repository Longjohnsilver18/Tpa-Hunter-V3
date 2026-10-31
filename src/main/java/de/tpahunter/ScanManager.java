package de.tpahunter;

import de.tpahunter.Config.Criterion;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat-Erkennung + Hintergrund-Scan per /invsee.
 * Das /invsee-Fenster wird per Mixin (MinecraftClient#setScreen) abgefangen und nie angezeigt.
 */
public final class ScanManager {
    private ScanManager() {}

    public enum Status { NEW, QUEUED, SCANNING, DONE, FAILED }

    public static final class Entry {
        public final String name;
        public String lastMessage = "";
        public long lastAsked = System.currentTimeMillis();
        public long scannedAt = 0;
        public long lastAttempt = 0;
        public Status status = Status.NEW;
        public Map<String, Integer> counts = new HashMap<>();
        public boolean wasMatch = false;

        Entry(String name) { this.name = name; }
    }

    private enum Phase { IDLE, WAIT_OPEN, WAIT_CONTENT }

    private static final int OPEN_TIMEOUT = 60;   // Ticks bis "Fehler"
    private static final int CONTENT_WAIT = 4;    // Ticks nach dem Oeffnen bis zum Auslesen
    private static final int CONTENT_MAX = 20;
    private static final int MAX_ENTRIES = 200;

    private static final Pattern FALLBACK = Pattern.compile(
            "^\\W{0,3}(?:[\\[(][^\\])]{1,24}[\\])]\\s*)*<?([A-Za-z0-9_.]{3,16})>?\\s*(?:[:»›>]|\\s-\\s|\\|)");

    public static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Deque<Entry> QUEUE = new ArrayDeque<>();

    private static Phase phase = Phase.IDLE;
    private static Entry current;
    private static ScreenHandler handler;
    private static int timer;
    private static int cooldown;
    private static String viewRequest;
    private static int swallowTicks;      // verspaetete /invsee-Fenster nach Stopp/Timeout still schliessen

    /** Chat-Erkennung + Auto-Scan laufen nur, wenn das hier true ist. Wird NIE gespeichert (nach Spielstart immer aus). */
    public static boolean running = false;

    // ------------------------------------------------------------------ Chat

    public static void onChat(String raw) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!running || mc.player == null || mc.getNetworkHandler() == null || raw == null || raw.isBlank()) return;
        Config cfg = Config.get();
        String self = mc.player.getName().getString();

        int[] end = new int[1];
        String sender = findSender(mc, raw, self, end);
        if (sender == null) return;
        if (!cfg.pattern().matcher(raw.substring(end[0])).find()) return;

        String key = sender.toLowerCase(Locale.ROOT);
        Entry e = ENTRIES.get(key);
        if (e == null) {
            e = new Entry(sender);
            ENTRIES.put(key, e);
            trim();
        }
        e.lastMessage = raw.trim();
        e.lastAsked = System.currentTimeMillis();
        if (cfg.autoScan) enqueue(e, false);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Sucht den Absender: frueheste Erwaehnung eines Online-Spielernamens, sonst Regex-Fallback. */
    private static String findSender(MinecraftClient mc, String raw, String self, int[] endOut) {
        String lower = raw.toLowerCase(Locale.ROOT);
        String best = null;
        int bestPos = Integer.MAX_VALUE, bestEnd = 0;
        try {
            Collection<String> names = mc.getNetworkHandler().getCommandSource().getPlayerNames();
            for (String n : names) {
                if (n == null || n.length() < 3) continue;
                String ln = n.toLowerCase(Locale.ROOT);
                int from = 0;
                while (true) {
                    int i = lower.indexOf(ln, from);
                    if (i < 0) break;
                    int e = i + ln.length();
                    boolean okL = i == 0 || !isNameChar(lower.charAt(i - 1));
                    boolean okR = e >= lower.length() || !isNameChar(lower.charAt(e));
                    if (okL && okR) {
                        if (best == null || i < bestPos || (i == bestPos && n.length() > best.length())) {
                            best = n;
                            bestPos = i;
                            bestEnd = e;
                        }
                        break;
                    }
                    from = i + 1;
                }
            }
        } catch (Exception ignored) {
        }
        if (best == null) {
            Matcher m = FALLBACK.matcher(raw);
            if (m.find()) {
                best = m.group(1);
                bestEnd = m.end();
            }
        }
        if (best == null || best.equalsIgnoreCase(self)) return null;
        endOut[0] = bestEnd;
        return best;
    }

    private static void trim() {
        Iterator<Entry> it = ENTRIES.values().iterator();
        while (ENTRIES.size() > MAX_ENTRIES && it.hasNext()) {
            Entry x = it.next();
            if (x.status != Status.QUEUED && x.status != Status.SCANNING) it.remove();
        }
    }

    // ------------------------------------------------------------------ API fuer die GUI

    public static void enqueue(Entry e, boolean force) {
        if (e.status == Status.QUEUED || e.status == Status.SCANNING) return;
        long now = System.currentTimeMillis();
        if (!force) {
            if (e.status == Status.DONE && now - e.scannedAt < Config.get().rescanSeconds * 1000L) return;
            if (e.status == Status.FAILED && now - e.lastAttempt < 60_000L) return;
        }
        e.status = Status.QUEUED;
        QUEUE.add(e);
    }

    public static void scanAll() {
        for (Entry e : ENTRIES.values()) enqueue(e, true);
    }

    /** Oeffnet /invsee sichtbar (Button "Ansehen"). */
    public static void requestView(String name) { viewRequest = name; }

    public static void clearAll() {
        if (phase != Phase.IDLE) abort();
        ENTRIES.clear();
        QUEUE.clear();
    }

    /** Start/Stopp-Schalter. Stopp bricht auch laufende Scans ab und leert die Warteschlange. */
    public static void startStop() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean stop = isBusy();
        if (stop) {
            running = false;
            stopAll();
        } else {
            running = true;
        }
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[TPA-Hunter] " + (stop ? "gestoppt" : "gestartet"))
                    .formatted(stop ? Formatting.RED : Formatting.GREEN), false);
        }
    }

    public static boolean isBusy() { return running || queueSize() > 0; }

    private static void stopAll() {
        for (Entry e : QUEUE) if (e.status == Status.QUEUED) e.status = Status.NEW;
        QUEUE.clear();
        viewRequest = null;
        if (phase != Phase.IDLE) abort();
    }

    public static int queueSize() { return QUEUE.size() + (phase != Phase.IDLE ? 1 : 0); }

    public static boolean matches(Entry e) {
        if (e.status != Status.DONE) return false;
        Config cfg = Config.get();
        List<Criterion> cs = cfg.criteria;
        if (cs.isEmpty()) return true;
        for (Criterion c : cs) {
            boolean ok = c.test(e.counts.getOrDefault(c.item, 0));
            if (cfg.matchAll && !ok) return false;
            if (!cfg.matchAll && ok) return true;
        }
        return cfg.matchAll;
    }

    public static int matchCount() {
        int n = 0;
        for (Entry e : ENTRIES.values()) if (matches(e)) n++;
        return n;
    }

    public static List<Entry> shownEntries() {
        boolean only = Config.get().onlyMatches;
        List<Entry> list = new ArrayList<>();
        for (Entry e : ENTRIES.values()) {
            if (only && !matches(e)) continue;
            list.add(e);
        }
        list.sort(Comparator.comparing((Entry e) -> !matches(e))
                .thenComparing(Comparator.comparingLong((Entry e) -> e.lastAsked).reversed()));
        return list;
    }

    public static String describe(Entry e) {
        List<String> parts = new ArrayList<>();
        for (Criterion c : Config.get().criteria) {
            int n = e.counts.getOrDefault(c.item, 0);
            if (n > 0 && c.test(n)) parts.add(n + "x " + ItemUtil.name(c.item));
        }
        if (parts.isEmpty()) {
            int total = 0;
            for (int v : e.counts.values()) total += v;
            return total + " Items gesamt";
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ Abfangen des Fensters (Mixin)

    public static boolean shouldIntercept(Screen screen) {
        if (phase != Phase.WAIT_OPEN && swallowTicks <= 0) return false;
        if (!(screen instanceof HandledScreen<?>)) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        ScreenHandler h = mc.player.currentScreenHandler;
        return h != null && h != mc.player.playerScreenHandler;
    }

    public static void onIntercepted() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (phase != Phase.WAIT_OPEN) {   // verspaetetes Fenster nach Stopp/Timeout: still schliessen
            if (mc.getNetworkHandler() != null) closeHandler(mc, mc.player.currentScreenHandler);
            return;
        }
        handler = mc.player.currentScreenHandler;
        phase = Phase.WAIT_CONTENT;
        timer = 0;
    }

    // ------------------------------------------------------------------ Tick / Zustandsautomat

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            running = false;
            swallowTicks = 0;
            if (!ENTRIES.isEmpty() || phase != Phase.IDLE) {
                ENTRIES.clear();
                QUEUE.clear();
                phase = Phase.IDLE;
                handler = null;
                current = null;
                viewRequest = null;
                cooldown = 0;
            }
            return;
        }
        if (swallowTicks > 0) swallowTicks--;
        switch (phase) {
            case IDLE -> idle(mc);
            case WAIT_OPEN -> {
                if (++timer > OPEN_TIMEOUT) {
                    swallowTicks = 40;
                    fail();
                }
            }
            case WAIT_CONTENT -> content(mc);
        }
    }

    private static boolean canUseNow(MinecraftClient mc) {
        return mc.player.currentScreenHandler == mc.player.playerScreenHandler
                && !(mc.currentScreen instanceof HandledScreen<?>);
    }

    private static void idle(MinecraftClient mc) {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (!canUseNow(mc)) return;

        if (viewRequest != null) {
            String n = viewRequest;
            viewRequest = null;
            mc.getNetworkHandler().sendChatCommand("invsee " + n);
            cooldown = 60;
            return;
        }

        Entry next = QUEUE.poll();
        if (next == null) return;
        current = next;
        next.status = Status.SCANNING;
        next.lastAttempt = System.currentTimeMillis();
        phase = Phase.WAIT_OPEN;
        timer = 0;
        mc.getNetworkHandler().sendChatCommand("invsee " + next.name);
    }

    private static void content(MinecraftClient mc) {
        if (mc.player.currentScreenHandler != handler) {
            fail();
            return;
        }
        timer++;
        if (timer < CONTENT_WAIT) return;

        Map<String, Integer> counts = read(handler);
        if (counts.isEmpty() && timer < CONTENT_MAX) return;

        closeHandler(mc, handler);

        Entry e = current;
        e.counts = counts;
        e.scannedAt = System.currentTimeMillis();
        e.status = Status.DONE;
        boolean match = matches(e);
        if (match && !e.wasMatch && Config.get().notifyChat) {
            mc.player.sendMessage(Text.literal("[TPA-Hunter] ").formatted(Formatting.DARK_RED)
                    .append(Text.literal(e.name + " passt: " + describe(e)).formatted(Formatting.RED)), false);
        }
        e.wasMatch = match;

        phase = Phase.IDLE;
        current = null;
        handler = null;
        cooldown = Config.get().scanDelayTicks;
    }

    private static Map<String, Integer> read(ScreenHandler h) {
        Map<String, Integer> counts = new HashMap<>();
        for (Slot slot : h.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;   // eigenes Inventar ueberspringen
            ItemStack st = slot.getStack();
            if (st.isEmpty()) continue;
            counts.merge(Registries.ITEM.getId(st.getItem()).toString(), st.getCount(), Integer::sum);
        }
        return counts;
    }

    private static void closeHandler(MinecraftClient mc, ScreenHandler h) {
        mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(h.syncId));
        if (mc.player.currentScreenHandler == h) mc.player.currentScreenHandler = mc.player.playerScreenHandler;
    }

    private static void fail() {
        if (current != null) current.status = Status.FAILED;
        phase = Phase.IDLE;
        current = null;
        handler = null;
        cooldown = Config.get().scanDelayTicks;
    }

    private static void abort() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (phase == Phase.WAIT_OPEN) swallowTicks = 40;
        if (handler != null && mc.player != null && mc.getNetworkHandler() != null) closeHandler(mc, handler);
        if (current != null && current.status == Status.SCANNING) current.status = Status.NEW;
        phase = Phase.IDLE;
        current = null;
        handler = null;
    }
}
