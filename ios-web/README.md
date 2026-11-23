# LOKI CINEMA — versão web para iPhone

Projeto PWA independente. Todo o código fica em `ios-web/`; os arquivos Android não são alterados.

## Teste
Abra a URL publicada no Safari. Para adicionar à tela inicial no iPhone: Compartilhar → Adicionar à Tela de Início.

## Limitação importante
O aplicativo Android original usa uma WebView para abrir o endereço remoto do catálogo. Esta versão web reutiliza esse endereço. O site de origem pode bloquear iframes ou ter limitações de reprodução no Safari; nesse caso, o botão **Abrir catálogo** leva o usuário ao site diretamente. A PWA não reproduz a VPN DNS nem os filtros internos do app Android.

## Publicação
Projeto estático, sem etapa de build. Na Vercel, a raiz do projeto deve ser `ios-web`.
