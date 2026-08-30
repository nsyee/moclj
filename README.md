# moclj

A minimal Clojure dialect that compiles each REPL input to a fresh JVM class
with the [Class-File API](https://openjdk.org/jeps/484), on JDK 26.

The point of interest is how state is shared: every input is compiled into its
own, completely independent class, yet inputs still see each other's
definitions - and a definition can be replaced without recompiling the code that
reads it. A name is not bound to a value but to a `Var`, a mutable container
holding the value, and compiled code caches the container: the generated
`<clinit>` interns each `Var` it needs once and stores it in a `static final`
field, so reading a name at run time is `getstatic` plus `Var.deref()` with no
lookup by name. Redefining a name only swaps the value inside the container that
the already compiled classes point at. This is what `clojure.lang.Var` does.

Values, by contrast, are never mutated: `[1 2 3]` is a persistent vector, and
`conj` returns a new one that shares its structure with the old.

## Requirements

JDK 26. With [mise](https://mise.jdx.dev) installed, `mise install` picks up the
toolchain pinned in `mise.toml`.

## Usage

```console
$ ./gradlew run
moclj REPL - :trace toggles bytecode printing, :quit exits
user=> (def x 10)
#'user/x
user=> (def y 20)
#'user/y
user=> (+ x y)
30
user=> (def x 100)
#'user/x
user=> (+ x y)
120
user=> (def v [1 2 3])
#'user/v
user=> (conj v 4)
[1 2 3 4]
user=> v
[1 2 3]
```

`def` evaluates to the Var, printed `#'user/x`, not to the bound value.

`./gradlew run --args="--demo"` replays that session non-interactively, and
`./gradlew build` runs the tests.

## Language

| Form | Meaning |
| --- | --- |
| `10`, `-2` | 64-bit integer literal |
| `x` | Var reference, compiled to `VAR_x.deref()` on a cached field |
| `[<form> ...]` | vector literal, evaluated to a `PersistentVector` |
| `(def x <form>)` | binds the Var's root, compiled to `RT.def("x", <form>)` |
| `(<op> <a> <b>)` | built-in binary operator: `+ - * / < > =` |
| `(conj v <form>)` | the vector with one element appended |
| `(assoc v <i> <form>)` | the vector with index `<i>` replaced |
| `(nth v <i>)`, `(count v)`, `(vector <form> ...)` | indexed read, size, construction |

`=` compares numbers by value and everything else, vectors included, by
`equals`, so `(= [1 2] [1 2])` is `true`.

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
    Invoke[OP=INVOKESTATIC, m=moclj/RT.def(Ljava/lang/String;Ljava/lang/Object;)Lmoclj/Var;]
    Return[OP=ARETURN]
#'user/x
user=> (* x 5)
    LoadConstant[OP=LDC, val=x]
    Invoke[OP=INVOKESTATIC, m=moclj/RT.var(Ljava/lang/String;)Lmoclj/Var;]
    Field[OP=PUTSTATIC, field=moclj/ReplEval_5.VAR_x:Lmoclj/Var;]
    Return[OP=RETURN]
    LoadConstant[OP=LDC, val=*]
    Field[OP=GETSTATIC, field=moclj/ReplEval_5.VAR_x:Lmoclj/Var;]
    Invoke[OP=INVOKEVIRTUAL, m=moclj/Var.deref()Ljava/lang/Object;]
    LoadConstant[OP=LDC2_W, val=5]
    Invoke[OP=INVOKESTATIC, m=java/lang/Long.valueOf(J)Ljava/lang/Long;]
    Invoke[OP=INVOKESTATIC, m=moclj/RT.invokeOp(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;]
    Return[OP=ARETURN]
50
```

The first block is the `<clinit>` of the generated class, which caches the Var
for `x`; the `invoke()` method that follows never mentions the name `x` again.
A name with no Var yet is rejected at compile time, the way Clojure reports
`Unable to resolve symbol`.

The pipeline behind that:

| Class | Role |
| --- | --- |
| `Reader` | source text to `Form` (a sealed interface of `Sym`, `Num`, `Seq`, `Vec`) |
| `Compiler` | `Form` to a `ReplEval_N` class, loaded with `Lookup.defineClass` |
| `PersistentVector` | immutable vector, a 32-way bit-partitioned trie |
| `Var` | mutable container holding a name's root value |
| `RT` | name-to-`Var` intern table and the operators generated code calls |
| `Repl` | read-eval-print loop and `:trace` |

Because a generated class caches Vars rather than values, each input keeps the
speed of compiled code - the same `getstatic` / `invokevirtual` pair handwritten
Java would produce - while still seeing definitions made after it was compiled.

## Persistent vectors

`[1 2 3]` compiles to an `Object[]` of the evaluated elements handed to
`RT.vector`, and `(conj v 4)` to `RT.conj(VAR_v.deref(), 4L)`. Nothing about the
vector is mutable, yet appending does not copy it either, because
`PersistentVector` is the structure Clojure is known for: a tree whose nodes are
arrays of 32.

An index is routed to its leaf by cutting it into groups of five bits, one group
per level, so descending a level is a shift and a mask - no division, no hashing.
Thirty-two branches keep the tree flat: 32^4 slots is a little over a million, so
a million-element vector is four levels deep and lookup, `O(log32 n)`, is
effectively constant.

Appending or replacing an element copies only the nodes on the path from the root
to the leaf it touches - at most one 32-element array per level - and the new
root points at the old tree's other subtrees. Growing a million-element vector by
one element therefore duplicates about 128 pointers rather than a million, and
both versions remain valid and readable.

When the tree is full, `conj` makes the old root the leftmost child of a new one,
which adds a level and multiplies capacity by 32; the old vector still points at
the old root and is unaffected. `PersistentVector` implements `java.util.List`,
so it compares and iterates like any list while every mutator throws.

## Not implemented

Functions and `fn`, `if` and other special forms, `invokedynamic`-based call
sites, namespaces, thread-local (dynamic) Var bindings, and every data type
beyond integers and vectors. Vectors have no `tail` array, the cache real
Clojure keeps for the last, incomplete leaf so that most appends touch no tree
node at all, and there are no maps, sets, seqs or transients. `Var`s carry no
metadata and no namespace; `#'user/x` prints a namespace that does not exist yet.
