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

    /** 高频搜索关键词（用于全站搜索采集） */
    private val searchKeywords = listOf(
        "一", "人", "的我", "是不", "他有", "了大", "这中", "在上",
        "之", "了", "不", "是", "的", "一", "有", "大"
    )

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

        val wBook = WBook(bs, userid = userid, debugLog = false)
        var total = 0

        // 模式1: 有发现规则 → 通过发现采集
        if (!bs.exploreUrl.isNullOrBlank()) {
            val exploreUrls = resolveExploreUrls(bs)
            if (exploreUrls.isNotEmpty()) {
                for (exploreUrl in exploreUrls) {
                    var page = 0
                    while (page < 50) {
                        val books = withTimeoutOrNull(30000) {
                            runCatching { wBook.exploreBook(exploreUrl, page) }.getOrNull()
                        } ?: break
                        if (books.isEmpty()) break
                        total += processBooks(wBook, books, onBook)
                        page++
                        onProgress(total)
                        delay(1000)
                    }
                }
                onComplete(total)
                return
            }
        }

        // 模式2: 有搜索规则 → 通过关键词遍历搜索
        if (!bs.searchUrl.isNullOrBlank()) {
            onError("使用关键词搜索模式全站采集...")
            for (keyword in searchKeywords) {
                var page = 0
                var emptyPages = 0
                while (page < 20 && emptyPages < 3) {
                    val books = withTimeoutOrNull(30000) {
                        runCatching { wBook.searchBook(keyword, page) }.getOrNull()
                    } ?: break
                    if (books.isEmpty()) { emptyPages++; page++; continue }
                    emptyPages = 0
                    total += processBooks(wBook, books, onBook)
                    page++
                    onProgress(total)
                    delay(1500)
                }
            }
            onComplete(total)
            return
        }

        onError("书源无发现/搜索规则，无法采集")
    }

    /** 处理一批书籍：去重+下载 */
    private suspend fun processBooks(
        wBook: WBook,
        books: List<SearchBook>,
        onBook: (SearchBook, Book?) -> Boolean
    ): Int {
        var count = 0
        for (sb in books) {
            if (sb.bookUrl.isBlank() || sb.name.isBlank()) continue
            val bookInfo = withTimeoutOrNull(15000) {
                runCatching { wBook.getBookInfo(sb.bookUrl, canReName = false) }.getOrNull()
            }
            if (onBook(sb, bookInfo)) {
                count++
                if (downloadDir.isNotBlank() && bookInfo != null) {
                    downloadBook(wBook, sb, bookInfo)
                }
            }
            delay(500)
        }
        return count
    }

    /** 下载书籍所有章节到本地文件 */
    private suspend fun downloadBook(wBook: WBook, sb: SearchBook, bookInfo: Book) {
        try {
            val dir = File(downloadDir, sanitizeFileName(sb.name))
            dir.mkdirs()
            val chapters = withTimeoutOrNull(30000) {
                runCatching { wBook.getChapterList(bookInfo) }.getOrNull()
            } ?: return
            val contentFile = File(dir, "${sanitizeFileName(sb.name)}.txt")
            if (contentFile.exists()) return

            contentFile.bufferedWriter().use { writer ->
                writer.write("书名：${sb.name}\n作者：${sb.author}\n")
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
                    writer.write("第${idx + 1}章 ${chapter.title}\n\n$content\n\n")
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
                gson.fromJson<List<Map<String, String>>>(kindRaw, type).mapNotNull { m ->
                    val url = m["url"] ?: return@mapNotNull null
                    if (url.startsWith("http")) url else bs.bookSourceUrl.trimEnd('/') + "/" + url.trimStart('/')
                }
            } catch (_: Exception) { listOf(bs.exploreUrl ?: bs.bookSourceUrl) }
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
