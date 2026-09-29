# Assinatura do APK de release

`x25-release.jks` + `keystore.properties` assinam o `app-release.apk` gerado pelo CI
quando os secrets `STORE`/`LOCAL` não estão configurados.

Como este repositório é público, qualquer pessoa pode usar esta chave. Ela serve apenas para
que as atualizações instaladas no projetor mantenham a mesma assinatura. Para uma chave
privada: crie os secrets do repositório `STORE` (base64 de um .jks) e `LOCAL` (base64 de um
local.properties com storeFile=../upload.jks, storePassword, keyAlias, keyPassword); o
workflow passa a usá-los automaticamente. Trocar de chave exige desinstalar o app uma vez.
