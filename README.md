# MAXi-FLIX

Aplicativo Android independente que abre o site configurado dentro de um WebView, sem encaminhar links HTTP/HTTPS para o navegador externo.

## Aplicativo

- Nome: **MAXi-FLIX**
- Pacote Android: com.maxiflix.app
- Site inicial: https://www18.redecanais.in/
- Android mínimo: 8.0 (API 26)
- A navegação web permanece dentro do app e o player pode usar tela cheia.
- A interface nativa contém somente a identificação MAXi-FLIX e o botão **Atualizar app**.

O acesso ao site e aos vídeos continua sujeito à disponibilidade, às regras e às permissões definidas pelo próprio site e pelos titulares do conteúdo. O aplicativo não contorna bloqueios do servidor.

## Atualizador remoto

O atualizador é independente do DORAMAX-GRATIS e consulta a publicação mais recente deste repositório em GitHub Releases. Cada release precisa conter os arquivos MAXi-FLIX.apk e update.json.

O manifesto precisa conter enabled, versionCode, versionName, apkFile, apkSha256, apkSizeBytes e releaseNotes. O aplicativo confere tamanho, SHA-256, nome do pacote e versão antes de abrir o instalador do Android.

## Compilar APK de teste

- Android Studio: abra o repositório e execute a tarefa :app:assembleDebug.
- Codemagic: conecte binhozer09/MAXi-FLIX e execute o workflow android-debug.
- GitHub Actions: cada push para main compila um APK debug e publica o artefato do workflow.

## Publicar uma atualização remota

1. Gere uma chave de assinatura própria para o MAXi-FLIX e mantenha uma cópia segura.
2. Cadastre os secrets do repositório em Settings → Secrets and variables → Actions:
   - MAXIFLIX_KEYSTORE_BASE64
   - MAXIFLIX_KEYSTORE_PASSWORD
   - MAXIFLIX_KEY_ALIAS
   - MAXIFLIX_KEY_PASSWORD
3. Incremente versionCode e versionName em app/build.gradle.kts.
4. Envie uma tag no formato maxiflix-v1.0.1 (ajuste a versão da tag).
5. O workflow Publish MAXi-FLIX update compila o APK assinado, calcula SHA-256 e tamanho e publica os dois arquivos no GitHub Release.

A chave de assinatura é necessária para que o Android aceite instalar atualizações sobre a versão já instalada. Não compartilhe senhas nem o arquivo da chave no chat.
