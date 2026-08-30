package moclj;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.util.stream.Collectors;

/// Renders the instructions of a compiled form, which is how the generated Var
/// caching and the calls into `RT` can be inspected from the REPL.
public final class Disassembler {

    private Disassembler() {
    }

    public static String disassemble(byte[] classBytes) {
        return ClassFile.of().parse(classBytes).methods().stream()
                .flatMap(method -> method.code().stream())
                .flatMap(code -> code.elementStream())
                .filter(element -> element instanceof Instruction)
                .map(instruction -> "    " + instruction)
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
