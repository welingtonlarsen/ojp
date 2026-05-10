package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.ServerConfiguration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedStatementCachePropertyTranslatorTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("ojp.connection.pool.statementCache.enabled");
        System.clearProperty("ojp.connection.pool.statementCache.maxSize");
        System.clearProperty("ojp.connection.pool.statementCache.sqlLimit");
        System.clearProperty("ojp.connection.pool.statementCache.serverPrepare");
        System.clearProperty("ojp.connection.pool.statementCache.prepareThreshold");
        System.clearProperty("ojp.xa.connection.pool.statementCache.enabled");
        System.clearProperty("ojp.xa.connection.pool.statementCache.maxSize");
        System.clearProperty("ojp.xa.connection.pool.statementCache.sqlLimit");
        System.clearProperty("ojp.xa.connection.pool.statementCache.serverPrepare");
        System.clearProperty("ojp.xa.connection.pool.statementCache.prepareThreshold");
    }

    @Test
    void shouldTranslateMySqlNonXaStatementCacheDefaults() {
        ServerConfiguration configuration = new ServerConfiguration();

        Map<String, String> properties = PreparedStatementCachePropertyTranslator.buildNonXaProperties(
                configuration, "jdbc:mysql://localhost:3306/testdb");

        assertEquals("true", properties.get("cachePrepStmts"));
        assertEquals("250", properties.get("prepStmtCacheSize"));
        assertEquals("2048", properties.get("prepStmtCacheSqlLimit"));
        assertEquals("true", properties.get("useServerPrepStmts"));
    }

    @Test
    void shouldDisablePostgresServerPrepareWhenConfiguredFalse() {
        System.setProperty("ojp.connection.pool.statementCache.serverPrepare", "false");

        ServerConfiguration configuration = new ServerConfiguration();
        Map<String, String> properties = PreparedStatementCachePropertyTranslator.buildNonXaProperties(
                configuration, "jdbc:postgresql://localhost:5432/testdb");

        assertEquals("0", properties.get("prepareThreshold"));
    }

    @Test
    void shouldReturnNoPropertiesWhenStatementCacheDisabled() {
        System.setProperty("ojp.connection.pool.statementCache.enabled", "false");

        ServerConfiguration configuration = new ServerConfiguration();
        Map<String, String> properties = PreparedStatementCachePropertyTranslator.buildNonXaProperties(
                configuration, "jdbc:mysql://localhost:3306/testdb");

        assertTrue(properties.isEmpty());
    }

    @Test
    void shouldTranslateXaPropertiesWithXaPrefix() {
        ServerConfiguration configuration = new ServerConfiguration();

        Map<String, String> properties = PreparedStatementCachePropertyTranslator.buildXaProperties(
                configuration, "jdbc:sqlserver://localhost:1433;databaseName=testdb");

        assertEquals("false", properties.get("xa.property.disableStatementPooling"));
        assertEquals("250", properties.get("xa.property.statementPoolingCacheSize"));
    }
}
