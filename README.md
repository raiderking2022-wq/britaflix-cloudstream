# Britaflix CloudStream

Complemento de CloudStream 3 para ver series en español (latino, castellano y subtitulado) en español: latino, castellano y subtitulado.

## Características

- **Catálogo principal** de series con paginación (`/series-online/`).
- **Géneros**: acción, animación, aventura, ciencia ficción, comedia, crimen, drama, romance, suspense y terror.
- **Productoras**: Netflix, Amazon, Disney, HBO, Fox, Marvel y DC.
- **Buscador** funcional (WordPress `?s=`) con paginación de resultados.
- **Series con temporadas y episodios**: cada temporada se carga desde su página `/temporada/` con número de temporada y episodio correctos.
- **Reproducción directa**: resuelve el reproductor propio de nupload.my a su playlist HLS (m3u8) sin pasar por la web, con enlaces separados por idioma (Latino, Castellano, Subtitulado) en HD (720p, la máxima que publica el sitio).

## Instalación

En CloudStream: **Configuración → Extensiones → Añadir repositorio** y pegar:

```
https://raw.githubusercontent.com/eagleblue2024-pixel/seriesflix-cloudstream/builds/repo.json
```

Luego instala el complemento **Britaflix** desde ese repositorio.

## Cómo funciona

1. **Catálogos y búsqueda** usan el HTML público del sitio (`article.TPost` tarjetas).
2. **Cada serie** (`/serie/{slug}/`) enumera sus temporadas (`/temporada/{slug}-{n}/`) y de ahí los episodios (`/episodio/{slug}-{S}x{E}/`).
3. **Cada episodio** expone opciones por idioma con un `data-url` en Base64 que apunta a `nupload.my/watch/{id}`.
4. El **reproductor de nupload** ofusca la URL del stream en un array Base64 (dígitos menos una constante que cambia en cada carga) más un token de sesión `sesz`; el plugin lo decodifica y devuelve el m3u8 final (requiere `Referer: https://nupload.my/`, incluido automáticamente).

## Notas

- El sitio es un indexador: si un archivo fue eliminado en el host (DMCA), ese episodio no reproducirá aunque aparezca en el catálogo.
- Si el sitio cambia de dominio, basta actualizar `mainUrl` en `BritaflixProvider.kt`.

## Estructura

```
Britaflix/src/main/kotlin/britaflix/
├── BritaflixPlugin.kt      # Registro del plugin
└── BritaflixProvider.kt    # Proveedor (catálogo, búsqueda, episodios, enlaces)
```

Compilación automática con GitHub Actions (rama `builds`: `repo.json`, `plugins.json`, `Britaflix.cs3`, `Britaflix.jar`).
