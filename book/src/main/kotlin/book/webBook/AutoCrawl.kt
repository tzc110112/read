package book.webBook

import book.model.Book
import book.model.BookSource
import book.model.SearchBook
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

object AutoCrawl {
    private val runningTasks = ConcurrentHashMap<String, Boolean>()
    private val gson = Gson()

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

        // 获取分类URL列表
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
                    val info = withTimeoutOrNull(15000) {
                        runCatching { wBook.getBookInfo(sb.bookUrl, canReName = false) }.getOrNull()
                    }
                    if (onBook(sb, info)) total++
                    delay(500)
                }
                page++
                onProgress(total)
                delay(1000)
            }
        }
        onComplete(total)
    }

    /** 解析书源的所有发现分类URL */
    private fun resolveExploreUrls(bs: BookSource): List<String> {
        val kindRaw = kotlin.runCatching { bs.exploreKinds(true) }.getOrNull()?.trim() ?: bs.exploreUrl?.trim() ?: return emptyList()

        // 尝试 JSON 数组解析 [{"title":"xxx","url":"..."}, ...]
        if (kindRaw.startsWith("[")) {
            return try {
                val type = object : TypeToken<List<Map<String, String>>>() {}.type
                val list: List<Map<String, String>> = gson.fromJson(kindRaw, type)
                list.mapNotNull { m ->
                    val url = m["url"] ?: return@mapNotNull null
                    if (url.startsWith("http")) url else bs.bookSourceUrl.trimEnd('/') + "/" + url.trimStart('/')
                }
            } catch (_: Exception) {
                // JSON解析失败，直接用 exploreUrl
                listOf(bs.exploreUrl ?: bs.bookSourceUrl)
            }
        }

        // "名称::url" 格式
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

        // 普通文本，按行作为URL
        return listOf(bs.exploreUrl ?: bs.bookSourceUrl)
    }
}
