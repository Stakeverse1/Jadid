package com.example.videograbber

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // ---------- Theme ----------
    private val cBg = Color.parseColor("#F2F4F8")
    private val cCard = Color.WHITE
    private val cPrimary = Color.parseColor("#4F46E5")
    private val cPrimaryDark = Color.parseColor("#3730A3")
    private val cText = Color.parseColor("#111827")
    private val cSub = Color.parseColor("#6B7280")
    private val cOk = Color.parseColor("#16A34A")
    private val cErr = Color.parseColor("#DC2626")
    private val cWarn = Color.parseColor("#D97706")
    private val cSoft = Color.parseColor("#E0E7FF")
    private val cChipBg = Color.parseColor("#E5E7EB")
    private val cLine = Color.parseColor("#E5E7EB")

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val HOME = "https://www.google.com"
    private val SAVE_FOLDER = "Movies/VideoGrabber"

    // ---------- Browser tabs ----------
    private inner class BrowserTab(var url: String, var title: String) {
        lateinit var web: WebView
        var loaded = false
        val items = ArrayList<Media>()
        val seen = HashSet<String>()
        val variantUrls = HashSet<String>()
    }

    private val tabs = ArrayList<BrowserTab>()
    private var activeIndex = 0

    private fun activeTab(): BrowserTab? = tabs.getOrNull(activeIndex)
    private fun activeWeb(): WebView? = activeTab()?.web

    private lateinit var webContainer: FrameLayout
    private lateinit var tabStrip: LinearLayout
    private lateinit var urlBox: EditText
    private lateinit var pageBar: ProgressBar
    private lateinit var fab: TextView
    private lateinit var repairBanner: TextView
    private lateinit var browserPage: FrameLayout
    private lateinit var dlPage: LinearLayout
    private lateinit var dlList: LinearLayout
    private lateinit var dlEmpty: TextView
    private lateinit var tabBrowser: TextView
    private lateinit var tabDl: TextView
    private lateinit var dlStats: TextView
    private lateinit var dlSpeed: TextView
    private val concButtons = ArrayList<TextView>()

    private val probePool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private val views = HashMap<Long, TaskView>()
    private var shownIds: List<Long> = emptyList()
    private var repairTask: Task? = null
    private var onDownloads = false

    private val scanner = object : Runnable {
        override fun run() {
            if (!onDownloads) scan()
            ui.postDelayed(this, 2500)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateTabs()
            if (onDownloads) renderTasks()
            ui.postDelayed(this, 700)
        }
    }

    // ---------- Small UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    private fun rect(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    // Apple-style press feedback: the button visibly dims while it is held,
    // with a short fade back when released.
    private fun darken(color: Int, factor: Float = 0.78f): Int {
        if (Color.alpha(color) == 0) return Color.parseColor("#E5E7EB")
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    private fun pressable(normalColor: Int, radius: Int): StateListDrawable {
        return pressableCustom(normalColor, darken(normalColor), radius)
    }

    private fun pressableCustom(normalColor: Int, pressedColor: Int, radius: Int): StateListDrawable {
        val d = StateListDrawable()
        d.addState(intArrayOf(android.R.attr.state_pressed), rect(pressedColor, radius))
        d.addState(intArrayOf(), rect(normalColor, radius))
        d.setExitFadeDuration(150)
        return d
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        return v
    }

    private fun chip(text: String, bg: Int, fg: Int, click: () -> Unit): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = 13f
        v.setTextColor(fg)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(14), dp(8), dp(14), dp(8))
        v.background = pressable(bg, 18)
        v.setOnClickListener { click() }
        return v
    }

    private fun icon(sym: String, click: () -> Unit): TextView {
        val v = TextView(this)
        v.text = sym
        v.textSize = 22f
        v.setTextColor(cPrimary)
        v.gravity = Gravity.CENTER
        v.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        v.background = pressableCustom(Color.TRANSPARENT, Color.parseColor("#E5E7EB"), 20)
        v.setOnClickListener { click() }
        return v
    }

    private fun hostOf(url: String): String = try {
        URL(url).host.removePrefix("www.")
    } catch (e: Exception) {
        url
    }

    private fun shortTitle(tab: BrowserTab): String {
        val t = tab.title.trim()
        if (t.isNotEmpty() && t != "New Tab") return t
        val h = hostOf(tab.url)
        return if (h.isNotEmpty()) h else "New Tab"
    }

    // ---------- Lifecycle ----------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Downloads.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        // ----- Top bar -----
        urlBox = EditText(this)
        urlBox.hint = "Search or enter a website address"
        urlBox.textSize = 14f
        urlBox.setSingleLine(true)
        urlBox.setTextColor(cText)
        urlBox.background = rect(Color.parseColor("#EEF0F5"), 22)
        urlBox.setPadding(dp(16), dp(10), dp(16), dp(10))
        urlBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.setOnEditorActionListener { _, _, _ ->
            go()
            true
        }

        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(Color.WHITE)
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6))
        topBar.addView(icon("←") { activeWeb()?.let { if (it.canGoBack()) it.goBack() } })
        topBar.addView(icon("→") { activeWeb()?.let { if (it.canGoForward()) it.goForward() } })
        val urlLp = lp(0, WRAP, 4, 0, 4, 0)
        urlLp.weight = 1f
        topBar.addView(urlBox, urlLp)
        topBar.addView(icon("⟳") { activeWeb()?.reload() })
        topBar.addView(icon("+") { addTab(HOME, true) })

        // ----- Tab strip -----
        tabStrip = LinearLayout(this)
        tabStrip.orientation = LinearLayout.HORIZONTAL
        tabStrip.setPadding(dp(8), dp(6), dp(8), dp(6))
        val tabScroll = HorizontalScrollView(this)
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.setBackgroundColor(Color.WHITE)
        tabScroll.addView(tabStrip)

        pageBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pageBar.max = 100
        pageBar.progressTintList = ColorStateList.valueOf(cPrimary)
        pageBar.visibility = View.GONE

        repairBanner = tv(
            "🔧 Fix Link mode: play the video again so a fresh link can be found (tap to cancel)",
            13f, Color.WHITE, true
        )
        repairBanner.setBackgroundColor(cWarn)
        repairBanner.setPadding(dp(12), dp(10), dp(12), dp(10))
        repairBanner.visibility = View.GONE
        repairBanner.setOnClickListener {
            repairTask = null
            repairBanner.visibility = View.GONE
            updateFab()
        }

        webContainer = FrameLayout(this)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(topBar)
        column.addView(tabScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(pageBar, LinearLayout.LayoutParams(MATCH, dp(3)))
        column.addView(repairBanner)
        column.addView(webContainer, LinearLayout.LayoutParams(MATCH, 0, 1f))

        fab = chip("", cPrimary, Color.WHITE) { showSheet() }
        fab.textSize = 15f
        fab.typeface = Typeface.DEFAULT_BOLD
        fab.elevation = dp(8).toFloat()
        fab.setPadding(dp(22), dp(12), dp(22), dp(12))
        fab.visibility = View.GONE

        browserPage = FrameLayout(this)
        browserPage.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        val fabLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        fabLp.bottomMargin = dp(24)
        browserPage.addView(fab, fabLp)

        // ----- Downloads page -----
        dlStats = tv("", 14f, cSub)
        dlSpeed = tv("", 14f, cPrimary, true)

        val concRow = LinearLayout(this)
        concRow.orientation = LinearLayout.HORIZONTAL
        concRow.gravity = Gravity.CENTER_VERTICAL
        concRow.addView(tv("Parallel downloads:", 13f, cText, true))
        for (n in 1..5) {
            val b = TextView(this)
            b.text = n.toString()
            b.textSize = 14f
            b.gravity = Gravity.CENTER
            b.setPadding(dp(14), dp(8), dp(14), dp(8))
            b.background = rect(cChipBg, 18)
            b.setOnClickListener {
                Downloads.applyConcurrent(n)
                updateConcButtons()
            }
            concButtons.add(b)
            concRow.addView(b, lp(WRAP, WRAP, 8, 0, 0, 0))
        }
        val clearChip = chip("🧹 Clear finished", cChipBg, cText) { Downloads.clearDone() }

        val saveCard = LinearLayout(this)
        saveCard.orientation = LinearLayout.VERTICAL
        saveCard.setPadding(dp(16), dp(12), dp(16), dp(12))
        saveCard.background = rect(Color.WHITE, 14)
        saveCard.elevation = dp(2).toFloat()
        saveCard.addView(tv("📁 Save location", 15f, cText, true))
        saveCard.addView(tv(SAVE_FOLDER, 13f, cPrimary, true), lp(WRAP, WRAP, 0, 4, 0, 0))
        saveCard.addView(
            tv("Every video is saved to this folder on your device. Each download card below also shows its file name.", 12f, cSub),
            lp(WRAP, WRAP, 0, 4, 0, 0)
        )

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(dp(16), dp(16), dp(16), dp(8))
        head.addView(tv("Downloads", 22f, cText, true))
        head.addView(dlStats, lp(WRAP, WRAP, 0, 4, 0, 0))
        head.addView(dlSpeed, lp(WRAP, WRAP, 0, 2, 0, 0))
        head.addView(concRow, lp(WRAP, WRAP, 0, 12, 0, 0))
        head.addView(clearChip, lp(WRAP, WRAP, 0, 10, 0, 0))
        head.addView(saveCard, lp(MATCH, WRAP, 0, 6, 0, 0))

        dlList = LinearLayout(this)
        dlList.orientation = LinearLayout.VERTICAL
        dlList.setPadding(dp(12), dp(4), dp(12), dp(16))

        dlEmpty = tv("No downloads yet.\nPlay a video in the browser and tap the blue button at the bottom of the screen.", 14f, cSub)
        dlEmpty.gravity = Gravity.CENTER
        dlEmpty.setPadding(dp(24), dp(60), dp(24), dp(24))

        val sv = ScrollView(this)
        sv.addView(dlList)

        dlPage = LinearLayout(this)
        dlPage.orientation = LinearLayout.VERTICAL
        dlPage.setBackgroundColor(cBg)
        dlPage.addView(head)
        val svLp = lp(MATCH, 0)
        svLp.weight = 1f
        dlPage.addView(sv, svLp)

        // ----- Bottom tabs -----
        tabBrowser = tv("🌐 Browser", 14f, cPrimary, true)
        tabBrowser.gravity = Gravity.CENTER
        tabBrowser.setPadding(0, dp(14), 0, dp(14))
        tabBrowser.setOnClickListener { selectTab(false) }
        tabDl = tv("⬇ Downloads", 14f, cSub, true)
        tabDl.gravity = Gravity.CENTER
        tabDl.setPadding(0, dp(14), 0, dp(14))
        tabDl.setOnClickListener { selectTab(true) }

        val tabsBar = LinearLayout(this)
        tabsBar.orientation = LinearLayout.HORIZONTAL
        tabsBar.setBackgroundColor(Color.WHITE)
        tabsBar.elevation = dp(8).toFloat()
        val t1 = lp(0, WRAP)
        t1.weight = 1f
        val t2 = lp(0, WRAP)
        t2.weight = 1f
        tabsBar.addView(tabBrowser, t1)
        tabsBar.addView(tabDl, t2)

        val pages = FrameLayout(this)
        pages.addView(browserPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(dlPage, FrameLayout.LayoutParams(MATCH, MATCH))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        val pagesLp = lp(MATCH, 0)
        pagesLp.weight = 1f
        root.addView(pages, pagesLp)
        root.addView(tabsBar, lp(MATCH, WRAP))
        setContentView(root)

        updateConcButtons()
        selectTab(false)
        restoreTabs()
    }

    override fun onResume() {
        super.onResume()
        ui.post(scanner)
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
        ui.removeCallbacks(scanner)
        ui.removeCallbacks(ticker)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onDownloads) selectTab(false)
        else {
            val w = activeWeb()
            if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
        }
    }

    // ---------- Tabs: create / select / close / persist ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(url: String, title: String, loadNow: Boolean): BrowserTab {
        val tab = BrowserTab(url, title)
        val web = WebView(this)
        tab.web = web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        Downloads.ua = web.settings.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab == activeTab()) {
                    pageBar.progress = newProgress
                    pageBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView, t: String) {
                if (t.isNotEmpty()) {
                    tab.title = t
                    refreshTabStrip()
                    saveTabs()
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (looksLikeMedia(u)) found(tab, u, r.requestHeaders["Referer"] ?: tab.url)
                return null
            }

            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                tab.url = u
                if (tab == activeTab()) {
                    if (!urlBox.hasFocus()) urlBox.setText(u)
                    tab.items.clear()
                    tab.seen.clear()
                    tab.variantUrls.clear()
                    updateFab()
                }
                refreshTabStrip()
                saveTabs()
            }

            override fun onPageFinished(v: WebView, u: String) {
                tab.url = u
                if (tab == activeTab()) scan()
                saveTabs()
            }
        }
        web.setDownloadListener { u, _, _, mime, _ ->
            if (mime != null && mime.startsWith("video")) {
                Downloads.add(Media(u, "", tab.url, false, 0), tab.url)
                Toast.makeText(this, "Added to Downloads", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Only video downloads are supported", Toast.LENGTH_SHORT).show()
            }
        }
        webContainer.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        web.visibility = View.GONE
        if (loadNow) {
            tab.loaded = true
            web.loadUrl(url)
        }
        return tab
    }

    private fun addTab(url: String, select: Boolean) {
        val tab = createTab(url, "New Tab", select)
        tabs.add(tab)
        if (select) {
            selectBrowserTab(tabs.size - 1)
        } else {
            refreshTabStrip()
        }
        saveTabs()
    }

    private fun selectBrowserTab(index: Int) {
        if (tabs.isEmpty()) return
        activeIndex = index.coerceIn(0, tabs.size - 1)
        val tab = tabs[activeIndex]
        for (t in tabs) t.web.visibility = View.GONE
        tab.web.visibility = View.VISIBLE
        if (!tab.loaded) {
            tab.loaded = true
            tab.web.loadUrl(tab.url)
        }
        if (!urlBox.hasFocus()) urlBox.setText(tab.url)
        updateFab()
        refreshTabStrip()
        saveTabs()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            // Keep at least one tab: reset it to the home page.
            val tab = tabs[0]
            tab.title = "New Tab"
            tab.items.clear()
            tab.seen.clear()
            tab.variantUrls.clear()
            tab.url = HOME
            tab.loaded = true
            tab.web.loadUrl(HOME)
            selectBrowserTab(0)
            return
        }
        val tab = tabs.removeAt(index)
        webContainer.removeView(tab.web)
        try {
            tab.web.stopLoading()
            tab.web.destroy()
        } catch (e: Exception) {
        }
        if (activeIndex >= tabs.size) activeIndex = tabs.size - 1
        selectBrowserTab(activeIndex)
    }

    private fun refreshTabStrip() {
        tabStrip.removeAllViews()
        for (i in tabs.indices) {
            val tab = tabs[i]
            val isActive = i == activeIndex
            val chipBox = LinearLayout(this)
            chipBox.orientation = LinearLayout.HORIZONTAL
            chipBox.gravity = Gravity.CENTER_VERTICAL
            chipBox.setPadding(dp(12), dp(8), dp(8), dp(8))
            chipBox.background = pressable(if (isActive) cPrimary else Color.WHITE, 18)
            val titleTv = TextView(this)
            titleTv.text = shortTitle(tab)
            titleTv.textSize = 13f
            titleTv.setTextColor(if (isActive) Color.WHITE else cText)
            titleTv.isSingleLine = true
            titleTv.ellipsize = android.text.TextUtils.TruncateAt.END
            titleTv.maxWidth = dp(140)
            if (isActive) titleTv.typeface = Typeface.DEFAULT_BOLD
            chipBox.addView(titleTv)
            val close = TextView(this)
            close.text = "  ×"
            close.textSize = 18f
            close.setTextColor(if (isActive) Color.WHITE else cSub)
            close.setPadding(dp(4), 0, 0, 0)
            close.setOnClickListener { closeTab(i) }
            chipBox.addView(close)
            chipBox.setOnClickListener { selectBrowserTab(i) }
            tabStrip.addView(chipBox, lp(WRAP, WRAP, 0, 0, 8, 0))
        }
        val plus = chip("+ New tab", cSoft, cPrimary) { addTab(HOME, true) }
        tabStrip.addView(plus)
    }

    private fun saveTabs() {
        try {
            val arr = JSONArray()
            for (tab in tabs) {
                val o = JSONObject()
                o.put("u", tab.url)
                o.put("t", tab.title)
                arr.put(o)
            }
            getSharedPreferences("vg_tabs", MODE_PRIVATE).edit()
                .putString("tabs", arr.toString())
                .putInt("active", activeIndex)
                .apply()
        } catch (e: Exception) {
        }
    }

    private fun restoreTabs() {
        try {
            val prefs = getSharedPreferences("vg_tabs", MODE_PRIVATE)
            val raw = prefs.getString("tabs", null)
            val savedActive = prefs.getInt("active", 0)
            if (raw.isNullOrEmpty()) {
                addTab(HOME, true)
                return
            }
            val arr = JSONArray(raw)
            if (arr.length() == 0) {
                addTab(HOME, true)
                return
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("u", HOME)
                val t = o.optString("t", "New Tab")
                val tab = createTab(u, t, false)
                tabs.add(tab)
            }
            refreshTabStrip()
            selectBrowserTab(savedActive.coerceIn(0, tabs.size - 1))
        } catch (e: Exception) {
            if (tabs.isEmpty()) addTab(HOME, true)
        }
    }

    private fun go() {
        val web = activeWeb() ?: return
        var t = urlBox.text.toString().trim()
        if (t.isEmpty()) return
        if (!t.startsWith("http")) {
            t = if (t.contains(".") && !t.contains(" ")) "https://$t"
            else "https://www.google.com/search?q=" + Uri.encode(t)
        }
        urlBox.clearFocus()
        web.loadUrl(t)
    }

    private fun selectTab(dl: Boolean) {
        onDownloads = dl
        if (dl) {
            activeWeb()?.evaluateJavascript(
                "document.querySelectorAll('video').forEach(function(v){v.pause()})", null
            )
        }
        browserPage.visibility = if (dl) View.GONE else View.VISIBLE
        dlPage.visibility = if (dl) View.VISIBLE else View.GONE
        tabBrowser.setTextColor(if (dl) cSub else cPrimary)
        tabDl.setTextColor(if (dl) cPrimary else cSub)
        if (dl) renderTasks()
    }

    private fun updateTabs() {
        val n = Downloads.tasks.count { it.state == St.RUNNING || it.state == St.QUEUED }
        tabDl.text = if (n > 0) "⬇ Downloads ($n)" else "⬇ Downloads"
    }

    private fun updateConcButtons() {
        for ((i, b) in concButtons.withIndex()) {
            val n = i + 1
            val selected = n == Downloads.concurrent
            b.background = pressable(if (selected) cPrimary else cChipBg, 18)
            b.setTextColor(if (selected) Color.WHITE else cText)
            if (selected) b.typeface = Typeface.DEFAULT_BOLD else b.typeface = Typeface.DEFAULT
        }
    }

    // ---------- Detection ----------

    private fun looksLikeMedia(u: String): Boolean {
        val p = Downloads.path(u)
        return p.endsWith(".mp4") || p.endsWith(".m3u8") || p.endsWith(".webm") ||
            p.endsWith(".mkv") || p.endsWith(".mov") || p.endsWith(".m4v") ||
            p.endsWith(".flv") || p.endsWith(".3gp")
    }

    private fun scan() {
        val web = activeWeb() ?: return
        val js = "(function(){var r=[];document.querySelectorAll('video,video source').forEach(function(e){" +
            "var s=e.currentSrc||e.src;if(s&&s.indexOf('http')===0)r.push(s)});return JSON.stringify(r)})()"
        web.evaluateJavascript(js) { raw ->
            try {
                val tab = activeTab() ?: return@evaluateJavascript
                val inner = JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                val arr = JSONArray(inner)
                for (i in 0 until arr.length()) found(tab, arr.getString(i), tab.url)
            } catch (e: Exception) {
            }
        }
    }

    private fun found(tab: BrowserTab, u: String, ref: String) {
        ui.post {
            if (tab.seen.add(u)) {
                probePool.execute { probe(tab, u, ref) }
            }
        }
    }

    private fun probe(tab: BrowserTab, u: String, ref: String) {
        val isHls = Downloads.path(u).endsWith(".m3u8")
        try {
            if (isHls) {
                hlsEntries(tab, u, ref)
                return
            }
            val c = Downloads.open(u, ref, "HEAD")
            val len = c.contentLengthLong
            val type = (c.contentType ?: "").lowercase()
            c.disconnect()
            if (type.startsWith("text/") || type.contains("json") || type.startsWith("image/")) return
            if (type.contains("mpegurl")) {
                hlsEntries(tab, u, ref)
                return
            }
            if (len in 1L..100_000L) return
            val ext = Downloads.extOf(u, "mp4").uppercase()
            val label = if (len > 0) "$ext • ${Downloads.fmt(len)}" else ext
            ui.post { add(tab, Media(u, label, ref, false, 0)) }
        } catch (e: Exception) {
            ui.post { add(tab, Media(u, if (isHls) "HLS" else "Video", ref, isHls, 0)) }
        }
    }

    private fun hlsEntries(tab: BrowserTab, u: String, ref: String) {
        val txt = Downloads.readText(u, ref)
        val v = Downloads.parseVariants(txt, URL(u), ref)
        ui.post {
            if (v.isEmpty()) {
                add(tab, Media(u, "HLS", ref, true, 0))
            } else {
                v.forEach { tab.variantUrls.add(it.url) }
                tab.items.removeAll { it.url in tab.variantUrls && it.label == "HLS" }
                v.forEach { add(tab, it) }
            }
        }
    }

    private fun add(tab: BrowserTab, m: Media) {
        if (m.label == "HLS" && m.url in tab.variantUrls) return
        if (tab.items.any { it.url == m.url }) return
        tab.items.add(m)
        if (tab == activeTab()) updateFab()
        val rt = repairTask
        if (rt != null && sameVideo(rt, m)) applyRepair(rt, m)
    }

    private fun updateFab() {
        val items = activeTab()?.items ?: emptyList()
        fab.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        fab.text = if (repairTask != null) "🔧 ${items.size} links found" else "⬇ ${items.size} videos found"
    }

    // ---------- Link repair ----------

    private fun startRepair(t: Task) {
        repairTask = t
        repairBanner.visibility = View.VISIBLE
        selectTab(false)
        val tab = activeTab()
        if (tab != null) {
            tab.items.clear()
            tab.seen.clear()
            tab.variantUrls.clear()
            updateFab()
            tab.url = t.pageUrl
            tab.loaded = true
            tab.web.loadUrl(t.pageUrl)
        }
        Toast.makeText(this, "Play the video so a fresh link can be found", Toast.LENGTH_LONG).show()
    }

    private fun sameVideo(t: Task, m: Media): Boolean {
        if (t.hls != m.hls) return false
        val a = Downloads.path(m.url).substringAfterLast('/')
        val b = Downloads.path(t.url).substringAfterLast('/')
        if (t.hls) return m.label == t.label || a == b
        return a.isNotEmpty() && a == b
    }

    private fun applyRepair(t: Task, m: Media) {
        Downloads.relink(t, m.url, m.ref)
        repairTask = null
        repairBanner.visibility = View.GONE
        Toast.makeText(this, "Link refreshed; download will resume", Toast.LENGTH_LONG).show()
        Downloads.resume(t)
        updateFab()
        selectTab(true)
    }

    // ---------- Found-videos sheet + preview ----------

    private fun showSheet() {
        val tab = activeTab() ?: return
        val items = tab.items.toList()
        if (items.isEmpty()) return
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        val r = dp(24).toFloat()
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setPadding(dp(16), dp(18), dp(16), dp(16))
        sheet.background = bg

        val rt = repairTask
        sheet.addView(tv(if (rt != null) "🔧 Pick a fresh link to fix the download" else "Videos found on this page", 17f, cText, true))
        sheet.addView(
            tv("Tap Preview to watch it first, or Download to save it. Long-press a card to copy its link.", 12f, cSub),
            lp(WRAP, WRAP, 0, 2, 0, 10)
        )

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        for (m in items) list.addView(foundCard(m, d), lp(MATCH, WRAP, 0, 0, 0, 8))

        val sv = ScrollView(this)
        sv.addView(list)
        val maxH = (resources.displayMetrics.heightPixels * 0.62).toInt()
        sheet.addView(sv, LinearLayout.LayoutParams(MATCH, minOf(maxH, items.size * dp(96) + dp(8))))

        d.setContentView(sheet)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    private fun foundCard(m: Media, d: Dialog): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        card.background = rect(cBg, 14)

        val titleTv = tv(if (m.label.isEmpty()) "Video" else m.label, 16f, cText, true)
        card.addView(titleTv)
        val nameTv = tv(Downloads.path(m.url).substringAfterLast('/').take(48), 12f, cSub)
        nameTv.textDirection = View.TEXT_DIRECTION_LTR
        card.addView(nameTv, lp(WRAP, WRAP, 0, 2, 0, 0))
        val hostTv = tv(hostOf(m.ref), 12f, cSub)
        card.addView(hostTv, lp(WRAP, WRAP, 0, 2, 0, 8))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val previewBtn = chip("▶ Preview", cSoft, cPrimary) { preview(m) }
        row.addView(previewBtn)
        val rt = repairTask
        val btn = chip(if (rt != null) "Use this link" else "⬇ Download", cPrimary, Color.WHITE) {}
        var added = false
        btn.setOnClickListener {
            if (rt != null) {
                applyRepair(rt, m)
                d.dismiss()
            } else if (!added) {
                added = true
                val tab = activeTab()
                Downloads.add(m, tab?.url ?: m.ref)
                btn.text = "✓ Added"
                btn.background = pressable(cOk, 18)
                updateTabs()
            }
        }
        row.addView(btn, lp(WRAP, WRAP, 8, 0, 0, 0))
        card.addView(row)

        card.setOnLongClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", m.url))
            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            true
        }
        return card
    }

    private fun preview(m: Media) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(18), dp(16), dp(16))
        box.background = rect(Color.WHITE, 20)

        box.addView(tv(if (m.label.isEmpty()) "Video preview" else m.label, 16f, cText, true))
        val nameTv = tv(Downloads.path(m.url).substringAfterLast('/').take(60), 12f, cSub)
        nameTv.textDirection = View.TEXT_DIRECTION_LTR
        box.addView(nameTv, lp(WRAP, WRAP, 0, 4, 0, 0))
        box.addView(tv("This is the video that will be downloaded.", 12f, cSub), lp(WRAP, WRAP, 0, 2, 0, 10))

        // WebView preview: unlike VideoView, this shares the browser's cookies
        // and user agent, so signed links and HLS streams that need the site's
        // session actually play instead of failing silently.
        val pv = WebView(this)
        pv.setBackgroundColor(Color.BLACK)
        pv.settings.javaScriptEnabled = true
        pv.settings.domStorageEnabled = true
        pv.settings.mediaPlaybackRequiresUserGesture = false
        pv.webChromeClient = WebChromeClient()
        pv.webViewClient = WebViewClient()
        box.addView(pv, LinearLayout.LayoutParams(MATCH, dp(220)))
        val safeUrl = m.url.replace("&", "&amp;").replace("\"", "&quot;")
        val html = "<html><body style=\"margin:0;background:#000\">" +
            "<video controls autoplay playsinline style=\"width:100%;height:100%\" src=\"" + safeUrl + "\"></video>" +
            "</body></html>"
        try {
            pv.loadDataWithBaseURL(if (m.ref.isNotEmpty()) m.ref else null, html, "text/html", "utf-8", null)
        } catch (e: Exception) {
            Toast.makeText(this, "Preview is not available for this link", Toast.LENGTH_SHORT).show()
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val dlBtn = chip("⬇ Download this video", cPrimary, Color.WHITE) {
            val tab = activeTab()
            Downloads.add(m, tab?.url ?: m.ref)
            Toast.makeText(this, "Added to Downloads", Toast.LENGTH_SHORT).show()
            d.dismiss()
        }
        row.addView(dlBtn)
        val tabBtn = chip("🌐 Open in new tab", cSoft, cPrimary) {
            d.dismiss()
            addTab(m.url, true)
            selectTab(false)
        }
        row.addView(tabBtn, lp(WRAP, WRAP, 8, 0, 0, 0))
        val closeBtn = chip("Close", cChipBg, cText) { d.dismiss() }
        row.addView(closeBtn, lp(WRAP, WRAP, 8, 0, 0, 0))
        box.addView(row, lp(WRAP, WRAP, 0, 14, 0, 0))

        d.setContentView(box)
        d.setOnDismissListener {
            try {
                pv.stopLoading()
                pv.destroy()
            } catch (e: Exception) {
            }
        }
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    // ---------- Downloads list ----------

    private fun fmtEta(t: Task): String {
        if (t.total <= 0 || t.done >= t.total || t.speed <= 0) return ""
        val sec = (t.total - t.done) / t.speed
        return if (sec < 3600) " • ETA ${sec / 60}:${"%02d".format(sec % 60)}"
        else " • ETA ${sec / 3600}:${"%02d".format((sec % 3600) / 60)}"
    }

    private fun renderTasks() {
        val list = Downloads.tasks.reversed()
        val ids = list.map { it.id }
        if (ids != shownIds) {
            shownIds = ids
            dlList.removeAllViews()
            views.keys.retainAll(ids.toSet())
            if (list.isEmpty()) {
                dlList.addView(dlEmpty)
            } else {
                for (t in list) {
                    val v = views.getOrPut(t.id) { TaskView(t) }
                    dlList.addView(v.card, lp(MATCH, WRAP, 0, 0, 0, 10))
                }
            }
        }
        val running = list.count { it.state == St.RUNNING }
        val queued = list.count { it.state == St.QUEUED }
        val done = list.count { it.state == St.DONE }
        val totalSpeed = list.filter { it.state == St.RUNNING }.sumOf { it.speed }
        dlStats.text = "Active: $running • Queued: $queued • Finished: $done • Parallel: ${Downloads.concurrent}"
        dlSpeed.text = if (running > 0) "Total speed: ${Downloads.fmt(totalSpeed)}/s" else ""
        for (t in list) views[t.id]?.update()
    }

    private fun confirmCancel(t: Task) {
        if (t.state == St.DONE || t.state == St.ERROR) {
            Downloads.cancel(t)
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Cancel this download and delete the incomplete file?")
            .setPositiveButton("Yes, cancel") { _, _ -> Downloads.cancel(t) }
            .setNegativeButton("No", null)
            .show()
    }

    private fun play(t: Task) {
        val u = t.uri ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(Uri.parse(u), t.mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "No player found; the file is in $SAVE_FOLDER", Toast.LENGTH_LONG).show()
        }
    }

    private inner class TaskView(val t: Task) {
        val card = LinearLayout(this@MainActivity)
        val title = tv(t.title, 15f, cText, true)
        val info = tv("", 12f, cSub)
        val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal)
        val b1 = chip("", cPrimary, Color.WHITE) {}
        val b2 = chip("", cSoft, cPrimary) {}
        val b3 = chip("", Color.parseColor("#FEE2E2"), cErr) {}

        init {
            bar.max = 1000
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(16), dp(14), dp(16), dp(14))
            card.background = rect(Color.WHITE, 16)
            card.elevation = dp(2).toFloat()
            card.addView(title)
            card.addView(info, lp(WRAP, WRAP, 0, 4, 0, 0))
            card.addView(bar, lp(MATCH, dp(6), 0, 10, 0, 0))
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(b1)
            row.addView(b2, lp(WRAP, WRAP, 8, 0, 0, 0))
            row.addView(b3, lp(WRAP, WRAP, 8, 0, 0, 0))
            card.addView(row, lp(WRAP, WRAP, 0, 10, 0, 0))
        }

        private fun show(b: TextView, text: String, action: () -> Unit) {
            b.visibility = View.VISIBLE
            b.text = text
            b.setOnClickListener { action() }
        }

        fun update() {
            val st = t.state
            val pct: Int = if (t.hls && t.segTotal > 0) {
                t.segDone * 1000 / t.segTotal
            } else if (!t.hls && t.total > 0) {
                (t.done * 1000 / t.total).toInt()
            } else {
                0
            }
            val pTxt = if (pct > 0) "${pct / 10}% • " else ""
            val sizeTxt = Downloads.fmt(t.done)
            val fileLine = "\n📁 $SAVE_FOLDER" + if (t.name.isNotEmpty()) " • ${t.name}" else ""

            bar.isIndeterminate = (st == St.RUNNING && pct == 0 && t.done == 0L)
            bar.progress = if (st == St.DONE) 1000 else pct
            val tint = when (st) {
                St.DONE -> cOk
                St.ERROR -> cErr
                St.PAUSED -> cWarn
                else -> cPrimary
            }
            bar.progressTintList = ColorStateList.valueOf(tint)

            info.text = when (st) {
                St.QUEUED -> "⏳ Queued…$fileLine"
                St.RUNNING -> {
                    val seg = if (t.hls && t.segTotal > 0) "Segment ${t.segDone}/${t.segTotal} • " else ""
                    val tot = if (!t.hls && t.total > 0) " / ${Downloads.fmt(t.total)}" else ""
                    "⬇ $pTxt$seg$sizeTxt$tot • ${Downloads.fmt(t.speed)}/s${fmtEta(t)}$fileLine"
                }
                St.PAUSED -> "⏸ Paused • $pTxt$sizeTxt$fileLine"
                St.ERROR -> "❌ ${t.msg}$fileLine"
                St.DONE -> "✅ Complete • $sizeTxt$fileLine"
            }

            b2.visibility = View.GONE
            when (st) {
                St.QUEUED, St.RUNNING -> {
                    show(b1, "⏸ Pause") { Downloads.pause(t) }
                    show(b3, "✕ Cancel") { confirmCancel(t) }
                }
                St.PAUSED -> {
                    show(b1, "▶ Resume") { Downloads.resume(t) }
                    show(b2, "🔧 Fix link") { startRepair(t) }
                    show(b3, "✕ Cancel") { confirmCancel(t) }
                }
                St.ERROR -> {
                    show(b1, "↻ Retry") { Downloads.resume(t) }
                    show(b2, "🔧 Fix link") { startRepair(t) }
                    show(b3, "✕ Delete") { confirmCancel(t) }
                }
                St.DONE -> {
                    show(b1, "▶ Play") { play(t) }
                    show(b3, "🗑 Remove") { Downloads.cancel(t) }
                }
            }
        }
    }
}
