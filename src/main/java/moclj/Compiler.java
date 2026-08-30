package moclj;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_long;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/// Compiles a [Form] into a fresh class whose static `invoke()` method evaluates
/// it, then loads and runs that class.
///
/// Each input gets its own class, so nothing is shared through fields; state is
/// shared exclusively through [RT]'s static Var registry, reached with
/// `invokestatic` instructions emitted by [#compileNode].
public final class Compiler {

    /// Lookup of this class, hence generated classes land in package `moclj`
    /// and may call the package's runtime.
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

    private static final String INVOKE_METHOD = "invoke";
    private static final MethodTypeDesc INVOKE_DESC = MethodTypeDesc.of(CD_Object);
    private static final MethodTypeDesc BIND_DESC = MethodTypeDesc.of(CD_Object, CD_String, CD_Object);
    private static final MethodTypeDesc GET_DESC = MethodTypeDesc.of(CD_Object, CD_String);
    private static final MethodTypeDesc INVOKE_OP_DESC = MethodTypeDesc.of(CD_Object, CD_String, CD_Object, CD_Object);
    private static final MethodTypeDesc BOX_LONG_DESC = MethodTypeDesc.of(ConstantDescs.CD_Long, CD_long);

    private Compiler() {
    }

    /// Compiles and immediately evaluates `form`, the "eval" of the REPL.
    public static Object eval(Form form) {
        String className = nextClassName();
        Class<?> compiled = load(className, compile(form, className));
        try {
            return compiled.getMethod(INVOKE_METHOD).invoke(null);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new MocljException("Failed to evaluate " + form, e);
        }
    }

    /// Generates the class file bytes for `form` under a generated class name.
    public static byte[] compile(Form form) {
        return compile(form, nextClassName());
    }

    private static byte[] compile(Form form, String className) {
        return ClassFile.of().build(ClassDesc.of(className), classBuilder -> {
            classBuilder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            classBuilder.withMethodBody(
                    INVOKE_METHOD,
                    INVOKE_DESC,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> {
                        compileNode(form, code);
                        code.areturn();
                    });
        });
    }

    private static Class<?> load(String className, byte[] bytes) {
        try {
            return LOOKUP.defineClass(bytes);
        } catch (IllegalAccessException e) {
            throw new MocljException("Failed to load generated class " + className, e);
        }
    }

    /// Emits the instructions leaving exactly one `Object` on the operand stack.
    private static void compileNode(Form form, CodeBuilder code) {
        switch (form) {
            case Form.Num(long value) -> {
                code.loadConstant(value);
                code.invokestatic(ConstantDescs.CD_Long, "valueOf", BOX_LONG_DESC);
            }
            case Form.Sym(String name) -> {
                code.loadConstant(name);
                code.invokestatic(RT.CLASS_DESC, "get", GET_DESC);
            }
            case Form.Seq(List<Form> items) when items.isEmpty() ->
                throw new MocljException("Cannot compile an empty form");
            case Form.Seq(List<Form> items) -> {
                if (!(items.getFirst() instanceof Form.Sym(String op))) {
                    throw new MocljException("Can only invoke symbols but got: " + items.getFirst());
                }
                if (op.equals("def")) {
                    compileDef(items, code);
                } else {
                    compileOp(op, items, code);
                }
            }
        }
    }

    /// `(def name value)` becomes `RT.bind("name", <value>)`.
    private static void compileDef(List<Form> items, CodeBuilder code) {
        if (items.size() != 3 || !(items.get(1) instanceof Form.Sym(String name))) {
            throw new MocljException("def expects a symbol and a value: " + new Form.Seq(items));
        }
        code.loadConstant(name);
        compileNode(items.get(2), code);
        code.invokestatic(RT.CLASS_DESC, "bind", BIND_DESC);
    }

    /// `(op a b)` becomes `RT.invokeOp("op", <a>, <b>)`.
    private static void compileOp(String op, List<Form> items, CodeBuilder code) {
        if (items.size() != 3) {
            throw new MocljException("Wrong number of args (" + (items.size() - 1) + ") passed to: " + op);
        }
        code.loadConstant(op);
        compileNode(items.get(1), code);
        compileNode(items.get(2), code);
        code.invokestatic(RT.CLASS_DESC, "invokeOp", INVOKE_OP_DESC);
    }

    private static String nextClassName() {
        return Compiler.class.getPackageName() + ".ReplEval_" + CLASS_COUNTER.incrementAndGet();
    }
}
