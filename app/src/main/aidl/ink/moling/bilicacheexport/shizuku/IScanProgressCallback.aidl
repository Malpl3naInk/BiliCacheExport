// 扫描进度回调：由 Shizuku UserService(:scan 进程, shell 身份) 流式推送进度与结果。
package ink.moling.bilicacheexport.shizuku;

interface IScanProgressCallback {
    // 已处理条数 / 总数，用于 UI 显示「正在扫描：done / total」。
    oneway void onProgress(int done, int total);

    // 逐条推送单条缓存记录（行协议拆分，避免一次绑定超大字符串导致事务过大而丢失）。
    // 字段同 IScanService 注释：hasVideo \t hasAudio \t base64(entry.json) \t dir \t videoPath \t audioPath。
    oneway void onItem(int hasVideo, int hasAudio, String b64Json, String dir, String videoPath, String audioPath);

    // 全部条目推送完毕（无负载，事务极小，必定可达）。
    oneway void onDone();
}
