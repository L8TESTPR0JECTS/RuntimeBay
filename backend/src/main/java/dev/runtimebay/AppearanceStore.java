package dev.runtimebay;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Cosmetic preferences live outside the web origin, which changes each launch. */
final class AppearanceStore {
    record Preferences(int version, String palette, String appearance, boolean onboardingComplete) {}
    private static final Set<String> PALETTES = Set.of("harbor", "ocean", "glacier", "forest", "sage", "amber", "copper", "clay", "rose", "plum", "violet", "graphite");
    private static final Set<String> MODES = Set.of("system", "light", "dark");
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Preferences current = new Preferences(1, "harbor", "system", false);
    AppearanceStore(Path directory) throws IOException {
        file = directory.resolve("appearance.json");
        if (Files.exists(file)) {
            try { current = validate(JsonParser.parseString(Files.readString(file)).getAsJsonObject()); }
            catch (RuntimeException e) { System.err.println("Invalid appearance preferences; using defaults. Existing file preserved."); }
        }
    }
    synchronized Preferences get() { return current; }
    synchronized Preferences save(JsonObject value) throws IOException {
        Preferences next = validate(value);
        Path temporary = Files.createTempFile(file.getParent(), "appearance-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, gson.toJson(next));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            current = next;
        } finally { Files.deleteIfExists(temporary); }
        return current;
    }
    private static Preferences validate(JsonObject value) {
        if (value.size() != 4 || !value.has("version") || !value.get("version").isJsonPrimitive()
            || !value.getAsJsonPrimitive("version").isNumber() || !value.get("version").getAsString().equals("1"))
            throw new ApiException(400, "Unsupported appearance preferences");
        String palette = text(value, "palette"), mode = text(value, "appearance");
        if (!PALETTES.contains(palette) || !MODES.contains(mode) || !value.has("onboardingComplete")
            || !value.get("onboardingComplete").isJsonPrimitive() || !value.getAsJsonPrimitive("onboardingComplete").isBoolean())
            throw new ApiException(400, "Choose a supported palette and appearance");
        return new Preferences(1, palette, mode, value.get("onboardingComplete").getAsBoolean());
    }
    private static String text(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString())
            throw new ApiException(400, "Invalid appearance preference");
        return value.get(key).getAsString();
    }
}
