# DDD Layer Config Reference

This reference documents how Visual DDD Skill assigns DDD layer metadata to Java classes.

## Config Schema

Layer rules are stored in JSON:

```json
{
  "layers": [
    {
      "id": "domain",
      "label": "Domain",
      "color": "#2F855A",
      "patterns": [".*\\.model(\\..*)?\\.[^.]+$"]
    }
  ],
  "defaultLayer": {
    "id": "unknown",
    "label": "Unknown",
    "color": "#A0AEC0"
  }
}
```

- `layers`: Ordered layer rules.
- `id`: Stable machine-readable layer id.
- `label`: Human-readable layer name.
- `color`: Hex color used by later visualization steps.
- `patterns`: Java regular expressions matched against the fully qualified class name.
- `defaultLayer`: Fallback metadata when no rule matches.

## Matching Rules

- Regex patterns match the full class name, not only the package.
- Rules are evaluated in order.
- The first matching layer wins.
- Classes that do not match any pattern use `defaultLayer`.
- `include-prefix` filters which classes are emitted before layer matching is written.

## Output JSONL Schema

Each line is one class metadata record:

```jsonl
{"class":"io.pillopl.library.lending.patron.model.Patron","layer":"domain","label":"Domain","color":"#2F855A"}
```

- `class`: Fully qualified class name.
- `layer`: Matched layer id.
- `label`: Matched layer label.
- `color`: Matched layer color.

## Default Layer Set

`default-ddd-layers.json` covers common package names:

- `model` / `domain` -> `domain`
- `application` / `usecase` / `service` -> `application`
- `infrastructure` / `adapter` / `persistence` -> `infrastructure`
- `web` / `controller` / `api` -> `presentation`
- `commons` / `common` / `shared` -> `shared`
