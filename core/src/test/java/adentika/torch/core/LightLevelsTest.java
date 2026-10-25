package adentika.torch.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightLevelsTest {
    @Test
    void zeroBlockLightIsDangerous() {
        assertFalse(LightLevels.isSafeForOverworldMonsters(0));
    }

    @Test
    void anyBlockLightIsSafe() {
        for (int light = 1; light <= LightLevels.MAX; light++) {
            assertTrue(LightLevels.isSafeForOverworldMonsters(light), "light " + light);
        }
    }
}
