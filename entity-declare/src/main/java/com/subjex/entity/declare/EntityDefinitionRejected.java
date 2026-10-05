package com.subjex.entity.declare;

/**
 * EntityDefinitionRejected — 实体定义被拒绝：声明式实体本身不合法。
 * <p>
 * Nothing was written to disk or to a database.
 * 没有写入磁盘，也没有写入数据库。
 */
public final class EntityDefinitionRejected extends IllegalArgumentException {

    public EntityDefinitionRejected(String message) {
        super(message);
    }
}
