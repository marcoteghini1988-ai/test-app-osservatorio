# MyBotRun Osservatore Android v1.0 — progetto NATIVO

**Non è la versione Android che controlla Clash.** Questa applicazione non usa Accessibilità, overlay o comandi di gioco.
È dedicata soltanto alla lettura locale di registrazioni video e alla raccolta dei risultati.

## Funzioni programmate nel codice

- Più video MP4 selezionati con il selettore documenti Android; coda **sequenziale**.
- Video decodificati con Android `MediaExtractor` + `MediaCodec` + `ImageReader`, senza HTML/Chrome/`content://` nel browser.
- Campionamento visivo medio a 0,5 s, fino a 0,25 s dopo un cambiamento significativo.
- ONNX Runtime Android **offline** e modello YOLOv5 reale `best_320.onnx` incluso, SHA256 `3260b821449e1c9dd182cdf7dbe3a7b6247cd30150d0c475349215777a3e9f36`.
- Rilevazioni delle **16 classi del modello** (edifici/difese/altari) con NMS per classe.
- Possibili cambiamenti della barra inferiore, **non** riconoscimento certo di truppe, schieramenti o incantesimi.
- Risultati di ogni video in ZIP con `manifest.json`, `timeline.csv`, `detections_yolo.csv`, `action_candidates.csv`, JPEG limitati.
- ZIP cumulativo con `INDICE_BATCH.json` e `DIAGNOSI_UMANA.txt`; errori e incompletezza sono registrati.
- Pulsanti **SELEZIONA VIDEO → START → SALVA ZIP**, e interruzione.

## Attenzione: stato della build

Questo archivio contiene il **codice sorgente Android**, non un APK già compilato o installato. Nell'ambiente di generazione non erano disponibili Android SDK né accesso ai repository Gradle. La build su Android e il comportamento del decoder sullo Z Fold 6 **non sono ancora stati provati**.

L'app mantiene lo schermo acceso durante la scansione: per la v1 è necessario lasciarla aperta. Non c'è un servizio in background o una garanzia di compatibilità con ogni codec MP4.

## Compilazione automatica GitHub Actions, senza PC

Il workflow `.github/workflows/android.yml` compila l'APK su GitHub (non esegue l'analisi dei video su GitHub). Sono compresi il setup SDK e l'accettazione delle licenze che in precedenza avevano provocato un errore di build.

Se vuoi caricare **soltanto due file da telefono** in un nuovo repository, usa `MyBotRun_Osservatore_Android_v1_SORGENTE.zip` e il file `WORKFLOW_SOLO_ZIP_PER_TELEFONO.yml` (da inserire come `.github/workflows/android.yml`). Il secondo workflow estrae lo ZIP al momento della compilazione. Le istruzioni esatte sono in `COME_OTTENERE_APK_DAL_SAMSUNG.md`.

L'APK debug generato da GitHub è **installabile**, ma potrebbe richiedere il consenso Android per l'installazione da Chrome/GitHub; non è una release firmata per il Play Store.

## Limitazioni note

- Non sono state riconosciute automaticamente tutte le truppe/azioni: il modello contiene soltanto 16 classi di strutture.
- Le coordinate di rilevazione si riferiscono ai fotogrammi decodificati dal file; la rotazione del display dipende dai metadati del video e va verificata.
- Per registrazioni enormi l'elaborazione CPU ONNX può richiedere molto tempo; solo un video per volta.
- Allo stato attuale non c'è una modalità foreground service per proseguire con lo schermo spento.
- In caso di impossibilità di decodifica nativa, il risultato viene marcato **INCOMPLETO**, senza inventare rilevazioni.

## Componenti

Gradle 8.10.2 · Android Gradle Plugin 8.7.3 · Kotlin 2.0.21 · SDK 35 · API minima 28 · ONNX Runtime Android 1.20.0 (incluso nell'APK compilato).

La build ha bisogno di Internet per scaricare le dipendenze Gradle, **non per analizzare i video sul dispositivo**.
