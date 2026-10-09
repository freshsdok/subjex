package com.subjex.entity.declare;

import java.util.stream.Collectors;

/**
 * EntityCrudStubGenerator — 实体 CRUD 桩生成器：写出 Java 记录、CRUD 端口接口，以及无 ORM 的 JDBC 草图。
 * <p>
 * The caller checks the results into the repository. Stubs are not wired into {@code platform-app}.
 * 调用方把结果检入仓库。桩尚未接入 {@code platform-app}。
 */
public final class EntityCrudStubGenerator {

    /**
     * Java record for one row — 一行对应的 Java 记录。
     */
    public String recordSource(RenderedEntity entity, String packageName) {
        String components = entity.fields().stream()
                .map(field -> javaType(field) + " " + field.name())
                .collect(Collectors.joining(", "));
        return "package " + packageName + ";\n"
                + "\n"
                + "/**\n"
                + " * " + entity.typeName() + " — 实体 " + entity.entityKey()
                + " 的行记录草稿，已检入仓库。\n"
                + " * <p>\n"
                + " * Draft row type for table {@code " + entity.tableName()
                + "}. Checked in so the build does not run an annotation processor.\n"
                + " * 对应表 {@code " + entity.tableName() + "} 的草稿行类型。检入仓库，构建时不需要注解处理器。\n"
                + " */\n"
                + "public record " + entity.typeName() + "(" + components + ") {\n"
                + "}\n";
    }

    /**
     * CRUD port interface stub — CRUD 端口接口桩。
     */
    public String storeSource(RenderedEntity entity, String packageName) {
        String type = entity.typeName();
        String pkType = javaType(entity.primaryKey());
        String pkName = entity.primaryKey().name();
        return "package " + packageName + ";\n"
                + "\n"
                + "import java.util.List;\n"
                + "import java.util.Optional;\n"
                + "\n"
                + "/**\n"
                + " * " + type + "Store — 实体 " + entity.entityKey() + " 的 CRUD 端口桩（无 ORM）。\n"
                + " * <p>\n"
                + " * Draft only. Not wired into {@code platform-app} yet.\n"
                + " * 仅草稿。尚未接入 {@code platform-app}。\n"
                + " */\n"
                + "public interface " + type + "Store {\n"
                + "\n"
                + "    /** Persist one row — 持久化一行。 */\n"
                + "    void save(" + type + " row);\n"
                + "\n"
                + "    /** Find by primary key — 按主键查找。 */\n"
                + "    Optional<" + type + "> findById(" + pkType + " " + pkName + ");\n"
                + "\n"
                + "    /** Recent rows, newest first when ordered by caller — 最近若干行（排序由调用方约定）。 */\n"
                + "    List<" + type + "> list(int limit);\n"
                + "}\n";
    }

    /**
     * JDBC sketch with SQL strings; methods throw until wired — 带 SQL 字符串的 JDBC 草图；接入前方法抛出。
     */
    public String jdbcSource(RenderedEntity entity, String packageName) {
        String type = entity.typeName();
        String table = entity.tableName();
        EntityField pk = entity.primaryKey();
        String columnList = entity.fields().stream()
                .map(EntityField::columnName)
                .collect(Collectors.joining(", "));
        String placeholders = entity.fields().stream().map(f -> "?").collect(Collectors.joining(", "));
        String selectList = columnList;
        return "package " + packageName + ";\n"
                + "\n"
                + "import java.util.List;\n"
                + "import java.util.Objects;\n"
                + "import java.util.Optional;\n"
                + "\n"
                + "/**\n"
                + " * Jdbc" + type + "Store — JDBC 草图：针对表 {@code " + table + "} 的薄 CRUD（无 ORM）。\n"
                + " * <p>\n"
                + " * Draft only. Replace the unsupported stubs with {@code JdbcTemplate} (or plain JDBC)\n"
                + " * when wiring into {@code platform-app}. Bilingual comments match platform style.\n"
                + " * 仅草稿。接入 {@code platform-app} 时用 {@code JdbcTemplate}（或纯 JDBC）替换未实现方法。\n"
                + " */\n"
                + "public final class Jdbc" + type + "Store implements " + type + "Store {\n"
                + "\n"
                + "    static final String INSERT_SQL =\n"
                + "            \"INSERT INTO " + table + " (" + columnList + ") VALUES (" + placeholders + ")\";\n"
                + "\n"
                + "    static final String SELECT_BY_ID_SQL =\n"
                + "            \"SELECT " + selectList + " FROM " + table + " WHERE " + pk.columnName() + " = ?\";\n"
                + "\n"
                + "    static final String LIST_SQL =\n"
                + "            \"SELECT " + selectList + " FROM " + table + " LIMIT ?\";\n"
                + "\n"
                + "    public Jdbc" + type + "Store() {}\n"
                + "\n"
                + "    @Override\n"
                + "    public void save(" + type + " row) {\n"
                + "        Objects.requireNonNull(row, \"row\");\n"
                + "        throw new UnsupportedOperationException(\n"
                + "                \"draft JDBC stub — wire JdbcTemplate; SQL is INSERT_SQL / 草稿，请接入 JdbcTemplate\");\n"
                + "    }\n"
                + "\n"
                + "    @Override\n"
                + "    public Optional<" + type + "> findById(" + javaType(pk) + " " + pk.name() + ") {\n"
                + "        Objects.requireNonNull(" + pk.name() + ", \"" + pk.name() + "\");\n"
                + "        throw new UnsupportedOperationException(\n"
                + "                \"draft JDBC stub — wire JdbcTemplate; SQL is SELECT_BY_ID_SQL / 草稿，请接入 JdbcTemplate\");\n"
                + "    }\n"
                + "\n"
                + "    @Override\n"
                + "    public List<" + type + "> list(int limit) {\n"
                + "        if (limit < 1) {\n"
                + "            throw new IllegalArgumentException(\"limit must be at least 1\");\n"
                + "        }\n"
                + "        throw new UnsupportedOperationException(\n"
                + "                \"draft JDBC stub — wire JdbcTemplate; SQL is LIST_SQL / 草稿，请接入 JdbcTemplate\");\n"
                + "    }\n"
                + "}\n";
    }

    public String recordFileName(RenderedEntity entity) {
        return entity.typeName() + ".java";
    }

    public String storeFileName(RenderedEntity entity) {
        return entity.typeName() + "Store.java";
    }

    public String jdbcFileName(RenderedEntity entity) {
        return "Jdbc" + entity.typeName() + "Store.java";
    }

    private static String javaType(EntityField field) {
        return switch (field.kind()) {
            case INTEGER -> field.required() ? "int" : "Integer";
            case BOOLEAN -> field.required() ? "boolean" : "Boolean";
            case TEXT, ENUM, DATE, SUBJECT_REF, ORGANIZATION_REF, ENTITY_REF -> "String";
        };
    }
}
