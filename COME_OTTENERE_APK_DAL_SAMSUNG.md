# Compilare da Android senza PC

Per chi possiede soltanto il Samsung, la compilazione via GitHub Actions è la strada prevista.
Il file disponibile qui è un progetto sorgente, **non** l'APK già compilato.

### Percorso semplice con due file

1. Da Chrome sul Samsung apri GitHub e crea un repository **privato e vuoto** dedicato all'Osservatore (può anche essere uno nuovo separato dagli altri progetti).
2. In quel repository carica `MyBotRun_Osservatore_Android_v1_SORGENTE.zip` **come file**, non estrarlo su GitHub.
3. In GitHub scegli **Add file → Create new file**, e come nome digita esattamente `.github/workflows/android.yml`.
4. Copia dentro il contenuto del file `WORKFLOW_SOLO_ZIP_PER_TELEFONO.yml` e fai Commit.
5. Apri **Actions → Compila Osservatore da ZIP sul telefono → Run workflow**.
6. Al termine apri la build, sezione **Artifacts**, scarica `MyBotRun-Osservatore-Android-APK`.
7. Estrai quello ZIP dell'Artifact e tocca `app-debug.apk` per installarlo sul Samsung.

Se GitHub richiede conferme relative ad Actions o permessi di installazione, segui le richieste dell'interfaccia. Non sostituire il repository del bot Windows: questo progetto è indipendente.

### Se Actions fallisce

Leggi l'errore esatto del job e riportalo qui. L'APK **non è stato già compilato/testato** nell'ambiente di generazione. Se una dipendenza del runtime Android è stata rimossa dal repository Maven, bisognerà aggiornare `app/build.gradle.kts`.

### Primo test dopo installazione

Seleziona **un MP4** (quello che prima dava errore 4 in Chrome), premi START, lascia l'app in primo piano e salva lo ZIP. Solo se tutto funziona prova i quattro video insieme. Se fallisce, lo ZIP conterrà le diagnosi, senza indicare un'analisi inesistente come completata.
