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
import android.webkit.RenderProcessGoneDetail
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
            "pagead2.googlesyndication.com",
            "ads.google.com",
            "adservice.google.com",
            "ads.yahoo.com",
            "adsafeprotected.com",
            "adkernel.com",
            "admanmedia.com",
            "ad-maven.com",
            "adcash.com",
            "adf.ly",
            "adclick.g.doubleclick.net",
            "adclick.g.doubleclick.net",
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
            "trafficstars.com",
            "tsyndicate.com",
            "exosrv.com",
            "exoclick.com",
            "ad-maven.com",
            "admaven.com",
            "adnium.com",
            "monetag.com",
            "propeller-tracking.com",
            "push.house",
            "pushwelcome.com",
            "richpush.co",
            "media.net",
            "sharethrough.com",
            "lijit.com",
            "bidswitch.net",
            "bidvertiser.com",
            "yieldmo.com",
            "smaato.com",
            "inmobi.com",
            "unityads.unity3d.com",
            "applovin.com",
            "ironsrc.com",
            "vungle.com",
            "tapjoy.com",
            "adcolony.com",
            "adpushup.com",
            "adthrive.com",
            "ezoic.net",
            "ezoic.com",
            "popunder.net",
            "popunder.com",
            "adfoc.us",
            "bc.vc",
            "clk.sh",
            "shortest.link",
            "mgid.com",
            "revcontent.com",
            "adskeeper.co.uk",
            "1win.com",
            "1win.bet",
            "1win.tv",
            "1win.run",
            "1win.link",
            "1win.games",
            "1win.live",
            "shopee.com.br", "shopee.com", "shope.ee",
            "appsflyer.com", "adjust.com", "branch.io", "app.link",
            "onelink.me", "linksynergy.com", "awin1.com", "impact.com",
            "redirectingat.com", "linkvertise.com", "loot-link.com",
            "lootlinks.co", "ouo.io", "ouo.press", "shorte.st"
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
        runCatching {
            ContextCompat.startForegroundService(this, serviceIntent)
        }.onFailure { error ->
            Log.e(TAG, "DNS opcional indisponível; abrindo o site pela rede normal", error)
            loadSiteAndCheckUpdates()
        }
        // Não deixa o carregamento do site depender do túnel DNS.
        // Se o serviço não responder, o WebView usa a conexão normal do Android.
        siteWeb.postDelayed({ loadSiteAndCheckUpdates() }, 3500L)
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
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Cache HTTP em disco e DOM Storage ficam no sandbox privado do aplicativo.
            cacheMode = if (hasValidatedInternet()) WebSettings.LOAD_DEFAULT
                else WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(siteWeb, true)

        siteWeb.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                val crashedView = view ?: return true
                val parent = crashedView.parent as? ViewGroup
                Log.e(TAG, "Processo de renderização do WebView encerrado; recriando a tela")
                if (parent == null) {
                    crashedView.destroy()
                    return true
                }

                val lastUrl = crashedView.url
                    ?: getSharedPreferences("maxiflix_local_data", MODE_PRIVATE)
                        .getString("last_page_url", SITE_URL)
                    ?: SITE_URL
                val childIndex = parent.indexOfChild(crashedView)
                val layoutParams = crashedView.layoutParams
                parent.removeView(crashedView)
                crashedView.destroy()

                val replacement = WebView(this@MainActivity).apply {
                    id = R.id.siteWeb
                }
                parent.addView(replacement, childIndex.coerceAtLeast(0), layoutParams)
                siteWeb = replacement
                configureWebView()
                replacement.post { replacement.loadUrl(lastUrl) }
                return true
            }

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
                applyFullscreenVideoSizing()
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
                // Abre páginas de filmes, detalhes e players HTTPS dentro do MAXi-FLIX,
                // mesmo quando o site usa outro domínio para a reprodução.
                return scheme != "http" && scheme != "https"
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                    ?: return true
                if (isBlockedAdHost(uri)) return true
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                return scheme != "http" && scheme != "https"
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


    private fun applyFullscreenVideoSizing() {
        // Uma única aplicação por carregamento. Evita observar cada mutação da página:
        // o catálogo altera o DOM continuamente ao abrir detalhes e players.
        val script = """
            (function() {
              if (window.__maxiflixVideoFitInstalled) return;
              window.__maxiflixVideoFitInstalled = true;
              const style = document.createElement('style');
              style.id = 'maxiflix-fullscreen-video-fit';
              style.textContent = `
                video:fullscreen, video:-webkit-full-screen {
                  width: 100vw !important; height: 100vh !important;
                  max-width: 100vw !important; max-height: 100vh !important;
                  object-fit: contain !important; background: #000 !important;
                }
                :fullscreen video, :-webkit-full-screen video {
                  width: 100% !important; height: 100% !important;
                  object-fit: contain !important;
                }
              `;
              (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
        siteWeb.evaluateJavascript(script, null)
    }


    private fun hideSiteBranding() {
        // Oculta a marca do site tanto no título quanto nos textos e logotipos visíveis.
        // Varredura única e limitada: sem MutationObserver nem tarefas repetidas no clique.
        val script = """
            (function() {
              try {
                document.title = 'MAXi-FLIX';
                const brand = /rede\s*de\s*canais(?:\s*\.?\s*in)?/i;
                const styleId = 'maxiflix-hide-site-brand';
                if (!document.getElementById(styleId)) {
                  const style = document.createElement('style');
                  style.id = styleId;
                  style.textContent = [
                    'a[href*="redecanais" i] img',
                    'img[alt*="redecanais" i]',
                    'img[alt*="rede de canais" i]',
                    '[class*="logo" i] img[alt*="rede" i]',
                    '[class*="site-brand" i]'
                  ].join(',') + '{display:none!important;visibility:hidden!important}';
                  (document.head || document.documentElement).appendChild(style);
                }
                const walker = document.createTreeWalker(
                  document.body || document.documentElement,
                  NodeFilter.SHOW_TEXT
                );
                let node, checked = 0;
                while ((node = walker.nextNode()) && checked < 3500) {
                  checked++;
                  const value = (node.nodeValue || '').replace(/\s+/g, ' ').trim();
                  if (!value || value.length > 70 || !brand.test(value)) continue;
                  let el = node.parentElement;
                  if (!el) continue;
                  const target = el.closest('a,span,h1,h2,h3,p,small,strong,div') || el;
                  if ((target.textContent || '').trim().length <= 90) {
                    target.style.setProperty('display', 'none', 'important');
                  }
                }
              } catch (_) {}
            })();
        """.trimIndent()
        siteWeb.evaluateJavascript(script, null)
    }


    private fun hide1WinAds() {
        // Remove quadros de publicidade inseridos depois do carregamento sem tocar no player.
        val script = """
            (function() {
              try {
                const blocked = [
                  'shopee.com','shopee.com.br','shope.ee','appsflyer.com',
                  'doubleclick.net','googlesyndication.com','googleadservices.com',
                  'popads.net','popcash.net','propellerads.com','adsterra.com',
                  'exoclick.com','exosrv.com','trafficstars.com','tsyndicate.com',
                  'juicyads.com','trafficjunky.net','hilltopads.net','onclickads.net',
                  'onclickalgo.com','ad-maven.com','admaven.com','monetag.com',
                  '1win.com','1win.bet','1win.tv','1win.run','1win.link'
                ];
                function isAdUrl(value) {
                  try {
                    const host = new URL(value, location.href).hostname.toLowerCase();
                    if (blocked.some(function(domain) {
                      return host === domain || host.endsWith('.' + domain);
                    })) return true;
                    const url = String(value || '').toLowerCase();
                    return [
                      '/adserver','/ad-serving','/ads/','/advertising/',
                      '/clickunder','/popunder','/popup-ad','/popupads',
                      '/banner-ad','/banner_ads','/adclick','/clickad',
                      'doubleclick','googlesyndication','googleadservices',
                      'adsterra','exoclick','propellerads','popcash',
                      'popads','trafficstars','monetag','appsflyer','shopee','1win.'
                    ].some(function(marker) { return url.indexOf(marker) !== -1; });
                  } catch (_) { return false; }
                }
                function removeAdOverlays(root) {
                  const scope = root && root.querySelectorAll ? root : document;
                  const markers = /(^|[-_\\s])(ad|ads|advert|advertisement|popup|popunder|interstitial|sponsor)([-_\\s]|$)/i;
                  const nodes = scope.querySelectorAll(
                    '[id],[class],[data-ad],[aria-label]'
                  );
                  nodes.forEach(function(node) {
                    if (!node || node === document.body || node === document.documentElement) return;
                    if (node.matches('video,audio,source,button,[role="button"]')) return;
                    if (node.querySelector('video,audio,[class*="player" i],[id*="player" i]')) return;
                    const identity = [
                      node.id || '', typeof node.className === 'string' ? node.className : '',
                      node.getAttribute('data-ad') || '', node.getAttribute('aria-label') || ''
                    ].join(' ');
                    if (!markers.test(identity)) return;
                    const rect = node.getBoundingClientRect();
                    const area = Math.max(0, rect.width) * Math.max(0, rect.height);
                    const viewport = Math.max(1, innerWidth * innerHeight);
                    const style = getComputedStyle(node);
                    const coversScreen = area > viewport * 0.30 &&
                      (style.position === 'fixed' || style.position === 'absolute' ||
                       rect.width > innerWidth * 0.85 || rect.height > innerHeight * 0.65);
                    if (!coversScreen) return;
                    node.remove();
                  });
                }
                function cleanAdFrames(root) {
                  const scope = root && root.querySelectorAll ? root : document;
                  scope.querySelectorAll('iframe[src], frame[src]').forEach(function(frame) {
                    if (!isAdUrl(frame.getAttribute('src') || frame.src || '')) return;
                    frame.style.setProperty('display', 'none', 'important');
                    frame.style.setProperty('pointer-events', 'none', 'important');
                    const parent = frame.parentElement;
                    frame.remove();
                    if (parent && parent !== document.body && parent !== document.documentElement) {
                      const hasVideo = parent.querySelector('video, audio, iframe:not([src]), [class*="player" i], [id*="player" i]');
                      const text = (parent.textContent || '').trim();
                      if (!hasVideo && text.length < 80) {
                        parent.style.setProperty('display', 'none', 'important');
                        parent.style.setProperty('pointer-events', 'none', 'important');
                      }
                    }
                  });
                }
                if (!window.__maxiflixPopupGuard) {
                  window.__maxiflixPopupGuard = true;\n                  window.open = function() { return null; };
                  window.open = function() { return null; };
                  window.addEventListener('click', function(event) {
                    const link = event.target && event.target.closest
                      ? event.target.closest('a[href]') : null;
                    if (link && isAdUrl(link.href)) {
                      event.preventDefault();
                      event.stopImmediatePropagation();
                    }
                  }, true);
                  // A resposta 204 deixa o iframe de anúncio vazio ocupando espaço.
                  // A resposta de bloqueio agora avisa o documento pai para remover o frame.
                  window.addEventListener('message', function(event) {
                    if (!event.data || event.data.type !== 'maxiflix-blocked-ad-frame') return;
                    document.querySelectorAll('iframe,frame').forEach(function(frame) {
                      if (frame.contentWindow !== event.source) return;
                      let parent = frame.parentElement;
                      frame.remove();
                      let depth = 0;
                      while (parent && parent !== document.body &&
                             parent !== document.documentElement && depth++ < 3) {
                        if (parent.querySelector('video,audio,[class*="player" i],[id*="player" i]')) break;
                        const meaningful = Array.from(parent.children).filter(function(child) {
                          return !/^(SCRIPT|STYLE|NOSCRIPT)$/.test(child.tagName);
                        });
                        const text = (parent.textContent || '').replace(/\\s+/g, '').trim();
                        if (meaningful.length === 0 && text.length === 0) {
                          const next = parent.parentElement;
                          parent.remove();
                          parent = next;
                        } else break;
                      }
                    });
                  }, false);
                }
                cleanAdFrames(document);\n                removeAdOverlays(document);
                if (!window.__maxiflixAdObserver && document.documentElement) {
                  window.__maxiflixAdObserver = new MutationObserver(function(records) {
                    records.forEach(function(record) {
                      record.addedNodes.forEach(function(node) {
                        if (node.nodeType === 1) {
                          if (node.matches && node.matches('iframe[src],frame[src]') &&
                              isAdUrl(node.getAttribute('src') || node.src || '')) {
                            node.remove();
                          } else {
                            cleanAdFrames(node);\n                            removeAdOverlays(node);
                          }
                        }
                      });
                    });
                  });
                  window.__maxiflixAdObserver.observe(document.documentElement, {
                    childList: true, subtree: true
                  });
                }
                const styleId = 'maxiflix-ad-cleanup';
                if (!document.getElementById(styleId)) {
                  const style = document.createElement('style');
                  style.id = styleId;
                  style.textContent = [
                    'iframe[src*="shopee" i]','iframe[src*="appsflyer" i]',
                    'iframe[src*="doubleclick" i]','iframe[src*="googlesyndication" i]',
                    'iframe[src*="popads" i]','iframe[src*="popcash" i]',
                    'iframe[src*="1win" i]','iframe[src*="exoclick" i]',
                    'iframe[src*="trafficstars" i]','iframe[src*="propeller" i]',
                    'ins.adsbygoogle','.adsbygoogle','[id^="google_ads"]',
                    '[id*="ad-container" i]','[class*="ad-banner" i]',
                    '[class*="advertisement" i]','[data-ad-slot]','[data-ad-client]',
                    '[aria-label*="advertisement" i]','[aria-label*="anúncio" i]'
                  ].join(',') + '{display:none!important;visibility:hidden!important;pointer-events:none!important}';
                  (document.head || document.documentElement).appendChild(style);
                }
              } catch (_) {}
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
        // Motor local de filtros: bloqueia domínios conhecidos e padrões inequívocos
        // de chamadas publicitárias em URLs, inclusive quando o anúncio usa outro host.
        if (host.contains("shopee") || host.contains("appsflyer") ||
            host.contains("doubleclick") || host.contains("googlesyndication")) return true
        if (BLOCKED_AD_DOMAINS.any { domain ->
                host == domain || host.endsWith(".$domain")
            }) return true

        val pathAndQuery = (uri.encodedPath.orEmpty() + "?" + uri.encodedQuery.orEmpty())
            .lowercase(Locale.ROOT)
        val adUrlMarkers = listOf(
            "/adserver", "/ad-serving", "/ads/", "/advertising/",
            "/clickunder", "/popunder", "/popup-ad", "/popupads",
            "/banner-ad", "/banner_ads", "/adclick", "/clickad",
            "doubleclick", "googlesyndication", "googleadservices",
            "adsterra", "exoclick", "propellerads", "popcash",
            "popads", "trafficstars", "monetag", "appsflyer",
            "shopee", "1win."
        )
        return adUrlMarkers.any { marker -> pathAndQuery.contains(marker) }
    }

    private fun blockedAdResponse(): WebResourceResponse {
        // Notifica o documento pai para remover o iframe que receberia o anúncio.
        // Isso evita deixar um retângulo branco no lugar da publicidade bloqueada.
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{display:none!important;width:0!important;height:0!important;margin:0!important;padding:0!important}</style>
            </head><body><script>
              try { parent.postMessage({type:'maxiflix-blocked-ad-frame'}, '*'); } catch (_) {}
            </script></body></html>
        """.trimIndent()
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
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
