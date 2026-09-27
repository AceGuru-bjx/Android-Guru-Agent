package com.apex.agent.platform.csmem.store.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 图边实体 —— 存储节点间的所有关系。
 *
 * 分三种类型：
 * - SPATIAL:  空间拓扑（相邻、嵌套）
 * - CAUSAL:   因果关系（动作触发→状态跃迁）
 * - SEMANTIC: 语义关联
 */
@Entity(
    tableName = "edges",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_node_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["episode_id"]),
        Index(value = ["source_node_id"]),
        Index(value = ["target_node_id"]),
        Index(value = ["type"]),
        // 修复：v2→v3 迁移创建的 (episode_id, edge_label) 唯一索引必须在此声明，
        // 否则升级用户：迁移后校验发现"未声明索引"→ IllegalStateException；
        // 全新安装：Room 按实体建表，唯一索引不存在 → upsertAll REPLACE
        // 幂等去重失效，边表随帧重复膨胀。索引名须与 Room 默认命名
        // index_edges_episode_id_edge_label 一致（迁移 SQL 已同步对齐）。
        Index(value = ["episode_id", "edge_label"], unique = true)
    ]
)
data class EdgeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "episode_id")
    val episodeId: String?,

    /** 边在图表中的唯一 id */
    @ColumnInfo(name = "edge_label")
    val edgeLabel: String,

    @ColumnInfo(name = "source_node_id")
    val sourceNodeId: Long,

    @ColumnInfo(name = "target_node_id")
    val targetNodeId: Long,

    /** SPATIAL / CAUSAL / SEMANTIC */
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "metadata")
    val metadata: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 记忆能量值 */
    @ColumnInfo(name = "energy")
    val energy: Float = 1.0f
)
