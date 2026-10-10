# Frontend language contract

The static operator UI under `src/main/resources/static/` supports German and English.
German is the source language for HTML and browser-authored copy. Every visible HTML
text node, title, placeholder and accessibility label has an exact entry in
`js/core/i18n-catalog.js`. `js/core/i18n.js` translates static markup once at
`DOMContentLoaded`; the language selector saves `solarminer.pc-agent.language`
and reloads the page. The default is English.

Dynamic browser-authored text uses `SolarMinerI18n.t('German key', {parameter})`.
Parameter values are inserted after translation, so a completed sentence must not be
passed back as a new key. Agent-authored status and error text uses
`SolarMinerI18n.s(value)`: German agent messages use the frontend catalog for
English, English agent messages use the agent catalog for German. Both catalogs
contain exact strings, with narrow prefix rules for variable diagnostics in
`i18n.js`.

Run `node scripts/check-i18n.cjs` to verify all nine static pages, literal `t()`
keys, interpolation, both agent-message directions and the selector's saved
language/reload behavior. Run `node --check` over
the JavaScript files after changing rendering code. Dynamic API states still
require a manual review of their call sites because a source scanner cannot
prove the language of arbitrary data.

External miner console output, third-party tool diagnostics, hardware model and
sensor names, pool hostnames, URLs, wallet addresses and device IDs remain
verbatim. Surrounding controls, notices, labels and known agent statuses must
be localized. New agent status strings exposed to the UI need catalog entries
or bounded rules in the same change.
