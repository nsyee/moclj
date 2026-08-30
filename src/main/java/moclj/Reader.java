package moclj;

import java.util.ArrayList;
import java.util.List;

/// Turns source text into [Form]s.
public final class Reader {

    private final String source;
    private int pos;

    private Reader(String source) {
        this.source = source;
    }

    /// Reads every form contained in `source`.
    public static List<Form> readAll(String source) {
        var reader = new Reader(source);
        var forms = new ArrayList<Form>();
        reader.skipBlanks();
        while (reader.pos < source.length()) {
            forms.add(reader.readForm());
            reader.skipBlanks();
        }
        return List.copyOf(forms);
    }

    /// Reads exactly one form; fails if the text holds none or more than one.
    public static Form readOne(String source) {
        var forms = readAll(source);
        if (forms.size() != 1) {
            throw new MocljException("Expected exactly one form but read " + forms.size());
        }
        return forms.getFirst();
    }

    private Form readForm() {
        char c = source.charAt(pos);
        if (c == '(') {
            return new Form.Seq(readItems('(', ')'));
        }
        if (c == '[') {
            return new Form.Vec(readItems('[', ']'));
        }
        if (c == ')' || c == ']') {
            throw new MocljException("Unmatched '" + c + "' at position " + pos);
        }
        return readAtom();
    }

    /// Reads the forms between `open` and its matching `close`.
    private List<Form> readItems(char open, char close) {
        pos++; // consume `open`
        var items = new ArrayList<Form>();
        while (true) {
            skipBlanks();
            if (pos >= source.length()) {
                throw new MocljException("Unexpected end of input, expected '" + close + "'");
            }
            if (source.charAt(pos) == close) {
                pos++;
                return items;
            }
            items.add(readForm());
        }
    }

    private Form readAtom() {
        int start = pos;
        while (pos < source.length() && !isTerminator(source.charAt(pos))) {
            pos++;
        }
        String token = source.substring(start, pos);
        char first = token.charAt(0);
        boolean numeric = Character.isDigit(first)
                || ((first == '-' || first == '+') && token.length() > 1 && Character.isDigit(token.charAt(1)));
        if (numeric) {
            try {
                return new Form.Num(Long.parseLong(token));
            } catch (NumberFormatException e) {
                throw new MocljException("Invalid number: " + token, e);
            }
        }
        return new Form.Sym(token);
    }

    private void skipBlanks() {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == ';') {
                while (pos < source.length() && source.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (Character.isWhitespace(c) || c == ',') {
                pos++;
            } else {
                return;
            }
        }
    }

    private static boolean isTerminator(char c) {
        return Character.isWhitespace(c) || c == '(' || c == ')' || c == '[' || c == ']' || c == ',' || c == ';';
    }
}
