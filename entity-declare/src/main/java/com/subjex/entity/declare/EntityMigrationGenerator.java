package com.subjex.entity.declare;

/**
 * EntityMigrationGenerator — 实体迁移生成器：按已渲染实体写出一份 Flyway 风格的 CREATE TABLE 草稿。
 * <p>
 * The caller checks the result into a draft folder. It is not applied by {@code platform-app} Flyway.
 * 调用方把结果检入草稿目录。{@code platform-app} 的 Flyway 不会应用它。
 */
public final class EntityMigrationGenerator {

    /**
     * @return SQL draft source / SQL 草稿源码
     */
    public String sql(RenderedEntity entity) {
        StringBuilder columns = new StringBuilder();
        EntityField pk = entity.primaryKey();
        for (int i = 0; i < entity.fields().size(); i++) {
            EntityField field = entity.fields().get(i);
            if (i > 0) {
                columns.append(",\n");
            }
            columns.append("  ").append(field.columnName()).append(' ').append(sqlType(field));
            if (field.required()) {
                columns.append(" NOT NULL");
            }
        }
        columns.append(",\n  PRIMARY KEY (").append(pk.columnName()).append(')');
        return "-- Draft from entity " + entity.entityKey() + " — 由实体 " + entity.entityKey() + " 生成的草稿\n"
                + "-- Human-editable. Not applied by platform-app Flyway until moved into its migrations.\n"
                + "-- 可人工编辑。在移入 platform-app 迁移目录之前不会被应用。\n"
                + "-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).\n"
                + "-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。\n"
                + "\n"
                + "CREATE TABLE " + entity.tableName() + " (\n"
                + columns
                + "\n);\n";
    }

    /**
     * Suggested draft file name — 建议的草稿文件名。
     */
    public String fileName(RenderedEntity entity) {
        return "V" + entity.version() + "__" + entity.tableName() + ".sql";
    }

    private static String sqlType(EntityField field) {
        if (field.kind() == EntityFieldKind.INTEGER) {
            return "INTEGER";
        }
        int length = field.maxLength() == null ? 255 : field.maxLength();
        return "VARCHAR(" + length + ")";
    }
}
