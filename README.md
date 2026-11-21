# 🎵 Anghami patches by Nady

Morphe patches for [Anghami](https://play.google.com/store/apps/details?id=com.anghami) (`com.anghami`).

## ❓ About

Client-side patches for Anghami **8.0.28** (versionCode `8000280`, APKM) that relax
locally-enforced gates: Plus checks, playback/download restrictions, forced shuffle,
upgrade upsell UI, in-house ads, and Gold-gated rows. Everything here hooks **local
boolean gates** — stream/download URLs, audio quality authorization, and plan data
from `/authenticate` remain server-enforced, so anything the server refuses still
fails after patching.

### How to use these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=mohamedamrnady/anghami-patches

Then in Morphe Manager (Expert Mode): pick the stock `the-stock-apk` + matching
`arm64_v8a` + dpi splits (or a merged APK), select the patches below, and install.
All patches are enabled by default (`default=true`) — disable any you don't need.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.17.5](https://github.com/0xgalal/anghami-patches/releases/tag/v1.17.5)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;18 patches total
<details open>
<summary>📦 Anghami&nbsp;&nbsp;•&nbsp;&nbsp;18 patches</summary>
<br>

**🎯 Supported versions:**

| 8.0.28 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [AI lyrics translation · OpenRouter / OpenCode / xKiro](#ai-lyrics-translation-openrouter-opencode-xkiro) | Adds optional automatic lyric translation via OpenRouter, OpenCode, or xKiro. Automatic mode shows original and translated lyrics together after translation completes. Long-press Lyrics to configure. |  |
| [AMOLED black background](#amoled-black-background) | Forces pure-black app background in dark mode (window_background_color -> #000000). Surfaces/cards keep stock greys; light mode untouched. |  |
| [Disable audio ads](#disable-audio-ads) | Forces AdSettings.noAd=true and PlayQueue.getDisableAds=true so songs are treated as ad-free locally. Client flag only. |  |
| [Header Play + Shuffle](#header-play-shuffle) | Playlist/album headers show Play + Shuffle instead of Shuffle + Edit/Follow/Like (functional LEAVECOLLAB, local-songs ADD_MORE and podcasts untouched). Pulls in 'Unforce shuffle'. |  |
| [Hide Gold features](#hide-gold-features) | Forces Account.isGold/isGoldUser and all GoldUtilsKt.isGold overloads to false. Hides server-gated Gold UI instead of spoofing it. |  |
| [Hide shuffle badges](#hide-shuffle-badges) | Hides PLAYS IN SHUFFLE badges on playlist/album headers, feed cards, and rows. Cosmetic only. |  |
| [Hide upgrade upsell](#hide-upgrade-upsell) | Hides the nav upgrade entry, header promo banner, feed upsell cards and AI MIX button model (gap-free), and settings subscribe banner. Server-driven UI the Plus spoof cannot remove. |  |
| [Hide upsell feature buttons](#hide-upsell-feature-buttons) | Hides TRY SING ALONG karaoke upsell, disables the karaoke feature itself, and hides the player/playlist AI MIX buttons. Feature UI stays hidden rather than paywalled. |  |
| [LRCLIB lyrics fallback](#lrclib-lyrics-fallback) | When the server returns truncated/empty lyrics, fetches the full text from LRCLIB (opt-in free source) into a separate cache. Native full lyrics and the Plus path are untouched. |  |
| [Monet dynamic colors](#monet-dynamic-colors) | Replaces the static neon brand accents with wallpaper-based Monet dynamic colors (M3 Expressive primary/secondary/tertiary roles) on Android 12+. Older versions keep stock colors. |  |
| [Player theme](#player-theme) | Night-only player theme: removes the cover-art tint and paints the now-playing row, pills and action icons with the primary accent at night; day mode keeps the stock tinted player. Pulls in 'Player theme background' resources. |  |
| [Player theme background](#player-theme-background) | Makes the player background, text, icons and seekbar follow the app's day/night theme. Keeps the darker split below the progress bar. Pair with 'Player theme'. |  |
| [Remove popup promos](#remove-popup-promos) | No-ops the in-house popup funnel, the fullscreen startup dialog, and the flyer ad callback. Google SDK ads untouched. |  |
| [Spoof stock app signature](#spoof-stock-app-signature) | Forces SignatureUtils.getAppSignature to hash with the stock cert prefix (he9B...kw=), so X-ANGH-APP-RGSIG matches a stock install. Salt/body hashing unchanged. |  |
| [Unforce shuffle](#unforce-shuffle) | Forces server shuffleOn=false at both sync points, never reports shuffle to the server, disables the pick-a-song radio redirect, enables shuffle buttons, and disarms the shuffle upsell dialog. Manual shuffle toggle and the header Shuffle button keep working. |  |
| [Unlock downloads](#unlock-downloads) | No-ops download limit asserts, forces limited-plan=false and large offline caps (999999). Local gates only; the server still authorizes files. |  |
| [Unlock local Plus](#unlock-local-plus) | Forces Account.isPlus/isPlusUser=true, enablePlayerRestrictions=false, canPlayOfflineAndFree=true. Local UI/gating only; server premium checks remain. |  |
| [Unlock playback limits](#unlock-playback-limits) | Disables skip and queue limits (skipLimitReached/queueRestrictionsEnabled=false, disable-flags=true). Local gates only. |  |

</details>

<!-- PATCHES_END -->

### 🎯 Compatibility

| App | Package | Version | File type |
|-----|---------|---------|-----------|
| Anghami | `com.anghami` | 8.0.28 (8000280, all ABIs) | APKM |

Fingerprints pin 8.0.28 method shapes — re-verify them against a fresh decode
before using these patches on any other app version.

### Lyrics sources

When Anghami returns truncated or empty lyrics, the fallback looks for full
synced lyrics in this order and stops at the first match:

1. **LRCLIB** — open, key-less database; best coverage for Western pop.
2. **LyricsPlus** — aggregator querying Apple, Musixmatch and Spotify.
3. **Better Lyrics** — Apple TTML with tight line timings.

Each source is optional: a timeout or error just moves on to the next one.
Results are cached per song, and a cached "no lyrics found" is re-checked in
the background so an update from any source appears without reopening the
song. The player's **Lyrics options** dialog has a **Lyrics sources** button
that lets you choose which sources to use. The selection is one saved global
preference: it is shown as-is for every song and only changes when you change a
checkbox. The result is shown immediately and sent for translation when
automatic translation is enabled.

### AI lyrics translation via OpenRouter, OpenCode, or xKiro

Enable **AI lyrics translation · OpenRouter / OpenCode / xKiro** in Morphe. It pulls in
the LRCLIB fallback patch it depends on.
In the player, long-press the native **Lyrics** button to open the translation
dialog. Tap **Provider** to select **OpenRouter**, **OpenCode**, **xKiro**, or
**Cloudflare Workers AI**, then enter that provider's API token/key, model ID,
and target language. Keys and selected models are saved separately for each
provider.

The model picker fetches OpenRouter's current free models, OpenCode's
externally usable chat models, xKiro's public chat catalog, or the Workers AI
models for your Cloudflare account. If a catalog cannot be reached, it uses
that provider's built-in list. Custom model IDs are also supported. Defaults
are `inception/mercury-2.5-preview` for OpenRouter, `glm-5.3-flash` for
OpenCode, `mistralai/mistral-small-2603` for xKiro, and
`@cf/meta/llama-3.3-70b-instruct-fp8-fast` for Cloudflare Workers AI.
OpenCode uses a [Console API key](https://opencode.ai/docs/console/harnesses)
with pay-as-you-go credits. Its free-tier chat models are restricted to the
OpenCode app and cannot be used from Anghami.
xKiro uses an [API key](https://docs.xkiro.com/guides/authentication/) and
supports OpenAI-compatible chat completions. Free models have a daily token
allowance; paid models need an eligible plan or balance, and premium models
need an eligible plan or a real deposit.

#### Cloudflare Workers AI

Cloudflare needs **two** credentials: an API token with the `Workers AI - Read`
and `Workers AI - Edit` permissions, and your **Account ID**. Both are shown on
the [Workers AI REST API page](https://dash.cloudflare.com/?to=/:account/ai/workers-ai)
of the Cloudflare dashboard (select **Use REST API**), or see the
[REST API guide](https://developers.cloudflare.com/workers-ai/get-started/rest-api/).
Enter the token in the **API key** row and the Account ID in the new
**Account ID** row; it only appears when Cloudflare is the selected provider.
The token is sent as `Authorization: Bearer ...`, and the Account ID plus the
model ID are placed in the URL path
(`/client/v4/accounts/{account}/ai/run/{model}`), so the model is not included
in the JSON body. This endpoint does not use `response_format`; the model is
prompted to return the `{"lines": [...]}` JSON object and the reply text in
`result.response` is parsed by the same extraction path as the other
providers. The catalog at `/ai/models` is fetched with the same token and
account ID; if it fails, the built-in model list is shown instead. Inference is
billed to your Cloudflare account, so models are never marked FREE here.

The default target language is Arabic on Arabic-locale devices or English
otherwise. Enabling auto-translate
turns on Lyrics mode and translates lyrics as they load, with no per-song
Translate action. The original is held while translation runs, then the
original and translation appear together. Predominantly Arabic-script lyrics
are skipped.

The translation dialog has a pinned **Translate now** footer button in both its
configuration and manual modes. It immediately translates the current song with
the *saved* provider, key, model and language settings (showing the usual
validation message if any are missing), then dismisses the dialog. The dialog's
original positive button keeps its existing behaviour: save settings in
configuration mode, or translate with the values currently typed in manual mode.

The original lyrics remain intact. Each translation is displayed beneath its
source line, using the source line’s timestamp for synchronized lyrics.
The model is instructed to return only a JSON object with a `lines` array,
preserving empty lines and the exact source line count.
OpenRouter, OpenCode and xKiro still translate the whole song in a **single
request**, exactly as before. Cloudflare Workers AI is the exception: its models
can truncate a long single response, so lyrics are split into small sequential
batches (10 lines / ~1200 characters by default, 6 lines / ~800 characters for
`3b`/`8b` models). Each batch has its own 10-second timeout and cache entry keyed
by provider, account ID, model, language, system prompt and that batch's lines —
not the whole song — so successful batches are reused and only failed ones are
retried. If a batch fails, its lines are left original and a short notice is
shown; no lines are ever dropped, and line order/timestamps are preserved.
A safety cap of 800 lines / 40,000 characters still applies to avoid abuse.

The auto-translation choice, provider, API keys, models, target language, optional extra
system prompt, and cached translations are stored in app-private preferences.
Translations are cached by lyric, provider, model, target language, and system prompt;
only uncached lyrics are sent to the selected provider.

Translation always uses lyrics already cached on the device when they are available —
both for automatic translation and for the **Translate now** button. The source text is
read from the same local `lyrics_fallback` cache the lyrics view uses, so translating a
song never fetches its lyrics from LRCLIB / LyricsPlus / Better Lyrics again. If there is
no usable cached entry, translation simply has no source (the original behavior); it does
not trigger a new lyrics search on its own.

### 🛠️ Building locally

- Run `./gradlew buildAndroid`
  (needs JDK 17+ and a GitHub PAT with `read:packages`, e.g. via `GITHUB_ACTOR` / `GITHUB_TOKEN`, for the Morphe registry)
- The built patches `.mpp` file is found in `patches/build/libs/patches-*.mpp`
- Apply the `.mpp` with [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)
  like any other patch bundle

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## ⚠️ Disclaimer

For research and interoperability purposes. Spoofing entitlement on a commercial
streaming service can violate its Terms of Service — use a throwaway account,
expect server-gated features (full lyrics, high-quality streams, downloads) to
keep failing, and do not redistribute patched APKs as "Premium Unlocked".

## 📜 License

Anghami patches by Nady are licensed under the [GNU General Public License v3.0](LICENSE)

> v1.17.2: patch set restored to the v1.3.0 baseline; lyrics fallback, AI translation and dialogs retained.
