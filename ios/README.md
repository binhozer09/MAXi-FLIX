# LOKI CINEMA para iOS — base Android 1.8.41

Este projeto é a adaptação nativa para iPhone da versão Android **LOKI CINEMA 1.8.41**. A interface foi reproduzida a partir de `activity_main.xml` e dos recursos visuais da mesma tag: faixa superior dourada/preta, coroa, botão AirPlay, ASSISTIDOS, ATUALIZAR e navegação Voltar/Avançar.

## O que já está implementado no código

- WKWebView nativo mantendo catálogo, detalhes e páginas de player dentro do aplicativo.
- Cabeçalho e barra inferior com dimensões, cores e rótulos baseados no Android 1.8.41.
- Reprodução inline por padrão; o app não força o player a entrar em tela cheia.
- AirPlay pelo seletor nativo do iOS.
- Histórico local de páginas de filmes/séries e progresso de vídeo, com lista ASSISTIDOS.
- Bloqueio de requisições para domínios de publicidade por WKContentRuleList e remoção de elementos 1win conhecidos.
- Navegação Voltar/Avançar.

## Limitações reais do iOS

- O botão ATUALIZAR recarrega o catálogo. O iOS não permite que um app instale uma nova versão de si mesmo por um APK ou download comum; versões do aplicativo precisam ser distribuídas por App Store, TestFlight ou outro canal assinado permitido pela Apple.
- A lista de bloqueio cobre domínios conhecidos e não garante remover todo anúncio servido pelo próprio domínio do catálogo.
- O DNS Cloudflare/VPN por aplicativo do Android depende de uma extensão Network Extension e de entitlement Apple. Este projeto não finge ativar uma VPN sem esse entitlement. A navegação usa o DNS configurado no iPhone.
- O projeto precisa ser aberto/gerado no Xcode em um Mac, com uma equipe de assinatura Apple selecionada, para compilar e instalar em um iPhone.

## Gerar o projeto Xcode

Instale XcodeGen e execute:

```sh
cd ios
xcodegen generate
open LOKICINEMA.xcodeproj
```

No Xcode, selecione a equipe de assinatura em **Signing & Capabilities** e compile para um iPhone ou simulador.
