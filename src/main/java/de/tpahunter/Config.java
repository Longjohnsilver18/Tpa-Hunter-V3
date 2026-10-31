package de.tpahunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Config {
    public enum Mode {
        EGAL("egal"), GENAU("genau"), GROESSER("größer"), KLEINER("kleiner");

        public final String label;

        Mode(String label) { this.label = label; }

        public Mode next() {
            Mode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    public static class Criterion {
        public String item;
        public Mode mode = Mode.EGAL;
        public int amount = 1;

        public Criterion() {}

        public Criterion(String item) { this.item = item; }

        /** EGAL = mindestens 1 (Menge egal), GENAU = X, GROESSER = mehr als X, KLEINER = weniger als X */
        public boolean test(int count) {
            return switch (mode) {
                case EGAL -> count >= 1;
                case GENAU -> count == amount;
                case GROESSER -> count > amount;
                case KLEINER -> count < amount;
            };
        }
    }

    public static final String DEFAULT_REGEX =
            "(?i)(?<![\\p{L}\\d])(tpa\\w*|tp|tphere|tpr|tele(?:port\\w*)?)(?![\\p{L}\\d])";

    public List<Criterion> criteria = new ArrayList<>();
    public boolean matchAll = true;       // true = UND, false = ODER
    public boolean autoScan = true;
    public boolean notifyChat = true;
    public boolean onlyMatches = false;
    public int scanDelayTicks = 30;       // Pause zwischen zwei /invsee (20 Ticks = 1 s)
    public int rescanSeconds = 300;       // Auto-Rescan fruehestens nach so vielen Sekunden
    public String keywordRegex = DEFAULT_REGEX;

    private transient Pattern compiled;
    private transient String compiledFrom;

    public Pattern pattern() {
        if (compiled == null || !keywordRegex.equals(compiledFrom)) {
            try {
                compiled = Pattern.compile(keywordRegex);
            } catch (Exception e) {
                compiled = Pattern.compile(DEFAULT_REGEX);
            }
            compiledFrom = keywordRegex;
        }
        return compiled;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance = new Config();

    public static Config get() { return instance; }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("tpahunter.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                Config c = GSON.fromJson(Files.readString(p), Config.class);
                if (c != null) {
                    if (c.criteria == null) c.criteria = new ArrayList<>();
                    if (c.keywordRegex == null) c.keywordRegex = DEFAULT_REGEX;
                    instance = c;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(instance));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
