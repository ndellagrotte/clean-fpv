package io.github.ndellagrotte.cleanfpv.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsStoreTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private SettingsStore store() {
        return new SettingsStore(dir.toFile(), FIXED);
    }

    private static final String LEGACY_V1_FILE = """
            {
              "schemaVersion": 1,
              "models": {
                "5in Radio": {
                  "name": "5in Radio", "preset": true, "scheme": "RADIO",
                  "controllerGuid": "03000000c01600008704000011010000", "controllerName": "RadioMaster TX16S",
                  "channels": { "throttleAxis": 2, "rollAxis": 0, "pitchAxis": 1, "yawAxis": 3,
                                "invertPitch": true, "armSwitch": 4,
                                "calMin": [-0.62, -0.7, -0.66, -0.64, -1.0, -1.0, -1.0, -1.0],
                                "calMax": [0.61, 0.69, 0.67, 0.65, 1.0, 1.0, 1.0, 1.0] },
                  "rollRates": { "rate": 1.3, "superRate": 0.7, "expo": 0.1 },
                  "pitchRates": { "rate": 1.3, "superRate": 0.7, "expo": 0.1 },
                  "yawRates": { "rate": 1.0, "superRate": 0.6, "expo": 0.0 },
                  "mouseGain": 1.0, "showCrosshairs": false, "showBlockOutline": false,
                  "showStickOverlay": true, "switchlessAngle": 25.0, "fov": 120.0, "useFisheye": false,
                  "flightMode3d": false, "useRealtimePhysics": false, "highFidelity": true
                },
                "5in Gamepad": { "name": "5in Gamepad", "preset": true, "scheme": "GAMEPAD", "showCrosshairs": true },
                "5in Keyboard": { "name": "5in Keyboard", "preset": true, "scheme": "KEYBOARD", "mouseGain": 3.0 },
                "Tiny Whoop": { "name": "Tiny Whoop", "preset": true, "scheme": "RADIO", "fov": 140.0 },
                "test_model_1": {
                  "name": "test_model_1", "preset": false, "scheme": "RADIO", "mouseGain": 2.5,
                  "build": { "motorKv": 1800.0, "blades": 2 }
                }
              },
              "currentModel": "5in Radio",
              "firstTimeSetup": false
            }
            """;

    private SettingsStore loaded() {
        SettingsStore s = store();
        s.load();
        return s;
    }

    private void writeFile(String text) throws IOException {
        Files.writeString(dir.resolve(SettingsStore.FILE_NAME), text, StandardCharsets.UTF_8);
    }

    private JsonObject readFile() throws IOException {
        return JsonParser.parseString(Files.readString(dir.resolve(SettingsStore.FILE_NAME))).getAsJsonObject();
    }

    @Test
    void freshLoadCreatesDefaults() throws IOException {
        SettingsStore s = loaded();
        assertTrue(s.file().isFile());
        assertEquals(List.of("5 Inch 4S", "Tiny Whoop"), s.modelNames(), "exactly the two built-in models");
        assertEquals("5 Inch 4S", s.currentName());
        assertEquals(Presets.FIVE_INCH, Presets.defaultModel());
        assertTrue(s.isFirstTimeSetup());
        assertTrue(s.current().preset);
        assertEquals(DroneBuild.fiveInch(), s.model(Presets.FIVE_INCH).build);
        assertEquals(DroneBuild.tinyWhoop(), s.model(Presets.TINY_WHOOP).build);
        for (String p : Presets.names()) {
            assertEquals(ControllerScheme.RADIO, s.model(p).scheme, "presets start with radio defaults");
        }
        assertEquals(Settings.SCHEMA_VERSION, readFile().get("schemaVersion").getAsInt());
        assertFalse(new File(dir.toFile(), SettingsStore.FILE_NAME + SettingsStore.LEGACY_BACKUP_SUFFIX).exists());
        assertFalse(new File(dir.toFile(), SettingsStore.FILE_NAME + ".tmp").exists());
    }

    @Test
    void roundTripKeepsEverything() throws IOException {
        SettingsStore s = loaded();
        DroneModelConfig cur = s.current();
        cur.rollRates.set(2.0f, 0.5f, 0.25f);
        cur.mouseGain = 2.7f;
        cur.controllerGuid = "0300abcdef";
        cur.controllerName = "Test Radio";
        cur.channels.invertArm = true;
        cur.channels.invertAngle = true;
        cur.channels.invertAngleSwitch = true;
        cur.channels.invertRightClick = true;
        cur.channels.armSwitch = -2;
        cur.channels.calMin[5] = -0.8f;
        cur.channels.calMax[5] = 0.7f;
        cur.fov = 120f;
        DroneModelConfig mine = s.cloneCurrent("Mine");
        assertNotNull(mine);
        mine.build.motorKv = 1750f;
        mine.build.mass = 650f;
        assertTrue(s.select("Mine"));
        s.setFirstTimeSetup(false);
        assertTrue(s.save());

        // preset build keys are not written, user builds are
        JsonObject root = readFile();
        JsonObject models = root.getAsJsonObject("models");
        assertFalse(models.getAsJsonObject(Presets.FIVE_INCH).has("build"));
        assertTrue(models.getAsJsonObject("Mine").has("build"));

        SettingsStore r = loaded();
        assertEquals("Mine", r.currentName());
        assertFalse(r.isFirstTimeSetup());
        DroneModelConfig radio = r.model(Presets.FIVE_INCH);
        assertEquals(new RateTriple(2.0f, 0.5f, 0.25f), radio.rollRates);
        assertEquals(2.7f, radio.mouseGain);
        assertEquals("0300abcdef", radio.controllerGuid);
        assertEquals("Test Radio", radio.controllerName);
        assertTrue(radio.channels.invertArm && radio.channels.invertAngle
                && radio.channels.invertAngleSwitch && radio.channels.invertRightClick);
        assertEquals(-2, radio.channels.armSwitch);
        assertEquals(-0.8f, radio.channels.calMin[5]);
        assertEquals(0.7f, radio.channels.calMax[5]);
        assertEquals(120f, radio.fov);
        DroneModelConfig m = r.current();
        assertFalse(m.preset);
        assertEquals(1750f, m.build.motorKv);
        assertEquals(650f, m.build.mass);
        assertEquals(new RateTriple(2.0f, 0.5f, 0.25f), m.rollRates);
        List<String> expected = new ArrayList<>(Presets.names());
        expected.add("Mine");
        assertEquals(expected, r.modelNames());
    }

    @Test
    void corruptEntryIsDroppedOthersLoad() throws IOException {
        writeFile("""
                {
                  "schemaVersion": 1,
                  "currentModel": "Bad",
                  "firstTimeSetup": false,
                  "models": {
                    "Good": { "mouseGain": 3.5, "scheme": "GAMEPAD", "fov": 999 },
                    "Bad": { "mouseGain": "not a number" },
                    "AlsoBad": 42,
                    "Rates": { "rollRates": [1, 2, 3] }
                  }
                }
                """);
        SettingsStore s = loaded();
        assertNotNull(s.model("Good"));
        assertNull(s.model("Bad"));
        assertNull(s.model("AlsoBad"));
        assertNull(s.model("Rates"));
        assertEquals(3.5f, s.model("Good").mouseGain);
        assertEquals(ControllerScheme.GAMEPAD, s.model("Good").scheme);
        assertEquals(DroneModelConfig.FOV_MAX, s.model("Good").fov, "normalized after load");
        assertEquals(Presets.defaultModel(), s.currentName(), "unknown current falls back");
        assertFalse(s.isFirstTimeSetup());
        assertEquals(0, listBackups().length, "entry corruption is not a file corruption");
        for (String p : Presets.names()) {
            assertNotNull(s.model(p), "missing presets re-added");
        }
    }

    @Test
    void corruptFileIsBackedUpAndRecreated() throws IOException {
        String garbage = "{ \"models\": { oops";
        writeFile(garbage);
        SettingsStore s = loaded();
        File backup = new File(dir.toFile(), SettingsStore.FILE_NAME + ".corrupt-20260102-030405");
        assertTrue(backup.isFile(), "backup named from the injected clock");
        assertEquals(garbage, Files.readString(backup.toPath()));
        assertTrue(s.isFirstTimeSetup());
        assertEquals(Presets.names(), s.modelNames());
        assertEquals(Presets.names().size(), readFile().getAsJsonObject("models").size(), "defaults rewritten");

        // second corruption in the same second gets a distinct name
        writeFile("[]");
        loaded();
        assertTrue(new File(dir.toFile(), SettingsStore.FILE_NAME + ".corrupt-20260102-030405-1").isFile());
    }

    @Test
    void wrongTopLevelTypesCountAsCorruptFile() throws IOException {
        writeFile("{ \"models\": [1, 2], \"currentModel\": \"x\" }");
        SettingsStore s = loaded();
        assertEquals(1, listBackups().length);
        assertEquals(Presets.defaultModel(), s.currentName());
    }

    @Test
    void storedPresetBuildIsIgnoredButPresetSettingsKept() throws IOException {
        writeFile("""
                { "currentModel": "Tiny Whoop",
                  "models": {
                    "Tiny Whoop": { "preset": false, "mouseGain": 4.0,
                                    "build": { "motorKv": 9999, "blades": 7 } },
                    "Custom": { "preset": true }
                  } }
                """);
        SettingsStore s = loaded();
        DroneModelConfig whoop = s.model(Presets.TINY_WHOOP);
        assertTrue(whoop.preset);
        assertEquals(DroneBuild.tinyWhoop(), whoop.build);
        assertEquals(4.0f, whoop.mouseGain);
        assertFalse(s.model("Custom").preset, "only preset names can be presets");
        assertTrue(s.delete("Custom"));
    }

    @Test
    void presetsAreDeleteAndRenameProtected() {
        SettingsStore s = loaded();
        for (String p : Presets.names()) {
            assertFalse(s.delete(p));
            assertFalse(s.rename(p, "Other"));
            assertNotNull(s.model(p));
        }
        DroneModelConfig replaced = s.put(DroneModelConfig.keyboard(Presets.FIVE_INCH));
        assertNotNull(replaced);
        assertTrue(replaced.preset, "put over a preset keeps it a preset");
        assertEquals(DroneBuild.fiveInch(), replaced.build);
        assertEquals(ControllerScheme.KEYBOARD, replaced.scheme);
    }

    @Test
    void deleteAndRenameUserModels() {
        SettingsStore s = loaded();
        s.cloneCurrent("A");
        s.cloneCurrent("B");
        assertTrue(s.select("A"));
        assertTrue(s.rename("A", "  Alpha  "));
        assertEquals("Alpha", s.currentName());
        assertEquals("Alpha", s.current().name);
        List<String> names = s.modelNames();
        assertEquals(List.of("Alpha", "B"), names.subList(names.size() - 2, names.size()), "order kept");
        assertFalse(s.rename("Alpha", "B"), "duplicate");
        assertTrue(s.delete("Alpha"));
        assertEquals(Presets.defaultModel(), s.currentName(), "deleting the active model selects the default");
        assertFalse(s.delete("Alpha"));
    }

    @Test
    void cloneCopiesTheCurrentModelNotTheDefault() {
        SettingsStore s = loaded();
        assertTrue(s.select(Presets.TINY_WHOOP));
        DroneModelConfig whoop = s.current();
        whoop.yawRates.set(0.5f, 0.1f, 0.9f);
        whoop.switchlessAngle = 12f;
        DroneModelConfig copy = s.cloneCurrent("My Whoop");
        assertNotNull(copy);
        assertEquals("My Whoop", copy.name);
        assertFalse(copy.preset);
        assertEquals(DroneBuild.tinyWhoop(), copy.build);
        assertEquals(new RateTriple(0.5f, 0.1f, 0.9f), copy.yawRates);
        assertEquals(12f, copy.switchlessAngle);
        assertEquals(Presets.TINY_WHOOP, s.currentName(), "clone does not select");
        assertNotSame(whoop.build, copy.build);
        assertNotSame(whoop.channels, copy.channels);
        copy.build.motorKv = 100f;
        copy.channels.invertYaw = true;
        assertEquals(13000f, whoop.build.motorKv, "deep copy");
        assertFalse(whoop.channels.invertYaw);
        assertNull(s.cloneCurrent("My Whoop"), "duplicate name rejected");
    }

    @Test
    void nameValidation() {
        SettingsStore s = loaded();
        assertEquals(SettingsStore.NameCheck.EMPTY, s.checkName("   "));
        assertEquals(SettingsStore.NameCheck.EMPTY, s.checkName(null));
        assertEquals(SettingsStore.NameCheck.TOO_LONG, s.checkName("x".repeat(SettingsStore.MAX_NAME_LENGTH + 1)));
        assertEquals(SettingsStore.NameCheck.OK, s.checkName("x".repeat(SettingsStore.MAX_NAME_LENGTH)));
        assertEquals(SettingsStore.NameCheck.RESERVED, s.checkName(Presets.TINY_WHOOP));
        s.cloneCurrent("Taken");
        assertEquals(SettingsStore.NameCheck.DUPLICATE, s.checkName(" Taken "));
        assertNull(s.cloneCurrent(""));
    }

    @Test
    void listenersSeeTheLiveCurrentModel() {
        SettingsStore s = store();
        List<String> seen = new ArrayList<>();
        s.addListener((change, current) -> seen.add(change + ":" + current.name));
        s.load();
        s.cloneCurrent("Z");
        s.select("Z");
        s.save();
        s.delete("Z");
        assertEquals(List.of(
                "LOADED:" + Presets.defaultModel(),
                "MODELS:" + Presets.defaultModel(),
                "SELECTED:Z",
                "SAVED:Z",
                "MODELS:" + Presets.defaultModel()), seen);
    }

    @Test
    void nonFiniteCalibrationIsRepairedBeforeSaving() throws IOException {
        SettingsStore s = loaded();
        s.current().channels.calMin[0] = Float.NaN;
        s.current().channels.calMax[1] = Float.POSITIVE_INFINITY;
        assertTrue(s.save());
        SettingsStore r = loaded();
        assertArrayEquals(new float[] {-1f, 1f},
                new float[] {r.current().channels.calMin[0], r.current().channels.calMax[1]});
    }

    @Test
    void legacyV1FileIsMigrated() throws IOException {
        writeFile(LEGACY_V1_FILE);
        SettingsStore s = loaded();

        assertEquals(List.of("5 Inch 4S", "Tiny Whoop", "test_model_1"), s.modelNames(),
                "legacy scheme presets dropped, user model kept");
        assertEquals("5 Inch 4S", s.currentName(), "current '5in Radio' maps to '5 Inch 4S'");
        assertFalse(s.isFirstTimeSetup());

        DroneModelConfig five = s.model(Presets.FIVE_INCH);
        assertTrue(five.preset);
        assertEquals(DroneBuild.fiveInch(), five.build);
        assertEquals(ControllerScheme.RADIO, five.scheme);
        assertEquals("03000000c01600008704000011010000", five.controllerGuid);
        assertEquals("RadioMaster TX16S", five.controllerName);
        assertEquals(2, five.channels.throttleAxis);
        assertTrue(five.channels.invertPitch);
        assertEquals(4, five.channels.armSwitch);
        assertEquals(-0.62f, five.channels.calMin[0], "calibration preserved");
        assertEquals(0.69f, five.channels.calMax[1]);
        assertEquals(new RateTriple(1.3f, 0.7f, 0.1f), five.rollRates);
        assertEquals(new RateTriple(1.0f, 0.6f, 0.0f), five.yawRates);
        assertFalse(five.showBlockOutline);
        assertEquals(25f, five.switchlessAngle);
        assertEquals(120f, five.fov);
        assertFalse(five.useFisheye);

        assertEquals(140f, s.model(Presets.TINY_WHOOP).fov, "Tiny Whoop settings untouched");
        assertEquals(DroneBuild.tinyWhoop(), s.model(Presets.TINY_WHOOP).build);
        DroneModelConfig user = s.model("test_model_1");
        assertFalse(user.preset);
        assertEquals(2.5f, user.mouseGain);
        assertEquals(1800f, user.build.motorKv, "user build untouched");
        assertEquals(2, user.build.blades);

        // rewritten once as the current schema, original kept aside
        JsonObject root = readFile();
        assertEquals(Settings.SCHEMA_VERSION, root.get("schemaVersion").getAsInt());
        assertEquals("5 Inch 4S", root.get("currentModel").getAsString());
        assertFalse(root.getAsJsonObject("models").has("5in Radio"));
        File backup = new File(dir.toFile(), SettingsStore.FILE_NAME + SettingsStore.LEGACY_BACKUP_SUFFIX);
        assertEquals(LEGACY_V1_FILE, Files.readString(backup.toPath()));

        // a second load is a plain load of the migrated file
        SettingsStore again = loaded();
        assertEquals(s.modelNames(), again.modelNames());
        assertEquals(-0.62f, again.current().channels.calMin[0]);
    }

    @Test
    void legacyCurrentGamepadPresetIsTheOneCarriedOver() throws IOException {
        writeFile(LEGACY_V1_FILE.replace("\"currentModel\": \"5in Radio\"", "\"currentModel\": \"5in Gamepad\""));
        SettingsStore s = loaded();
        assertEquals("5 Inch 4S", s.currentName());
        assertEquals(ControllerScheme.GAMEPAD, s.current().scheme);
        assertTrue(s.current().showCrosshairs);
        assertEquals(DroneBuild.fiveInch(), s.current().build);
    }

    @Test
    void legacyUserModelCurrentKeepsRadioSettingsOnFiveInch() throws IOException {
        writeFile(LEGACY_V1_FILE.replace("\"currentModel\": \"5in Radio\"", "\"currentModel\": \"test_model_1\""));
        SettingsStore s = loaded();
        assertEquals("test_model_1", s.currentName(), "a user current model stays current");
        assertEquals("RadioMaster TX16S", s.model(Presets.FIVE_INCH).controllerName,
                "5in Radio settings carried onto 5 Inch 4S when no legacy preset was current");
    }

    @Test
    void legacyUserModelWithANewPresetNameIsRenamed() throws IOException {
        writeFile("""
                { "schemaVersion": 1, "currentModel": "5 Inch 4S", "firstTimeSetup": false,
                  "models": {
                    "5in Radio": { "preset": true, "mouseGain": 4.0 },
                    "Tiny Whoop": { "preset": true },
                    "5 Inch 4S": { "preset": false, "mouseGain": 1.5, "build": { "motorKv": 2000.0 } },
                    "5 Inch 4S 2": { "preset": false }
                  } }
                """);
        SettingsStore s = loaded();
        assertEquals(List.of("5 Inch 4S", "Tiny Whoop", "5 Inch 4S 3", "5 Inch 4S 2"), s.modelNames());
        DroneModelConfig renamed = s.model("5 Inch 4S 3");
        assertFalse(renamed.preset);
        assertEquals("5 Inch 4S 3", renamed.name);
        assertEquals(1.5f, renamed.mouseGain);
        assertEquals(2000f, renamed.build.motorKv, "user build kept under the new name");
        assertEquals("5 Inch 4S 3", s.currentName(), "current follows the renamed user model");
        assertTrue(s.model(Presets.FIVE_INCH).preset);
        assertEquals(4.0f, s.model(Presets.FIVE_INCH).mouseGain, "5in Radio carried onto the preset");
    }

    @Test
    void schemaTwoFileIsNotMigrated() throws IOException {
        writeFile("""
                { "schemaVersion": 2, "currentModel": "5in Radio",
                  "models": { "5in Radio": { "preset": false, "mouseGain": 2.0 } } }
                """);
        SettingsStore s = loaded();
        assertNotNull(s.model("5in Radio"), "legacy names are ordinary user names in schema 2");
        assertEquals("5in Radio", s.currentName());
        assertFalse(s.model("5in Radio").preset);
        assertFalse(new File(dir.toFile(), SettingsStore.FILE_NAME + SettingsStore.LEGACY_BACKUP_SUFFIX).exists());
    }

    @Test
    void applySchemeChangesControllerSettingsNotIdentityOrBuild() {
        SettingsStore s = loaded();
        DroneModelConfig draft = s.current().copy();
        draft.rollRates.set(2.2f, 0.4f, 0.3f);
        assertTrue(draft.applyScheme(ControllerScheme.GAMEPAD));
        assertEquals(ControllerScheme.GAMEPAD, draft.scheme);
        assertEquals(RateTriple.gamepad(), draft.rollRates);
        assertEquals(RateTriple.gamepadYaw(), draft.yawRates);
        assertTrue(draft.showCrosshairs);
        draft.rollRates.set(2.2f, 0.4f, 0.3f);
        assertFalse(draft.applyScheme(ControllerScheme.GAMEPAD), "same scheme: nothing reset");
        assertEquals(new RateTriple(2.2f, 0.4f, 0.3f), draft.rollRates);

        // committed like the wizard does: stays the 5 Inch 4S preset with its built-in build
        DroneModelConfig stored = s.put(draft);
        assertNotNull(stored);
        assertEquals(Presets.FIVE_INCH, stored.name);
        assertTrue(stored.preset);
        assertEquals(DroneBuild.fiveInch(), stored.build);
        assertEquals(List.of("5 Inch 4S", "Tiny Whoop"), s.modelNames(), "no scheme preset appears");
        assertEquals(ControllerScheme.GAMEPAD, s.current().scheme);

        assertTrue(stored.applyScheme(ControllerScheme.KEYBOARD));
        assertEquals(RateTriple.radio(), stored.rollRates);
        assertFalse(stored.showCrosshairs);
        assertTrue(stored.preset);
        assertEquals(DroneBuild.fiveInch(), stored.build);
    }

    private File[] listBackups() {
        File[] files = dir.toFile().listFiles((d, n) -> n.startsWith(SettingsStore.FILE_NAME + ".corrupt-"));
        return files == null ? new File[0] : files;
    }
}
