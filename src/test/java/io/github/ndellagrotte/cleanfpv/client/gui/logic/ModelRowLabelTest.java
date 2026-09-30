package io.github.ndellagrotte.cleanfpv.client.gui.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelRowLabelTest {

    @Test
    void rowShowsOnlyTheNameAndMarkers() {
        assertEquals("Tiny Whoop", ModelRowLabel.of("Tiny Whoop", false, false, "[active]"));
        assertEquals("5 Inch 4S §a[active]", ModelRowLabel.of("5 Inch 4S", false, true, "[active]"));
        assertEquals("§e> test_model_1 §e<", ModelRowLabel.of("test_model_1", true, false, "[active]"));
        assertEquals("§e> 5 Inch 4S §a[active] §e<", ModelRowLabel.of("5 Inch 4S", true, true, "[active]"));
    }
}
