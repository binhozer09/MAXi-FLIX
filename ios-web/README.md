# LOKI CINEMA — PWA para iPhone

Esta pasta contém a versão web independente para iPhone. O trabalho fica isolado em `ios-web/`; os arquivos Android não são alterados.

## Comportamento

- Abre o catálogo `https://www18.redecanais.in/` dentro de um iframe na própria interface da PWA.
- Não usa `window.location`, links externos nem redirecionamento automático para o Safari.
- O botão **ATUALIZAR** recarrega o catálogo dentro da mesma tela.
- O manifest usa `display: standalone` e o service worker mantém o shell da PWA em cache.
- No iPhone, abra o endereço da PWA no Safari uma vez e use Compartilhar → Adicionar à Tela de Início. Depois, abra pelo ícone.

## Limite importante

O site remoto pode enviar cabeçalhos `X-Frame-Options` ou uma política CSP `frame-ancestors` que proíbam sua exibição dentro de iframe. Uma PWA não pode remover nem contornar essa política do servidor. Se o catálogo bloquear a incorporação, ele não será exibido nesta tela; a PWA continuará aberta e não redirecionará automaticamente para o Safari.

Como o catálogo é de outro domínio, a PWA também não pode ler o conteúdo do player, controlar o histórico interno do site ou injetar scripts nele. Por isso, recursos nativos do Android como histórico de reprodução e bloqueio de anúncios não podem ser prometidos nesta versão web.

## Publicação

Site estático, sem etapa de build. Configure a raiz do projeto Vercel como `ios-web` e publique a branch `web/ios-pwa`.
