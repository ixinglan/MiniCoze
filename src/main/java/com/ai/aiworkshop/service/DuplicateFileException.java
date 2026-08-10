package com.ai.aiworkshop.service;

/**
 * 文件内容重复（同一份文件已上传过）时抛出，由 Controller 转成 ok=false + duplicate=true 的响应，
 * 前端据此提示用户并跳过，不会重复落盘或建记录。
 */
public class DuplicateFileException extends RuntimeException {

    private final String filename;
    private final String status;
    private final String existingId;

    public DuplicateFileException(String filename, String status, String existingId) {
        super("文件已上传过：" + filename);
        this.filename = filename;
        this.status = status;
        this.existingId = existingId;
    }

    public String getFilename() {
        return filename;
    }

    /** 已存在记录的索引状态：uploaded（仅上传）/ indexed（已向量化） */
    public String getStatus() {
        return status;
    }

    public String getExistingId() {
        return existingId;
    }
}
