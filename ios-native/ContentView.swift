import SwiftUI
import WebKit

private let catalogURL = URL(string: "https://www18.redecanais.in/")!

struct ContentView: View {
    @State private var webView: WKWebView?
    @State private var canGoBack = false
    @State private var canGoForward = false
    @State private var showingHistory = false
    @State private var pageTitle = "LOKI CINEMA"

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
                canGoForward: $canGoForward,
                pageTitle: $pageTitle
            )
            .background(Color.black)

            HStack(spacing: 10) {
                Button { webView?.goBack() } label: {
                    Label("Voltar", systemImage: "chevron.left")
                        .frame(maxWidth: .infinity)
                }
                .disabled(!canGoBack)

                Button { webView?.goForward() } label: {
                    Label("Avançar", systemImage: "chevron.right")
                        .frame(maxWidth: .infinity)
                }
                .disabled(!canGoForward)

                Button { showingHistory = true } label: {
                    Label("Assistidos", systemImage: "clock")
                        .frame(maxWidth: .infinity)
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
            WatchHistoryView(webView: webView)
                .presentationDetents([.medium, .large])
        }
        .statusBarHidden()
    }
}

private struct CatalogWebView: UIViewRepresentable {
    let url: URL
    @Binding var webView: WKWebView?
    @Binding var canGoBack: Bool
    @Binding var canGoForward: Bool
    @Binding var pageTitle: String

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
                self.parent.pageTitle = webView.title ?? "LOKI CINEMA"
            }
            webView.evaluateJavaScript(WatchHistoryView.historyTrackerScript, completionHandler: nil)
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.parent.canGoBack = webView.canGoBack
                self.parent.canGoForward = webView.canGoForward
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let scheme = navigationAction.request.url?.scheme?.lowercased(),
                  scheme == "https" || scheme == "http" else {
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }
    }
}

private struct WatchHistoryView: View {
    var webView: WKWebView?

    var body: some View {
        NavigationStack {
            List {
                Text("O histórico é registrado no armazenamento do catálogo quando o site permite acesso ao seu conteúdo.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("Voltar ao catálogo") { webView?.goBack() }
            }
            .navigationTitle("Assistidos")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    static let historyTrackerScript = """
    (() => {
      const key = 'maxiflix_watch_history_v1';
      if (window.__lokiHistoryInit) return;
      window.__lokiHistoryInit = true;
      document.addEventListener('click', e => {
        const link = e.target?.closest?.('a[href]');
        if (!link) return;
        try {
          const u = new URL(link.href, location.href);
          if (u.origin !== location.origin || u.pathname === '/') return;
          const title = (link.innerText || link.querySelector('img')?.alt || link.title || '').trim();
          if (!title) return;
          const old = JSON.parse(localStorage.getItem(key) || '[]');
          const next = [{url:u.href,title,updatedAt:Date.now()}, ...old.filter(x => x.url !== u.href)].slice(0,30);
          localStorage.setItem(key, JSON.stringify(next));
        } catch (_) {}
      }, true);
    })();
    """
}
