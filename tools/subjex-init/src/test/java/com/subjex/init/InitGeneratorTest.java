package com.subjex.init;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitGeneratorTest {

    @TempDir
    Path temp;

    @Test
    void writesStubsWithRedisNotes() throws Exception {
        InitConfig config = InitConfig.defaults(temp.resolve("dev"));
        InitGenerator.Result r = new InitGenerator().write(config);
        assertTrue(Files.isRegularFile(r.envExample()));
        assertTrue(Files.isRegularFile(r.applicationLocal()));
        assertTrue(Files.isRegularFile(r.notes()));
        String env = Files.readString(r.envExample());
        assertTrue(env.contains("PLATFORM_CONNECTION_VENDOR=postgresql"));
        assertTrue(env.contains("OPERATOR_SESSION_SECRET="));
        assertTrue(env.contains("SESSION_REDIS_URL="));
        String yml = Files.readString(r.applicationLocal());
        assertTrue(yml.contains("vendor: postgresql"));
        assertTrue(yml.contains("bootstrap: false"));
        String notes = Files.readString(r.notes());
        assertTrue(notes.contains("deploy/compose/docker-compose.yml"));
        assertTrue(notes.contains("platform.operator.bootstrap=true"));
        assertTrue(notes.contains("Does **not** force bootstrap") || notes.contains("不会**强制开通"));
    }

    @Test
    void omitsRedisBlockWhenDisabled() throws Exception {
        InitConfig config =
                new InitConfig(
                        temp.resolve("noredirect"),
                        "mysql",
                        InitConfig.defaultJdbcUrl("mysql"),
                        "u",
                        "p",
                        false);
        String env = InitGenerator.envExampleContent(config);
        assertFalse(env.contains("OPERATOR_SESSION_SECRET"));
        assertTrue(env.contains("PLATFORM_CONNECTION_VENDOR=mysql"));
        assertTrue(env.contains("com.mysql.cj.jdbc.Driver"));
    }
}
