package dev.runtimebay;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceStoreTest {
    @TempDir Path directory;
    JsonObject preferences() { return JsonParser.parseString("{\"version\":1,\"palette\":\"copper\",\"appearance\":\"dark\",\"onboardingComplete\":true}").getAsJsonObject(); }
    @Test void survivesNewStoreAndUsesPrivateAtomicFile() throws Exception {
        AppearanceStore store = new AppearanceStore(directory);
        assertFalse(store.get().onboardingComplete());
        var saved = store.save(preferences());
        assertEquals(saved, new AppearanceStore(directory).get());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("appearance.json")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void rejectsInvalidChoicesWithoutChangingSavedPreferences() throws Exception {
        AppearanceStore store = new AppearanceStore(directory);
        var original = store.save(preferences());
        for (String field : new String[]{"palette", "appearance", "onboardingComplete", "version"}) {
            var invalid = preferences(); invalid.addProperty(field, "invalid");
            assertThrows(ApiException.class, () -> store.save(invalid));
        }
        assertEquals(original, new AppearanceStore(directory).get());
    }
    @Test void preservesCorruptCosmeticFileAndFallsBackToDefaults() throws Exception {
        Path file = directory.resolve("appearance.json"); Files.writeString(file, "malformed");
        assertEquals("harbor", new AppearanceStore(directory).get().palette());
        assertEquals("malformed", Files.readString(file));
    }
    @Test void migratesLegacyPreferencesAndPersistsFontSizeWithoutLosingAppearance() throws Exception {
        Files.writeString(directory.resolve("appearance.json"), preferences().toString());
        AppearanceStore store = new AppearanceStore(directory);
        assertEquals(14, store.get().fontSize());
        assertEquals("copper", store.get().palette());
        assertTrue(store.get().onboardingComplete());
        var larger = preferences(); larger.addProperty("fontSize", 20);
        store.save(larger);
        assertEquals(20, new AppearanceStore(directory).get().fontSize());
        // An older client saving a palette must not reset a newer font preference.
        store.save(preferences());
        assertEquals(20, new AppearanceStore(directory).get().fontSize());
    }
    @Test void rejectsInvalidFontSizesWithoutOverwritingSavedPreferences() throws Exception {
        AppearanceStore store = new AppearanceStore(directory);
        var valid = preferences(); valid.addProperty("fontSize", 18); store.save(valid);
        for (String value : new String[]{"11", "21", "14.5", "null", "true", "\"18\"", "{}", "1e100"}) {
            var invalid = preferences(); invalid.add("fontSize", JsonParser.parseString(value));
            assertThrows(ApiException.class, () -> store.save(invalid), value);
        }
        assertEquals(18, new AppearanceStore(directory).get().fontSize());
    }
}
