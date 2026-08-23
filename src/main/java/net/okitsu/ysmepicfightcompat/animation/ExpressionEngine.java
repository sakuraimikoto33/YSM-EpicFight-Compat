package net.okitsu.ysmepicfightcompat.animation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Small Molang-compatible expression compiler used by model animation clips. */
public final class ExpressionEngine {
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
    }

    @FunctionalInterface
    public interface Expression {
        double evaluate(Environment environment);

        default Dependencies dependencies() {
            return Dependencies.EMPTY;
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

    private interface Node extends Expression {
    }

    private record CompiledExpression(Node program, Dependencies dependencies)
            implements Expression {
        @Override
        public double evaluate(Environment environment) {
            return program.evaluate(environment);
        }
    }

    private record Literal(double value) implements Node {
        @Override
        public double evaluate(Environment environment) {
            return value;
        }
    }

    private static final class Variable implements Node {
        private final int slot;
        private final boolean writable;

        private Variable(String name) {
            String canonicalName = canonicalVariableName(name);
            writable = isVariableName(canonicalName);
            slot = writable ? slot(canonicalName) : querySlot(canonicalName);
        }

        @Override
        public double evaluate(Environment environment) {
            return writable ? environment.readVariable(slot) : environment.readQuery(slot);
        }
    }

    private record TextArgument(String value) {
    }

    private static final Map<String, Expression> COMPILED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> SLOT_BY_NAME = new ConcurrentHashMap<>();
    private static final List<String> NAME_BY_SLOT = new ArrayList<>();
    private static final ThreadLocal<InvocationArguments> NUMBER_ARGUMENTS =
            ThreadLocal.withInitial(InvocationArguments::new);
    private static final Expression ZERO = environment -> 0.0D;

    /** Per-thread stack keeps nested Molang calls from overwriting their caller's arguments. */
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

    private ExpressionEngine() {
    }

    public static int slot(String name) {
        String canonicalName = canonicalVariableName(name);
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
        if (source == null || source.isBlank()) {
            return ZERO;
        }
        return COMPILED.computeIfAbsent(source, ExpressionEngine::compileUncached);
    }

    private static Expression compileUncached(String source) {
        try {
            List<Token> tokens = tokenize(source);
            Parser parser = new Parser(tokens);
            Node program = parser.program();
            parser.requireEnd();
            return new CompiledExpression(program, dependencies(tokens));
        } catch (RuntimeException ignored) {
            return ZERO;
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
            if (token.kind() != Kind.IDENTIFIER) {
                continue;
            }
            Token next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
            if (next != null && next.kind() == Kind.SYMBOL && next.text().equals("(")) {
                functions.add(token.text().toLowerCase(Locale.ROOT));
                continue;
            }
            String canonical = canonicalVariableName(token.text());
            if (isVariableName(canonical)) {
                variables.add(slot(canonical));
                if (next != null && next.kind() == Kind.SYMBOL
                        && (next.text().equals("=") || next.text().equals("+=")
                        || next.text().equals("-="))) {
                    writes = true;
                }
            } else {
                queries.add(querySlot(canonical));
            }
        }
        return new Dependencies(variables, queries, functions, writes, text);
    }

    private static List<Token> tokenize(String source) {
        List<Token> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            char ch = source.charAt(cursor);
            if (Character.isWhitespace(ch)) {
                cursor++;
                continue;
            }
            if (Character.isDigit(ch)
                    || ch == '.' && cursor + 1 < source.length()
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
                String text = source.substring(start, cursor);
                result.add(new Token(Kind.NUMBER, text, Double.parseDouble(text)));
                continue;
            }
            if (ch == '\'' || ch == '"') {
                char delimiter = ch;
                int start = ++cursor;
                while (cursor < source.length() && source.charAt(cursor) != delimiter) {
                    cursor++;
                }
                result.add(new Token(Kind.TEXT, source.substring(start, cursor), 0.0D));
                if (cursor < source.length()) {
                    cursor++;
                }
                continue;
            }
            if (Character.isLetter(ch) || ch == '_' || ch == '$') {
                int start = cursor++;
                while (cursor < source.length()) {
                    char next = source.charAt(cursor);
                    if (Character.isLetterOrDigit(next) || next == '_' || next == '$' || next == '.') {
                        cursor++;
                    } else {
                        break;
                    }
                }
                result.add(new Token(Kind.IDENTIFIER, source.substring(start, cursor), 0.0D));
                continue;
            }
            String two = cursor + 1 < source.length() ? source.substring(cursor, cursor + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                    || two.equals("&&") || two.equals("||") || two.equals("??")
                    || two.equals("+=") || two.equals("-=")) {
                result.add(new Token(Kind.SYMBOL, two, 0.0D));
                cursor += 2;
            } else if ("+-*/%(),?:!<>=;".indexOf(ch) >= 0) {
                result.add(new Token(Kind.SYMBOL, Character.toString(ch), 0.0D));
                cursor++;
            } else {
                cursor++;
            }
        }
        result.add(new Token(Kind.END, "", 0.0D));
        return result;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int cursor;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Node program() {
            List<Node> statements = new ArrayList<>();
            while (current().kind() != Kind.END && !is(")")) {
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
            return environment -> {
                double value = 0.0D;
                for (Node statement : statements) {
                    value = statement.evaluate(environment);
                }
                return value;
            };
        }

        private Node expression(int minimumPrecedence) {
            Node left = prefix();
            while (true) {
                String operator = current().text();
                int precedence = precedence(operator);
                if (precedence < minimumPrecedence) {
                    return left;
                }
                advance();
                if (operator.equals("?")) {
                    Node whenTrue = expression(0);
                    expect(":");
                    Node whenFalse = expression(precedence);
                    Node condition = left;
                    left = environment -> truth(condition.evaluate(environment))
                            ? whenTrue.evaluate(environment) : whenFalse.evaluate(environment);
                    continue;
                }
                int nextMinimum = isRightAssociative(operator) ? precedence : precedence + 1;
                Node right = expression(nextMinimum);
                left = combine(operator, left, right);
            }
        }

        private Node prefix() {
            Token token = advance();
            if (token.kind() == Kind.NUMBER) {
                return new Literal(token.number());
            }
            if (token.kind() == Kind.TEXT) {
                return new Literal(textMarker(token.text()));
            }
            if (token.kind() == Kind.IDENTIFIER) {
                if (take("(")) {
                    return call(token.text());
                }
                return new Variable(token.text());
            }
            if (token.text().equals("(")) {
                Node value = program();
                expect(")");
                return value;
            }
            if (token.text().equals("+") || token.text().equals("-") || token.text().equals("!")) {
                Node operand = expression(11);
                return switch (token.text()) {
                    case "-" -> environment -> -operand.evaluate(environment);
                    case "!" -> environment -> truth(operand.evaluate(environment)) ? 0.0D : 1.0D;
                    default -> operand;
                };
            }
            throw new IllegalStateException("Unexpected expression token " + token.text());
        }

        private Node call(String name) {
            List<Object> arguments = new ArrayList<>();
            boolean containsText = false;
            if (!take(")")) {
                do {
                    if (current().kind() == Kind.TEXT) {
                        containsText = true;
                        arguments.add(new TextArgument(advance().text()));
                    } else {
                        arguments.add(expression(0));
                    }
                } while (take(","));
                expect(")");
            }
            if (containsText) {
                String[] strings = new String[arguments.size()];
                Node[] numbers = new Node[arguments.size()];
                for (int i = 0; i < arguments.size(); i++) {
                    Object argument = arguments.get(i);
                    if (argument instanceof TextArgument text) {
                        strings[i] = text.value();
                    } else {
                        numbers[i] = (Node) argument;
                    }
                }
                return environment -> {
                    InvocationArguments argumentsStack = NUMBER_ARGUMENTS.get();
                    double[] values = argumentsStack.acquire(numbers.length);
                    try {
                        for (int i = 0; i < numbers.length; i++) {
                            values[i] = numbers[i] == null
                                    ? 0.0D : numbers[i].evaluate(environment);
                        }
                        return clean(environment.invokeWithMixedArguments(
                                name, strings, values));
                    } finally {
                        argumentsStack.release();
                    }
                };
            }
            Node[] numbers = arguments.toArray(Node[]::new);
            return environment -> {
                InvocationArguments argumentsStack = NUMBER_ARGUMENTS.get();
                double[] values = argumentsStack.acquire(numbers.length);
                try {
                    for (int i = 0; i < numbers.length; i++) {
                        values[i] = numbers[i].evaluate(environment);
                    }
                    return clean(environment.invoke(name, values));
                } finally {
                    argumentsStack.release();
                }
            };
        }

        private Node combine(String operator, Node left, Node right) {
            if (operator.equals("=") || operator.equals("+=") || operator.equals("-=")) {
                if (!(left instanceof Variable variable) || !variable.writable) {
                    throw new IllegalStateException("Assignment target is not a variable");
                }
                return environment -> {
                    double value = right.evaluate(environment);
                    if (operator.equals("+=")) {
                        value += environment.readVariable(variable.slot);
                    } else if (operator.equals("-=")) {
                        value = environment.readVariable(variable.slot) - value;
                    }
                    value = clean(value);
                    environment.writeVariable(variable.slot, value);
                    return value;
                };
            }
            if (operator.equals("??")) {
                return environment -> left instanceof Variable variable && variable.writable
                        && !environment.hasVariable(variable.slot)
                        ? right.evaluate(environment) : left.evaluate(environment);
            }
            return switch (operator) {
                case "||" -> environment -> truth(left.evaluate(environment))
                        || truth(right.evaluate(environment)) ? 1.0D : 0.0D;
                case "&&" -> environment -> truth(left.evaluate(environment))
                        && truth(right.evaluate(environment)) ? 1.0D : 0.0D;
                case "==" -> environment -> nearlyEqual(left.evaluate(environment), right.evaluate(environment))
                        ? 1.0D : 0.0D;
                case "!=" -> environment -> nearlyEqual(left.evaluate(environment), right.evaluate(environment))
                        ? 0.0D : 1.0D;
                case "<" -> environment -> left.evaluate(environment) < right.evaluate(environment) ? 1.0D : 0.0D;
                case ">" -> environment -> left.evaluate(environment) > right.evaluate(environment) ? 1.0D : 0.0D;
                case "<=" -> environment -> left.evaluate(environment) <= right.evaluate(environment) ? 1.0D : 0.0D;
                case ">=" -> environment -> left.evaluate(environment) >= right.evaluate(environment) ? 1.0D : 0.0D;
                case "+" -> environment -> clean(left.evaluate(environment) + right.evaluate(environment));
                case "-" -> environment -> clean(left.evaluate(environment) - right.evaluate(environment));
                case "*" -> environment -> clean(left.evaluate(environment) * right.evaluate(environment));
                case "/" -> environment -> {
                    double divisor = right.evaluate(environment);
                    return divisor == 0.0D ? 0.0D : clean(left.evaluate(environment) / divisor);
                };
                case "%" -> environment -> {
                    double divisor = right.evaluate(environment);
                    return divisor == 0.0D ? 0.0D : clean(left.evaluate(environment) % divisor);
                };
                default -> throw new IllegalStateException("Unsupported operator " + operator);
            };
        }

        private Token current() {
            return tokens.get(cursor);
        }

        private Token advance() {
            return tokens.get(cursor++);
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
                throw new IllegalStateException("Expected " + symbol);
            }
        }

        private void requireEnd() {
            if (current().kind() != Kind.END) {
                throw new IllegalStateException("Trailing expression tokens");
            }
        }
    }

    private static int precedence(String operator) {
        return switch (operator) {
            case "=", "+=", "-=" -> 1;
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

    private static boolean isRightAssociative(String operator) {
        return operator.equals("=") || operator.equals("+=") || operator.equals("-=")
                || operator.equals("??") || operator.equals("?");
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
        return lower.startsWith("variable.")
                ? "v." + lower.substring("variable.".length()) : lower;
    }

    private static boolean truth(double value) {
        return value != 0.0D;
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) < 1.0E-6D;
    }

    private static double clean(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }

    private static double textMarker(String text) {
        return -1.0E18D - slot("text:" + text) * 4096.0D;
    }
}
