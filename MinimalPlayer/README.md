# MinimalPlayer

Player video minimalista per Android che legge file da server HTTP.

## Funzionalità
- 📁 Sfoglia directory del server HTTP
- 🎬 Riproduce file video direttamente in streaming (MP4, MKV, AVI, MOV, WebM, TS...)
- ▶️ Riprende da dove hai lasciato (resume automatico)
- 🔐 Autenticazione HTTP Basic opzionale
- 🌙 UI dark minimalista

## Setup del server (lato server)

### Opzione 1 — Python (sviluppo/test)
```bash
cd /cartella/con/video
python3 -m http.server 8080
```
Poi nell'app inserisci: `http://IP_DEL_SERVER:8080`

### Opzione 2 — nginx (produzione)
```nginx
server {
    listen 8080;
    root /path/to/videos;
    autoindex on;
    autoindex_format html;

    # Autenticazione opzionale
    # auth_basic "MinimalPlayer";
    # auth_basic_user_file /etc/nginx/.htpasswd;
}
```

### Opzione 3 — Caddy (più semplice)
```
:8080 {
    root * /path/to/videos
    file_server browse
    basicauth /* {
        user $2a$14$...  # bcrypt hash
    }
}
```

## Come buildare

1. Apri la cartella in Android Studio (Hedgehog o superiore)
2. Attendi la sync Gradle
3. Build → Run su dispositivo o emulatore

## Struttura progetto

```
app/src/main/java/com/minimalplayer/
├── ServerConfigActivity.kt   — Schermata inserimento URL
├── FileBrowserActivity.kt    — Navigazione directory
├── FileAdapter.kt            — RecyclerView adapter
├── HttpDirectoryParser.kt    — Parser listing HTTP
├── ResumeManager.kt          — Persistenza posizioni
└── PlayerActivity.kt         — ExoPlayer fullscreen
```

## Note tecniche
- ExoPlayer (Media3) gestisce lo streaming direttamente senza download
- Il seeking funziona solo se il server supporta Range requests (tutti i server moderni lo fanno)
- Per FTP natale: aggiungi `ftp4j` o `Apache Commons Net` e wrappa il download in un InputStream custom da passare a ExoPlayer via `CustomDataSource`
