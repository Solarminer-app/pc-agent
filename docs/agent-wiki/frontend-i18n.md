# Frontend language contract

The static operator UI under `src/main/resources/static/` supports German and English.
Translation is **key-based**: the dictionary in `js/core/i18n-catalog.js` maps stable
snake_case keys to both locales, `{de: "German text", en: "English text"}`. Frontend
code and static HTML never reference display text — they reference keys.

## Frontend dictionary (keyed)

- Dynamic browser-authored text uses `SolarMinerI18n.t('some_key', {parameter})`.
  Keys are lowercase snake_case slugs derived from the English text. `t()` resolves
  `catalog.frontend[key][locale]`; an unknown key logs a console warning and
  `scripts/check-i18n.cjs` fails. Parameter values fill `{placeholder}` tokens after
  lookup, so a completed sentence must never be passed back as a new key, and template
  literals inside `t()` are forbidden.
- Static HTML keeps the German text as the no-JS fallback and references keys via
  `data-i18n` (element text content) and `data-i18n-title` / `data-i18n-placeholder` /
  `data-i18n-aria-label` (attributes). `js/core/i18n.js` applies only those explicit
  keys at `DOMContentLoaded`; it does not guess from text nodes or attributes. The
  language selector saves `solarminer.pc-agent.language` and reloads the page. The
  default is English. All page script URLs carry the same explicit version query, so a
  browser cannot combine newly keyed markup with a cached older catalog or renderer.
- `t()` accepts only frontend keys. An unknown value is recorded as a missing key in
  both locales; there is intentionally no raw-text fallback. API values and external
  miner output must use `s()` instead.

## Agent dictionaries (text-keyed by necessity)

Strings produced at runtime by the Java agent (miner errors, `connectionDetail`,
`downloadDetail`, `managedDetail`, `regulationError`, `unavailableReason`, benchmark
phases) arrive as arbitrary text and cannot carry keys. They go through
`SolarMinerI18n.s(value)`: English agent text resolves through the
`agentEnglishToGerman` dictionary for German, German agent text through the
`agentGermanToEnglish` dictionary for English. Both dictionaries contain exact
strings, with narrow prefix rules for variable diagnostics in `i18n.js`. `s()` also resolves frontend keys, so messages thrown as
`Error(t('some_key'))` stay localizable when they return through `s(error.message)`.

## Verification

- `node scripts/check-i18n.cjs` — dictionary shape (both locales per entry), every
  `data-i18n*` reference, literal `t()`/`s()` keys, interpolation, both agent-message
  directions and the selector's saved language/reload behavior.
- `node scripts/simulate-i18n-render.cjs` — applies the static-markup translation pass
  to all nine pages and asserts no wrong-locale text node survives in either locale.
- `node scripts/scan-i18n-literals.cjs` — regression scan for browser-authored display
  literals in JS, including German text without umlauts. Proper names, algorithm names,
  CSS selectors/classes and SVG paths are explicit technical exceptions.
- `node --check` over the JavaScript files after changing rendering code. Dynamic API
  states still require a manual review of their call sites because a source scanner
  cannot prove the language of arbitrary data.

## Exceptions

External miner console output, third-party tool diagnostics, hardware model and
sensor names, pool hostnames, URLs, wallet addresses and device IDs remain
verbatim. Surrounding controls, notices, labels and known agent statuses must
be localized. New agent status strings exposed to the UI need agent-dictionary
entries or bounded rules in the same change; new browser-authored copy needs a
frontend dictionary key in the same change.
