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
}
