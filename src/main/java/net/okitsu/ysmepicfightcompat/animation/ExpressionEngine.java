package net.okitsu.ysmepicfightcompat.animation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded Molang compiler shared by numeric animation channels and typed scripts. */
public final class ExpressionEngine {
    public static final int MAX_SOURCE_LENGTH = 65_536;
    public static final int MAX_LOOP_ITERATIONS = 1_024;
    public static final int MAX_EVALUATION_OPERATIONS = 16_384;
    private static final int MAX_TOKENS = 16_384;
    private static final int MAX_DEPTH = 128;
    private static final int MAX_ARGUMENTS = 128;
    private static final int MAX_COMPILED = 4_096;
    private static final int MAX_VALUE_ELEMENTS = 4_096;
    private static final Object[] NO_ARGUMENTS = new Object[0];

    public interface Environment {
        double readVariable(int slot);
        boolean hasVariable(int slot);
        void writeVariable(int slot, double value);
        double readQuery(int slot);
        double invoke(String name, double[] arguments);
        double invokeWithText(String name, String[] arguments);

        default double invokeWithMixedArguments(String name, String[] textArguments,
                                                double[] numericArguments) {
            return invokeWithText(name, textArguments);
        }

        default Object readVariableValue(int slot) {
            return readVariable(slot);
        }

        default void writeVariableValue(int slot, Object value) {
            writeVariable(slot, number(value));
        }

        default Object readQueryValue(int slot) {
            return readQuery(slot);
        }

        /** Arguments are borrowed for this call only; copy them before retaining them. */
        default Object invokeValue(String name, Object[] arguments) {
            InvocationArguments stack = NUMBER_ARGUMENTS.get();
            double[] numeric = stack.acquire(arguments.length);
            String[] text = null;
            try {
                for (int index = 0; index < arguments.length; index++) {
                    Object argument = arguments[index];
                    if (argument instanceof String string) {
                        if (text == null) {
                            text = new String[arguments.length];
                        }
                        text[index] = string;
                        numeric[index] = 0.0D;
                    } else {
                        numeric[index] = number(argument);
                    }
                }
                return clean(text == null ? invoke(name, numeric)
                        : invokeWithMixedArguments(name, text, numeric));
            } finally {
                stack.release();
            }
        }

        default Object[] arguments() {
            return NO_ARGUMENTS;
        }
    }

    @FunctionalInterface
    public interface Expression {
        double evaluate(Environment environment);

        default Object evaluateValue(Environment environment) {
            return evaluate(environment);
        }

        default Dependencies dependencies() {
            return Dependencies.EMPTY;
        }

        default boolean isValid() {
            return true;
        }

        default String diagnostic() {
            return "";
        }
    }

    public record Dependencies(Set<Integer> variableSlots, Set<Integer> querySlots,
                               Set<String> functions, boolean writesVariables,
                               boolean hasTextArguments) {
        private static final Dependencies EMPTY = new Dependencies(
                Set.of(), Set.of(), Set.of(), false, false);

        public Dependencies {
            variableSlots = Set.copyOf(variableSlots);
            querySlots = Set.copyOf(querySlots);
            functions = Set.copyOf(functions);
        }
    }

    private enum Kind {NUMBER, IDENTIFIER, TEXT, SYMBOL, END}

    private record Token(Kind kind, String text, double number) {
    }

    @FunctionalInterface
    private interface Node {
        Object run(Environment environment);

        default Object value(Environment environment) {
            consumeOperations(1);
            EvaluationBudget budget = BUDGET.get();
            if (++budget.activeNodes > MAX_DEPTH * 2) {
                budget.activeNodes--;
                throw new EvaluationLimitException();
            }
            try {
                return run(environment);
            } finally {
                budget.activeNodes--;
            }
        }

        default int depth() {
            return 1;
        }
    }

    private record Operation(int depth, Node action) implements Node {
        @Override
        public Object run(Environment environment) {
            return action.run(environment);
        }
    }

    private interface Writable extends Node {
        void write(Environment environment, Object value);

        default boolean present(Environment environment) {
            return value(environment) != null;
        }

        default Access access(Environment environment) {
            return new Access() {
                @Override
                public Object read() {
                    return value(environment);
                }

                @Override
                public void write(Object value) {
                    Writable.this.write(environment, value);
                }

                @Override
                public boolean present() {
                    return Writable.this.present(environment);
                }
            };
        }
    }

    private interface Access {
        Object read();

        void write(Object value);

        default boolean present() {
            return read() != null;
        }
    }

    private record CompiledExpression(Node program, Dependencies dependencies)
            implements Expression {
        @Override
        public double evaluate(Environment environment) {
            return number(evaluateValue(environment));
        }

        @Override
        public Object evaluateValue(Environment environment) {
            try (EvaluationScope scope = beginEvaluation()) {
                try {
                    return boundedValue(program.value(environment));
                } catch (ReturnSignal result) {
                    return boundedValue(result.value);
                } catch (EvaluationLimitException failure) {
                    if (!scope.outermost) {
                        throw failure;
                    }
                    return 0.0D;
                }
            }
        }
    }

    private record InvalidExpression(String diagnostic) implements Expression {
        @Override
        public double evaluate(Environment environment) {
            return 0.0D;
        }

        @Override
        public boolean isValid() {
            return false;
        }
    }

    private record Literal(Object literal) implements Node {
        @Override
        public Object run(Environment environment) {
            return literal;
        }
    }

    private static final class Variable implements Writable {
        private final int slot;
        private final String name;
        private final boolean writable;
        private final List<Integer> parentSlots = new ArrayList<>();
        private final List<String[]> parentPaths = new ArrayList<>();

        private Variable(String name) {
            this.name = canonicalVariableName(name);
            writable = isVariableName(this.name);
            slot = writable ? slot(this.name) : querySlot(this.name);
            if (writable) {
                int separator = this.name.lastIndexOf('.');
                while (separator > this.name.indexOf('.')) {
                    parentSlots.add(slot(this.name.substring(0, separator)));
                    parentPaths.add(this.name.substring(separator + 1).split("\\."));
                    separator = this.name.lastIndexOf('.', separator - 1);
                }
            }
        }

        @Override
        public Object run(Environment environment) {
            if (!writable) {
                return boundedValue(environment.readQueryValue(slot));
            }
            if (environment.hasVariable(slot)) {
                return boundedValue(environment.readVariableValue(slot));
            }
            for (int index = 0; index < parentSlots.size(); index++) {
                int parent = parentSlots.get(index);
                if (environment.hasVariable(parent)) {
                    Object value = environment.readVariableValue(parent);
                    if (value instanceof Map<?, ?>) {
                        for (String member : parentPaths.get(index)) {
                            value = member(value, member);
                        }
                        return boundedValue(value);
                    }
                }
            }
            return boundedValue(environment.readVariableValue(slot));
        }

        @Override
        public void write(Environment environment, Object value) {
            if (!writable) {
                throw new IllegalStateException("Read-only query " + name);
            }
            if (!environment.hasVariable(slot)) {
                for (int index = 0; index < parentSlots.size(); index++) {
                    int parent = parentSlots.get(index);
                    if (environment.hasVariable(parent)
                            && environment.readVariableValue(parent) instanceof Map<?, ?> map) {
                        environment.writeVariableValue(parent, replacePath(
                                map, parentPaths.get(index), 0, boundedValue(value)));
                        return;
                    }
                }
            }
            environment.writeVariableValue(slot, boundedValue(value));
        }

        @Override
        public boolean present(Environment environment) {
            if (!writable || environment.hasVariable(slot)) {
                return !writable || environment.readVariableValue(slot) != null;
            }
            return parentSlots.stream().anyMatch(environment::hasVariable)
                    && run(environment) != null;
        }
    }

    private record Indexed(Node container, Node index) implements Writable {
        @Override
        public Object run(Environment environment) {
            return access(environment).read();
        }

        @Override
        public void write(Environment environment, Object value) {
            access(environment).write(value);
        }

        @Override
        public Access access(Environment environment) {
            Access parent = container instanceof Writable writable ? writable.access(environment) : null;
            Object source = parent == null ? container.value(environment) : parent.read();
            Object key = index.value(environment);
            return new Access() {
                @Override
                public Object read() {
                    return member(source, key);
                }

                @Override
                public void write(Object value) {
                    if (parent == null) {
                        throw new IllegalStateException("Indexed target is read-only");
                    }
                    parent.write(replaceMember(source, key, boundedValue(value)));
                }
            };
        }

        @Override
        public int depth() {
            return Math.max(container.depth(), index.depth()) + 1;
        }
    }

    private static final Map<String, Expression> COMPILED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> SLOT_BY_NAME = new ConcurrentHashMap<>();
    private static final List<String> NAME_BY_SLOT = new ArrayList<>();
    private static final ThreadLocal<InvocationArguments> NUMBER_ARGUMENTS =
            ThreadLocal.withInitial(InvocationArguments::new);
    private static final ThreadLocal<ValueArguments> VALUE_ARGUMENTS =
            ThreadLocal.withInitial(ValueArguments::new);
    private static final ThreadLocal<EvaluationBudget> BUDGET =
            ThreadLocal.withInitial(EvaluationBudget::new);
    private static final Expression ZERO = environment -> 0.0D;

    private static final class InvocationArguments {
        private double[][] frames = new double[4][];
        private int depth;

        private double[] acquire(int size) {
            if (depth == frames.length) {
                frames = Arrays.copyOf(frames, frames.length * 2);
            }
            double[] values = frames[depth];
            if (values == null || values.length != size) {
                values = new double[size];
                frames[depth] = values;
            }
            depth++;
            return values;
        }

        private void release() {
            depth--;
        }
    }

    private static final class EvaluationBudget {
        private int depth;
        private int remaining;
        private int activeNodes;
    }

    private static final class ValueArguments {
        private Object[][] frames = new Object[4][];
        private int depth;

        private Object[] acquire(int size) {
            if (depth == frames.length) {
                frames = Arrays.copyOf(frames, frames.length * 2);
            }
            Object[] values = frames[depth];
            if (values == null || values.length != size) {
                values = new Object[size];
                frames[depth] = values;
            }
            depth++;
            return values;
        }

        private void release() {
            Arrays.fill(frames[--depth], null);
        }
    }

    /** Shares one instruction budget across an event or nested custom function calls. */
    public static final class EvaluationScope implements AutoCloseable {
        private final EvaluationBudget budget;
        private final boolean outermost;
        private boolean closed;

        private EvaluationScope(EvaluationBudget budget) {
            this.budget = budget;
            outermost = budget.depth++ == 0;
            if (outermost) {
                budget.remaining = MAX_EVALUATION_OPERATIONS;
                budget.activeNodes = 0;
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                budget.depth--;
            }
        }
    }

    public static final class EvaluationLimitException extends RuntimeException {
        private EvaluationLimitException() {
            super("Molang evaluation budget exhausted", null, false, false);
        }
    }

    private static final class ReturnSignal extends RuntimeException {
        private final Object value;

        private ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private static final class LoopSignal extends RuntimeException {
        private static final LoopSignal BREAK = new LoopSignal();
        private static final LoopSignal CONTINUE = new LoopSignal();

        private LoopSignal() {
            super(null, null, false, false);
        }
    }

    private ExpressionEngine() {
    }

    public static EvaluationScope beginEvaluation() {
        return new EvaluationScope(BUDGET.get());
    }

    public static void consumeOperations(int count) {
        EvaluationBudget budget = BUDGET.get();
        if (count < 0 || count > budget.remaining) {
            budget.remaining = 0;
            throw new EvaluationLimitException();
        }
        budget.remaining -= count;
    }

    public static int slot(String name) {
        String canonicalName = canonicalVariableName(Objects.requireNonNull(name));
        Integer known = SLOT_BY_NAME.get(canonicalName);
        if (known != null) {
            return known;
        }
        synchronized (NAME_BY_SLOT) {
            return SLOT_BY_NAME.computeIfAbsent(canonicalName, key -> {
                int id = NAME_BY_SLOT.size();
                NAME_BY_SLOT.add(key);
                return id;
            });
        }
    }

    public static int querySlot(String name) {
        String canonicalName = canonicalVariableName(name);
        return slot(canonicalName.startsWith("q.")
                ? "query." + canonicalName.substring(2) : canonicalName);
    }

    public static String slotName(int id) {
        synchronized (NAME_BY_SLOT) {
            return id >= 0 && id < NAME_BY_SLOT.size() ? NAME_BY_SLOT.get(id) : "";
        }
    }

    public static Expression compile(String source) {
        if (source == null) {
            return ZERO;
        }
        if (source.length() > MAX_SOURCE_LENGTH) {
            return new InvalidExpression("Expression exceeds source length limit");
        }
        if (source.isBlank()) {
            return ZERO;
        }
        Expression known = COMPILED.get(source);
        if (known != null) {
            return known;
        }
        Expression compiled = compileUncached(source);
        if (COMPILED.size() < MAX_COMPILED) {
            Expression raced = COMPILED.putIfAbsent(source, compiled);
            return raced == null ? compiled : raced;
        }
        return compiled;
    }

    private static Expression compileUncached(String source) {
        try {
            List<Token> tokens = tokenize(source);
            Parser parser = new Parser(tokens);
            Node program = parser.program("");
            parser.requireEnd();
            return new CompiledExpression(program, dependencies(tokens));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return new InvalidExpression(failure.getMessage());
        }
    }

    private static Dependencies dependencies(List<Token> tokens) {
        Set<Integer> variables = new LinkedHashSet<>();
        Set<Integer> queries = new LinkedHashSet<>();
        Set<String> functions = new LinkedHashSet<>();
        boolean writes = false;
        boolean text = false;
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.kind() == Kind.TEXT) {
                text = true;
                continue;
            }
            if (token.kind() == Kind.SYMBOL
                    && (token.text().equals("{") || token.text().equals("["))) {
                writes = true; // Typed/context programs cannot use numeric worker snapshots.
            }
            if (token.kind() != Kind.IDENTIFIER) {
                continue;
            }
            String name = token.text().toLowerCase(Locale.ROOT);
            if (Set.of("return", "break", "continue", "loop", "for_each", "args").contains(name)
                    || name.startsWith("fn.") || name.startsWith("ctrl.")
                    || name.startsWith("context.") || name.startsWith("c.")) {
                writes = true;
            }
            if (Set.of("true", "false", "null", "return", "break", "continue", "args")
                    .contains(name)) {
                continue;
            }
            Token next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
            if (next != null && next.kind() == Kind.SYMBOL && next.text().equals("(")) {
                functions.add(name);
                continue;
            }
            String canonical = canonicalVariableName(name);
            if (isVariableName(canonical)) {
                variables.add(slot(canonical));
                if (canonical.indexOf('.', canonical.indexOf('.') + 1) >= 0
                        && !canonical.startsWith("v.roaming.")) {
                    writes = true;
                }
                if (next != null && next.kind() == Kind.SYMBOL && isAssignment(next.text())) {
                    writes = true;
                }
            } else {
                if (Set.of("ysm.texture_name", "ysm.dimension_name", "ysm.entity_type",
                        "ysm.left_shoulder_parrot_variant", "ysm.right_shoulder_parrot_variant",
                        "ysm.hit_target_id", "ysm.hit_target_type").contains(canonical)) {
                    // These read-only queries produce text rather than numeric snapshot values.
                    text = true;
                }
                queries.add(querySlot(canonical));
            }
        }
        return new Dependencies(variables, queries, functions, writes, text);
    }

    private static List<Token> tokenize(String source) {
        List<Token> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            if (result.size() >= MAX_TOKENS) {
                throw new IllegalArgumentException("Expression exceeds token limit");
            }
            char ch = source.charAt(cursor);
            if (Character.isWhitespace(ch) || ch == '\uFEFF') {
                cursor++;
                continue;
            }
            if (ch == '/' && cursor + 1 < source.length()) {
                char next = source.charAt(cursor + 1);
                if (next == '/') {
                    int end = source.indexOf('\n', cursor + 2);
                    cursor = end < 0 ? source.length() : end + 1;
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", cursor + 2);
                    if (end < 0) {
                        throw new IllegalArgumentException("Unterminated comment");
                    }
                    cursor = end + 2;
                    continue;
                }
            }
            if (Character.isDigit(ch) || ch == '.' && cursor + 1 < source.length()
                    && Character.isDigit(source.charAt(cursor + 1))) {
                int start = cursor++;
                boolean dotSeen = ch == '.';
                while (cursor < source.length()) {
                    char next = source.charAt(cursor);
                    if (Character.isDigit(next)) {
                        cursor++;
                    } else if (next == '.' && !dotSeen) {
                        dotSeen = true;
                        cursor++;
                    } else {
                        break;
                    }
                }
                if (cursor < source.length()
                        && (source.charAt(cursor) == 'e' || source.charAt(cursor) == 'E')) {
                    cursor++;
                    if (cursor < source.length()
                            && (source.charAt(cursor) == '+' || source.charAt(cursor) == '-')) {
                        cursor++;
                    }
                    int exponent = cursor;
                    while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) {
                        cursor++;
                    }
                    if (cursor == exponent) {
                        throw new IllegalArgumentException("Missing exponent digits");
                    }
                }
                String text = source.substring(start, cursor);
                result.add(new Token(Kind.NUMBER, text, clean(Double.parseDouble(text))));
                continue;
            }
            if (ch == '\'' || ch == '"') {
                char delimiter = ch;
                cursor++;
                StringBuilder text = new StringBuilder();
                boolean closed = false;
                while (cursor < source.length()) {
                    char next = source.charAt(cursor++);
                    if (next == delimiter) {
                        closed = true;
                        break;
                    }
                    if (next == '\\') {
                        if (cursor >= source.length()) {
                            break;
                        }
                        next = switch (source.charAt(cursor++)) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case '\\' -> '\\';
                            case '\'' -> '\'';
                            case '"' -> '"';
                            default -> throw new IllegalArgumentException("Invalid string escape");
                        };
                    }
                    text.append(next);
                    if (text.length() > MAX_SOURCE_LENGTH / 4) {
                        throw new IllegalArgumentException("String exceeds length limit");
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                result.add(new Token(Kind.TEXT, text.toString(), 0.0D));
                continue;
            }
            if (Character.isLetter(ch) || ch == '_' || ch == '$') {
                int start = cursor++;
                while (cursor < source.length()) {
                    char next = source.charAt(cursor);
                    if (Character.isLetterOrDigit(next) || next == '_' || next == '$'
                            || next == '.' && cursor + 1 < source.length()
                            && (Character.isLetter(source.charAt(cursor + 1))
                            || source.charAt(cursor + 1) == '_' || source.charAt(cursor + 1) == '$')) {
                        cursor++;
                    } else {
                        break;
                    }
                }
                result.add(new Token(Kind.IDENTIFIER, source.substring(start, cursor), 0.0D));
                continue;
            }
            String two = cursor + 1 < source.length() ? source.substring(cursor, cursor + 2) : "";
            if (Set.of("==", "!=", "<=", ">=", "&&", "||", "??", "+=", "-=", "*=", "/=", "%=")
                    .contains(two)) {
                result.add(new Token(Kind.SYMBOL, two, 0.0D));
                cursor += 2;
            } else if ("+-*/%(),?:!<>=;{}[].".indexOf(ch) >= 0) {
                result.add(new Token(Kind.SYMBOL, Character.toString(ch), 0.0D));
                cursor++;
            } else {
                throw new IllegalArgumentException("Unexpected character at " + cursor);
            }
        }
        result.add(new Token(Kind.END, "", 0.0D));
        return result;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int cursor;
        private int recursionDepth;
        private int loopDepth;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Node program(String terminator) {
            List<Node> statements = new ArrayList<>();
            while (current().kind() != Kind.END && !is(terminator)) {
                if (take(";")) {
                    continue;
                }
                statements.add(expression(0));
                if (!take(";")) {
                    break;
                }
            }
            if (statements.isEmpty()) {
                return new Literal(0.0D);
            }
            if (statements.size() == 1) {
                return statements.get(0);
            }
            return operation(environment -> {
                Object value = 0.0D;
                for (Node statement : statements) {
                    value = statement.value(environment);
                }
                return value;
            }, statements.toArray(Node[]::new));
        }

        private Node expression(int minimumPrecedence) {
            if (++recursionDepth > MAX_DEPTH) {
                throw new IllegalArgumentException("Expression exceeds nesting limit");
            }
            try {
                Node left = postfix(prefix());
                while (true) {
                    String operator = current().text();
                    int precedence = precedence(operator);
                    if (precedence < minimumPrecedence) {
                        return left;
                    }
                    advance();
                    if (operator.equals("?")) {
                        Node whenTrue = expression(0);
                        Node whenFalse = take(":")
                                ? expression(precedence) : new Literal(0.0D);
                        Node condition = left;
                        left = operation(environment -> truth(condition.value(environment))
                                ? whenTrue.value(environment) : whenFalse.value(environment),
                                condition, whenTrue, whenFalse);
                        continue;
                    }
                    int nextMinimum = isRightAssociative(operator) ? precedence : precedence + 1;
                    Node right = expression(nextMinimum);
                    left = combine(operator, left, right);
                }
            } finally {
                recursionDepth--;
            }
        }

        private Node prefix() {
            Token token = advance();
            if (token.kind() == Kind.NUMBER) {
                return new Literal(token.number());
            }
            if (token.kind() == Kind.TEXT) {
                return new Literal(token.text());
            }
            if (token.kind() == Kind.IDENTIFIER) {
                String name = token.text().toLowerCase(Locale.ROOT);
                if (name.equals("return")) {
                    Node result = current().kind() == Kind.END || is(";") || is("}") || is(")")
                            ? new Literal(0.0D) : expression(0);
                    return operation(environment -> {
                        throw new ReturnSignal(result.value(environment));
                    }, result);
                }
                if (name.equals("break") || name.equals("continue")) {
                    if (loopDepth == 0) {
                        throw new IllegalArgumentException(name + " outside a loop");
                    }
                    return environment -> {
                        throw name.equals("break") ? LoopSignal.BREAK : LoopSignal.CONTINUE;
                    };
                }
                if (take("(")) {
                    return name.equals("loop") || name.equals("for_each")
                            ? loop(name) : call(name);
                }
                return switch (name) {
                    case "true" -> new Literal(1.0D);
                    case "false" -> new Literal(0.0D);
                    case "null" -> new Literal(null);
                    case "args" -> environment -> boundedValue(environment.arguments());
                    default -> new Variable(name);
                };
            }
            if (token.text().equals("(") || token.text().equals("{")) {
                String terminator = token.text().equals("(") ? ")" : "}";
                Node value = program(terminator);
                expect(terminator);
                return value;
            }
            if (token.text().equals("[")) {
                List<Node> entries = new ArrayList<>();
                if (!take("]")) {
                    do {
                        if (entries.size() >= MAX_LOOP_ITERATIONS) {
                            throw new IllegalArgumentException("Array exceeds length limit");
                        }
                        entries.add(expression(0));
                    } while (take(","));
                    expect("]");
                }
                return operation(environment -> {
                    List<Object> result = new ArrayList<>(entries.size());
                    for (Node entry : entries) {
                        result.add(entry.value(environment));
                    }
                    return boundedValue(result);
                }, entries.toArray(Node[]::new));
            }
            if (token.text().equals("+") || token.text().equals("-") || token.text().equals("!")) {
                Node operand = expression(11);
                return switch (token.text()) {
                    case "-" -> operation(environment -> -number(operand.value(environment)), operand);
                    case "!" -> operation(environment -> truth(operand.value(environment)) ? 0.0D : 1.0D,
                            operand);
                    default -> operand;
                };
            }
            throw new IllegalArgumentException("Unexpected expression token " + token.text());
        }

        private Node postfix(Node value) {
            while (true) {
                if (take("[")) {
                    Node index = expression(0);
                    expect("]");
                    value = new Indexed(value, index);
                } else if (take(".")) {
                    Token member = advance();
                    if (member.kind() != Kind.IDENTIFIER) {
                        throw new IllegalArgumentException("Expected struct member");
                    }
                    for (String name : member.text().toLowerCase(Locale.ROOT).split("\\.")) {
                        value = new Indexed(value, new Literal(name));
                    }
                } else {
                    return value;
                }
                checkDepth(value.depth());
            }
        }

        private Node loop(String name) {
            Node first = expression(0);
            expect(",");
            Writable target = null;
            Node source = first;
            if (name.equals("for_each")) {
                target = writable(first);
                source = expression(0);
                expect(",");
            }
            loopDepth++;
            Node body;
            try {
                body = expression(0);
            } finally {
                loopDepth--;
            }
            expect(")");
            Writable iterator = target;
            Node count = source;
            return operation(environment -> {
                Object values = count.value(environment);
                List<?> entries = iterator == null ? List.of()
                        : values instanceof List<?> list ? list : List.of();
                int iterations = iterator == null
                        ? (int) Math.max(0, Math.min(MAX_LOOP_ITERATIONS, number(values)))
                        : Math.min(MAX_LOOP_ITERATIONS, entries.size());
                for (int index = 0; index < iterations; index++) {
                    consumeOperations(1);
                    if (iterator != null) {
                        iterator.write(environment, entries.get(index));
                    }
                    try {
                        body.value(environment);
                    } catch (LoopSignal signal) {
                        if (signal == LoopSignal.BREAK) {
                            break;
                        }
                    }
                }
                return 0.0D;
            }, first, source, body);
        }

        private Node call(String name) {
            List<Node> arguments = new ArrayList<>();
            if (!take(")")) {
                do {
                    if (arguments.size() >= MAX_ARGUMENTS) {
                        throw new IllegalArgumentException("Function exceeds argument limit");
                    }
                    arguments.add(expression(0));
                } while (take(","));
                expect(")");
            }
            Node[] nodes = arguments.toArray(Node[]::new);
            return operation(environment -> {
                ValueArguments stack = VALUE_ARGUMENTS.get();
                Object[] values = stack.acquire(nodes.length);
                try {
                    for (int index = 0; index < nodes.length; index++) {
                        values[index] = boundedValue(nodes[index].value(environment));
                    }
                    return boundedValue(environment.invokeValue(name, values));
                } finally {
                    stack.release();
                }
            }, nodes);
        }

        private Node combine(String operator, Node left, Node right) {
            if (isAssignment(operator)) {
                Writable target = writable(left);
                return operation(environment -> {
                    Access access = target.access(environment);
                    Object value = right.value(environment);
                    if (!operator.equals("=")) {
                        value = arithmetic(operator.substring(0, 1),
                                number(access.read()), number(value));
                    }
                    access.write(value);
                    return value;
                }, left, right);
            }
            if (operator.equals("??")) {
                return operation(environment -> {
                    Object value;
                    if (left instanceof Writable writable) {
                        Access access = writable.access(environment);
                        value = access.present() ? access.read() : null;
                    } else {
                        value = left.value(environment);
                    }
                    return value == null ? right.value(environment) : value;
                }, left, right);
            }
            return operation(environment -> {
                Object first = left.value(environment);
                if (operator.equals("||")) {
                    return truth(first) || truth(right.value(environment)) ? 1.0D : 0.0D;
                }
                if (operator.equals("&&")) {
                    return truth(first) && truth(right.value(environment)) ? 1.0D : 0.0D;
                }
                Object second = right.value(environment);
                if (operator.equals("==") || operator.equals("!=")) {
                    boolean equal = equal(first, second);
                    return equal == operator.equals("==") ? 1.0D : 0.0D;
                }
                double a = number(first);
                double b = number(second);
                return switch (operator) {
                    case "<" -> a < b ? 1.0D : 0.0D;
                    case ">" -> a > b ? 1.0D : 0.0D;
                    case "<=" -> a <= b ? 1.0D : 0.0D;
                    case ">=" -> a >= b ? 1.0D : 0.0D;
                    default -> arithmetic(operator, a, b);
                };
            }, left, right);
        }

        private Writable writable(Node target) {
            if (!(target instanceof Writable writable)
                    || target instanceof Variable variable && !variable.writable
                    || target instanceof Indexed indexed && !isWritableRoot(indexed.container())) {
                throw new IllegalArgumentException("Assignment target is not a variable");
            }
            return writable;
        }

        private Token current() {
            return tokens.get(Math.min(cursor, tokens.size() - 1));
        }

        private Token advance() {
            Token token = current();
            cursor++;
            return token;
        }

        private boolean is(String symbol) {
            return current().kind() == Kind.SYMBOL && current().text().equals(symbol);
        }

        private boolean take(String symbol) {
            if (!is(symbol)) {
                return false;
            }
            cursor++;
            return true;
        }

        private void expect(String symbol) {
            if (!take(symbol)) {
                throw new IllegalArgumentException("Expected " + symbol);
            }
        }

        private void requireEnd() {
            if (current().kind() != Kind.END) {
                throw new IllegalArgumentException("Trailing expression tokens");
            }
        }
    }

    private static boolean isWritableRoot(Node node) {
        return node instanceof Variable variable && variable.writable
                || node instanceof Indexed indexed && isWritableRoot(indexed.container());
    }

    private static Node operation(Node action, Node... children) {
        int depth = 1;
        for (Node child : children) {
            depth = Math.max(depth, child.depth() + 1);
        }
        checkDepth(depth);
        return new Operation(depth, action);
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Expression exceeds AST depth limit");
        }
    }

    private static int precedence(String operator) {
        return switch (operator) {
            case "=", "+=", "-=", "*=", "/=", "%=" -> 1;
            case "?" -> 2;
            case "??" -> 3;
            case "||" -> 4;
            case "&&" -> 5;
            case "==", "!=" -> 6;
            case "<", ">", "<=", ">=" -> 7;
            case "+", "-" -> 8;
            case "*", "/", "%" -> 9;
            default -> -1;
        };
    }

    private static boolean isAssignment(String operator) {
        return Set.of("=", "+=", "-=", "*=", "/=", "%=").contains(operator);
    }

    private static boolean isRightAssociative(String operator) {
        return isAssignment(operator) || operator.equals("??") || operator.equals("?");
    }

    private static boolean isVariableName(String name) {
        if (name == null) {
            return false;
        }
        int separator = name.indexOf('.');
        if (separator <= 0) {
            return false;
        }
        String prefix = name.substring(0, separator);
        return prefix.equals("v") || prefix.equals("variable")
                || prefix.equals("temp") || prefix.equals("t");
    }

    private static String canonicalVariableName(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("variable.")) {
            return "v." + lower.substring("variable.".length());
        }
        return lower.startsWith("temp.") ? "t." + lower.substring("temp.".length()) : lower;
    }

    /** Preserves stable numeric string markers for pre-existing numeric query implementations. */
    public static double number(Object value) {
        if (value instanceof Number numeric) {
            return clean(numeric.doubleValue());
        }
        if (value instanceof Boolean bool) {
            return bool ? 1.0D : 0.0D;
        }
        return value instanceof String text ? textMarker(text) : 0.0D;
    }

    private static boolean truth(Object value) {
        return number(value) != 0.0D;
    }

    private static boolean equal(Object first, Object second) {
        if (first instanceof String a && second instanceof String b) {
            return a.equals(b);
        }
        if (first instanceof List<?> || first instanceof Map<?, ?>
                || second instanceof List<?> || second instanceof Map<?, ?>) {
            return Objects.equals(first, second);
        }
        return Math.abs(number(first) - number(second)) < 1.0E-6D;
    }

    private static double arithmetic(String operator, double first, double second) {
        return clean(switch (operator) {
            case "+" -> first + second;
            case "-" -> first - second;
            case "*" -> first * second;
            case "/" -> second == 0.0D ? 0.0D : first / second;
            case "%" -> second == 0.0D ? 0.0D : first % second;
            default -> throw new IllegalArgumentException("Unsupported operator " + operator);
        });
    }

    private static double clean(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }

    private static double textMarker(String text) {
        return -1.0E18D - slot("text:" + text) * 4096.0D;
    }

    /** Copies query/function data to bounded immutable numeric/string/list/struct values. */
    public static Object boundedValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value == null ? null : number(value);
        }
        if (value instanceof String text) {
            return text.length() <= MAX_SOURCE_LENGTH / 4 ? text : null;
        }
        return boundedValue(value, 0, new int[]{MAX_VALUE_ELEMENTS});
    }

    private static Object boundedValue(Object value, int depth, int[] remaining) {
        if (remaining[0]-- <= 0 || depth > 16) {
            return null;
        }
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return boundedValue(value);
        }
        if (value instanceof Object[] array) {
            value = Arrays.asList(array);
        }
        if (value instanceof List<?> list) {
            int length = Math.min(MAX_LOOP_ITERATIONS, list.size());
            List<Object> result = new ArrayList<>(length);
            for (int index = 0; index < length && remaining[0] > 0; index++) {
                result.add(boundedValue(list.get(index), depth + 1, remaining));
            }
            return Collections.unmodifiableList(result);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            int visited = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (visited++ >= MAX_LOOP_ITERATIONS || remaining[0] <= 0) {
                    break;
                }
                if (entry.getKey() instanceof String key && key.length() <= MAX_SOURCE_LENGTH / 4) {
                    result.put(key.toLowerCase(Locale.ROOT),
                            boundedValue(entry.getValue(), depth + 1, remaining));
                }
            }
            return Collections.unmodifiableMap(result);
        }
        return null;
    }

    private static Object member(Object container, Object index) {
        if (container instanceof Map<?, ?> map && index instanceof String key) {
            return boundedValue(map.get(key.toLowerCase(Locale.ROOT)));
        }
        int element = index instanceof Number number ? (int) Math.floor(number.doubleValue()) : -1;
        if (container instanceof List<?> list) {
            return element >= 0 && element < Math.min(MAX_LOOP_ITERATIONS, list.size())
                    ? boundedValue(list.get(element)) : null;
        }
        if (container instanceof Object[] array) {
            return element >= 0 && element < Math.min(MAX_LOOP_ITERATIONS, array.length)
                    ? boundedValue(array[element]) : null;
        }
        return null;
    }

    private static Object replaceMember(Object container, Object index, Object value) {
        if (container instanceof Map<?, ?> map && index instanceof String key) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String name && copy.size() < MAX_LOOP_ITERATIONS) {
                    copy.put(name.toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
            String canonical = key.toLowerCase(Locale.ROOT);
            if (copy.containsKey(canonical) || copy.size() < MAX_LOOP_ITERATIONS) {
                copy.put(canonical, value);
            }
            return boundedValue(copy);
        }
        int element = index instanceof Number number ? (int) Math.floor(number.doubleValue()) : -1;
        if (container instanceof List<?> list && element >= 0 && element < MAX_LOOP_ITERATIONS) {
            List<Object> copy = new ArrayList<>(list.subList(0, Math.min(list.size(), MAX_LOOP_ITERATIONS)));
            while (copy.size() <= element) {
                copy.add(null);
            }
            copy.set(element, value);
            return boundedValue(copy);
        }
        return boundedValue(container);
    }

    private static Object replacePath(Object container, String[] path, int index, Object value) {
        if (index >= path.length) {
            return value;
        }
        Object child = member(container, path[index]);
        if (child == null && index + 1 < path.length) {
            child = Map.of();
        }
        return replaceMember(container, path[index], replacePath(child, path, index + 1, value));
    }
}
