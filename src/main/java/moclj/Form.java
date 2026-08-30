package moclj;

import java.util.List;

/// A read form of the moclj surface syntax.
public sealed interface Form {

    /// A symbol, either a special form name, an operator or a Var reference.
    record Sym(String name) implements Form {
        @Override
        public String toString() {
            return name;
        }
    }

    /// An integer literal.
    record Num(long value) implements Form {
        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    /// A parenthesized form, e.g. `(def x 10)`.
    record Seq(List<Form> items) implements Form {
        public Seq(List<Form> items) {
            this.items = List.copyOf(items);
        }

        @Override
        public String toString() {
            return join(items, '(', ')');
        }
    }

    /// A vector literal, e.g. `[1 x]`.
    record Vec(List<Form> items) implements Form {
        public Vec(List<Form> items) {
            this.items = List.copyOf(items);
        }

        @Override
        public String toString() {
            return join(items, '[', ']');
        }
    }

    private static String join(List<Form> items, char open, char close) {
        var sb = new StringBuilder().append(open);
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(items.get(i));
        }
        return sb.append(close).toString();
    }
}
