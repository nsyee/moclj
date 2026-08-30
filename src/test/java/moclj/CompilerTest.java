package moclj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompilerTest {

    @BeforeEach
    void resetRuntime() {
        RT.clear();
    }

    private static Object eval(String source) {
        return Compiler.eval(Reader.readOne(source));
    }

    @Test
    void defReturnsTheBoundValue() {
        assertEquals(10L, eval("(def x 10)"));
        assertEquals(10L, RT.lookupVar("x").deref());
    }

    @Test
    void separatelyCompiledClassesShareStateThroughVars() {
        eval("(def x 10)");
        eval("(def y 20)");
        assertEquals(30L, eval("(+ x y)"));
        assertEquals(50L, eval("(* x 5)"));
    }

    @Test
    void defRebindsAnExistingVar() {
        eval("(def x 1)");
        Var var = RT.lookupVar("x");
        eval("(def x 2)");
        assertEquals(var, RT.lookupVar("x"));
        assertEquals(2L, eval("x"));
    }

    @Test
    void unresolvedSymbolsFail() {
        MocljException e = assertThrows(MocljException.class, () -> eval("missing"));
        assertTrue(e.getMessage().contains("Unable to resolve symbol: missing"));
    }

    @Test
    void arithmeticAndComparisonOperatorsAreSupported() {
        assertEquals(3L, eval("(- 5 2)"));
        assertEquals(2L, eval("(/ 5 2)"));
        assertEquals(true, eval("(< 1 2)"));
        assertEquals(false, eval("(= 1 2)"));
        assertThrows(MocljException.class, () -> eval("(/ 1 0)"));
        assertThrows(MocljException.class, () -> eval("(unknown 1 2)"));
    }

    @Test
    void malformedFormsAreRejectedAtCompileTime() {
        assertThrows(MocljException.class, () -> eval("()"));
        assertThrows(MocljException.class, () -> eval("(+ 1)"));
        assertThrows(MocljException.class, () -> eval("(def 1 2)"));
        assertThrows(MocljException.class, () -> eval("((def x 1) 2 3)"));
    }

    @Test
    void generatedBytecodeCallsTheRuntimeRegistry() {
        String bytecode = Disassembler.disassemble(Compiler.compile(Reader.readOne("(+ x y)")));
        assertTrue(bytecode.contains("invokeOp"), bytecode);
        assertTrue(bytecode.contains("get"), bytecode);
    }
}
