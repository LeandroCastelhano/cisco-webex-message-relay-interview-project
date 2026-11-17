package dev.leandrocastelhano.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class RelayConfigTest {
    private final Map<String, String> originalProperties = new HashMap<>();
    private static final String[] HTTP_PROPERTIES = {
            "jdk.httpserver.maxConnections", "sun.net.httpserver.maxReqHeaderSize",
            "sun.net.httpserver.maxReqTime", "sun.net.httpserver.maxRspTime",
            "sun.net.httpserver.idleInterval"
    };

    @BeforeEach void saveProperties() {
        for (String name : HTTP_PROPERTIES) {
            originalProperties.put(name, System.getProperty(name));
            System.clearProperty(name);
        }
    }

    @AfterEach void restoreProperties() {
        originalProperties.forEach((name, value) -> {
            if (value == null) System.clearProperty(name);
            else System.setProperty(name, value);
        });
    }

    @Test void usesDefaultsWhenNoOverridesAreProvided() {
        assertEquals(RelayConfig.defaults(), RelayConfig.fromSystemProperties());
    }

    @Test void loadsAndAppliesHttpOverridesInSeconds() {
        System.setProperty("jdk.httpserver.maxConnections", "16");
        System.setProperty("sun.net.httpserver.maxReqHeaderSize", "2048");
        System.setProperty("sun.net.httpserver.maxReqTime", "3");
        System.setProperty("sun.net.httpserver.maxRspTime", "4");
        System.setProperty("sun.net.httpserver.idleInterval", "5");

        var config = RelayConfig.fromSystemProperties();
        assertEquals(16, config.maxConnections());
        assertEquals(2048, config.maxHeaderBytes());
        assertEquals(3, config.requestTimeoutSeconds());
        assertEquals(4, config.responseTimeoutSeconds());
        assertEquals(5, config.idleConnectionTimeoutSeconds());
        assertEquals(RelayConfig.defaults().limits(), config.limits());

        // Clear the properties first to check that the config sets them again.
        for (String name : HTTP_PROPERTIES) System.clearProperty(name);
        config.applyHttpProperties();
        assertEquals("16", System.getProperty("jdk.httpserver.maxConnections"));
        assertEquals("2048", System.getProperty("sun.net.httpserver.maxReqHeaderSize"));
        assertEquals("3", System.getProperty("sun.net.httpserver.maxReqTime"));
        assertEquals("4", System.getProperty("sun.net.httpserver.maxRspTime"));
        assertEquals("5", System.getProperty("sun.net.httpserver.idleInterval"));
    }

    @Test void rejectsInvalidHttpOverridesInsteadOfSilentlyFallingBack() {
        for (String name : HTTP_PROPERTIES) {
            for (String value : new String[] {"", "abc", "0", "-1", "2147483648"}) {
                System.setProperty(name, value);
                assertThrows(IllegalArgumentException.class, RelayConfig::fromSystemProperties,
                        name + "=" + value);
            }
            System.clearProperty(name);
        }
    }

    @Test void rejectsNonPositiveResourceLimitsAndRequestSizeOverflow() {
        // Check each HTTP setting with zero and a negative value.
        for (int position = 0; position < 9; position++) {
            for (int invalid : new int[] {0, -1}) {
                int[] values = {8192, 8, 32, 32, 64, 8192, 10, 10, 10};
                values[position] = invalid;
                assertThrows(IllegalArgumentException.class,
                        () -> new RelayConfig(RelayConfig.defaults().limits(),
                                values[0], values[1], values[2], values[3], values[4],
                                values[5], values[6], values[7], values[8]));
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> new RelayConfig(RelayConfig.defaults().limits(),
                        Integer.MAX_VALUE, 8, 32, 32, 64, 8192, 10, 10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new RelayConfig(null, 8192, 8, 32, 32, 64, 8192, 10, 10, 10));
    }
}
