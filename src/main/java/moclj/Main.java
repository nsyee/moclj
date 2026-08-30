package moclj;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Main {

    private static final List<String> DEMO_INPUTS = List.of(
            "(def x 10)",
            "(def y 20)",
            "(+ x y)",
            "(* x 5)",
            "(def x 100)",
            "(+ x y)");

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        var out = System.out;
        var repl = new Repl(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out);
        if (List.of(args).contains("--demo")) {
            out.println("Starting compiled REPL...");
            out.println();
            for (String input : DEMO_INPUTS) {
                out.println("user=> " + input);
                repl.evalLine(input);
                out.println();
            }
            return;
        }
        repl.run();
    }
}
