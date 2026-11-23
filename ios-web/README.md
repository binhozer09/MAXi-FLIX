# LOKI CINEMA — PWA para iPhone

Esta pasta contém a experiência web independente para iPhone. Nenhum arquivo Android é modificado.

## Comportamento implementado
- Página inicial própria LOKI CINEMA, sem iframe do catálogo.
- O botão **ABRIR LOKI CINEMA** navega diretamente para o endereço de catálogo usado pelo WebView Android: `https://www18.redecanais.in/`.
- Instruções de instalação pelo Safari: Compartilhar → Adicionar à Tela de Início.
- Ao abrir a PWA instalada em modo standalone, os controles de instalação ficam ocultos.
- Service worker atualiza o cache do shell e mantém a página inicial disponível offline.

## Limites técnicos que não podem ser ocultados
O Android original é um aplicativo nativo com Android WebView. Ele abre o catálogo remoto e injeta controles/ajustes próprios, gerencia cookies e usa um serviço VPN local por aplicativo para encaminhar consultas DNS UDP/53 aos resolvedores Cloudflare. Uma PWA executada no Safari não pode:
- controlar ou injetar scripts no site remoto após navegar para outro domínio;
- compartilhar automaticamente o cookie jar do Safari como um WebView nativo;
- interceptar tráfego DNS nem criar uma VPN;
- garantir reprodução de mídia que o site remoto não suporte no Safari.

A VPN equivalente exige um aplicativo iOS nativo com Network Extension, entitlement Apple, assinatura e autorização do usuário. Ela não está implementada nesta PWA. O catálogo e os vídeos dependem do serviço remoto e de sua compatibilidade com Safari.

## Publicação
Site estático. Configure a raiz do projeto Vercel como `ios-web`. O domínio antigo pode continuar servindo um deployment anterior até que esta branch seja selecionada/publicada no projeto Vercel.
