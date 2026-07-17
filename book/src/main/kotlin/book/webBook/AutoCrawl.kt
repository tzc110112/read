package book.webBook

import book.model.Book
import book.model.BookChapter
import book.model.BookSource
import book.model.SearchBook
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AutoCrawl {
    private val runningTasks = ConcurrentHashMap<String, Boolean>()
    private val gson = Gson()

    /** 下载目录，启动前设置 */
    var downloadDir: String = ""

    fun startCrawl(
        sourceJson: String,
        userid: String,
        onBook: (SearchBook, Book?) -> Boolean,
        onProgress: (done: Int) -> Unit = {},
        onComplete: (total: Int) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        val key = "$userid::${BookSource.fromJson(sourceJson).getOrNull()?.bookSourceUrl ?: sourceJson.hashCode()}"
        if (runningTasks.putIfAbsent(key, true) != null) return
        Thread {
            runBlocking {
                try { doCrawl(sourceJson, userid, onBook, onProgress, onComplete, onError)
                } finally { runningTasks.remove(key) }
            }
        }.start()
    }

    fun isRunning(sourceJson: String, userid: String): Boolean {
        val key = "$userid::${BookSource.fromJson(sourceJson).getOrNull()?.bookSourceUrl ?: sourceJson.hashCode()}"
        return runningTasks.containsKey(key)
    }

    private suspend fun doCrawl(
        sourceJson: String, userid: String,
        onBook: (SearchBook, Book?) -> Boolean,
        onProgress: (done: Int) -> Unit,
        onComplete: (total: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val bs = BookSource.fromJson(sourceJson).getOrNull()
        if (bs == null) { onError("书源JSON解析失败"); return }
        if (bs.exploreUrl.isNullOrBlank()) { onError("书源无发现规则"); return }

        val exploreUrls = resolveExploreUrls(bs)
        if (exploreUrls.isEmpty()) { onError("书源无可用发现分类"); return }

        val wBook = WBook(bs, userid = userid, debugLog = false)
        var total = 0

        for (exploreUrl in exploreUrls) {
            var page = 0
            while (page < 50) {
                val books = withTimeoutOrNull(30000) {
                    runCatching { wBook.exploreBook(exploreUrl, page) }.getOrNull()
                } ?: break
                if (books.isEmpty()) break
                for (sb in books) {
                    if (sb.bookUrl.isBlank() || sb.name.isBlank()) continue
                    val bookInfo = withTimeoutOrNull(15000) {
                        runCatching { wBook.getBookInfo(sb.bookUrl, canReName = false) }.getOrNull()
                    }
                    val added = onBook(sb, bookInfo)
                    if (added) {
                        total++
                        // 下载到本地文件
                        if (downloadDir.isNotBlank() && bookInfo != null) {
                            downloadBook(wBook, sb, bookInfo)
                        }
                    }
                    delay(500)
                }
                page++
                onProgress(total)
                delay(1000)
            }
        }
        onComplete(total)
    }

    /** 下载书籍所有章节到本地文件 */
    private suspend fun downloadBook(wBook: WBook, sb: SearchBook, bookInfo: Book) {
        try {
            val dir = File(downloadDir, sanitizeFileName(sb.name))
            dir.mkdirs()

            // 获取章节列表
            val chapters = withTimeoutOrNull(30000) {
                runCatching { wBook.getChapterList(bookInfo) }.getOrNull()
            } ?: return

            // 逐章下载
            val contentFile = File(dir, "${sanitizeFileName(sb.name)}.txt")
            if (contentFile.exists()) return // 已下载过

            contentFile.bufferedWriter().use { writer ->
                writer.write("书名：${sb.name}\n")
                writer.write("作者：${sb.author}\n")
                if (!sb.intro.isNullOrBlank()) writer.write("简介：${sb.intro}\n")
                writer.write("━━━━━━━━━━━━━━━━━━━━━━\n\n")

                for ((idx, chapter) in chapters.withIndex()) {
                    if (chapter.isVolume || chapter.title.isNullOrBlank()) continue
                    val content = withTimeoutOrNull(15000) {
                        runCatching {
                            val nextUrl = if (idx + 1 < chapters.size) chapters[idx + 1].url else ""
                            wBook.getBookContent(bookInfo, chapter, nextUrl)
                        }.getOrNull()
                    } ?: "【内容获取失败】\n"

                    writer.write("第${idx + 1}章 ${chapter.title}\n\n")
                    writer.write(content)
                    writer.write("\n\n")
                    delay(200)
                }
            }
        } catch (_: Exception) {}
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(100)
    }

    private fun resolveExploreUrls(bs: BookSource): List<String> {
        val kindRaw = kotlin.runCatching { bs.exploreKinds(true) }.getOrNull()?.trim() ?: bs.exploreUrl?.trim() ?: return emptyList()

        if (kindRaw.startsWith("[")) {
            return try {
                val type = object : TypeToken<List<Map<String, String>>>() {}.type
                val list: List<Map<String, String>> = gson.fromJson(kindRaw, type)
                list.mapNotNull { m ->
                    val url = m["url"] ?: return@mapNotNull null
                    if (url.startsWith("http")) url else bs.bookSourceUrl.trimEnd('/') + "/" + url.trimStart('/')
                }
            } catch (_: Exception) {
                listOf(bs.exploreUrl ?: bs.bookSourceUrl)
            }
        }

        if (kindRaw.contains("::")) {
            val urls = kindRaw.split("\n").mapNotNull { line ->
                val parts = line.trim().split("::")
                if (parts.size >= 2) {
                    val url = parts[1]
                    if (url.startsWith("http")) url else bs.bookSourceUrl.trimEnd('/') + "/" + url.trimStart('/')
                } else null
            }
            if (urls.isNotEmpty()) return urls
        }

        return listOf(bs.exploreUrl ?: bs.bookSourceUrl)
    }
}
