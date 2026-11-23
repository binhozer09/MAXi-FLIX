# LOKI CINEMA — iOS nativo

Este diretório é uma implementação independente para iPhone. Não altera nem substitui os arquivos Android.

## Abordagem
- SwiftUI para a estrutura do aplicativo.
- WKWebView abre o catálogo diretamente, sem iframe.
- Controles próprios para atualizar, voltar, avançar e acessar Assistidos.
- Cookies, armazenamento web e cache ficam no armazenamento padrão do WKWebView.

## Limitações que ainda precisam ser tratadas
- O atualizador de APK do Android não pode instalar APK no iOS. Atualizações do app nativo exigem distribuição iOS (por exemplo, App Store/TestFlight); conteúdo web pode ser atualizado no servidor.
- O serviço VPN/DNS do Android não pode ser portado como está. Um recurso equivalente exigiria uma implementação iOS específica, permissões e entitlement da Apple.
- O histórico e a reprodução precisam ser validados no domínio real, pois dependem do comportamento do catálogo e dos players de terceiros.

## Compilação
Abrir estes fontes em um projeto Xcode para iOS, selecionar uma equipe de assinatura Apple e compilar em um dispositivo ou simulador. Ainda não existe IPA assinado nem publicação iOS.
