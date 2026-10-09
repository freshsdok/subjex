package com.subjex.entity.declare;

/**
 * EntityMigrationGenerator — 实体迁移生成器：按已渲染实体写出一份 Flyway 风格的 CREATE TABLE 草稿。
 * <p>
 * The caller checks the result into a draft folder. It is not applied by {@code platform-app} Flyway.
 * When {@code tenantScoped} is true and no field already maps to {@code tenant_id}, appends
 * {@code tenant_id VARCHAR(64) NOT NULL} before the PRIMARY KEY (single PK unchanged; authors may edit indexes/PK).
 * 调用方把结果检入草稿目录。{@code platform-app} 的 Flyway 不会应用它。
 * {@code tenantScoped} 且字段列表未含 {@code tenant_id} 时，在主键前自动追加该列（主键仍单列；索引/主键可人工改）。
 */
public final class EntityMigrationGenerator {

    /** Physical tenant column used by generic CRUD — 通用 CRUD 使用的物理租户列。 */
    static final String TENANT_COLUMN = "tenant_id";

    /**
     * @return SQL draft source / SQL 草稿源码
     */
    public String sql(RenderedEntity entity) {
        StringBuilder columns = new StringBuilder();
        EntityField pk = entity.primaryKey();
        boolean hasTenantColumn = false;
        for (int i = 0; i < entity.fields().size(); i++) {
            EntityField field = entity.fields().get(i);
            if (i > 0) {
                columns.append(",\n");
            }
            columns.append("  ").append(field.columnName()).append(' ').append(sqlType(field));
            if (field.required()) {
                columns.append(" NOT NULL");
            }
            if (TENANT_COLUMN.equals(field.columnName())) {
                hasTenantColumn = true;
            }
        }
        if (entity.tenantScoped() && !hasTenantColumn) {
            columns.append(",\n  ").append(TENANT_COLUMN).append(" VARCHAR(64) NOT NULL");
        }
        columns.append(",\n  PRIMARY KEY (").append(pk.columnName()).append(')');

        StringBuilder header = new StringBuilder();
        header.append("-- Draft from entity ").append(entity.entityKey())
                .append(" — 由实体 ").append(entity.entityKey()).append(" 生成的草稿\n");
        header.append("-- Human-editable. Not applied by platform-app Flyway until moved into its migrations.\n");
        header.append("-- 可人工编辑。在移入 platform-app 迁移目录之前不会被应用。\n");
        header.append("-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).\n");
        header.append("-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。\n");
        if (entity.tenantScoped()) {
            header.append("-- Scoped table: needs tenant_id; authors may adjust indexes/PK (composite optional).\n");
            header.append("-- 隔离表须有 tenant_id；作者可改索引/主键（复合主键可选）。\n");
        }
        header.append("\n");
        header.append("CREATE TABLE ").append(entity.tableName()).append(" (\n");
        header.append(columns);
        header.append("\n);\n");
        return header.toString();
    }

    /**
     * Suggested draft file name — 建议的草稿文件名。
     */
    public String fileName(RenderedEntity entity) {
        return "V" + entity.version() + "__" + entity.tableName() + ".sql";
    }

    private static String sqlType(EntityField field) {
        return switch (field.kind()) {
            case INTEGER -> "INTEGER";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "VARCHAR(10)";
            case TEXT, ENUM, USER_REF, ORG_REF, ENTITY_REF -> {
                int length = field.maxLength() == null ? field.kind().defaultVarcharLength() : field.maxLength();
                yield "VARCHAR(" + length + ")";
            }
        };
    }
}
