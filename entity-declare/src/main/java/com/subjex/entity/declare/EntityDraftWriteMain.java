package com.subjex.entity.declare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * EntityDraftWriteMain — 实体草稿写出入口：读一份 {@code .entity.yaml}，写出 Flyway SQL 草稿与 CRUD Java 桩。
 * <p>
 * The caller checks the results into the repository. The build does not run this as an annotation processor.
 * Usage / 用法:
 * {@code java -cp entity-declare.jar:snakeyaml.jar com.subjex.entity.declare.EntityDraftWriteMain \\
 *   path/to/entity.entity.yaml path/to/sql-out-dir path/to/java-out-dir [packageName]}
 * Optional fourth argument: package name (default {@code com.subjex.entity.generated}).
 * 第四个参数可选：包名（默认 {@code com.subjex.entity.generated}）。
 * 调用方把结果检入仓库。构建不把它当成注解处理器来跑。
 */
public final class EntityDraftWriteMain {

    private static final String DEFAULT_PACKAGE = "com.subjex.entity.generated";

    private EntityDraftWriteMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            System.err.println(
                    "usage: EntityDraftWriteMain <entity.yaml> <sqlOutDir> <javaOutDir> [packageName]");
            System.err.println(
                    "用法：EntityDraftWriteMain <实体.yaml> <SQL输出目录> <Java输出目录> [包名]");
            System.exit(2);
        }
        Path yamlPath = Path.of(args[0]);
        Path sqlOutDir = Path.of(args[1]);
        Path javaOutDir = Path.of(args[2]);
        String packageName = args.length == 4 ? args[3] : DEFAULT_PACKAGE;

        String yaml = Files.readString(yamlPath, StandardCharsets.UTF_8);
        RenderedEntity entity = new EntityRenderer().render(yaml);

        EntityMigrationGenerator migration = new EntityMigrationGenerator();
        EntityCrudStubGenerator crud = new EntityCrudStubGenerator();

        Files.createDirectories(sqlOutDir);
        Files.createDirectories(javaOutDir);

        Path sqlPath = sqlOutDir.resolve(migration.fileName(entity));
        Files.writeString(sqlPath, migration.sql(entity), StandardCharsets.UTF_8);

        Path recordPath = javaOutDir.resolve(crud.recordFileName(entity));
        Path storePath = javaOutDir.resolve(crud.storeFileName(entity));
        Path jdbcPath = javaOutDir.resolve(crud.jdbcFileName(entity));
        Files.writeString(recordPath, crud.recordSource(entity, packageName), StandardCharsets.UTF_8);
        Files.writeString(storePath, crud.storeSource(entity, packageName), StandardCharsets.UTF_8);
        Files.writeString(jdbcPath, crud.jdbcSource(entity, packageName), StandardCharsets.UTF_8);

        System.out.println("wrote " + sqlPath.toAbsolutePath());
        System.out.println("wrote " + recordPath.toAbsolutePath() + " (" + entity.typeName() + ")");
        System.out.println("wrote " + storePath.toAbsolutePath());
        System.out.println("wrote " + jdbcPath.toAbsolutePath());
    }
}
