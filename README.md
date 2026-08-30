# moclj

A minimal Clojure dialect that compiles each REPL input to a fresh JVM class
with the [Class-File API](https://openjdk.org/jeps/484), on JDK 26.

The point of interest is how state is shared: every input is compiled into its
own, completely independent class, so nothing can be shared through fields.
Instead the compiler emits `invokestatic` calls into a global runtime registry
(`moclj.RT`), the same trick `clojure.lang.RT` plays with `Var`s.

## Requirements

JDK 26. With [mise](https://mise.jdx.dev) installed, `mise install` picks up the
toolchain pinned in `mise.toml`.

## Usage

```console
$ ./gradlew run
moclj REPL - :trace toggles bytecode printing, :quit exits
user=> (def x 10)
10
user=> (def y 20)
20
user=> (+ x y)
30
user=> (* x 5)
50
```

`./gradlew run --args="--demo"` replays that session non-interactively, and
`./gradlew build` runs the tests.

## Language

| Form | Meaning |
| --- | --- |
| `10`, `-2` | 64-bit integer literal |
| `x` | Var reference, compiled to `RT.get("x")` |
| `(def x <form>)` | interns a Var, compiled to `RT.bind("x", <form>)` |
| `(<op> <a> <b>)` | built-in binary operator: `+ - * / < > =` |

## How it works

`:trace` prints the bytecode generated for each input, which is the shortest
explanation of the design:

```console
user=> :trace
;; bytecode printing on
user=> (def x 10)
    LoadConstant[OP=LDC, val=x]
    LoadConstant[OP=LDC2_W, val=10]
    Invoke[OP=INVOKESTATIC, m=java/lang/Long.valueOf(J)Ljava/lang/Long;]
    Invoke[OP=INVOKESTATIC, m=moclj/RT.bind(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;]
    Return[OP=ARETURN]
10
user=> (* x 5)
    LoadConstant[OP=LDC, val=*]
    LoadConstant[OP=LDC, val=x]
    Invoke[OP=INVOKESTATIC, m=moclj/RT.get(Ljava/lang/String;)Ljava/lang/Object;]
    LoadConstant[OP=LDC2_W, val=5]
    Invoke[OP=INVOKESTATIC, m=java/lang/Long.valueOf(J)Ljava/lang/Long;]
    Invoke[OP=INVOKESTATIC, m=moclj/RT.invokeOp(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;]
    Return[OP=ARETURN]
50
```

The pipeline behind that:

| Class | Role |
| --- | --- |
| `Reader` | source text to `Form` (a sealed interface of `Sym`, `Num`, `Seq`) |
| `Compiler` | `Form` to a `ReplEval_N` class, loaded with `Lookup.defineClass` |
| `RT` | static `Var` registry and the operators generated code calls |
| `Repl` | read-eval-print loop and `:trace` |

Because values live in `RT`'s registry rather than in the generated classes,
each input keeps the speed of compiled code while still seeing the state left
behind by earlier inputs.

## Not implemented

Functions and `fn`, `if` and other special forms, `invokedynamic`-based call
sites, namespaces, thread-local (dynamic) Var bindings, persistent collections,
and every data type beyond integers.
