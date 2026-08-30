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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicInteger;

/// Compiles a [Form] into a fresh class whose static `invoke()` method evaluates
/// it, then loads and runs that class.
///
/// Every name a form reads gets a `private static final Var` field. The
/// generated `<clinit>` resolves each of them once, through [RT#var], and a
/// symbol reference then compiles to `getstatic` plus [Var#deref] - no lookup by
/// name happens while the method runs. Because the cached field holds the
/// container and not the value, a later `(def x ...)` is visible to classes that
/// were compiled before it, without recompiling them.
public final class Compiler {

    /// Lookup of this class, hence generated classes land in package `moclj`
    /// and may call the package's runtime.
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

    private static final String INVOKE_METHOD = "invoke";
    private static final String FIELD_PREFIX = "VAR_";
    private static final MethodTypeDesc INVOKE_DESC = MethodTypeDesc.of(CD_Object);
    private static final MethodTypeDesc VAR_DESC = MethodTypeDesc.of(Var.CLASS_DESC, CD_String);
    private static final MethodTypeDesc DEF_DESC = MethodTypeDesc.of(Var.CLASS_DESC, CD_String, CD_Object);
    private static final MethodTypeDesc INVOKE_OP_DESC = MethodTypeDesc.of(CD_Object, CD_String, CD_Object, CD_Object);
    private static final MethodTypeDesc BOX_LONG_DESC = MethodTypeDesc.of(ConstantDescs.CD_Long, CD_long);
    private static final MethodTypeDesc VECTOR_DESC =
            MethodTypeDesc.of(PersistentVector.CLASS_DESC, CD_Object.arrayType());

    /// The collection functions reachable by name from source, each compiled to
    /// an `invokestatic` of the [RT] method of the same name.
    private static final Map<String, MethodTypeDesc> BUILTINS = Map.of(
            "conj", MethodTypeDesc.of(PersistentVector.CLASS_DESC, CD_Object, CD_Object),
            "assoc", MethodTypeDesc.of(PersistentVector.CLASS_DESC, CD_Object, CD_Object, CD_Object),
            "nth", MethodTypeDesc.of(CD_Object, CD_Object, CD_Object),
            "count", MethodTypeDesc.of(CD_Object, CD_Object));

    private Compiler() {
    }

    /// Compiles and immediately evaluates `form`, the "eval" of the REPL.
    public static Object eval(Form form) {
        return invoke(compileToClass(form));
    }

    /// Compiles `form` and loads the result, so that the very same class can be
    /// invoked again later against whatever its cached Vars hold by then.
    public static Class<?> compileToClass(Form form) {
        String className = nextClassName();
        return load(className, compile(form, className));
    }

    /// Runs the `invoke()` method of a class produced by [#compileToClass].
    public static Object invoke(Class<?> compiled) {
        try {
            return compiled.getMethod(INVOKE_METHOD).invoke(null);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new MocljException("Failed to invoke " + compiled.getName(), e);
        }
    }

    /// Generates the class file bytes for `form` under a generated class name.
    public static byte[] compile(Form form) {
        return compile(form, nextClassName());
    }

    private static byte[] compile(Form form, String className) {
        ClassDesc classDesc = ClassDesc.of(className);
        SequencedMap<String, String> varFields = varFields(form);
        return ClassFile.of().build(classDesc, classBuilder -> {
            classBuilder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            varFields.values().forEach(field -> classBuilder.withField(
                    field,
                    Var.CLASS_DESC,
                    ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL));
            if (!varFields.isEmpty()) {
                classBuilder.withMethodBody(
                        ConstantDescs.CLASS_INIT_NAME,
                        ConstantDescs.MTD_void,
                        ClassFile.ACC_STATIC,
                        code -> {
                            varFields.forEach((name, field) -> {
                                code.loadConstant(name);
                                code.invokestatic(RT.CLASS_DESC, "var", VAR_DESC);
                                code.putstatic(classDesc, field, Var.CLASS_DESC);
                            });
                            code.return_();
                        });
            }
            classBuilder.withMethodBody(
                    INVOKE_METHOD,
                    INVOKE_DESC,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> {
                        compileNode(form, code, classDesc, varFields);
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

    /// Resolves every name `form` reads to the field that will cache its Var,
    /// keeping the order in which the names occur.
    private static SequencedMap<String, String> varFields(Form form) {
        var fields = new LinkedHashMap<String, String>();
        collectReferences(form, fields);
        return fields;
    }

    private static void collectReferences(Form form, Map<String, String> fields) {
        switch (form) {
            case Form.Sym(String name) -> {
                if (RT.lookupVar(name) == null) {
                    throw new MocljException("Unable to resolve symbol: " + name + " in this context");
                }
                fields.computeIfAbsent(name, Compiler::fieldName);
            }
            case Form.Num _ -> {
            }
            case Form.Vec(List<Form> items) -> items.forEach(item -> collectReferences(item, fields));
            case Form.Seq(List<Form> items) when items.isEmpty() ->
                throw new MocljException("Cannot compile an empty form");
            case Form.Seq(List<Form> items) -> {
                if (items.getFirst() instanceof Form.Sym(String op)) {
                    // The head names a special form or an operator, not a Var,
                    // and `def` binds its symbol rather than reading it.
                    int firstArg = op.equals("def") ? 2 : 1;
                    items.subList(Math.min(firstArg, items.size()), items.size())
                            .forEach(item -> collectReferences(item, fields));
                } else {
                    items.forEach(item -> collectReferences(item, fields));
                }
            }
        }
    }

    /// Emits the instructions leaving exactly one `Object` on the operand stack.
    private static void compileNode(
            Form form, CodeBuilder code, ClassDesc classDesc, Map<String, String> varFields) {
        switch (form) {
            case Form.Num(long value) -> {
                code.loadConstant(value);
                code.invokestatic(ConstantDescs.CD_Long, "valueOf", BOX_LONG_DESC);
            }
            case Form.Sym(String name) -> {
                code.getstatic(classDesc, varFields.get(name), Var.CLASS_DESC);
                code.invokevirtual(Var.CLASS_DESC, "deref", Var.DEREF_DESC);
            }
            case Form.Vec(List<Form> items) -> compileVector(items, code, classDesc, varFields);
            case Form.Seq(List<Form> items) when items.isEmpty() ->
                throw new MocljException("Cannot compile an empty form");
            case Form.Seq(List<Form> items) -> {
                if (!(items.getFirst() instanceof Form.Sym(String op))) {
                    throw new MocljException("Can only invoke symbols but got: " + items.getFirst());
                }
                List<Form> args = items.subList(1, items.size());
                MethodTypeDesc builtin = BUILTINS.get(op);
                if (op.equals("def")) {
                    compileDef(items, code, classDesc, varFields);
                } else if (op.equals("vector")) {
                    compileVector(args, code, classDesc, varFields);
                } else if (builtin != null) {
                    compileBuiltin(op, builtin, args, code, classDesc, varFields);
                } else {
                    compileOp(op, items, code, classDesc, varFields);
                }
            }
        }
    }

    /// `(def name value)` becomes `RT.def("name", <value>)`, which leaves the
    /// Var on the stack.
    private static void compileDef(
            List<Form> items, CodeBuilder code, ClassDesc classDesc, Map<String, String> varFields) {
        if (items.size() != 3 || !(items.get(1) instanceof Form.Sym(String name))) {
            throw new MocljException("def expects a symbol and a value: " + new Form.Seq(items));
        }
        code.loadConstant(name);
        compileNode(items.get(2), code, classDesc, varFields);
        code.invokestatic(RT.CLASS_DESC, "def", DEF_DESC);
    }

    /// A `[a b]` literal becomes `RT.vector(new Object[]{<a>, <b>})`, so the
    /// vector is built when the enclosing form runs, from the values its
    /// elements evaluate to.
    private static void compileVector(
            List<Form> items, CodeBuilder code, ClassDesc classDesc, Map<String, String> varFields) {
        code.loadConstant(items.size());
        code.anewarray(CD_Object);
        for (int i = 0; i < items.size(); i++) {
            code.dup();
            code.loadConstant(i);
            compileNode(items.get(i), code, classDesc, varFields);
            code.aastore();
        }
        code.invokestatic(RT.CLASS_DESC, "vector", VECTOR_DESC);
    }

    /// `(conj v x)` and its siblings become a direct call of the [RT] method of
    /// the same name; a collection is just another value on the stack.
    private static void compileBuiltin(
            String name,
            MethodTypeDesc desc,
            List<Form> args,
            CodeBuilder code,
            ClassDesc classDesc,
            Map<String, String> varFields) {
        if (args.size() != desc.parameterCount()) {
            throw new MocljException("Wrong number of args (" + args.size() + ") passed to: " + name);
        }
        args.forEach(arg -> compileNode(arg, code, classDesc, varFields));
        code.invokestatic(RT.CLASS_DESC, name, desc);
    }

    /// `(op a b)` becomes `RT.invokeOp("op", <a>, <b>)`.
    private static void compileOp(
            String op, List<Form> items, CodeBuilder code, ClassDesc classDesc, Map<String, String> varFields) {
        if (items.size() != 3) {
            throw new MocljException("Wrong number of args (" + (items.size() - 1) + ") passed to: " + op);
        }
        code.loadConstant(op);
        compileNode(items.get(1), code, classDesc, varFields);
        compileNode(items.get(2), code, classDesc, varFields);
        code.invokestatic(RT.CLASS_DESC, "invokeOp", INVOKE_OP_DESC);
    }

    /// Field names have to be readable Java identifiers, so characters a symbol
    /// may contain but a field name may not are escaped by code point.
    private static String fieldName(String symbol) {
        var name = new StringBuilder(FIELD_PREFIX);
        symbol.codePoints().forEach(codePoint -> {
            if (Character.isJavaIdentifierPart(codePoint)) {
                name.appendCodePoint(codePoint);
            } else {
                name.append('_').append(Integer.toHexString(codePoint)).append('_');
            }
        });
        return name.toString();
    }

    private static String nextClassName() {
        return Compiler.class.getPackageName() + ".ReplEval_" + CLASS_COUNTER.incrementAndGet();
    }
}
