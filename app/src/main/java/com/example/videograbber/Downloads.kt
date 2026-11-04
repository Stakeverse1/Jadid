package com.example.videograbber

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Media(
    val url: String,
    val label: String,
    val ref: String,
    val hls: Boolean,
    val bw: Long
)

class Seg(val url: String, val key: ByteArray?, val iv: ByteArray?)

enum class St { QUEUED, RUNNING, PAUSED, DONE, ERROR }

class HttpErr(val code: Int) : IOException("Server error $code")

class Task(
    val id: Long,
    var url: String,
    val pageUrl: String,
    var ref: String,
    val hls: Boolean,
    val label: String,
    val title: String
) {
    var name = ""
    var mime = ""
    var uri: String? = null
    @Volatile var state = St.QUEUED
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var segDone = 0
    @Volatile var segTotal = 0
    @Volatile var speed = 0L
    @Volatile var msg = ""
    @Volatile var stop = false
    @Volatile var cancel = false
    var job: Runnable? = null
}

object Downloads {

    lateinit var app: Context
    @Volatile var ua = ""
    val tasks = CopyOnWriteArrayList<Task>()
    var concurrent = 3

    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(3, 3, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())
    private var lastId = 0L
    private var inited = false

    // ---------- Setup / persistence ----------

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("dl", Context.MODE_PRIVATE)
        concurrent = prefs.getInt("concurrent", 3).coerceIn(1, 5)
        applyConcurrent(concurrent)
        pool.allowCoreThreadTimeOut(true)
        val ids = (prefs.getString("ids", "") ?: "").split(",").filter { it.isNotEmpty() }
        for (id in ids) {
            val s = prefs.getString("t_$id", null) ?: continue
            try {
                tasks.add(fromJson(JSONObject(s)))
            } catch (e: Exception) {
            }
        }
    }

    fun applyConcurrent(n: Int) {
        concurrent = n.coerceIn(1, 5)
        if (::prefs.isInitialized) prefs.edit().putInt("concurrent", concurrent).apply()
        if (concurrent > pool.maximumPoolSize) {
            pool.maximumPoolSize = concurrent
            pool.corePoolSize = concurrent
        } else {
            pool.corePoolSize = concurrent
            pool.maximumPoolSize = concurrent
        }
    }

    private fun toJson(t: Task): JSONObject {
        val j = JSONObject()
        j.put("id", t.id)
        j.put("url", t.url)
        j.put("page", t.pageUrl)
        j.put("ref", t.ref)
        j.put("hls", t.hls)
        j.put("label", t.label)
        j.put("title", t.title)
        j.put("name", t.name)
        j.put("mime", t.mime)
        j.put("uri", t.uri ?: "")
        j.put("done", t.done)
        j.put("total", t.total)
        j.put("seg", t.segDone)
        j.put("segTotal", t.segTotal)
        j.put("state", t.state.name)
        j.put("msg", t.msg)
        return j
    }

    private fun fromJson(j: JSONObject): Task {
        val t = Task(
            j.getLong("id"), j.getString("url"), j.getString("page"), j.getString("ref"),
            j.getBoolean("hls"), j.getString("label"), j.getString("title")
        )
        t.name = j.optString("name")
        t.mime = j.optString("mime")
        val u = j.optString("uri")
        t.uri = if (u.isEmpty()) null else u
        t.done = j.optLong("done")
        t.total = j.optLong("total")
        t.segDone = j.optInt("seg")
        t.segTotal = j.optInt("segTotal")
        t.msg = j.optString("msg")
        val s = try {
            St.valueOf(j.optString("state"))
        } catch (e: Exception) {
            St.PAUSED
        }
        t.state = if (s == St.RUNNING || s == St.QUEUED) St.PAUSED else s
        return t
    }

    private fun save(t: Task) {
        prefs.edit().putString("t_${t.id}", toJson(t).toString()).apply()
    }

    private fun saveIds() {
        prefs.edit().putString("ids", tasks.joinToString(",") { it.id.toString() }).apply()
    }

    // ---------- Public task control ----------

    @Synchronized
    private fun newId(): Long {
        val n = System.currentTimeMillis()
        lastId = if (n > lastId) n else lastId + 1
        return lastId
    }

    fun add(m: Media, pageUrl: String): Task {
        val host = try {
            URL(pageUrl).host.removePrefix("www.")
        } catch (e: Exception) {
            ""
        }
        val q = if (m.label.isEmpty()) "Video" else m.label
        val t = Task(newId(), m.url, pageUrl, m.ref, m.hls, m.label, "$host • $q")
        if (!m.hls) {
            val ext = extOf(m.url, "mp4")
            t.name = "video_${t.id}.$ext"
            t.mime = mimeOf(ext)
        }
        tasks.add(t)
        saveIds()
        save(t)
        enqueue(t)
        return t
    }

    private fun enqueue(t: Task) {
        t.stop = false
        t.cancel = false
        t.state = St.QUEUED
        t.msg = ""
        t.speed = 0
        val job = Runnable { runTask(t) }
        t.job = job
        pool.execute(job)
        save(t)
        updateService()
    }

    fun pause(t: Task) {
        t.stop = true
        if (t.state == St.QUEUED) {
            val j = t.job
            if (j != null && pool.remove(j)) endStop(t)
        }
    }

    fun resume(t: Task) {
        if (t.state == St.PAUSED || t.state == St.ERROR) enqueue(t)
    }

    fun cancel(t: Task) {
        t.cancel = true
        when (t.state) {
            St.QUEUED, St.RUNNING -> pause(t)
            St.DONE -> removeFromList(t)
            else -> discard(t)
        }
    }

    fun relink(t: Task, url: String, ref: String) {
        t.url = url
        t.ref = ref
        save(t)
    }

    fun clearDone() {
        for (t in tasks) {
            if (t.state == St.DONE) removeFromList(t)
        }
    }

    private fun removeFromList(t: Task) {
        main.post {
            tasks.remove(t)
            saveIds()
            prefs.edit().remove("t_${t.id}").apply()
        }
    }

    private fun discard(t: Task) {
        val u = t.uri
        if (u != null) {
            try {
                app.contentResolver.delete(Uri.parse(u), null, null)
            } catch (e: Exception) {
            }
        }
        removeFromList(t)
    }

    private fun endStop(t: Task) {
        if (t.cancel) {
            discard(t)
        } else {
            t.state = St.PAUSED
            t.speed = 0
            save(t)
        }
        updateService()
    }

    private fun updateService() {
        main.post {
            val n = tasks.count { it.state == St.RUNNING || it.state == St.QUEUED }
            try {
                if (n > 0) {
                    app.startForegroundService(
                        Intent(app, DlService::class.java).putExtra("t", "$n downloads in progress")
                    )
                } else {
                    main.postDelayed({
                        val m = tasks.count { it.state == St.RUNNING || it.state == St.QUEUED }
                        if (m == 0) app.stopService(Intent(app, DlService::class.java))
                    }, 2500)
                }
            } catch (e: Exception) {
            }
        }
    }

    // ---------- Worker ----------

    private fun runTask(t: Task) {
        if (t.stop) {
            endStop(t)
            return
        }
        t.state = St.RUNNING
        t.msg = ""
        try {
            if (t.hls) runHls(t) else runDirect(t)
            if (t.stop) {
                endStop(t)
                return
            }
            t.state = St.DONE
            t.speed = 0
        } catch (e: Exception) {
            if (t.stop) {
                endStop(t)
                return
            }
            t.state = St.ERROR
            t.speed = 0
            t.msg = msgOf(e)
        }
        save(t)
        updateService()
    }

    private fun msgOf(e: Exception): String = when (e) {
        is HttpErr -> "Server error ${e.code} — the link may have expired; tap Fix link"
        is UnknownHostException -> "No internet connection"
        else -> e.message ?: "Unknown error"
    }

    private fun runDirect(t: Task) {
        var tries = 0
        while (true) {
            try {
                runDirectOnce(t)
                return
            } catch (e: HttpErr) {
                throw e
            } catch (e: IOException) {
                tries++
                if (t.stop || tries > 3) throw e
                Thread.sleep(1500)
            }
        }
    }

    private fun runDirectOnce(t: Task) {
        val uri: Uri
        val existing = t.uri
        if (existing == null) {
            uri = createOut(t.name, t.mime)
            t.uri = uri.toString()
            save(t)
        } else {
            uri = Uri.parse(existing)
        }
        val have = sizeOf(uri)
        val c = open(t.url, t.ref, "GET", if (have > 0) have else -1L)
        try {
            val code = c.responseCode
            if (code == 416 && have > 0) {
                t.done = have
                t.total = have
                finishOut(uri)
                return
            }
            if (code >= 400) throw HttpErr(code)
            val type = (c.contentType ?: "").lowercase()
            if (type.startsWith("text/")) throw IOException("This link is not a video or has expired")
            val from = if (have > 0 && code == 206) have else 0L
            val len = c.contentLengthLong
            val crTotal = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            t.total = crTotal ?: (if (len > 0) from + len else 0L)
            t.done = from

            val os = openAt(uri, from)
            try {
                c.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var lastT = SystemClock.uptimeMillis()
                    var lastB = t.done
                    var n = ins.read(buf)
                    while (n >= 0) {
                        if (t.stop) break
                        os.write(buf, 0, n)
                        t.done += n
                        val now = SystemClock.uptimeMillis()
                        if (now - lastT >= 1000) {
                            t.speed = (t.done - lastB) * 1000 / (now - lastT)
                            lastT = now
                            lastB = t.done
                        }
                        n = ins.read(buf)
                    }
                }
            } finally {
                try {
                    os.close()
                } catch (e: Exception) {
                }
            }
            if (!t.stop) {
                if (t.total > 0 && t.done < t.total) throw IOException("اتصال قطع شد")
                finishOut(uri)
            }
        } finally {
            c.disconnect()
        }
    }

    private fun runHls(t: Task) {
        val pair = loadHls(t)
        val segs = pair.first
        val mapUrl = pair.second
        t.segTotal = segs.size
        if (t.uri == null) {
            val ext = if (mapUrl != null) "mp4" else "ts"
            t.name = "video_${t.id}.$ext"
            t.mime = mimeOf(ext)
            t.uri = createOut(t.name, t.mime).toString()
            t.segDone = 0
            t.done = 0
            save(t)
        }
        val uri = Uri.parse(t.uri)
        if (t.segDone == 0) t.done = 0
        val os = openAt(uri, t.done)
        try {
            if (t.segDone == 0 && mapUrl != null) {
                val b = fetchBytes(mapUrl, t.ref)
                os.write(b)
                t.done += b.size
            }
            var lastT = SystemClock.uptimeMillis()
            var lastB = t.done
            for (i in t.segDone until segs.size) {
                if (t.stop) break
                val s = segs[i]
                var data = fetchBytes(s.url, t.ref)
                val k = s.key
                val v = s.iv
                if (k != null && v != null) {
                    val ci = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    ci.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(v))
                    data = ci.doFinal(data)
                }
                os.write(data)
                t.done += data.size
                t.segDone = i + 1
                val now = SystemClock.uptimeMillis()
                if (now - lastT >= 1000) {
                    t.speed = (t.done - lastB) * 1000 / (now - lastT)
                    lastT = now
                    lastB = t.done
                }
                if (i % 3 == 0) save(t)
            }
        } finally {
            try {
                os.close()
            } catch (e: Exception) {
            }
        }
        if (!t.stop) finishOut(uri)
    }

    private fun loadHls(t: Task): Pair<List<Seg>, String?> {
        var url = t.url
        var txt = readText(url, t.ref)
        if (txt.contains("#EXT-X-STREAM-INF")) {
            val best = parseVariants(txt, URL(url), t.ref).firstOrNull()
                ?: throw IOException("No quality found")
            url = best.url
            txt = readText(url, t.ref)
        }
        val base = URL(url)
        val seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(txt)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        var key: ByteArray? = null
        var ivAttr: ByteArray? = null
        var mapUrl: String? = null
        val segs = ArrayList<Seg>()
        var idx = 0L

        for (raw in txt.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXT-X-KEY")) {
                val method = Regex("METHOD=([^,]+)").find(l)?.groupValues?.get(1)
                if (method == "NONE") {
                    key = null
                    ivAttr = null
                } else if (method == "AES-128") {
                    val ku = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                        ?: throw IOException("Decryption key not found")
                    key = fetchBytes(URL(base, ku).toString(), t.ref)
                    ivAttr = Regex("IV=0x([0-9A-Fa-f]+)").find(l)?.groupValues?.get(1)?.let { hexIv(it) }
                } else {
                    throw IOException("This video is DRM-protected and cannot be downloaded")
                }
            } else if (l.startsWith("#EXT-X-MAP")) {
                val mu = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                if (mu != null) mapUrl = URL(base, mu).toString()
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                val iv = if (key != null) (ivAttr ?: seqIv(seq + idx)) else null
                segs.add(Seg(URL(base, l).toString(), key, iv))
                idx++
            }
        }
        if (segs.isEmpty()) throw IOException("Segment list is empty")
        return Pair(segs, mapUrl)
    }

    // ---------- MediaStore ----------

    private fun createOut(name: String, mime: String): Uri {
        val cv = ContentValues()
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name)
        cv.put(MediaStore.Video.Media.MIME_TYPE, mime)
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoGrabber")
        cv.put(MediaStore.Video.Media.IS_PENDING, 1)
        return app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("Could not create the file")
    }

    private fun finishOut(uri: Uri) {
        val cv = ContentValues()
        cv.put(MediaStore.Video.Media.IS_PENDING, 0)
        app.contentResolver.update(uri, cv, null, null)
    }

    private fun sizeOf(uri: Uri): Long {
        return try {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun openAt(uri: Uri, pos: Long): ParcelFileDescriptor.AutoCloseOutputStream {
        val pfd = app.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not open the file")
        val os = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        os.channel.truncate(pos)
        os.channel.position(pos)
        return os
    }

    // ---------- Network & helpers (shared with UI) ----------

    fun open(u: String, ref: String, method: String = "GET", from: Long = -1L): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Encoding", "identity")
        if (ref.isNotEmpty()) c.setRequestProperty("Referer", ref)
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        val ck = CookieManager.getInstance().getCookie(u)
        if (ck != null) c.setRequestProperty("Cookie", ck)
        return c
    }

    fun readText(u: String, ref: String): String {
        val c = open(u, ref)
        try {
            if (c.responseCode >= 400) throw HttpErr(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun fetchBytes(u: String, ref: String): ByteArray {
        var err: Exception? = null
        for (i in 0 until 3) {
            try {
                val c = open(u, ref)
                try {
                    if (c.responseCode >= 400) throw HttpErr(c.responseCode)
                    return c.inputStream.use { it.readBytes() }
                } finally {
                    c.disconnect()
                }
            } catch (e: HttpErr) {
                throw e
            } catch (e: Exception) {
                err = e
                Thread.sleep(800)
            }
        }
        throw (err ?: IOException("Download error"))
    }

    fun parseVariants(txt: String, base: URL, ref: String): List<Media> {
        val lines = txt.lines().map { it.trim() }
        val list = ArrayList<Media>()
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)
            val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
            if (j >= lines.size) continue
            val q = if (res != null) res.groupValues[2] + "p" else "${bw / 1000} kbps"
            list.add(Media(URL(base, lines[j]).toString(), "$q • HLS", ref, true, bw))
        }
        list.sortByDescending { it.bw }
        return list
    }

    fun path(u: String): String = u.substringBefore('#').substringBefore('?').lowercase()

    fun extOf(u: String, def: String): String {
        val e = path(u).substringAfterLast('.', "")
        return if (e in setOf("mp4", "webm", "mkv", "mov", "m4v", "flv", "3gp")) e else def
    }

    fun mimeOf(ext: String): String = when (ext) {
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "flv" -> "video/x-flv"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    fun fmt(b: Long): String =
        if (b >= 1048576) "%.1f MB".format(b / 1048576.0) else "${b / 1024} KB"

    private fun hexIv(s: String): ByteArray {
        val h = s.padStart(32, '0')
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun seqIv(n: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0..7) iv[15 - i] = (n shr (8 * i)).toByte()
        return iv
    }
}
