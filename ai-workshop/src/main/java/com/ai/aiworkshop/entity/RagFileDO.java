package com.ai.aiworkshop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * rag_file 表映射（RAG 文件管理：上传记录 + 向量化状态）。
 * 文件本体落盘到本地目录（rag.storage.dir），本表只存元数据与索引状态。
 */
@Data
@TableName("rag_file")
public class RagFileDO {

    /** 文件 ID（UUID），同时作为向量库中文档段的 fileId 前缀 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 原始文件名（展示用） */
    @TableField("filename")
    private String filename;

    /** MIME 类型，如 application/pdf */
    @TableField("content_type")
    private String contentType;

    /** 文件大小（字节） */
    @TableField("size")
    private Long size;

    /** 落盘后的物理路径（含 UUID 前缀，避免重名/路径穿越） */
    @TableField("storage_path")
    private String storagePath;

    /** 文件内容 SHA-256（上传去重指纹：内容相同即视为重复） */
    @TableField("content_hash")
    private String contentHash;

    /** 索引状态：uploaded（仅上传，未向量化）/ indexed（已向量化可检索） */
    @TableField("status")
    private String status;

    /** 该文件切片后在向量库中的文档 ID 列表（逗号分隔），用于按文件移除索引 */
    @TableField("doc_ids")
    private String docIds;    /** 归属用户 id（阶段 9 用户隔离） */
    @TableField("user_id")
    private Long userId;


    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("indexed_at")
    private LocalDateTime indexedAt;
}
