package org.howsauth.plugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.Location;
import org.bukkit.World;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/**
 * 退出位置序列化契约（world:x:y:z:yaw:pitch）：登录后传送回原位置依赖它，
 * 格式错误或世界不存在时必须返回 null（调用方据此跳过传送），而不是抛异常。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayerDataLocationTest {

    private MockBukkitHarness env;

    @BeforeAll
    void bootstrap() throws Exception {
        env = MockBukkitHarness.start("hsauth-location-test");
    }

    @AfterAll
    void teardown() {
        if (env != null) {
            env.close();
        }
    }

    /** 序列化格式固定为 world:x:y:z:yaw:pitch，且反序列化后各分量一致 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void roundTripPreservesCoordinates() {
        World world = env.server().getWorld("world");
        assertNotNull(world, "the test world must exist");
        Location original = new Location(world, 12.5, 64.0, -33.25, 90f, 45f);

        String serialized = PlayerData.serializeLocation(original);
        assertEquals("world:12.5:64.0:-33.25:90.0:45.0", serialized, "serialized form must be world:x:y:z:yaw:pitch");

        Location parsed = PlayerData.deserializeLocation(serialized);
        assertNotNull(parsed, "a valid string must be deserializable");
        assertNotNull(parsed.getWorld(), "a world must be resolved");
        assertEquals("world", parsed.getWorld().getName(), "the world name must match");
        assertEquals(original.getX(), parsed.getX(), 1e-9, "x must match");
        assertEquals(original.getY(), parsed.getY(), 1e-9, "y must match");
        assertEquals(original.getZ(), parsed.getZ(), 1e-9, "z must match");
        assertEquals(original.getYaw(), parsed.getYaw(), 1e-6, "yaw must match");
        assertEquals(original.getPitch(), parsed.getPitch(), 1e-6, "pitch must match");
    }

    /** 非法/不完整输入与世界不存在时返回 null（不抛异常） */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void rejectsMalformedInput() {
        assertNull(PlayerData.deserializeLocation(null), "null must yield null");
        assertNull(PlayerData.deserializeLocation(""), "an empty string must yield null");
        assertNull(PlayerData.deserializeLocation("world"), "too few segments must yield null");
        assertNull(PlayerData.deserializeLocation("world:1:2:3:0"), "5 segments must yield null");
        assertNull(PlayerData.deserializeLocation("world:1:2:3:0:0:extra"), "7 segments must yield null");
        assertNull(PlayerData.deserializeLocation("missing-world:1:2:3:0:0"), "a missing world must yield null");
        assertNull(PlayerData.deserializeLocation("world:a:b:c:0:0"), "non-numeric coordinates must yield null");
        assertNull(PlayerData.deserializeLocation("world:1:2:3:x:y"), "non-numeric rotation must yield null");
    }
}
