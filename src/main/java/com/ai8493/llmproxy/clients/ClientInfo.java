package com.ai8493.llmproxy.clients;

import java.util.List;

// 前端左列表展示用 DTO。files 是简化的 {filename, language, absolutePath} 列表，不暴露 templatePath
// absolutePath 仅供前端展示文件磁盘位置，由 homeDir/configSubdir/filename 拼成绝对路径
public record ClientInfo(
    String id,
    String displayName,
    List<FileMeta> files
) {
    public record FileMeta(String filename, String language, String absolutePath) {}
}
