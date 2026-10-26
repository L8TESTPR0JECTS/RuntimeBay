package dev.runtimebay;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Cosmetic preferences live outside the web origin, which changes each launch. */
final class AppearanceStore {
    record Preferences(int version, String palette, String appearance, boolean onboardingComplete, int fontSize, boolean suggestionsEnabled) {}
    private static final Set<String> PALETTES = Set.of("harbor", "ocean", "glacier", "forest", "sage", "amber", "copper", "clay", "rose", "plum", "violet", "graphite");
    private static final Set<String> MODES = Set.of("system", "light", "dark");
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Preferences current = new Preferences(1, "harbor", "system", false, 14, true);
    AppearanceStore(Path directory) throws IOException {
        file = directory.resolve("appearance.json");
        if (Files.exists(file)) {
            try { current = validate(JsonParser.parseString(Files.readString(file)).getAsJsonObject(), 14, true); }
            catch (RuntimeException e) { System.err.println("Invalid appearance preferences; using defaults. Existing file preserved."); }
        }
    }
    synchronized Preferences get() { return current; }
    synchronized Preferences save(JsonObject value) throws IOException {
        Preferences next = validate(value, current.fontSize(), current.suggestionsEnabled());
        Path temporary = Files.createTempFile(file.getParent(), "appearance-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, gson.toJson(next));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            current = next;
        } finally { Files.deleteIfExists(temporary); }
        return current;
    }
    private static Preferences validate(JsonObject value, int fallbackFontSize, boolean fallbackSuggestionsEnabled) {
        if (value.size() < 4 || value.size() > 6 || !value.has("version") || !value.get("version").isJsonPrimitive()
            || !value.getAsJsonPrimitive("version").isNumber() || !value.get("version").getAsString().equals("1"))
            throw new ApiException(400, "Unsupported appearance preferences");
        String palette = text(value, "palette"), mode = text(value, "appearance");
        if (!PALETTES.contains(palette) || !MODES.contains(mode) || !value.has("onboardingComplete")
            || !value.get("onboardingComplete").isJsonPrimitive() || !value.getAsJsonPrimitive("onboardingComplete").isBoolean())
            throw new ApiException(400, "Choose a supported palette and appearance");
        int fontSize = fallbackFontSize;
        if (value.has("fontSize")) {
            JsonElement size = value.get("fontSize");
            if (!size.isJsonPrimitive() || !size.getAsJsonPrimitive().isNumber()) throw new ApiException(400, "Choose a font size between 12 and 20 pixels");
            try { fontSize = size.getAsBigDecimal().intValueExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw new ApiException(400, "Choose a whole-number font size"); }
            if (fontSize < 12 || fontSize > 20) throw new ApiException(400, "Choose a font size between 12 and 20 pixels");
        }
        boolean suggestionsEnabled = fallbackSuggestionsEnabled;
        if (value.has("suggestionsEnabled")) {
            JsonElement enabled = value.get("suggestionsEnabled");
            if (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) throw new ApiException(400, "Choose whether terminal suggestions are enabled");
            suggestionsEnabled = enabled.getAsBoolean();
        }
        for (String key : value.keySet()) if (!Set.of("version", "palette", "appearance", "onboardingComplete", "fontSize", "suggestionsEnabled").contains(key)) throw new ApiException(400, "Unsupported appearance preferences");
        return new Preferences(1, palette, mode, value.get("onboardingComplete").getAsBoolean(), fontSize, suggestionsEnabled);
    }
    private static String text(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString())
            throw new ApiException(400, "Invalid appearance preference");
        return value.get(key).getAsString();
    }
}
