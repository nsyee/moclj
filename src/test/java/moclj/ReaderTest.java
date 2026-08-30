package moclj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReaderTest {

    @Test
    void readsNestedForms() {
        assertEquals(
                new Form.Seq(List.of(
                        new Form.Sym("def"),
                        new Form.Sym("x"),
                        new Form.Seq(List.of(new Form.Sym("+"), new Form.Num(1), new Form.Num(-2))))),
                Reader.readOne("(def x (+ 1 -2))"));
    }

    @Test
    void readsSeveralFormsIgnoringCommentsAndCommas() {
        assertEquals(
                List.of(new Form.Num(1), new Form.Sym("x")),
                Reader.readAll("1, x ; trailing comment"));
    }

    @Test
    void readsVectorLiterals() {
        assertEquals(
                new Form.Vec(List.of(new Form.Num(1), new Form.Vec(List.of(new Form.Sym("x"))))),
                Reader.readOne("[1 [x]]"));
        assertEquals(new Form.Vec(List.of()), Reader.readOne("[]"));
    }

    @Test
    void rejectsUnbalancedDelimiters() {
        assertThrows(MocljException.class, () -> Reader.readOne("(+ 1 2"));
        assertThrows(MocljException.class, () -> Reader.readOne("+ 1 2)"));
        assertThrows(MocljException.class, () -> Reader.readOne("[1 2"));
        assertThrows(MocljException.class, () -> Reader.readOne("1 2]"));
    }
}
