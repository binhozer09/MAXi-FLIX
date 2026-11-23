# LOKI CINEMA — iOS nativo

Implementação iOS independente. Os arquivos do aplicativo Android permanecem na branch `main` e não são alterados por este port.

## O que esta versão implementa

- Interface nativa SwiftUI com identidade LOKI CINEMA.
- Catálogo aberto diretamente em WKWebView — sem iframe e sem redirecionar para uma página intermediária.
- Cookies, armazenamento web e cache pelo `WKWebsiteDataStore.default()`.
- Navegação voltar/avançar, atualizar e gesto de navegação.
- Reprodução HTML5 dentro do WKWebView; o comportamento de tela cheia depende do player e deve ser validado em iPhone real.
- Histórico de títulos selecionados, progresso de vídeo e retomada de reprodução usando armazenamento local do site.
- Bloqueio de uma lista de domínios de publicidade por WKContentRuleList e remoção dos elementos 1win detectados na página.
- Restrição de navegação principal ao domínio `redecanais.in` e seus subdomínios.

## O que ainda não está implementado

- **VPN/DNS Cloudflare:** o serviço Android `VpnService` não pode ser reutilizado no iOS. O equivalente exige Network Extension, extensão Packet Tunnel, entitlement aprovado pela Apple, perfil de assinatura e autorização do usuário. Esta versão ainda não cria VPN.
- **Atualização do aplicativo:** o atualizador de APK Android não se aplica ao iOS. Distribuição nativa exige App Store/TestFlight ou outro método de distribuição iOS permitido.
- **Validação em iPhone:** a compilação de simulador não comprova reprodução, cookies, histórico ou tela cheia em aparelho físico. Esses testes ainda são necessários.

## Por que não é possível entregar a mesma coisa como uma PWA

Uma PWA aberta no Safari não tem acesso às APIs de WKWebView, não pode injetar o código do LOKI CINEMA em outro domínio depois de navegar até o catálogo, não controla os cookies do domínio remoto e não pode criar uma VPN. Uma PWA pode instalar um atalho e abrir o site, mas isso não reproduz o aplicativo Android. Para preservar o comportamento do app, a implementação correta é nativa.

## Compilação

O workflow `.github/workflows/ios-build.yml` gera o projeto Xcode com XcodeGen e compila para o simulador iOS sem assinatura. Isso não gera IPA instalável nem substitui a assinatura de uma equipe Apple.
