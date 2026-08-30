package moclj;

import static java.lang.constant.ConstantDescs.CD_Object;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;

/// A mutable container binding a name to a value, like `clojure.lang.Var`.
///
/// Symbols do not name values directly: they name a Var, and the Var holds the
/// value. Compiled code caches the container itself, so redefining a name only
/// swaps the value inside a Var that generated classes already point at.
public final class Var {

    static final ClassDesc CLASS_DESC = ClassDesc.of(Var.class.getName());

    static final MethodTypeDesc DEREF_DESC = MethodTypeDesc.of(CD_Object);

    private final String name;
    private volatile Object root;

    Var(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /// Reads the root binding. Called by the bytecode generated for a symbol
    /// reference, on a Var cached in a static field.
    public Object deref() {
        return root;
    }

    void bindRoot(Object value) {
        this.root = value;
    }

    @Override
    public String toString() {
        return "#'user/" + name;
    }
}
