package moclj;

/// A mutable global binding. Every dynamically compiled class reaches its value
/// through [RT], so state survives across independently compiled REPL inputs.
public final class Var {

    private final String name;
    private volatile Object root;

    Var(String name, Object root) {
        this.name = name;
        this.root = root;
    }

    public String name() {
        return name;
    }

    public Object deref() {
        return root;
    }

    void bindRoot(Object value) {
        this.root = value;
    }

    @Override
    public String toString() {
        return "#'" + name;
    }
}
