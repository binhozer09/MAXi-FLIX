import AVKit
import SwiftUI
import WebKit
import UIKit

private enum LokiPalette {
    static let background = Color(red: 8/255, green: 9/255, blue: 13/255)
    static let topBar = Color(red: 16/255, green: 17/255, blue: 22/255)
    static let gold = Color(red: 215/255, green: 168/255, blue: 62/255)
    static let goldBright = Color(red: 1, green: 211/255, blue: 106/255)
    static let red = Color(red: 197/255, green: 29/255, blue: 45/255)
    static let button = Color(red: 32/255, green: 32/255, blue: 39/255)
    static let buttonStroke = Color(red: 72/255, green: 64/255, blue: 74/255)
    static let text = Color(red: 228/255, green: 223/255, blue: 210/255)
}

struct WatchEntry: Identifiable, Codable, Equatable {
    var id: String { url }
    let title: String
    let url: String
    let position: Double
    let duration: Double
    let updatedAt: Date
}

@MainActor
final class BrowserModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var isLoading = true
    @Published var watched: [WatchEntry] = []
    @Published var showUpdateNotice = false

    let webView: WKWebView
    private let startURL = URL(string: "https://www18.redecanais.in/")!
    private let historyKey = "lokiCinema.watchHistory.v1"
    private var pendingResumePosition: Double = 0

    override init() {
        let contentController = WKUserContentController()
        let script = WKUserScript(source: Self.pageEnhancementScript,
                                  injectionTime: .atDocumentEnd,
                                  forMainFrameOnly: false)
        contentController.addUserScript(script)
        contentController.add(self, name: "lokiWatchHistory")

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.websiteDataStore = .default()

        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()

        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.isOpaque = false
        webView.backgroundColor = UIColor(LokiPalette.background)
        webView.scrollView.backgroundColor = UIColor(LokiPalette.background)
        webView.scrollView.contentInsetAdjustmentBehavior = .never

        if let stored = UserDefaults.standard.data(forKey: historyKey),
           let entries = try? JSONDecoder().decode([WatchEntry].self, from: stored) {
            watched = entries
        }

        installContentBlockerThenLoad()
    }

    func goBack() {
        guard webView.canGoBack else { return }
        webView.goBack()
    }

    func goForward() {
        guard webView.canGoForward else { return }
        webView.goForward()
    }

    func reloadCatalog() {
        webView.reload()
    }

    func open(_ entry: WatchEntry) {
        guard let url = URL(string: entry.url), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return }
        pendingResumePosition = entry.position
        webView.load(URLRequest(url: url))
    }

    func removeHistory() {
        watched = []
        UserDefaults.standard.removeObject(forKey: historyKey)
    }

    private func installContentBlockerThenLoad() {
        let domains = [
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "googletagservices.com", "adservice.google.com", "adnxs.com",
            "adform.net", "taboola.com", "outbrain.com", "criteo.com",
            "pubmatic.com", "openx.net", "rubiconproject.com", "casalemedia.com",
            "smartadserver.com", "adroll.com", "amazon-adsystem.com", "popads.net",
            "popcash.net", "propellerads.com", "adsterra.com", "exoclick.com",
            "trafficjunky.net", "juicyads.com", "hilltopads.net", "onclickads.net",
            "onclickalgo.com", "trafficstars.com", "tsyndicate.com", "exosrv.com",
            "admaven.com", "adnium.com", "monetag.com", "push.house",
            "pushwelcome.com", "richpush.co", "media.net", "sharethrough.com",
            "lijit.com", "bidswitch.net", "bidvertiser.com", "yieldmo.com",
            "smaato.com", "inmobi.com", "unityads.unity3d.com", "applovin.com",
            "ironsrc.com", "vungle.com", "tapjoy.com", "adcolony.com",
            "adpushup.com", "adthrive.com", "ezoic.net", "ezoic.com",
            "popunder.net", "popunder.com", "mgid.com", "revcontent.com",
            "adskeeper.co.uk", "1win.com", "1win.bet", "1win.tv", "1win.run",
            "1win.link", "1win.games", "1win.live", "ad-maven.com", "adcash.com",
            "adkernel.com", "admanmedia.com", "shopee.com.br", "shopee.com",
            "appsflyer.com", "adjust.com", "branch.io", "linkvertise.com",
            "loot-link.com", "lootlinks.co", "ouo.io", "ouo.press", "shorte.st"
        ]
        let rules: [[String: Any]] = domains.map { domain in
            [
                "trigger": ["url-filter": ".*", "if-domain": ["*." + domain, domain]],
                "action": ["type": "block"]
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rules),
              let encoded = String(data: data, encoding: .utf8) else {
            webView.load(URLRequest(url: startURL))
            return
        }

        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "LokiCinemaAdRules-v1",
            encodedContentRuleList: encoded
        ) { [weak self] list, _ in
            DispatchQueue.main.async {
                if let list {
                    self?.webView.configuration.userContentController.add(list)
                }
                self?.webView.load(URLRequest(url: self?.startURL ?? URL(string: "https://www18.redecanais.in/")!))
            }
        }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "lokiWatchHistory",
              let payload = message.body as? [String: Any],
              let url = payload["url"] as? String,
              let title = payload["title"] as? String else { return }

        let entry = WatchEntry(
            title: String(title.prefix(120)),
            url: url,
            position: payload["position"] as? Double ?? 0,
            duration: payload["duration"] as? Double ?? 0,
            updatedAt: Date()
        )
        watched.removeAll { $0.url == entry.url }
        watched.insert(entry, at: 0)
        watched = Array(watched.prefix(40))
        if let data = try? JSONEncoder().encode(watched) {
            UserDefaults.standard.set(data, forKey: historyKey)
        }
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        isLoading = true
        syncNavigation()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        isLoading = false
        syncNavigation()
        webView.evaluateJavaScript(Self.pageEnhancementScript, completionHandler: nil)
        if pendingResumePosition > 0 {
            let seconds = pendingResumePosition
            pendingResumePosition = 0
            let resumeScript = """
            (function() {
              var target = \(seconds);
              var attempts = 0;
              var timer = setInterval(function() {
                attempts++;
                var video = Array.from(document.querySelectorAll('video')).find(function(item) {
                  return Number.isFinite(item.duration) && item.duration > 0;
                });
                if (video) {
                  try { video.currentTime = Math.min(target, Math.max(0, video.duration - 2)); } catch (_) {}
                  clearInterval(timer);
                } else if (attempts >= 40) {
                  clearInterval(timer);
                }
              }, 500);
            })();
            """
            webView.evaluateJavaScript(resumeScript, completionHandler: nil)
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        isLoading = false
        syncNavigation()
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        isLoading = false
        syncNavigation()
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url,
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            decisionHandler(.cancel)
            return
        }
        // Keep catalog, detail pages and supported external video players inside the app.
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            webView.load(URLRequest(url: url))
        }
        return nil
    }

    private func syncNavigation() {
        canGoBack = webView.canGoBack
        canGoForward = webView.canGoForward
    }

    private static let pageEnhancementScript = #"""
    (function() {
      if (window.__lokiCinemaIOSInstalled) return;
      window.__lokiCinemaIOSInstalled = true;
      const blockedBrand = /(?:rede\s*de\s*canais|redecanais)(?:\s*\.?\s*in)?/ig;
      const adSelectors = [
        'iframe[src*="1win" i]', 'a[href*="1win" i]', 'img[src*="1win" i]',
        'img[alt*="1win" i]', '[class*="1win" i]', '[id*="1win" i]', '[data-ad*="1win" i]'
      ];
      function cleanPage() {
        try {
          document.querySelectorAll(adSelectors.join(',')).forEach(function(node) {
            if (node && node !== document.body && node !== document.documentElement) node.remove();
          });
          const walker = document.createTreeWalker(document.body || document, NodeFilter.SHOW_TEXT);
          const nodes = [];
          while (walker.nextNode()) nodes.push(walker.currentNode);
          nodes.forEach(function(node) {
            if (node.parentElement && !node.parentElement.closest('script,style,noscript')) {
              node.nodeValue = node.nodeValue.replace(blockedBrand, '');
            }
          });
        } catch (_) {}
      }
      function record(title, url, position, duration) {
        try {
          window.webkit.messageHandlers.lokiWatchHistory.postMessage({
            title: title || document.title || 'Conteúdo assistido',
            url: url || location.href,
            position: Number(position || 0),
            duration: Number(duration || 0)
          });
        } catch (_) {}
      }
      document.addEventListener('click', function(event) {
        const node = event.target && event.target.nodeType === 1 ? event.target : event.target && event.target.parentElement;
        const link = node && node.closest ? node.closest('a[href]') : null;
        if (!link) return;
        try {
          const target = new URL(link.href, location.href);
          if (!/^https?:$/.test(target.protocol)) return;
          const image = link.querySelector('img');
          const cardHint = ((link.className || '') + ' ' +
            (link.parentElement && link.parentElement.className || '')).toLowerCase();
          if (!image && !/poster|filme|movie|serie|card|item/.test(cardHint)) return;
          const title = link.getAttribute('aria-label') || link.title ||
            (image && (image.alt || image.title)) || link.innerText || link.textContent ||
            decodeURIComponent(target.pathname.split('/').filter(Boolean).pop() || '');
          if (title && title.trim()) record(title.trim(), target.href, 0, 0);
        } catch (_) {}
      }, true);
      function attachVideos() {
        document.querySelectorAll('video').forEach(function(video) {
          if (video.dataset.lokiIOSHistory === '1') return;
          video.dataset.lokiIOSHistory = '1';
          let lastSave = 0;
          function save() {
            if (!Number.isFinite(video.duration) || video.duration <= 0) return;
            record(document.title, location.href, video.currentTime, video.duration);
          }
          video.addEventListener('timeupdate', function() {
            if (video.paused || video.ended || Date.now() - lastSave < 3000) return;
            lastSave = Date.now();
            save();
          });
          video.addEventListener('pause', save);
        });
      }
      cleanPage();
      attachVideos();
      if (document.documentElement && !window.__lokiIOSObserver) {
        window.__lokiIOSObserver = new MutationObserver(function() {
          cleanPage();
          attachVideos();
        });
        window.__lokiIOSObserver.observe(document.documentElement, {childList:true,subtree:true,characterData:true});
      }
    })();
    """#
}

struct ContentView: View {
    @StateObject private var browser = BrowserModel()
    @State private var showingHistory = false
    @State private var showingUpdateInfo = false

    var body: some View {
        VStack(spacing: 0) {
            header
            Rectangle().fill(Color(red: 128/255, green: 111/255, blue: 73/255).opacity(0.32)).frame(height: 1)
            if browser.isLoading {
                ProgressView().tint(LokiPalette.gold).frame(height: 3).frame(maxWidth: .infinity)
            }
            WebViewContainer(webView: browser.webView)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(LokiPalette.background)
            navigationBar
        }
        .background(LokiPalette.background.ignoresSafeArea())
        .safeAreaInset(edge: .top, spacing: 0) { Color.clear.frame(height: 0) }
        .sheet(isPresented: $showingHistory) {
            historySheet
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .alert("Atualizar LOKI CINEMA", isPresented: $showingUpdateInfo) {
            Button("Atualizar catálogo") { browser.reloadCatalog() }
            Button("Fechar", role: .cancel) {}
        } message: {
            Text("O catálogo será recarregado. Atualizações do aplicativo iOS serão distribuídas por uma versão assinada (App Store ou TestFlight), não instaladas diretamente pelo botão.")
        }
    }

    private var header: some View {
        HStack(spacing: 4) {
            CrownMark()
                .fill(LokiPalette.goldBright)
                .frame(width: 32, height: 36)
                .padding(.trailing, 1)
            VStack(alignment: .leading, spacing: -2) {
                Text("LOKI")
                    .font(.system(size: 18, weight: .bold, design: .default))
                    .tracking(1)
                    .foregroundStyle(LokiPalette.gold)
                Text("C I N E M A")
                    .font(.system(size: 9, weight: .bold))
                    .tracking(1.1)
                    .foregroundStyle(LokiPalette.gold)
            }
            Spacer(minLength: 2)
            AirPlayRouteButton()
                .frame(width: 38, height: 39)
                .background(LokiPalette.button)
                .clipShape(RoundedRectangle(cornerRadius: 13))
                .overlay(RoundedRectangle(cornerRadius: 13).stroke(LokiPalette.buttonStroke, lineWidth: 1))
                .padding(.trailing, 1)
            Button { showingHistory = true } label: {
                Text("♡ ASSISTIDOS")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(LokiPalette.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    .frame(width: 76, height: 39)
                    .background(LokiPalette.button)
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                    .overlay(RoundedRectangle(cornerRadius: 13).stroke(LokiPalette.buttonStroke, lineWidth: 1))
            }
            Button { showingUpdateInfo = true } label: {
                Text("ATUALIZAR")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(width: 78, height: 39)
                    .background(LinearGradient(colors: [Color(red: 214/255, green: 41/255, blue: 61/255), Color(red: 148/255, green: 20/255, blue: 38/255)], startPoint: .leading, endPoint: .trailing))
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                    .overlay(RoundedRectangle(cornerRadius: 13).stroke(Color(red: 230/255, green: 90/255, blue: 101/255), lineWidth: 1))
            }
        }
        .padding(.horizontal, 9)
        .frame(height: 76)
        .background(LinearGradient(colors: [Color(red: 23/255, green: 19/255, blue: 26/255), LokiPalette.topBar, Color(red: 11/255, green: 12/255, blue: 17/255)], startPoint: .leading, endPoint: .trailing))
    }

    private var navigationBar: some View {
        HStack(spacing: 12) {
            Button { browser.goBack() } label: {
                Text("‹   Voltar")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LokiPalette.text)
                    .frame(maxWidth: .infinity)
                    .frame(height: 43)
                    .background(LokiPalette.button)
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                    .overlay(RoundedRectangle(cornerRadius: 13).stroke(LokiPalette.buttonStroke, lineWidth: 1))
            }
            .disabled(!browser.canGoBack)
            .opacity(browser.canGoBack ? 1 : 0.45)

            Button { browser.goForward() } label: {
                Text("Avançar   ›")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LokiPalette.text)
                    .frame(maxWidth: .infinity)
                    .frame(height: 43)
                    .background(LokiPalette.button)
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                    .overlay(RoundedRectangle(cornerRadius: 13).stroke(LokiPalette.buttonStroke, lineWidth: 1))
            }
            .disabled(!browser.canGoForward)
            .opacity(browser.canGoForward ? 1 : 0.45)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(LokiPalette.topBar)
        .overlay(alignment: .top) { Rectangle().fill(LokiPalette.buttonStroke.opacity(0.55)).frame(height: 1) }
    }

    private var historySheet: some View {
        NavigationStack {
            Group {
                if browser.watched.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.system(size: 34))
                            .foregroundStyle(LokiPalette.gold)
                        Text("Nenhum conteúdo assistido")
                            .font(.headline)
                        Text("Os filmes e episódios que você abrir aparecerão aqui.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding()
                } else {
                    List(browser.watched) { entry in
                        Button {
                            browser.open(entry)
                            showingHistory = false
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(entry.title).font(.headline).foregroundStyle(.primary)
                                if entry.position > 0 {
                                    Text("Continuar de \(timeLabel(entry.position))")
                                        .font(.caption).foregroundStyle(.secondary)
                                } else {
                                    Text("Aberto recentemente").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("ASSISTIDOS")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Limpar") { browser.removeHistory() }.disabled(browser.watched.isEmpty)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Fechar") { showingHistory = false }
                }
            }
        }
        .tint(LokiPalette.gold)
    }

    private func timeLabel(_ seconds: Double) -> String {
        let value = max(0, Int(seconds))
        let hours = value / 3600
        let minutes = (value % 3600) / 60
        let secs = value % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, secs)
            : String(format: "%d:%02d", minutes, secs)
    }
}

private struct WebViewContainer: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

private struct AirPlayRouteButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.tintColor = UIColor(LokiPalette.goldBright)
        picker.activeTintColor = UIColor(LokiPalette.gold)
        picker.prioritizesVideoDevices = true
        return picker
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

private struct CrownMark: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let w = rect.width, h = rect.height
        path.move(to: CGPoint(x: w * 0.10, y: h * 0.30))
        path.addLine(to: CGPoint(x: w * 0.28, y: h * 0.46))
        path.addLine(to: CGPoint(x: w * 0.50, y: h * 0.12))
        path.addLine(to: CGPoint(x: w * 0.73, y: h * 0.46))
        path.addLine(to: CGPoint(x: w * 0.91, y: h * 0.29))
        path.addLine(to: CGPoint(x: w * 0.83, y: h * 0.79))
        path.addLine(to: CGPoint(x: w * 0.18, y: h * 0.79))
        path.closeSubpath()
        path.move(to: CGPoint(x: w * 0.18, y: h * 0.87))
        path.addLine(to: CGPoint(x: w * 0.82, y: h * 0.87))
        path.addLine(to: CGPoint(x: w * 0.78, y: h * 0.96))
        path.addLine(to: CGPoint(x: w * 0.22, y: h * 0.96))
        path.closeSubpath()
        return path
    }
}
