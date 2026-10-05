package com.subjex.entity.declare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * EntityDraftWriteMainTest — CLI 写出测试：YAML → SQL 草稿与 Java 桩写到目标路径。
 */
class EntityDraftWriteMainTest {

    @TempDir
    Path temp;

    @Test
    void writesSqlAndJavaDraftsToOutputPaths() throws Exception {
        Path yaml = temp.resolve("sample.entity.yaml");
        Files.writeString(
                yaml,
                """
                entityKey: demo-item
                tableName: demo_item
                version: 2
                permission: page.read
                fields:
                  - name: itemId
                    kind: text
                    required: true
                    maxLength: 32
                  - name: label
                    kind: text
                    required: true
                    maxLength: 64
                """);
        Path sqlDir = temp.resolve("sql");
        Path javaDir = temp.resolve("java");
        EntityDraftWriteMain.main(new String[] {yaml.toString(), sqlDir.toString(), javaDir.toString()});

        Path sql = sqlDir.resolve("V2__demo_item.sql");
        assertTrue(Files.isRegularFile(sql));
        String sqlText = Files.readString(sql);
        assertTrue(sqlText.contains("CREATE TABLE demo_item"));
        assertTrue(sqlText.contains("item_id VARCHAR(32) NOT NULL"));
        assertTrue(sqlText.contains("label VARCHAR(64) NOT NULL"));

        assertTrue(Files.isRegularFile(javaDir.resolve("DemoItem.java")));
        assertTrue(Files.isRegularFile(javaDir.resolve("DemoItemStore.java")));
        assertTrue(Files.isRegularFile(javaDir.resolve("JdbcDemoItemStore.java")));

        RenderedEntity entity = new EntityRenderer().render(Files.readString(yaml));
        EntityCrudStubGenerator crud = new EntityCrudStubGenerator();
        assertEquals(
                crud.recordSource(entity, "com.subjex.entity.generated"),
                Files.readString(javaDir.resolve("DemoItem.java")));
    }
}
