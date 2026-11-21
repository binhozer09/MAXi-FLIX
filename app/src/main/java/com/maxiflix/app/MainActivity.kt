package com.maxiflix.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private data class RemoteUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
    val releaseNotes: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var mainContent: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var siteWeb: WebView
    private lateinit var updateButton: Button
    private lateinit var updateProgress: ProgressBar
    private lateinit var updateStatus: TextView
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button

    private val worker = Executors.newSingleThreadExecutor()
    private val updateBusy = AtomicBoolean(false)
    private var pendingInstallAfterPermission = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var dnsReceiverRegistered = false
    private var siteLoadStarted = false
    private var cacheRetryInProgress = false
    private var restoredWebViewState = false

    private val dnsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CloudflareDnsVpnService.ACTION_DNS_READY -> loadSiteAndCheckUpdates()
                CloudflareDnsVpnService.ACTION_DNS_FAILED -> {
                    val reason = intent.getStringExtra("reason")
                        ?: "Não foi possível ativar o DNS Cloudflare."
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                    loadSiteAndCheckUpdates()
                }
            }
        }
    }

    companion object {
        private const val TAG = "MAXI_FLIX"
        private const val SITE_URL = "https://www18.redecanais.in/"
        private const val SITE_HOST = "redecanais.in"
        private const val RELEASE_API =
            "https://api.github.com/repos/binhozer09/MAXi-FLIX/releases/latest"
        private const val APK_ASSET_NAME = "MAXi-FLIX.apk"
        private const val MANIFEST_ASSET_NAME = "update.json"
        private const val REQUEST_DNS_PERMISSION = 4102
        private val BLOCKED_AD_DOMAINS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "googletagservices.com",
            "adservice.google.com",
            "adnxs.com",
            "adform.net",
            "taboola.com",
            "outbrain.com",
            "criteo.com",
            "pubmatic.com",
            "openx.net",
            "rubiconproject.com",
            "casalemedia.com",
            "smartadserver.com",
            "adroll.com",
            "amazon-adsystem.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "adsterra.com",
            "exoclick.com",
            "trafficjunky.net",
            "juicyads.com",
            "hilltopads.net",
            "onclickads.net",
            "onclickalgo.com",
            "mgid.com",
            "revcontent.com",
            "adskeeper.co.uk",
            "1win.com",
            "1win.bet",
            "1win.tv",
            "1win.run",
            "1win.link",
            "1win.games",
            "1win.live"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainContent = findViewById(R.id.mainContent)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        siteWeb = findViewById(R.id.siteWeb)
        updateButton = findViewById(R.id.updateButton)
        updateProgress = findViewById(R.id.updateProgress)
        updateStatus = findViewById(R.id.updateStatus)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)

        backButton.setOnClickListener {
            if (siteWeb.canGoBack()) siteWeb.goBack()
        }
        forwardButton.setOnClickListener {
            if (siteWeb.canGoForward()) siteWeb.goForward()
        }
        updateNavigationButtons()

        cleanupInstalledUpdate()
        configureWebView()
        restoredWebViewState = savedInstanceState?.let { state ->
            siteWeb.restoreState(state) != null
        } ?: false
        getSharedPreferences("maxiflix_local_data", MODE_PRIVATE).edit()
            .putLong("last_opened_at", System.currentTimeMillis()).apply()

        updateButton.setOnClickListener {
            if (downloadedApkFile().exists()) {
                showDownloadedInstallDialog()
            } else {
                checkForUpdates(showLatestMessage = true)
            }
        }

        registerDnsReceiver()
        startDnsProtection()
    }

    private fun registerDnsReceiver() {
        val filter = IntentFilter().apply {
            addAction(CloudflareDnsVpnService.ACTION_DNS_READY)
            addAction(CloudflareDnsVpnService.ACTION_DNS_FAILED)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dnsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(dnsReceiver, filter)
        }
        dnsReceiverRegistered = true
    }

    private fun startDnsProtection() {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            startDnsService()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("DNS automático")
            .setMessage(
                "Para abrir o site sem alterar o DNS do Wi-Fi, o MAXi-FLIX usará " +
                    "a Cloudflare 1.1.1.1 somente neste aplicativo. O Android vai " +
                    "pedir autorização para criar uma conexão VPN local."
            )
            .setNegativeButton("Continuar sem DNS") { _, _ ->
                loadSiteAndCheckUpdates()
            }
            .setPositiveButton("Ativar DNS") { _, _ ->
                startActivityForResult(permissionIntent, REQUEST_DNS_PERMISSION)
            }
            .setCancelable(false)
            .show()
    }

    private fun startDnsService() {
        val serviceIntent = Intent(this, CloudflareDnsVpnService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun loadSiteAndCheckUpdates() {
        if (siteLoadStarted || isFinishing) return
        siteLoadStarted = true
        if (!restoredWebViewState) {
            siteWeb.settings.cacheMode = if (hasValidatedInternet()) WebSettings.LOAD_DEFAULT
                else WebSettings.LOAD_CACHE_ELSE_NETWORK
            siteWeb.loadUrl(SITE_URL)
        }
        siteWeb.postDelayed({ checkForUpdates(showLatestMessage = false) }, 2500L)
    }

    @Deprecated("Deprecated in Android API, retained for compatibility with VPN consent flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DNS_PERMISSION) return

        if (resultCode == RESULT_OK) {
            startDnsService()
        } else {
            Toast.makeText(
                this,
                "DNS automático não foi autorizado. O site tentará abrir com a rede atual.",
                Toast.LENGTH_LONG
            ).show()
            loadSiteAndCheckUpdates()
        }
    }

    private fun configureWebView() {
        siteWeb.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Cache HTTP em disco e DOM Storage ficam no sandbox privado do aplicativo.
            cacheMode = if (hasValidatedInternet()) WebSettings.LOAD_DEFAULT
                else WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(siteWeb, true)

        siteWeb.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                updateNavigationButtons()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cacheRetryInProgress = false
                view?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                url?.let { lastUrl -> getSharedPreferences("maxiflix_local_data", MODE_PRIVATE)
                    .edit().putString("last_page_url", lastUrl).apply() }
                CookieManager.getInstance().flush()
                hideSiteBranding()
                hide1WinAds()
                updateNavigationButtons()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && !hasValidatedInternet() && !cacheRetryInProgress) {
                    val failedUrl = request.url.toString()
                    cacheRetryInProgress = true
                    view?.post {
                        view.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        view.loadUrl(failedUrl)
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                updateNavigationButtons()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (isBlockedAdHost(request.url)) return blockedAdResponse()
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                url: String?
            ): WebResourceResponse? {
                val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                if (uri != null && isBlockedAdHost(uri)) return blockedAdResponse()
                return super.shouldInterceptRequest(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                if (isBlockedAdHost(uri)) return true

                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                if (scheme != "http" && scheme != "https") return true

                // Mantém players e vídeos de terceiros dentro de iframes,
                // mas impede que a página principal saia do domínio do site.
                if (!request.isForMainFrame) return false
                return !isAllowedMainFrameUrl(uri)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                    ?: return true
                if (isBlockedAdHost(uri)) return true
                return !isAllowedMainFrameUrl(uri)
            }
        }

        siteWeb.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {
                if (view == null || callback == null) return
                showFullscreenView(view, callback)
            }

            override fun onHideCustomView() {
                hideFullscreenView()
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                return false
            }
        }
    }

    private fun hideSiteBranding() {
        val script = """
            (function() {
              const brandPattern = /rede\s*(?:de\s*)?canais(?:\s*\.?\s*in)?/i;
              const historyKey = 'maxiflix_watch_history_v1';
              const resumeKey = 'maxiflix_resume_v1';
              const pendingKey = 'maxiflix_resume_pending_v1';

              function readHistory() {
                try {
                  const value = JSON.parse(localStorage.getItem(historyKey) || '[]');
                  return Array.isArray(value) ? value : [];
                } catch (e) { return []; }
              }

              function saveHistory(item) {
                try {
                  const list = readHistory().filter(function(entry) { return entry.url !== item.url; });
                  list.unshift(item);
                  localStorage.setItem(historyKey, JSON.stringify(list.slice(0, 30)));
                  updateResumeButton();
                } catch (e) {}
              }

              function currentTitle() {
                let title = (document.title || '').replace(/\s*[-|]\s*MAXi-FLIX.*/i, '').trim();
                if (!title || brandPattern.test(title)) {
                  title = document.querySelector('h1,h2,[class*="title"],[class*="name"]')?.textContent?.trim() || 'Conteúdo assistido';
                }
                return title.slice(0, 120);
              }

              // O histórico só recebe um título quando a pessoa toca em um filme/série no catálogo.
              // Não registrar páginas visitadas automaticamente evita salvar páginas de categorias e anúncios.
              function recordCurrentPage() {}

              function installSelectionTracker() {
                if (window.__maxiflixSelectionTrackerInstalled) return;
                window.__maxiflixSelectionTrackerInstalled = true;

                document.addEventListener('click', function(event) {
                  try {
                    const target = event.target && event.target.nodeType === 1
                      ? event.target : event.target && event.target.parentElement;
                    const link = target && target.closest ? target.closest('a[href]') : null;
                    if (!link) return;

                    const destination = new URL(link.href, window.location.href);
                    if (!/^https?:$/.test(destination.protocol) ||
                        !(destination.hostname === window.location.hostname ||
                          destination.hostname.endsWith('.' + window.location.hostname))) return;
                    if (destination.pathname === '/' || destination.pathname === '') return;

                    const image = link.querySelector('img');
                    let title = link.getAttribute('aria-label') ||
                      link.getAttribute('title') ||
                      (image && (image.getAttribute('alt') || image.getAttribute('title'))) ||
                      (link.innerText || link.textContent || '');
                    title = (title || '').replace(/\\s+/g, ' ').trim();
                    if (!title || brandPattern.test(title)) {
                      title = decodeURIComponent(destination.pathname.split('/').filter(Boolean).pop() || '')
                        .replace(/[-_]+/g, ' ').trim();
                    }
                    if (!title || brandPattern.test(title)) return;

                    saveHistory({
                      url: destination.href,
                      title: title.slice(0, 120),
                      time: 0,
                      duration: 0,
                      updatedAt: Date.now()
                    });
                  } catch (e) {}
                }, true);
              }

              function formatTime(seconds) {
                seconds = Math.max(0, Math.floor(seconds || 0));
                const h = Math.floor(seconds / 3600);
                const m = Math.floor((seconds % 3600) / 60);
                const s = seconds % 60;
                return h > 0 ? h + ':' + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0')
                  : m + ':' + String(s).padStart(2, '0');
              }

              function saveProgress(video) {
                try {
                  if (!video || !Number.isFinite(video.duration) || video.duration <= 0 ||
                      !Number.isFinite(video.currentTime) || video.currentTime < 3) return;
                  const item = {
                    url: window.location.href,
                    title: currentTitle(),
                    time: video.currentTime,
                    duration: video.duration,
                    updatedAt: Date.now()
                  };
                  localStorage.setItem(resumeKey, JSON.stringify(item));
                  saveHistory(item);
                } catch (e) {}
              }

              function updateResumeButton() {
                const button = document.getElementById('maxiflix-continue-button');
                if (!button) return;
                const list = readHistory();
                const latest = list[0];
                const title = button.querySelector('.maxiflix-resume-title');
                const detail = button.querySelector('.maxiflix-resume-detail');
                title.textContent = 'CONTINUAR ASSISTINDO';
                detail.textContent = latest ? latest.title + ' • ' + list.length + ' no histórico'
                  : 'Seus filmes e séries aparecerão aqui';
                button.disabled = list.length === 0;
              }

              function showHistory() {
                const list = readHistory();
                if (!list.length) return;
                let overlay = document.getElementById('maxiflix-history-overlay');
                if (overlay) overlay.remove();
                overlay = document.createElement('div');
                overlay.id = 'maxiflix-history-overlay';
                overlay.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:rgba(0,0,0,.82);display:flex;align-items:center;justify-content:center;padding:18px;box-sizing:border-box;';
                const panel = document.createElement('div');
                panel.style.cssText = 'width:100%;max-width:520px;max-height:80vh;overflow:auto;background:#151922;color:#fff;border-radius:14px;padding:18px;box-sizing:border-box;font-family:Arial,sans-serif;';
                const heading = document.createElement('div');
                heading.textContent = 'CONTINUAR ASSISTINDO';
                heading.style.cssText = 'font-size:19px;font-weight:800;margin-bottom:14px;';
                panel.appendChild(heading);
                list.forEach(function(item) {
                  const row = document.createElement('button');
                  row.type = 'button';
                  row.style.cssText = 'display:block;width:100%;text-align:left;padding:13px;margin:7px 0;border:1px solid #343b49;border-radius:9px;background:#222a38;color:#fff;font-size:15px;';
                  const name = document.createElement('div');
                  name.textContent = item.title || 'Conteúdo assistido';
                  name.style.cssText = 'font-weight:700;';
                  const info = document.createElement('small');
                  info.textContent = item.time > 0 ? 'Continuar de ' + formatTime(item.time) : 'Assistido recentemente';
                  info.style.cssText = 'display:block;margin-top:5px;color:#b9c4d5;';
                  row.appendChild(name);
                  row.appendChild(info);
                  row.addEventListener('click', function() {
                    try { sessionStorage.setItem(pendingKey, JSON.stringify(item)); } catch (e) {}
                    window.location.href = item.url;
                  });
                  panel.appendChild(row);
                });
                const close = document.createElement('button');
                close.type = 'button';
                close.textContent = 'FECHAR';
                close.style.cssText = 'margin-top:12px;padding:11px 18px;border:0;border-radius:8px;background:#1769d2;color:#fff;font-weight:800;';
                close.addEventListener('click', function() { overlay.remove(); });
                panel.appendChild(close);
                overlay.appendChild(panel);
                overlay.addEventListener('click', function(event) { if (event.target === overlay) overlay.remove(); });
                document.body.appendChild(overlay);
              }

              function resumePendingIfPossible() {
                try {
                  const pending = JSON.parse(sessionStorage.getItem(pendingKey) || 'null');
                  if (!pending || pending.url !== window.location.href) return;
                  sessionStorage.removeItem(pendingKey);
                  if (!(pending.time > 0)) return;
                  let attempts = 0;
                  const timer = setInterval(function() {
                    attempts++;
                    const video = Array.from(document.querySelectorAll('video')).find(function(v) {
                      return Number.isFinite(v.duration) && v.duration > 0;
                    });
                    if (video) {
                      try { video.currentTime = Math.min(pending.time, Math.max(0, video.duration - 2)); } catch (e) {}
                      clearInterval(timer);
                    } else if (attempts >= 30) clearInterval(timer);
                  }, 500);
                } catch (e) {}
              }

              function installTracker() {
                try {
                  document.querySelectorAll('video').forEach(function(video) {
                    if (video.dataset.maxiflixTracked === '1') return;
                    video.dataset.maxiflixTracked = '1';
                    let lastSave = 0;
                    video.addEventListener('timeupdate', function() {
                      const now = Date.now();
                      if (now - lastSave >= 3000) { lastSave = now; saveProgress(video); }
                    });
                    video.addEventListener('pause', function() { saveProgress(video); });
                    video.addEventListener('ended', function() {
                      try { localStorage.removeItem(resumeKey); } catch (e) {}
                    });
                  });
                } catch (e) {}
              }

              function makeButton() {
                if (document.getElementById('maxiflix-continue-button')) return null;
                const button = document.createElement('button');
                button.id = 'maxiflix-continue-button';
                button.type = 'button';
                button.innerHTML = '<span class="maxiflix-resume-title">CONTINUAR ASSISTINDO</span><small class="maxiflix-resume-detail">Seus filmes e séries aparecerão aqui</small>';
                button.style.cssText = 'display:flex;flex-direction:column;align-items:flex-start;justify-content:center;gap:3px;width:100%;max-width:460px;min-height:54px;margin:8px auto;padding:10px 16px;border:0;border-radius:9px;background:#1769d2;color:#fff;font-weight:800;font-size:15px;text-align:left;box-shadow:0 3px 10px rgba(0,0,0,.22);cursor:pointer;position:relative;z-index:5;';
                button.querySelector('small').style.cssText = 'font-size:12px;font-weight:500;opacity:.9;';
                button.addEventListener('click', showHistory);
                updateResumeButton();
                return button;
              }

              function replaceBranding() {
                try {
                  if (!document.body) return;
                  let brandNode = null;
                  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                  const nodes = [];
                  while (walker.nextNode()) nodes.push(walker.currentNode);

                  nodes.forEach(function(node) {
                    if (brandNode || !node.parentElement ||
                        node.parentElement.closest('#maxiflix-continue-button')) return;
                    const value = (node.nodeValue || '').trim();
                    if (value && brandPattern.test(value)) brandNode = node;
                  });

                  if (!brandNode) return;

                  const range = document.createRange();
                  range.selectNode(brandNode);
                  const measuredWidth = Math.ceil(range.getBoundingClientRect().width);
                  const cover = document.createElement('span');
                  cover.setAttribute('aria-label', ' ');
                  cover.setAttribute('title', '');
                  cover.style.cssText =
                    'display:inline-block;vertical-align:middle;background:#000;' +
                    'width:' + Math.max(36, measuredWidth) + 'px;height:1.15em;' +
                    'border-radius:2px;pointer-events:none;user-select:none;';
                  brandNode.parentNode.replaceChild(cover, brandNode);

                  // O botão Assistidos fica separado da tarja e continua clicável.
                  if (!document.getElementById('maxiflix-continue-button')) {
                    const button = makeButton();
                    if (button) {
                      button.style.cssText =
                        'display:inline-flex;flex-direction:column;align-items:flex-start;' +
                        'justify-content:center;gap:2px;max-width:100%;min-height:38px;' +
                        'margin:6px 4px;padding:7px 12px;border:0;border-radius:8px;' +
                        'background:#1769d2;color:#fff;font-weight:800;font-size:13px;' +
                        'text-align:left;cursor:pointer;position:relative;z-index:5;';
                      const host = cover.parentElement;
                      if (host && host.parentNode) {
                        host.parentNode.insertBefore(button, host.nextSibling);
                      }
                    }
                  }
                } catch (e) {}
              }

              function start() {
                installSelectionTracker();
                replaceBranding();
                installTracker();
                updateResumeButton();
                resumePendingIfPossible();
                if (!window.__maxiflixResumeObserver && document.documentElement) {
                  window.__maxiflixResumeObserver = new MutationObserver(function() {
                    replaceBranding();
                    installTracker();
                  });
                  window.__maxiflixResumeObserver.observe(document.documentElement, {
                    childList: true, subtree: true, characterData: true
                  });
                }
                if (!window.__maxiflixResumeTimer) {
                  window.__maxiflixResumeTimer = setInterval(function() {
                    installTracker();
                  }, 2000);
                }
              }
              start();
            })();
        """.trimIndent()
        siteWeb.evaluateJavascript(script, null)
    }

    private fun hide1WinAds() {
        // Remove somente o próprio elemento publicitário identificado.
        // Não percorre nem remove ancestrais: isso poderia apagar uma camada
        // que cobre toda a página e bloquear os toques nos filmes.
        val script = """
            (function() {
              const selectors = [
                'iframe[src*="1win" i]',
                'a[href*="1win" i]',
                'img[src*="1win" i]',
                'img[alt*="1win" i]',
                '[class*="1win" i]',
                '[id*="1win" i]',
                '[data-ad*="1win" i]'
              ];

              function removeAdElement(element) {
                if (!element || element === document.body ||
                    element === document.documentElement ||
                    element.id === 'maxiflix-continue-button' ||
                    element.closest('#maxiflix-continue-button')) return;
                element.remove();
              }

              function scanForAds() {
                try {
                  selectors.forEach(function(selector) {
                    document.querySelectorAll(selector).forEach(removeAdElement);
                  });

                  document.querySelectorAll('body *').forEach(function(element) {
                    if (element.children.length !== 0) return;
                    const label = (element.textContent || '').trim();
                    if (label.length > 0 && label.length <= 40 &&
                        /^1\\s*win$/i.test(label)) {
                      removeAdElement(element);
                    }
                  });
                } catch (e) {}
              }

              if (window.__maxiflix1WinObserver) {
                window.__maxiflix1WinObserver.disconnect();
              }
              scanForAds();
              if (document.documentElement) {
                window.__maxiflix1WinObserver = new MutationObserver(function() {
                  scanForAds();
                });
                window.__maxiflix1WinObserver.observe(document.documentElement, {
                  childList: true,
                  subtree: true
                });
              }
            })();
        """.trimIndent()
        siteWeb.evaluateJavascript(script, null)
    }

    private fun updateNavigationButtons() {
        if (!::siteWeb.isInitialized || !::backButton.isInitialized ||
            !::forwardButton.isInitialized) return

        val canGoBack = siteWeb.canGoBack()
        val canGoForward = siteWeb.canGoForward()
        backButton.isEnabled = canGoBack
        forwardButton.isEnabled = canGoForward
        backButton.alpha = if (canGoBack) 1f else 0.45f
        forwardButton.alpha = if (canGoForward) 1f else 0.45f
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isAllowedMainFrameUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (scheme != "http" && scheme != "https") return false
        return host == SITE_HOST || host.endsWith(".$SITE_HOST")
    }

    private fun isBlockedAdHost(uri: Uri): Boolean {
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return BLOCKED_AD_DOMAINS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    private fun blockedAdResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun showFullscreenView(
        view: View,
        callback: WebChromeClient.CustomViewCallback
    ) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }

        customView = view
        customViewCallback = callback
        mainContent.visibility = View.GONE
        fullscreenContainer.visibility = View.VISIBLE
        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun hideFullscreenView() {
        val activeView = customView ?: return
        fullscreenContainer.removeView(activeView)
        fullscreenContainer.visibility = View.GONE
        mainContent.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    private fun checkForUpdates(showLatestMessage: Boolean) {
        if (!updateBusy.compareAndSet(false, true)) return

        updateButton.isEnabled = false
        showUpdateStatus("Verificando atualizações...")
        worker.execute {
            var foundUpdate: RemoteUpdate? = null
            var failure: String? = null

            try {
                foundUpdate = fetchLatestUpdate()
            } catch (error: Exception) {
                Log.w(TAG, "Falha ao consultar atualizações", error)
                failure = error.message ?: "Não foi possível consultar o servidor."
            } finally {
                updateBusy.set(false)
            }

            runOnUiThread {
                updateButton.isEnabled = true
                if (failure != null) {
                    hideUpdateStatus()
                    if (showLatestMessage) {
                        Toast.makeText(
                            this,
                            "Não foi possível verificar atualizações. Confira sua internet.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@runOnUiThread
                }

                if (foundUpdate != null) {
                    showUpdateDialog(foundUpdate)
                } else {
                    hideUpdateStatus()
                    if (showLatestMessage) {
                        Toast.makeText(
                            this,
                            "Você já está usando a versão mais recente do MAXi-FLIX.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun fetchLatestUpdate(): RemoteUpdate? {
        val release = fetchJson(RELEASE_API)
        if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
            return null
        }

        val assets = release.optJSONArray("assets")
            ?: throw IllegalStateException("A publicação não contém arquivos.")

        var manifestUrl: String? = null
        var apkUrl: String? = null
        var apkAssetSize = -1L
        var apkAssetDigest = ""

        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            when (asset.optString("name")) {
                MANIFEST_ASSET_NAME -> manifestUrl = asset.optString("browser_download_url")
                APK_ASSET_NAME -> {
                    apkUrl = asset.optString("browser_download_url")
                    apkAssetSize = asset.optLong("size", -1L)
                    apkAssetDigest = asset.optString("digest")
                }
            }
        }

        if (manifestUrl.isNullOrBlank() || apkUrl.isNullOrBlank()) {
            throw IllegalStateException("A publicação ainda não contém o APK e o manifesto.")
        }

        val manifest = fetchJson(manifestUrl)
        if (!manifest.optBoolean("enabled", true)) return null

        val versionCode = manifest.optInt("versionCode", -1)
        val versionName = manifest.optString("versionName")
        val expectedSize = manifest.optLong("apkSizeBytes", -1L)
        val expectedSha = manifest.optString("apkSha256").lowercase(Locale.ROOT)
        val apkFileName = manifest.optString("apkFile", APK_ASSET_NAME)

        if (versionCode <= BuildConfig.VERSION_CODE) return null
        if (versionName.isBlank() || apkFileName != APK_ASSET_NAME) {
            throw IllegalStateException("Manifesto de atualização inválido.")
        }
        if (expectedSize <= 0L || expectedSize != apkAssetSize) {
            throw IllegalStateException("O tamanho do APK não confere com o manifesto.")
        }
        if (!expectedSha.matches(Regex("[0-9a-f]{64}"))) {
            throw IllegalStateException("O SHA-256 publicado é inválido.")
        }
        if (apkAssetDigest.startsWith("sha256:", ignoreCase = true)) {
            val releaseDigest = apkAssetDigest.substringAfter(":").lowercase(Locale.ROOT)
            if (releaseDigest != expectedSha) {
                throw IllegalStateException("A assinatura SHA-256 do APK não confere.")
            }
        }

        val apkUri = Uri.parse(apkUrl)
        if (apkUri.scheme != "https" ||
            apkUri.host != "github.com" ||
            !apkUri.path.orEmpty().contains("/releases/download/")) {
            throw IllegalStateException("O endereço do APK não é confiável.")
        }

        val notes = manifest.optString(
            "releaseNotes",
            release.optString("body", "Atualização do MAXi-FLIX.")
        )

        return RemoteUpdate(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            apkSha256 = expectedSha,
            apkSizeBytes = expectedSize,
            releaseNotes = notes
        )
    }

    private fun fetchJson(address: String): JSONObject {
        val uri = Uri.parse(address)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val allowedHosts = setOf(
            "api.github.com",
            "github.com",
            "release-assets.githubusercontent.com"
        )
        if (uri.scheme != "https" || host !in allowedHosts) {
            throw IllegalStateException("Endereço de atualização não autorizado.")
        }

        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MAXi-FLIX-Android")
            connect()
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Servidor respondeu HTTP " + connection.responseCode)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun showUpdateDialog(update: RemoteUpdate) {
        val notes = update.releaseNotes.ifBlank {
            "Atualize para receber as melhorias mais recentes."
        }
        val message = "Nova versão " + update.versionName +
            " disponível.\n\n" + notes +
            "\n\nSua versão: " + BuildConfig.VERSION_NAME

        AlertDialog.Builder(this)
            .setTitle("Atualização MAXi-FLIX")
            .setMessage(message)
            .setNegativeButton("Depois", null)
            .setPositiveButton("Baixar e instalar") { _, _ ->
                downloadUpdate(update)
            }
            .show()
    }

    private fun showDownloadedInstallDialog() {
        AlertDialog.Builder(this)
            .setTitle("APK pronto para instalar")
            .setMessage("A atualização já foi baixada. Deseja abrir o instalador do Android?")
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Instalar agora") { _, _ ->
                installDownloadedUpdate()
            }
            .show()
    }

    private fun downloadUpdate(update: RemoteUpdate) {
        if (!updateBusy.compareAndSet(false, true)) return

        updateButton.isEnabled = false
        updateProgress.visibility = View.VISIBLE
        updateProgress.isIndeterminate = false
        updateProgress.progress = 0
        showUpdateStatus("Preparando o download da versão " + update.versionName + "...")

        worker.execute {
            var connection: HttpURLConnection? = null
            var failure: String? = null
            val updatesDirectory = File(cacheDir, "updates")
            val apkFile = downloadedApkFile()
            val temporaryFile = File(updatesDirectory, "MAXi-FLIX.apk.part")

            try {
                val apkUri = Uri.parse(update.apkUrl)
                if (apkUri.scheme != "https" ||
                    apkUri.host != "github.com" ||
                    !apkUri.path.orEmpty().contains("/releases/download/")) {
                    throw IllegalStateException("Link do APK inválido.")
                }

                updatesDirectory.mkdirs()
                apkFile.delete()
                temporaryFile.delete()

                connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.android.package-archive")
                    setRequestProperty("User-Agent", "MAXi-FLIX-Android")
                    connect()
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Não foi possível baixar o APK.")
                }

                val contentLength = connection.contentLengthLong
                if (contentLength > 0L && contentLength != update.apkSizeBytes) {
                    throw IllegalStateException("O tamanho recebido é diferente do publicado.")
                }

                var downloadedBytes = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    FileOutputStream(temporaryFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloadedBytes += count

                            val percent = ((downloadedBytes * 100L) / update.apkSizeBytes)
                                .toInt()
                                .coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                runOnUiThread {
                                    updateProgress.progress = percent
                                    updateStatus.text = "Baixando atualização... " + percent + "%"
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (!temporaryFile.exists() ||
                    temporaryFile.length() != update.apkSizeBytes ||
                    downloadedBytes != update.apkSizeBytes) {
                    throw IllegalStateException("O APK foi baixado incompleto.")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                temporaryFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actualSha = digest.digest().joinToString("") {
                    String.format(Locale.US, "%02x", it.toInt() and 0xff)
                }
                if (!actualSha.equals(update.apkSha256, ignoreCase = true)) {
                    throw IllegalStateException("A verificação de integridade do APK falhou.")
                }

                @Suppress("DEPRECATION")
                val archiveInfo = packageManager.getPackageArchiveInfo(temporaryFile.absolutePath, 0)
                    ?: throw IllegalStateException("O arquivo recebido não é um APK válido.")
                if (archiveInfo.packageName != packageName ||
                    readVersionCode(archiveInfo) != update.versionCode ||
                    update.versionCode <= BuildConfig.VERSION_CODE) {
                    throw IllegalStateException("O APK não corresponde a esta versão do MAXi-FLIX.")
                }

                if (!temporaryFile.renameTo(apkFile)) {
                    throw IllegalStateException("Não foi possível preparar o instalador.")
                }
            } catch (error: Exception) {
                Log.e(TAG, "Falha no download da atualização", error)
                failure = error.message ?: "Não foi possível baixar a atualização."
                temporaryFile.delete()
            } finally {
                connection?.disconnect()
                updateBusy.set(false)
            }

            runOnUiThread {
                updateButton.isEnabled = true
                if (failure != null) {
                    updateProgress.visibility = View.GONE
                    showUpdateStatus(failure)
                    Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                } else {
                    updateProgress.progress = 100
                    showUpdateStatus("Download concluído. Abrindo o instalador...")
                    installDownloadedUpdate()
                }
            }
        }
    }

    private fun downloadedApkFile(): File {
        return File(File(cacheDir, "updates"), "MAXi-FLIX.apk")
    }

    private fun cleanupInstalledUpdate() {
        val apkFile = downloadedApkFile()
        if (!apkFile.exists()) return

        runCatching {
            @Suppress("DEPRECATION")
            val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (archiveInfo == null ||
                archiveInfo.packageName != packageName ||
                readVersionCode(archiveInfo) <= BuildConfig.VERSION_CODE) {
                apkFile.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readVersionCode(info: PackageInfo): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }

    private fun installDownloadedUpdate() {
        val apkFile = downloadedApkFile()
        if (!apkFile.exists() || apkFile.length() < 1024L * 1024L) {
            Toast.makeText(this, "O APK da atualização não está disponível.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            pendingInstallAfterPermission = true
            Toast.makeText(
                this,
                "Autorize a instalação de aplicativos para o MAXi-FLIX e volte ao app.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + packageName)
                )
            )
            return
        }

        pendingInstallAfterPermission = false
        try {
            val apkUri = FileProvider.getUriForFile(
                this,
                packageName + ".fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (error: Exception) {
            Log.e(TAG, "Não foi possível abrir o instalador", error)
            Toast.makeText(
                this,
                "Não foi possível abrir a instalação. Tente novamente.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showUpdateStatus(message: String) {
        updateStatus.visibility = View.VISIBLE
        updateStatus.text = message
    }

    private fun hideUpdateStatus() {
        updateStatus.visibility = View.GONE
        updateProgress.visibility = View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::siteWeb.isInitialized) siteWeb.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (pendingInstallAfterPermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.canRequestPackageInstalls()) {
            pendingInstallAfterPermission = false
            installDownloadedUpdate()
        }
        if (::siteWeb.isInitialized) siteWeb.onResume()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            hideFullscreenView()
        } else if (::siteWeb.isInitialized && siteWeb.canGoBack()) {
            siteWeb.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        if (::siteWeb.isInitialized) siteWeb.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (customView != null) hideFullscreenView()
        if (dnsReceiverRegistered) {
            runCatching { unregisterReceiver(dnsReceiver) }
            dnsReceiverRegistered = false
        }
        worker.shutdownNow()
        if (::siteWeb.isInitialized) {
            CookieManager.getInstance().flush()
            siteWeb.destroy()
        }
        super.onDestroy()
    }
}

