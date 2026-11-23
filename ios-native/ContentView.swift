import SwiftUI
import WebKit

private let catalogURL = URL(string: "https://www18.redecanais.in/")!
private let catalogHost = "redecanais.in"

struct WatchItem: Decodable, Identifiable {
    let url: String
    let title: String
    let time: Double?
    let duration: Double?
    let updatedAt: Double?

    var id: String { url }

    var detail: String {
        guard let time, time > 0 else { return "Assistido recentemente" }
        let seconds = Int(time)
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        let remainder = seconds % 60
        return hours > 0
            ? String(format: "Continuar de %d:%02d:%02d", hours, minutes, remainder)
            : String(format: "Continuar de %d:%02d", minutes, remainder)
    }
}

struct ContentView: View {
    @State private var webView: WKWebView?
    @State private var canGoBack = false
    @State private var canGoForward = false
    @State private var showingHistory = false
    @State private var history: [WatchItem] = []
    @AppStorage("loki.lastVisitedURL") private var lastVisitedURL = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("LOKI CINEMA")
                    .font(.system(size: 19, weight: .bold))
                    .tracking(1.1)
                    .foregroundStyle(Color(red: 0.94, green: 0.82, blue: 0.53))
                Spacer()
                Button {
                    webView?.reload()
                } label: {
                    Label("Atualizar", systemImage: "arrow.clockwise")
                        .font(.system(size: 13, weight: .bold))
                }
                .buttonStyle(.borderedProminent)
                .tint(Color(red: 0.38, green: 0.25, blue: 0.10))
            }
            .padding(.horizontal, 15)
            .frame(height: 54)
            .background(Color(red: 0.045, green: 0.043, blue: 0.055))

            CatalogWebView(
                url: URL(string: lastVisitedURL).flatMap { isAllowedCatalogURL($0) ? $0 : nil } ?? catalogURL,
                webView: $webView,
                canGoBack: $canGoBack,
                canGoForward: $canGoForward,
                onURLChange: { lastVisitedURL = $0.absoluteString }
            )
            .background(.black)

            HStack(spacing: 8) {
                Button { webView?.goBack() } label: {
                    Label("Voltar", systemImage: "chevron.left").frame(maxWidth: .infinity)
                }
                .disabled(!canGoBack)

                Button { webView?.goForward() } label: {
                    Label("Avançar", systemImage: "chevron.right").frame(maxWidth: .infinity)
                }
                .disabled(!canGoForward)

                Button {
                    loadHistory()
                    showingHistory = true
                } label: {
                    Label("Assistidos", systemImage: "clock").frame(maxWidth: .infinity)
                }
            }
            .font(.system(size: 13, weight: .semibold))
            .buttonStyle(.borderedProminent)
            .tint(Color(red: 0.27, green: 0.20, blue: 0.35))
            .padding(8)
            .background(Color(red: 0.045, green: 0.043, blue: 0.055))
        }
        .background(.black)
        .sheet(isPresented: $showingHistory) {
            WatchHistoryView(items: history) { item in
                guard let url = URL(string: item.url), isAllowedCatalogURL(url) else { return }
                let escaped = item.time ?? 0
                let prepare = "sessionStorage.setItem('loki_resume_pending_v1', " +
                    "(function(){return JSON.stringify(" +
                    "{url:" + jsString(url.absoluteString) +
                    ",time:" + String(escaped) + "});})());"
                webView?.evaluateJavaScript(prepare) { _, _ in
                    self.webView?.load(URLRequest(url: url))
                }
                showingHistory = false
            }
            .presentationDetents([.medium, .large])
            .preferredColorScheme(.dark)
        }
        .statusBarHidden()
    }

    private func loadHistory() {
        let script = "JSON.stringify(JSON.parse(localStorage.getItem('loki_watch_history_v1') || '[]'))"
        webView?.evaluateJavaScript(script) { result, _ in
            guard let json = result as? String,
                  let data = json.data(using: .utf8),
                  let items = try? JSONDecoder().decode([WatchItem].self, from: data) else {
                DispatchQueue.main.async { history = [] }
                return
            }
            DispatchQueue.main.async { history = items }
        }
    }

    private func isAllowedCatalogURL(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased(),
              url.scheme == "https" || url.scheme == "http" else { return false }
        return host == catalogHost || host.hasSuffix("." + catalogHost)
    }

    private func jsString(_ value: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [value])
        let encoded = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return String(encoded.dropFirst().dropLast())
    }
}

private struct CatalogWebView: UIViewRepresentable {
    let url: URL
    @Binding var webView: WKWebView?
    @Binding var canGoBack: Bool
    @Binding var canGoForward: Bool
    let onURLChange: (URL) -> Void

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.websiteDataStore = .default()

        let controller = WKUserContentController()
        controller.addUserScript(WKUserScript(
            source: Self.catalogEnhancements,
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: false
        ))
        configuration.userContentController = controller

        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.uiDelegate = context.coordinator
        view.allowsBackForwardNavigationGestures = true
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.isOpaque = false
        view.backgroundColor = .black
        view.scrollView.backgroundColor = .black
        DispatchQueue.main.async { self.webView = view }

        Self.installAdBlocker(on: view) {
            view.load(URLRequest(url: url, cachePolicy: .useProtocolCachePolicy, timeoutInterval: 30))
        }
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(self) }

    private static func installAdBlocker(on webView: WKWebView, completion: @escaping () -> Void) {
        let domains = [
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "googletagservices.com", "adservice.google.com", "adnxs.com",
            "adform.net", "taboola.com", "outbrain.com", "criteo.com",
            "pubmatic.com", "openx.net", "rubiconproject.com", "casalemedia.com",
            "smartadserver.com", "adroll.com", "amazon-adsystem.com",
            "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
            "exoclick.com", "trafficjunky.net", "juicyads.com", "hilltopads.net",
            "onclickads.net", "onclickalgo.com", "mgid.com", "revcontent.com",
            "adskeeper.co.uk", "1win.com", "1win.bet", "1win.tv", "1win.run",
            "1win.link", "1win.games", "1win.live"
        ]
        let rules: [[String: Any]] = domains.map { domain in
            let escaped = NSRegularExpression.escapedPattern(for: domain)
            return [
                "trigger": ["url-filter": "^https?://([^/]+\\.)?\(escaped)/"],
                "action": ["type": "block"]
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rules),
              let encoded = String(data: data, encoding: .utf8) else {
            completion()
            return
        }
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "loki-cinema-ad-block",
            encodedContentRuleList: encoded
        ) { list, _ in
            if let list { webView.configuration.userContentController.add(list) }
            completion()
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let parent: CatalogWebView
        init(_ parent: CatalogWebView) { self.parent = parent }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.parent.canGoBack = webView.canGoBack
                self.parent.canGoForward = webView.canGoForward
                if let url = webView.url { self.parent.onURLChange(url) }
            }
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.parent.canGoBack = webView.canGoBack
                self.parent.canGoForward = webView.canGoForward
                if let url = webView.url { self.parent.onURLChange(url) }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let target = navigationAction.request.url,
                  let scheme = target.scheme?.lowercased(),
                  scheme == "https" || scheme == "http" else {
                decisionHandler(.cancel)
                return
            }
            if navigationAction.targetFrame?.isMainFrame == true {
                let host = target.host?.lowercased() ?? ""
                guard host == catalogHost || host.hasSuffix("." + catalogHost) else {
                    decisionHandler(.cancel)
                    return
                }
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            guard navigationAction.targetFrame == nil,
                  let url = navigationAction.request.url,
                  let scheme = url.scheme?.lowercased(),
                  scheme == "https" || scheme == "http",
                  let host = url.host?.lowercased(),
                  host == catalogHost || host.hasSuffix("." + catalogHost) else { return nil }
            webView.load(URLRequest(url: url))
            return nil
        }
    }

    private static let catalogEnhancements = #"""
    (() => {
      if (window.__lokiCinemaEnhancements) return;
      window.__lokiCinemaEnhancements = true;
      const historyKey = 'maxiflix_watch_history_v1';
      const resumeKey = 'maxiflix_resume_v1';
      const pendingKey = 'maxiflix_resume_pending_v1';
      const brandPattern = /rede\s*(?:de\s*)?canais(?:\s*\.?\s*in)?/i;
      const readHistory = () => {
        try { const value = JSON.parse(localStorage.getItem(historyKey) || '[]'); return Array.isArray(value) ? value : []; }
        catch (_) { return []; }
      };
      const save = item => {
        try {
          const list = readHistory().filter(entry => entry.url !== item.url);
          list.unshift(item);
          localStorage.setItem(historyKey, JSON.stringify(list.slice(0, 30)));
        } catch (_) {}
      };
      const currentTitle = () => {
        let title = (document.title || '').trim();
        if (!title || brandPattern.test(title)) title = document.querySelector('h1,h2,[class*="title"],[class*="name"]')?.textContent?.trim() || 'Conteúdo assistido';
        return title.slice(0, 120);
      };
      document.addEventListener('click', event => {
        try {
          const node = event.target?.nodeType === 1 ? event.target : event.target?.parentElement;
          const link = node?.closest?.('a[href]');
          if (!link) return;
          const destination = new URL(link.href, location.href);
          if (!(destination.hostname === 'redecanais.in' || destination.hostname.endsWith('.redecanais.in')) || destination.pathname === '/') return;
          const image = link.querySelector('img');
          const hint = ((link.className || '') + ' ' + (link.parentElement?.className || '')).toLowerCase();
          if (!image && !/poster|filme|movie|serie|card|item/.test(hint)) return;
          let title = link.getAttribute('aria-label') || link.getAttribute('title') || image?.alt || image?.title || link.innerText || link.textContent || '';
          title = title.replace(/\s+/g, ' ').trim();
          if (!title || brandPattern.test(title)) title = decodeURIComponent(destination.pathname.split('/').filter(Boolean).pop() || '').replace(/[-_]+/g, ' ').trim();
          if (title) save({url: destination.href, title: title.slice(0, 120), time: 0, duration: 0, updatedAt: Date.now()});
        } catch (_) {}
      }, true);
      const saveProgress = video => {
        try {
          if (!Number.isFinite(video.duration) || video.duration <= 0 || !Number.isFinite(video.currentTime) || video.currentTime < 3) return;
          const item = {url: location.href, title: currentTitle(), time: video.currentTime, duration: video.duration, updatedAt: Date.now()};
          localStorage.setItem(resumeKey, JSON.stringify(item));
          save(item);
        } catch (_) {}
      };
      const attachVideos = () => document.querySelectorAll('video').forEach(video => {
        if (video.dataset.lokiTracked === '1') return;
        video.dataset.lokiTracked = '1';
        let lastSave = 0;
        video.addEventListener('timeupdate', () => {
          const now = Date.now();
          if (now - lastSave >= 3000) { lastSave = now; saveProgress(video); }
        });
        video.addEventListener('pause', () => saveProgress(video));
        video.addEventListener('ended', () => { try { localStorage.removeItem(resumeKey); } catch (_) {} });
      });
      const cleanAds = () => {
        document.querySelectorAll('iframe[src*="1win" i],a[href*="1win" i],img[src*="1win" i],img[alt*="1win" i],[class*="1win" i],[id*="1win" i],[data-ad*="1win" i]').forEach(node => {
          if (!node.closest('#loki-continue-assistindo')) node.remove();
        });
      };
      const replaceBrand = () => {
        if (!document.body || document.getElementById('loki-brand-mask')) return;
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
          const node = walker.currentNode;
          if (node.parentElement?.closest('#loki-continue-assistindo')) continue;
          if (brandPattern.test((node.nodeValue || '').trim())) {
            const mask = document.createElement('span');
            mask.id = 'loki-brand-mask';
            mask.style.cssText = 'display:inline-block;background:#000;width:76px;height:1.1em;vertical-align:middle;border-radius:2px';
            node.parentNode.replaceChild(mask, node);
            break;
          }
        }
      };
      const resumePending = () => {
        try {
          const pending = JSON.parse(sessionStorage.getItem(pendingKey) || 'null');
          if (!pending || pending.url !== location.href || !(pending.time > 0)) return;
          sessionStorage.removeItem(pendingKey);
          let attempts = 0;
          const timer = setInterval(() => {
            attempts++;
            const video = [...document.querySelectorAll('video')].find(v => Number.isFinite(v.duration) && v.duration > 0);
            if (video) { try { video.currentTime = Math.min(pending.time, Math.max(0, video.duration - 2)); } catch (_) {} clearInterval(timer); }
            else if (attempts >= 40) clearInterval(timer);
          }, 500);
        } catch (_) {}
      };
      const start = () => { attachVideos(); cleanAds(); replaceBrand(); resumePending(); };
      start();
      new MutationObserver(start).observe(document.documentElement, {childList:true,subtree:true,characterData:true});
    })();
    """#
}

private struct WatchHistoryView: View {
    let items: [WatchItem]
    let onSelect: (WatchItem) -> Void

    var body: some View {
        NavigationStack {
            Group {
                if items.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "clock").font(.system(size: 34)).foregroundStyle(.secondary)
                        Text("Nenhum assistido ainda").font(.headline)
                        Text("Os filmes e séries escolhidos no catálogo aparecerão aqui.")
                            .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(items) { item in
                        Button { onSelect(item) } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(item.title).font(.headline).foregroundStyle(.primary)
                                Text(item.detail).font(.caption).foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("Assistidos")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
