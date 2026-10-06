package org.howsauth.plugin.dialog;

import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置阶段名字认领：先到者持续占有连接，后来者被拒。
 * <p>
 * 对应 AuthMe 6.0.1 修复的 pre-join 会话接管问题：离线模式下同名连接解析为同一 UUID，
 * 而服务端的单名单会话检查要到 play 阶段才生效，配置阶段必须由插件认领名字。
 */
class NameClaimsTest {

    private MockBukkitHarness env;
    private NameClaims claims;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-nameclaims-", config -> {});
        claims = new NameClaims(env.config());
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void firstConnectionKeepsTheName() {
        Object first = new Object();
        Object second = new Object();

        assertTrue(claims.claim("Steve", first), "先到的连接应认领成功");
        assertFalse(claims.claim("Steve", second),
                "同名的后到连接必须被拒绝（先到者持续占有，而不是被踢下线）");
        // 被拒的后来者不得影响先到者：后者仍持续持有该名字
        assertFalse(claims.claim("Steve", new Object()), "先到者的认领必须持续有效");
    }

    @Test
    void nameIsCaseInsensitive() {
        assertTrue(claims.claim("Steve", new Object()), "首次认领应成功");
        assertFalse(claims.claim("steve", new Object()),
                "大小写变体是同一个玩家名，必须同样被拒（与服务端同名检查口径一致）");
        assertFalse(claims.claim("STEVE", new Object()), "全大写变体同样必须被拒");
    }

    @Test
    void differentNamesDoNotConflict() {
        assertTrue(claims.claim("Steve", new Object()), "第一个名字应认领成功");
        assertTrue(claims.claim("Alex", new Object()), "不同名字互不影响");
    }

    @Test
    void releaseOnlyAppliesToTheOwner() {
        Object owner = new Object();
        Object intruder = new Object();
        assertTrue(claims.claim("Steve", owner), "先到者认领成功");

        // 非持有者尝试释放：必须无效，否则后来者可以挤掉先到者
        claims.release("Steve", intruder);
        assertFalse(claims.claim("Steve", new Object()), "非持有者的释放不得交出名字");

        // 真正的持有者释放后，名字可被重新连接使用
        claims.release("Steve", owner);
        assertTrue(claims.claim("Steve", new Object()), "持有者释放后应允许重新认领");
    }

    @Test
    void releaseByNameFreesOnJoin() {
        Object owner = new Object();
        assertTrue(claims.claim("Steve", owner), "配置阶段认领成功");

        // 玩家进入世界：play 阶段起服务端单会话检查接管，插件侧认领释放
        claims.releaseByName("Steve");
        assertTrue(claims.claim("Steve", new Object()),
                "进入世界后名字必须可被重新连接使用（否则名字会在 TTL 内被锁死）");
    }
}
