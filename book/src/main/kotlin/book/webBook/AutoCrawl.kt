package book.webBook

import book.model.Book
import book.model.BookSource
import book.model.SearchBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动采集工具——无任何数据库依赖，只输出结果
 */
object AutoCrawl {

    private val runningTasks = ConcurrentHashMap<String, Boolean>()

    /**
     * 对书源执行全量采集，回调方式处理每一本书
     *
     * @param sourceJson  书源 JSON
     * @param userid      用户 ID
     * @param onBook      回调：搜索到的书籍信息，返回 true=加入书架/false=跳过(已存在)
     * @param onProgress  回调：采集进度 (已采集数, 总字节数)
     */
    fun startCrawl(
        sourceJson: String,
        userid: String,
        onBook: (SearchBook, Book?) -> Boolean,
        onProgress: (done: Int) -> Unit = {},
        onComplete: (total: Int) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        val taskKey = "$userid::${BookSource.fromJson(sourceJson).getOrNull()?.bookSourceUrl ?: sourceJson.hashCode()}"
        if (runningTasks.putIfAbsent(taskKey, true) != null) return

        Thread {
            runBlocking {
                try {
                    doCrawl(sourceJson, userid, onBook, onProgress, onComplete, onError)
                } finally {
                    runningTasks.remove(taskKey)
                }
            }
        }.start()
    }

    fun isRunning(sourceJson: String, userid: String): Boolean {
        val key = "$userid::${BookSource.fromJson(sourceJson).getOrNull()?.bookSourceUrl ?: sourceJson.hashCode()}"
        return runningTasks.containsKey(key)
    }

    private suspend fun doCrawl(
        sourceJson: String,
        userid: String,
        onBook: (SearchBook, Book?) -> Boolean,
        onProgress: (done: Int) -> Unit,
        onComplete: (total: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val bs = BookSource.fromJson(sourceJson).getOrNull()
        if (bs == null) { onError("书源JSON解析失败"); return }

        if (bs.exploreUrl.isNullOrBlank()) { onError("书源无发现规则(exploreUrl)"); return }

        // 获取所有发现分类的URL列表
        val kindUrls = kotlin.runCatching {
            bs.exploreKinds(true)
        }.getOrNull() ?: bs.exploreUrl

        if (kindUrls.isBlank()) { onError("书源发现规则解析失败"); return }

        // 按行拆分为多个分类URL
        val urls = kindUrls.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (urls.isEmpty()) { onError("书源无可用发现分类"); return }

        val wBook = WBook(bs, userid = userid, debugLog = false)
        var total = 0

        for (exploreUrl in urls) {
            var page = 0
            val maxPages = 50

            while (page < maxPages) {
                val books: List<SearchBook> = withTimeoutOrNull(30000) {
                    runCatching {
                        wBook.exploreBook(exploreUrl, page)
                    }.getOrNull()
                } ?: break

                if (books.isEmpty()) break

                for (searchBook in books) {
                    if (searchBook.bookUrl.isBlank() || searchBook.name.isBlank()) continue

                    val bookInfo = withTimeoutOrNull(15000) {
                        runCatching {
                            wBook.getBookInfo(searchBook.bookUrl, canReName = false)
                        }.getOrNull()
                    }

                    val added = onBook(searchBook, bookInfo)
                    if (added) total++

                    delay(500)
                }
                page++
                onProgress(total)
                delay(1000)
            }
        }
        onComplete(total)
    }
}
