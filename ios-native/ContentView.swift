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

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("LOKI CINEMA")
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(.white)
                Spacer()
                Button("ATUALIZAR") { webView?.reload() }
                    .font(.system(size: 13, weight: .bold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(Color(red: 0.45, green: 0.12, blue: 0.66))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .padding(.horizontal, 15)
            .frame(height: 56)
            .background(Color(red: 0.08, green: 0.07, blue: 0.11))

            CatalogWebView(
                url: catalogURL,
                webView: $webView,
                canGoBack: $canGoBack,
                canGoForward: $canGoForward
            )
            .background(Color.black)

            HStack(spacing: 10) {
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
            .font(.system(size: 14, weight: .semibold))
            .buttonStyle(.borderedProminent)
            .tint(Color(red: 0.36, green: 0.12, blue: 0.55))
            .padding(8)
            .background(Color(red: 0.08, green: 0.07, blue: 0.11))
        }
        .background(.black)
        .sheet(isPresented: $showingHistory) {
            WatchHistoryView(items: history) { item in
                guard let url = URL(string: item.url) else { return }
                webView?.load(URLRequest(url: url))
                showingHistory = false
            }
            .presentationDetents([.medium, .large])
            .preferredColorScheme(.dark)
        }
        .statusBarHidden()
    }

    private func loadHistory() {
        let script = "JSON.stringify(JSON.parse(localStorage.getItem('maxiflix_watch_history_v1') || '[]'))"
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
}

private struct CatalogWebView: UIViewRepresentable {
    let url: URL
    @Binding var webView: WKWebView?
    @Binding var canGoBack: Bool
    @Binding var canGoForward: Bool

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.websiteDataStore = .default()

        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.allowsBackForwardNavigationGestures = true
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.isOpaque = false
        view.backgroundColor = .black
        view.scrollView.backgroundColor = .black
        view.load(URLRequest(url: url, cachePolicy: .useProtocolCachePolicy, timeoutInterval: 30))
        DispatchQueue.main.async { self.webView = view }
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, WKNavigationDelegate {
        let parent: CatalogWebView
        init(_ parent: CatalogWebView) { self.parent = parent }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.parent.canGoBack = webView.canGoBack
                self.parent.canGoForward = webView.canGoForward
            }
            webView.evaluateJavaScript(Self.historyTrackerScript, completionHandler: nil)
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.parent.canGoBack = webView.canGoBack
                self.parent.canGoForward = webView.canGoForward
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

        private static let historyTrackerScript = """
        (() => {
          const key = 'maxiflix_watch_history_v1';
          const resumeKey = 'maxiflix_resume_v1';
          const brand = /rede\\s*(?:de\\s*)?canais(?:\\s*\\.?\\s*in)?/i;
          if (window.__lokiHistoryTracker) return;
          window.__lokiHistoryTracker = true;
          const read = () => {
            try { const v = JSON.parse(localStorage.getItem(key) || '[]'); return Array.isArray(v) ? v : []; }
            catch (_) { return []; }
          };
          const save = item => {
            try {
              const list = read().filter(x => x.url !== item.url);
              list.unshift(item);
              localStorage.setItem(key, JSON.stringify(list.slice(0, 30)));
            } catch (_) {}
          };
          const title = () => {
            let value = (document.title || '').replace(/\\s*[-|]\\s*LOKI CINEMA.*/i, '').trim();
            if (!value || brand.test(value)) value = document.querySelector('h1,h2,[class*="title"],[class*="name"]')?.textContent?.trim() || 'Conteúdo assistido';
            return value.slice(0, 120);
          };
          document.addEventListener('click', event => {
            try {
              const node = event.target?.nodeType === 1 ? event.target : event.target?.parentElement;
              const link = node?.closest?.('a[href]');
              if (!link) return;
              const dest = new URL(link.href, location.href);
              if (!(dest.hostname === 'redecanais.in' || dest.hostname.endsWith('.redecanais.in')) || dest.pathname === '/') return;
              const image = link.querySelector('img');
              const hint = ((link.className || '') + ' ' + (link.parentElement?.className || '')).toLowerCase();
              if (!image && !/poster|filme|movie|serie|card|item/.test(hint)) return;
              let name = link.getAttribute('aria-label') || link.getAttribute('title') ||
                image?.getAttribute('alt') || image?.getAttribute('title') || link.innerText || link.textContent || '';
              name = name.replace(/\\s+/g, ' ').trim();
              if (!name || brand.test(name)) name = decodeURIComponent(dest.pathname.split('/').filter(Boolean).pop() || '').replace(/[-_]+/g, ' ').trim();
              if (!name || brand.test(name)) return;
              save({url:dest.href,title:name.slice(0,120),time:0,duration:0,updatedAt:Date.now()});
            } catch (_) {}
          }, true);
          const saveProgress = video => {
            try {
              if (!Number.isFinite(video.duration) || video.duration <= 0 || !Number.isFinite(video.currentTime) || video.currentTime < 3) return;
              const item = {url:location.href,title:title(),time:video.currentTime,duration:video.duration,updatedAt:Date.now()};
              localStorage.setItem(resumeKey, JSON.stringify(item));
              save(item);
            } catch (_) {}
          };
          const install = () => document.querySelectorAll('video').forEach(video => {
            if (video.dataset.lokiTracked === '1') return;
            video.dataset.lokiTracked = '1';
            let last = 0;
            video.addEventListener('timeupdate', () => { const now = Date.now(); if (now-last >= 3000) { last=now; saveProgress(video); } });
            video.addEventListener('pause', () => saveProgress(video));
          });
          install();
          new MutationObserver(install).observe(document.documentElement, {childList:true,subtree:true});
        })();
        """
    }
}

private struct WatchHistoryView: View {
    let items: [WatchItem]
    let onSelect: (WatchItem) -> Void

    var body: some View {
        NavigationStack {
            Group {
                if items.isEmpty {
                    ContentUnavailableView("Nenhum assistido ainda",
                                           systemImage: "clock",
                                           description: Text("Os filmes e séries escolhidos no catálogo aparecerão aqui."))
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
