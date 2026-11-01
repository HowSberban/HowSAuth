package org.howsauth.plugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * MySQL JDBC URL 的 IPv6 主机字面量处理。
 */
class PlayerDatabaseIpv6Test {

    @Test
    void bracketsBareIpv6Host() {
        assertEquals("[::1]", PlayerDatabase.jdbcHost("::1"));
        assertEquals("[2001:db8::1]", PlayerDatabase.jdbcHost("2001:db8::1"));
    }

    @Test
    void leavesIpv4HostnameAndAlreadyBracketedHostUntouched() {
        assertEquals("127.0.0.1", PlayerDatabase.jdbcHost("127.0.0.1"));
        assertEquals("db.example.com", PlayerDatabase.jdbcHost("db.example.com"));
        assertEquals("[::1]", PlayerDatabase.jdbcHost("[::1]"));
        assertNull(PlayerDatabase.jdbcHost(null));
    }
}
