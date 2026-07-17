package web.cron

import book.model.BookSource
import book.webBook.AutoCrawl
import book.webBook.WBook
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.noear.solon.annotation.Inject
import org.noear.solon.scheduling.annotation.Scheduled
import org.slf4j.LoggerFactory
import web.model.Booklist
import web.util.mapper.mapper
import java.util.concurrent.TimeUnit

/**
 * 定时检查书架更新，下载最新章节
 * 每 6 小时执行一次
 */
@Scheduled(fixedRate = 1000 * 60 * 60 * 6)
class UpdateDownloadJob : Runnable {
    private val logger = LoggerFactory.getLogger(UpdateDownloadJob::class.java)

    @Inject(value = "\${admin.cron:true}", autoRefreshed = true)
    var cron: Boolean = true

    @Inject(value = "\${admin.update:false}", autoRefreshed = true)
    var update: Boolean = false

    companion object {
        private var isRunning = false
    }

    override fun run() = runBlocking {
        if (!cron || !update || isRunning) return@runBlocking
        if (AutoCrawl.downloadDir.isBlank()) return@runBlocking // 未配置下载目录

        isRunning = true
        logger.info("开始检查书籍更新并下载...")

        try {
            val map = mapper.get()
            val books = map.booklistMapper.selectList(QueryWrapper<Booklist>()
                .ne("origin", "loc_book")
                .orderByDesc("last_check_time")
            ) ?: emptyList()

            val semaphore = Semaphore(3)
            var updated = 0

            withTimeoutOrNull(TimeUnit.HOURS.toMillis(2)) {
                coroutineScope {
                    for (book in books) {
                        val uid = book.userid ?: continue
                        val user = map.usersMapper.getUser(uid) ?: continue

                        // 检查更新时间：7天以上未更新的才处理
                        val lastCheck = book.lastCheckTime ?: 0
                        if (lastCheck > System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000) continue

                        val sourceUrl = book.origin ?: continue
                        val source = if (user.source == 2) {
                            map.userBookSourceMapper.getBookSource(sourceUrl, uid)?.toBaseSource()
                        } else {
                            map.bookSourceMapper.getBookSource(sourceUrl)?.toBaseSource()
                        } ?: continue

                        launch {
                            semaphore.acquire()
                            try {
                                val bs = BookSource.fromJson(source.json).getOrNull() ?: return@launch
                                val wBook = WBook(bs, userid = uid, debugLog = false)

                                withTimeoutOrNull(TimeUnit.MINUTES.toMillis(2)) {
                                    // 获取最新章节列表
                                    val chapters = runCatching {
                                        runBlocking { wBook.getChapterList(
                                            book.model.Book().apply {
                                                bookUrl = book.bookUrl ?: return@apply
                                                tocUrl = book.tocUrl ?: ""
                                                origin = sourceUrl
                                                originName = book.originName ?: ""
                                                name = book.name ?: ""
                                                author = book.author ?: ""
                                            }
                                        ) }
                                    }.getOrNull() ?: return@launch

                                    if (chapters.size > (book.totalChapterNum ?: 0)) {
                                        // 有更新，下载新章节
                                        val oldCount = book.totalChapterNum ?: 0
                                        val dir = java.io.File(AutoCrawl.downloadDir, 
                                            (book.name ?: "未知").replace(Regex("[/\\\\:*?\"<>|]"), "_").take(100))
                                        
                                        val contentFile = java.io.File(dir, 
                                            "${(book.name ?: "未知").replace(Regex("[/\\\\:*?\"<>|]"), "_").take(100)}.txt")

                                        if (contentFile.exists()) {
                                            // 追加新章节
                                            contentFile.appendText("\n━━━ 更新于 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())} ━━━\n\n")
                                            
                                            val bookInfo = book.model.Book().apply {
                                                bookUrl = book.bookUrl ?: ""
                                                origin = sourceUrl
                                                originName = book.originName ?: ""
                                                name = book.name ?: ""
                                                author = book.author ?: ""
                                            }

                                            for (i in oldCount until chapters.size) {
                                                val ch = chapters[i]
                                                if (ch.isVolume || ch.title.isNullOrBlank()) continue
                                                val content = runCatching {
                                                    val nextUrl = if (i + 1 < chapters.size) chapters[i + 1].url else ""
                                                    runBlocking { wBook.getBookContent(bookInfo, ch, nextUrl) }
                                                }.getOrNull() ?: "【获取失败】\n"

                                                contentFile.appendText("第${i + 1}章 ${ch.title}\n\n$content\n\n")
                                                delay(200)
                                            }
                                        }

                                        // 更新书架信息
                                        map.booklistMapper.updatetime(
                                            book.id ?: "",
                                            chapters.last().title ?: "",
                                            System.currentTimeMillis(),
                                            System.currentTimeMillis(),
                                            chapters.size,
                                            chapters.size
                                        )
                                        updated++
                                        logger.info("更新书籍[${book.name}] ${chapters.size - oldCount}章")
                                    } else {
                                        // 无更新，仅更新时间戳
                                        map.booklistMapper.updatetimefail(book.id ?: "", System.currentTimeMillis(), chapters.size)
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warn("检查更新失败[${book.name}]: ${e.message}")
                            } finally {
                                semaphore.release()
                            }
                        }
                        delay(100)
                    }
                }
            }
            logger.info("更新下载完成，共更新 $updated 本书")
        } catch (e: Exception) {
            logger.error("更新下载任务异常", e)
        } finally {
            isRunning = false
        }
    }
}
