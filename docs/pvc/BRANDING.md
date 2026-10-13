# English presentation and PVC branding

This guide describes the presentation changes in `2026.3-pvc.4`. It does not
authorize a production installation, configuration replacement or reload. Use
the exact artifact and declared qualification scope in
[VERIFICATION.md](VERIFICATION.md) before rollout.

## Server language

The bundled default language is English (`en-US`). The fork adds this policy:

```yaml
default-language: en-US
auto-language-detection: false
pvc:
  force-default-language: true
```

`pvc.force-default-language` defaults to `true` and is added only when absent.
It routes plugin language components, command descriptions/help and related
presentation through the configured server default. It forces the **server
default**, rather than hardcoding English: an existing operator-selected
`default-language` is preserved. Set that existing setting to `en-US` when
English presentation is required.

With the policy enabled, a player's client language or saved language does not
change service messages. Automatic first-join language selection is bypassed,
and opening or using the language selector returns the English/default-locale
`PVC_SERVER_LANGUAGE_FIXED` service reply. The ordinary language-selector
permission still controls whether its command is available.

Stored player language preferences are retained. The fork does not rewrite
player rows or change the raw `SuperiorPlayer#getUserLocale()` contract; data
copies, persistence and integrations can still inspect the stored preference.
Presentation resolves the effective locale separately. Upstream translations
remain packaged, and the language preference can be used again after opting out.

For a multilingual server, set `pvc.force-default-language: false`. This restores
upstream per-player presentation and language selection. The existing
`auto-language-detection` setting independently determines whether an eligible
first join adopts a supported client locale. Existing saved preferences remain
available without a data migration. A normal stopped configuration change is
the deployment procedure; existing full-reload restrictions in automatic sizing
mode still apply.

The language policy covers SuperiorSkyblock2. Other plugins, player-written
chat, island names, descriptions and configured operator content retain their
own language and formatting.

## Service colors and identity

Plugin-generated chat replies use the generic service prefix:

```text
&8[&6Skyblock&8] &7
```

| Part | Legacy color | Use |
| --- | --- | --- |
| Frame and separators | `&8` | Brackets, pipes, punctuation and unavailable GUI navigation |
| Labels and commands | `&6` | Service name, section labels and command text |
| Values | `&e` | Counts, identifiers, names, amounts and arguments |
| Explanation | `&7` | Ordinary sentences and instructions |

Error and recovery messages use the same palette; they do not introduce red,
green, blue or white status colors. Available page navigation is gold and
unavailable navigation is dark gray. Generated menu titles, labels and fallback
items follow the same palette. This changes presentation, not click behavior,
island permissions or color values that are functional command arguments.

For example:

```text
&8[&6Skyblock&8] &7You have reached your limit of &e{0} &7owned islands.
&8[&6Skyblock&8] &7Use one &6--island &e<id|uuid|name> &7selector before message text.
```

The dedicated `PvcMessages` helper brands plugin-generated Java service replies.
It does not transform the global `Message.CUSTOM` channel. Player-authored chat,
island messages and administrative message payloads therefore retain their
existing content. Configuration-editor echoes also preserve literal ampersands
in operator-authored values. Technical plugin/API/package identifiers,
permissions, data-folder names and original author/GPL credits remain intact.

## Existing configuration and explicit theming

The bundled English locale, menu resources and module messages provide the new
defaults. The initial migration remains **missing only**: it adds absent PVC
settings and language keys with backups, preserving existing values, comments
and explicit empty messages. It does not replace an existing message with the
new branding or reset a custom menu, module configuration or disabled feature.

Consequently, installing the new JAR by itself does not recolor all files that
an older version already generated. Apply an explicit, reviewed theme update to
the backend's existing `lang/en-US.yml`, configured menus and enabled module
message fields where required. Retain the existing settings and functional
values; change only the intended presentation fields. An existing custom
English message remains custom, including an explicitly empty message.

Back up every file before an authorized update. A prepared configuration/theme
package is staging evidence, not proof of application or activation. Do not
replace the complete data folder with bundled resources, delete translations to
force English, or use PlugMan/hot JAR replacement. Follow the stopped rollout
and rollback procedure in [INSTALLATION.md](INSTALLATION.md).

## Verification scope

The source-built candidate has passed 248 automated tests, inspection of 363
public API classes and two byte-identical clean builds. These checks establish
the recorded build/static result. Consult the release verification record for
the status and limits of native Leaf/Folia `1.21.11` and `26.2` presentation,
migration and retained-restart checks; this document does not assert that a
pending native run or a prepared production theme has been activated.
