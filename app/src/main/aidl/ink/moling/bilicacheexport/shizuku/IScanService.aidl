// IScanService - 运行在 Shizuku UserService 进程中，
// 该进程以 shell(uid 2000) 或 root 身份运行，因此可读取 /Android/data 下其他 App 的目录。
package ink.moling.bilicacheexport.shizuku;

import ink.moling.bilicacheexport.shizuku.IScanProgressCallback;

interface IScanService {
    // 流式扫描 B 站缓存：先统计总数，再逐条回调 onProgress/onItem，最后回调 onDone()。
    // 每条记录字段：hasVideo \t hasAudio \t base64(entry.json) \t c_目录绝对路径 \t video路径 \t audio路径。
    void scan(IScanProgressCallback callback);

    // 供 App 以 shell 身份读取文件（用于把 m4s 拉到 App 内部做合并导出）
    long length(String path);

    byte[] read(String path, long offset, int size);
}
