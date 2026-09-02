package ink.moling.bilicacheexport.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * 通过 Storage Access Framework（用户用系统选择器授权一个目录）扫描 B 站缓存。
 *
 * 适用场景：系统 DocumentsUI 允许用户定位到哔哩哔哩缓存目录（部分 ROM 的
 * DocumentsUI 能显示 /Android/data，或有已镜像到公共目录的副本）时，
 * 授权一次即可在无 Shizuku/root 的情况下读取。
 *
 * 只要授予的根目录下存在形如 `<avid>/c_<cid>/entry.json` 的树即可发现缓存。
 */
object SafCacheSource {

    private const val PREFS = "saf_grant"
    private const val KEY_TREE = "tree_uri"

    private val dirMime = Document.MIME_TYPE_DIR

    fun saveTree(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun clearTree(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE).apply()
    }

    fun loadTree(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE, null)?.let(Uri::parse)

    /** 在用户授予的目录树下递归扫描所有 entry.json，组装缓存列表。 */
    fun scan(context: Context): List<CachedVideo> {
        val tree = loadTree(context) ?: return emptyList()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val out = ArrayList<CachedVideo>()
        walkDir(context, tree, rootId, out)
        return out.sortedByDescending { it.createTimeMs }
    }

    private fun walkDir(
        context: Context,
        tree: Uri,
        docId: String,
        out: MutableList<CachedVideo>,
    ) {
        val resolver = context.contentResolver
        val children = listChildren(resolver, tree, docId) ?: return
        val subDirs = ArrayList<String>()
        var entryJsonText: String? = null
        var entryDocId: String? = null

        for ((id, name, mime) in children) {
            when {
                mime == dirMime -> subDirs.add(id)
                name == "entry.json" -> {
                    entryJsonText = readText(resolver, tree, id)
                    entryDocId = id
                }
                else -> Unit
            }
        }

        if (entryJsonText != null) {
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()
            val videoDoc = findNamed(resolver, tree, docId, "video.m4s")
            val audioDoc = findNamed(resolver, tree, docId, "audio.m4s")
            val video = EntryJsonParser.parse(
                entryJsonText,
                dirUri,
                videoDoc != null,
                audioDoc != null,
            )
            if (video != null) {
                out.add(
                    video.copy(
                        dirPath = dirUri,
                        videoSource = videoDoc?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it).toString() },
                        audioSource = audioDoc?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it).toString() },
                        entrySource = entryDocId?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it).toString() },
                    )
                )
            }
        }

        for (id in subDirs) {
            walkDir(context, tree, id, out)
        }
    }

    /** 在以 docId 为根的子树内（限深 4 层）查找指定文件名的 docId。 */
    private fun findNamed(
        resolver: ContentResolver,
        tree: Uri,
        docId: String,
        fileName: String,
    ): String? {
        val children = listChildren(resolver, tree, docId) ?: return null
        for ((id, name, mime) in children) {
            if (name == fileName) return id
            if (mime == dirMime) {
                findNamed(resolver, tree, id, fileName)?.let { return it }
            }
        }
        return null
    }

    private data class Child(val id: String, val name: String, val mime: String)

    private fun listChildren(resolver: ContentResolver, tree: Uri, docId: String): List<Child>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                buildList {
                    val idIdx = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                    while (c.moveToNext()) {
                        add(
                            Child(
                                id = c.getString(idIdx),
                                name = c.getString(nameIdx) ?: "",
                                mime = c.getString(mimeIdx) ?: "",
                            )
                        )
                    }
                }
            }
        }.getOrNull()
    }

    private fun readText(resolver: ContentResolver, tree: Uri, docId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return runCatching {
            resolver.openInputStream(uri)?.bufferedReader()?.readText()
        }.getOrNull()
    }
}
