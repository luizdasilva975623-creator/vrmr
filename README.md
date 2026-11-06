# VRMR 0.2

Projeto Android em Kotlin para visualização MR pelo celular.

## O que mudou

- Suavização adaptativa das 21 landmarks da mão e filtro de pinça com histerese.
- Google, YouTube e Mapas abrem em painéis WebView; é possível apontar, pinçar e interagir com a página.
- Gerenciador de janelas no rodapé do menu: até três painéis de apps ficam ativos; os demais são minimizados e podem ser restaurados.
- Config. abre os ajustes Bluetooth do Android para emparelhar controles HID compatíveis.
- Versão do app atualizada para 0.2 (versionCode 2).

## Limites importantes

- Isto não substitui o menu do sistema Meta Quest e não consegue mostrar aplicativos Android arbitrários em painéis sem suporte do sistema.
- Bluetooth aqui apenas abre os ajustes do Android; não controla o celular nem espelha apps.
- A renderização de páginas WebView é capturada em imagens para as texturas 3D. Vídeo, DRM, login do Google e áudio podem variar conforme o Android e precisam ser testados no aparelho.
- A câmera e o rastreamento dependem da câmera, iluminação e desempenho do celular. Não há compilação ou teste físico incluído neste pacote.

## Build

O workflow `.github/workflows/build.yml` usa Java 17 e Gradle 8.7 e baixa o modelo oficial do MediaPipe antes do build. O ZIP não contém `hand_landmarker.task`; a compilação local precisa baixar o mesmo arquivo para `app/src/main/assets/hand_landmarker.task`.
