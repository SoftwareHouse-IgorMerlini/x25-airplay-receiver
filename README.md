# X25 AirPlay Receiver

Transforma um projetor Android (ou qualquer TV box / Android TV) em um receptor AirPlay para
**espelhamento de tela** de iPhone, iPad e Mac, com **decodificação de vídeo por hardware**.

É um fork de [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
(GPLv3), que usa o [UxPlay](https://github.com/FDH2/UxPlay) para o protocolo AirPlay. O protocolo
**não** foi reimplementado: o código C/C++ do UxPlay e a camada JNI foram preservados.

**Download direto do APK** (navegador ou app *Downloader* no projetor):

```
https://github.com/SoftwareHouse-IgorMerlini/x25-airplay-receiver/releases/latest/download/X25-AirPlay.apk
```

---

## Pipeline de vídeo

```
iPhone/iPad/Mac ──AirPlay (RTSP/RTP, FairPlay)──▶ UxPlay (C, native)
      ──JNI (onVideoData: NAL units H.264/H.265)──▶ VideoRenderer (Kotlin)
      ──▶ MediaCodec (decoder de hardware selecionado) ──▶ Surface ──▶ SurfaceView ──▶ projetor
```

* Nada de `Bitmap`, nada de conversão YUV → RGB na CPU, nada de `TextureView`.
* O MediaCodec é configurado com um `Surface` de saída; os quadros ficam na memória de vídeo.
* Nos modos **LOW** e **BALANCED** o decoder renderiza **direto no Surface da SurfaceView**.
  No modo **STABLE** é usado o caminho original do projeto: o decoder renderiza numa
  `SurfaceTexture` e uma thread GL copia na GPU (sem CPU) para a SurfaceView, o que sobrevive à
  recriação da tela sem reiniciar o decoder.

### Arquivos principais

| Responsabilidade | Arquivo |
|---|---|
| AirPlay/RAOP, mDNS, FairPlay (nativo) | `app/src/main/cpp/third_party/UxPlay` (submodule) + `app/src/main/cpp/android_raop_callbacks.c` |
| Camada JNI | `app/src/main/cpp/native_bridge.cpp`, `bridge/NativeBridge.kt`, `bridge/RaopCallbackHandler.kt` |
| Anúncio Bonjour/mDNS | `cpp/android_dnssd_shim.c` (TXT records) + `discovery/NsdServiceManager.kt` (registro via NsdManager) |
| Serviço / ciclo de vida | `service/AirPlayService.kt` |
| MediaCodec (vídeo) | `renderer/VideoRenderer.kt`, `renderer/DecoderSelector.kt` |
| Diagnóstico de decoder | `renderer/CodecInspector.kt`, `renderer/CodecRanking.kt`, `ui/DiagnosticsScreen.kt` |
| Modos de latência | `renderer/LatencyMode.kt` |
| Pipeline GL (modo STABLE) | `renderer/VideoPipeline.kt`, `renderer/EglCore.kt` |
| Áudio (AAC-LC, AAC-ELD, ALAC) | `cpp/audio_engine.cpp`, `cpp/audio_decoder.h` (MediaCodec NDK + ALAC software/FFmpeg), saída Oboe |
| Activity / SurfaceView | `MainActivity.kt`, `ui/MainScreen.kt`, `ui/VideoSurfaceView.kt` |

---

## 1. Requisitos

**Para usar**

* Android 7.0+ (API 24). Funciona em Android TV, projetores e TV boxes genéricos.
* **Não** precisa de Google Play Services, Play Store, login ou conta.
* iPhone/iPad/Mac e projetor **na mesma rede Wi-Fi / sub-rede** (mDNS não atravessa roteadores).

**Para compilar**

* JDK 17+ (o CI usa JDK 21)
* Android SDK com `compileSdk 36` e **NDK 27.0.12077973** (o Gradle instala automaticamente se faltar)
* CMake 3.22+ (instalado pelo SDK Manager)
* Git (os submodules são obrigatórios)
* Uns 10 GB livres; a primeira compilação nativa (OpenSSL + FFmpeg + UxPlay × 3 ABIs) leva 20–40 min

## 2. Instalação (usuário final)

**Pelo navegador ou Downloader do projetor**

1. Abra o navegador (ou o app *Downloader*) no projetor.
2. Digite o endereço:
   `https://github.com/SoftwareHouse-IgorMerlini/x25-airplay-receiver/releases/latest/download/X25-AirPlay.apk`
3. Quando pedir, permita *Instalar apps desconhecidos* para esse navegador/Downloader.
4. Abra **X25 AirPlay Receiver**. O servidor inicia sozinho e fica anunciado como **X25 AirPlay**.

**No iPhone**: Central de Controle → **Espelhar a Tela** → **X25 AirPlay**.

## 3. Build

```bash
git clone https://github.com/SoftwareHouse-IgorMerlini/x25-airplay-receiver
cd x25-airplay-receiver
git submodule update --init --recursive

./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

O APK de release é assinado se existir `local.properties` com `storeFile`, `storePassword`,
`keyAlias` e `keyPassword` (veja `signing/README.md`). Sem isso o Gradle gera
`app-release-unsigned.apk`.

**CI**: `.github/workflows/x25-build.yml` compila, roda os testes unitários, gera
`app-debug.apk` e `app-release.apk` e publica ambos em *Releases* a cada push na `main`.

## 4. Instalação via ADB

Ative *Opções do desenvolvedor → Depuração USB/ADB* no projetor e:

```bash
adb connect IP_DO_PROJETOR:5555        # se for via rede
adb install -r app/build/outputs/apk/debug/app-debug.apk
# ou a versão de uso:
adb install -r app/build/outputs/apk/release/app-release.apk

adb shell am start -n com.x25.airplay/io.github.jqssun.airplay.MainActivity
```

## 5. Configuração

Aba **Settings**:

| Opção | Valores | Observação |
|---|---|---|
| Server name | padrão `X25 AirPlay` | nome que aparece no "Espelhar a Tela" |
| **Latency** | LOW / BALANCED / STABLE | padrão BALANCED (detalhes abaixo) |
| **Decoder** | AUTO / HARDWARE / SOFTWARE | padrão AUTO |
| **Screen** | FIT / FILL / STRETCH | padrão FIT (mantém a proporção) |
| **Overscan** | 0–10 % | reduz a imagem para compensar bordas cortadas pelo projetor |
| **Show debug overlay** | ON / OFF | estatísticas por cima do vídeo |
| **PIN** | OFF / 4 dígitos | ver nota abaixo |
| Resolution | AUTO / 720p / 1080p / 4K / personalizada | resolução anunciada ao iPhone |
| Maximum FPS | AUTO / 24 / 30 / 60 / 120 | AUTO = taxa da tela, limitada pela capacidade do decoder e a 60 |
| H.265 (HEVC) | ON / OFF | só é anunciado se houver decoder HEVC de hardware adequado |

**Modos de latência**

* **LOW** – MediaCodec → SurfaceView direto, cada quadro é exibido assim que decodificado,
  flags de baixa latência do decoder (`low-latency`, extensões Qualcomm/Amlogic/Exynos/HiSilicon),
  menor buffer de áudio.
* **BALANCED** – renderização direta, quadros alinhados ao VSYNC pelo timestamp (movimento mais
  uniforme, no máximo ~1 quadro a mais de atraso).
* **STABLE** – caminho GL original + buffer de áudio maior; mais tolerante a Wi-Fi instável.

**Decoder**

* **AUTO** – ordem: 1) hardware do fabricante (vendor), 2) hardware Android, 3) software só como
  fallback se o hardware recusar a configuração.
* **HARDWARE** – nunca cai para software (se o hardware falhar, o erro aparece nos Logs).
* **SOFTWARE** – decodificação na CPU (só H.264). Útil apenas para teste/comparação.

**PIN**: o pareamento AirPlay do iOS/macOS só aceita códigos de **4 dígitos** (o UxPlay também
armazena o PIN em 4 dígitos). Um PIN de 6 dígitos não é suportado pelo protocolo, por isso a opção
não é oferecida.

**Áudio**: mantido o suporte do projeto a AAC-LC, AAC-ELD (MediaCodec) e ALAC (software/FFmpeg ou
MediaCodec).

**Qualidade**: o app não aplica sharpening, upscaling por IA nem filtros. FIT/FILL/STRETCH e overscan
são apenas dimensionamento da SurfaceView feito pelo compositor do sistema.

## 6. Troubleshooting

| Sintoma | O que verificar |
|---|---|
| "X25 AirPlay" não aparece no iPhone | Mesma rede/sub-rede? Wi-Fi de convidados e "isolamento de AP/cliente" bloqueiam mDNS. Reinicie o servidor (Stop/Start). Veja os Logs: deve aparecer `Server started on port 7000`. |
| Aparece mas não conecta | Firewall/roteador bloqueando portas TCP/UDP altas; tente outra porta em *Server port*. Desative VPN no iPhone. |
| Tela preta com áudio | Troque Latency para **STABLE**; teste Decoder **AUTO**; desligue H.265; rode o teste em **Diagnostics**. |
| Travadas / quadros perdidos | Use **STABLE**, reduza para **720p** e **30 FPS**, aproxime o projetor do roteador, prefira 5 GHz. |
| Imagem cortada nas bordas | Aumente **Overscan** (2–5 % costuma bastar). |
| Imagem distorcida | Use **FIT**. |
| Áudio atrasado/adiantado | LOW reduz o buffer; STABLE aumenta. Em *Developer options* há ajuste fino do buffer. |
| Não inicia após reboot | Ative *Start server at boot* e confira se o fabricante não bloqueia apps em segundo plano. |

Logs detalhados: aba **Logs** (pode exportar) ou `adb logcat -s AirPlayService VideoRenderer DecoderSelector AirPlayNative`.

## 7. Diagnóstico de hardware decoder

Aba **Diagnostics**:

* **Hardware accelerated (window)**: `View.isHardwareAccelerated()` da janela.
* **Active decoder** (preenchido só com espelhamento ativo, lido da instância de `MediaCodec` que
  realmente iniciou — `MediaCodec.getCodecInfo()`):
  * Codec (nome completo), Decoder HARDWARE/SOFTWARE
  * Hardware codec (`isHardwareAccelerated`), Software only (`isSoftwareOnly`), Vendor (`isVendor`)
  * Em Android < 10 essas flags não existem; o app mostra `UNKNOWN (API<29)` e marca a
    classificação como *não verificada* em vez de afirmar hardware.
* **Capabilities summary**: quantidade de decoders H.264/HEVC hardware/software, decoder
  preferido, resolução máxima e FPS máximo a 1080p.
* **Run decoder test**: cria, configura (1080p), inicia e libera cada decoder H.264/HEVC e mostra
  OK/FAIL e o tempo — o resultado também vai para os Logs.
* Lista completa com performance points (Android 10+), recurso low-latency e adaptive playback.

O painel de status na tela inicial (e o debug overlay) mostram:
`Status, Decoder, Codec, Resolution, FPS, Bitrate, Dropped Frames, Buffer, Latency`.

* **FPS**: taxa real de quadros saindo do decoder (média dos intervalos).
* **Buffer**: quadros dentro do decoder convertidos em ms.
* **Latency**: tempo entre a chegada do quadro pela rede (JNI) e sua liberação para a tela.
  Não inclui o atraso do iPhone nem do projetor (não há como medir isso sem sincronizar relógios).

**Testes automatizados**

```bash
./gradlew testDebugUnitTest            # classificação/ordenação de decoders, modos de latência (JVM)
./gradlew connectedDebugAndroidTest    # no projetor via ADB: lista H.264/HEVC, hw/sw, resolução e FPS
adb logcat -s X25DecoderTest           # relatório do teste instrumentado
```

---

## Créditos e licença

* [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) – base do app
  (README original em `README.upstream.md`)
* [UxPlay](https://github.com/FDH2/UxPlay) – servidor AirPlay/RAOP
* [FFmpeg](https://ffmpeg.org) – decoder ALAC

Licença **GPLv3** (ver `LICENSE`). Projeto sem afiliação com a Apple Inc. Conteúdo com DRM (ex.: app
Apple TV) não é suportado.
