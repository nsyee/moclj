package moclj;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;

/// The read-eval-print loop: every input is compiled to a new class and run.
public final class Repl {

    private final BufferedReader in;
    private final PrintStream out;
    private boolean trace;

    public Repl(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    public void run() throws IOException {
        out.println("moclj REPL - :trace toggles bytecode printing, :quit exits");
        while (true) {
            out.print("user=> ");
            out.flush();
            String line = in.readLine();
            if (line == null || line.trim().equals(":quit")) {
                out.println();
                return;
            }
            if (line.trim().equals(":trace")) {
                trace = !trace;
                out.println(";; bytecode printing " + (trace ? "on" : "off"));
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            evalLine(line);
        }
    }

    /// Evaluates every form on `line`, printing the value of each.
    public void evalLine(String line) {
        try {
            for (Form form : Reader.readAll(line)) {
                if (trace) {
                    out.println(Disassembler.disassemble(Compiler.compile(form)));
                }
                out.println(print(Compiler.eval(form)));
            }
        } catch (RuntimeException e) {
            out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /// Formats a value the way Clojure's `pr-str` would for these few types.
    public static String print(Object value) {
        return value == null ? "nil" : value.toString();
    }
}
