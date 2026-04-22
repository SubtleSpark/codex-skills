# Class Dependency JSONL Reference

This reference records decisions and explanations for Visual DDD Skill's Java class dependency JSONL output.

## JSONL Schema

Each line is one class-to-class dependency edge:

```jsonl
{"from":"pkg.A","to":"pkg.B","kind":"field"}
```

- `from`: Fully qualified source class name.
- `to`: Fully qualified target class name.
- `kind`: The source-code construct that creates the dependency.

Edges are deduplicated by `from + to + kind`. If the same pair of classes is connected by different dependency forms, each `kind` is kept as a separate edge.

## Dependency Kind Values

| kind | Meaning |
|---|---|
| `extends` | `from` extends `to`, such as `class A extends B`. |
| `implements` | `from` implements `to`, such as `class A implements B`. |
| `field` | `from` declares a field whose type references `to`, including generic type arguments. |
| `method-return` | `from` declares a method or constructor signature whose return type references `to`. Constructors do not create this kind because they have no return type. |
| `method-param` | `from` declares a method, constructor, lambda, or catch/resource parameter whose type references `to`. |
| `throws` | `from` declares a method or constructor with a `throws` type referencing `to`. |
| `local-var` | `from` declares a local, resource, or exception variable whose type references `to`. |
| `new` | `from` creates an instance of `to`, such as `new B(...)`. |
| `annotation` | `from` is annotated with `to`, or a member declaration inside `from` is annotated with `to`. |
| `static-import` | `from` uses a statically imported member owned by `to`. Wildcard static imports only produce an edge when a concrete member is used. |
| `class-literal` | `from` references `to.class`. |
| `cast` | `from` casts an expression to `to`, such as `(B) value`. |
| `instanceof` | `from` checks an expression with `instanceof to`. |

## Import Handling

The exporter does not emit raw import edges. It uses `javac` symbol resolution to emit only concrete class dependencies that are actually used.

- `import pkg.*` never emits `pkg.*`.
- Unused imports do not emit edges.
- Same-package references can emit edges even though they require no import.
- Fully qualified references can emit edges even though they require no import.
- External dependencies are ignored by default when they are outside `include-prefix`.
