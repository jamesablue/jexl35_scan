package local.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlInfo;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Reads a Harness YAML file and reports whether JEXL 3.5 accepts each embedded
 * expression.
 *
 * <p>Findings come in two kinds, and the distinction matters:
 *
 * <ul>
 *   <li><b>SYNTAX</b> - the verdict of {@code jexlEngine.createExpression(src)}
 *       on the real engine on the classpath. Authoritative: if this fails, the
 *       shipped parser rejected the text, and the engine's own message and
 *       column are reported verbatim. Covers the documented nested-subscript
 *       and {@code ?[} restrictions.
 *   <li><b>POLICY</b> - a lexical flag, NOT an engine verdict. Reflection and
 *       global assignment both parse cleanly on a stock JEXL 3.5 engine, and
 *       were verified to do so; Harness rejects them through its own sandbox
 *       and context configuration, which is not reproducible from here. These
 *       are pattern matches against the documented SMP 0.43.0 restrictions and
 *       carry the same false-positive risk as any grep.
 * </ul>
 *
 * <p>Usage: {@code java -jar jexl-check.jar <file.yaml> [...] [--verbose] [--syntax-only]}
 * <p>Exit codes: 0 = clean, 1 = findings, 2 = a file could not be read or parsed.
 */
public final class JexlCheck {

    /**
     * Deliberately minimal. This tool only ever compiles expressions, never
     * evaluates them, and permissions/sandbox settings apply at evaluation -
     * so configuring them here would buy nothing and would tie the build to
     * JEXL 3.3+, where {@code JexlPermissions} was introduced. Keeping the
     * builder bare is what lets the same source compile against 3.0 for a
     * side-by-side comparison.
     */
    private final JexlEngine jexlEngine = new JexlBuilder()
            .strict(true)
            .silent(false)
            .create();

    private final Yaml yamlParser = new Yaml();

    // ----------------------------------------------------------------- model

    record Scalar(int line, String text) { }

    enum Kind { SYNTAX, SCRIPT, NESTED, WRAPPER, POLICY }

    record Finding(int line, String expression, Kind kind, String detail) { }

    /**
     * One extracted expression body, and whether its {@code <+ ... >} wrapper was
     * actually closed. An unterminated wrapper cannot be caught by the engine -
     * the text inside it usually parses perfectly well - so the structural fact
     * has to be carried out of extraction rather than rediscovered later.
     */
    record Extracted(String body, boolean terminated) { }

    // ------------------------------------------------------------ YAML layer

    /**
     * Compose the document into a node tree and collect every scalar.
     *
     * <p>Composing rather than text-scanning is what makes comments and block
     * scalars behave: a commented-out expression never becomes a node at all,
     * and a folded scalar arrives already folded, exactly as the platform sees
     * it. Each scalar keeps the source line for reporting.
     */
    List<Scalar> collectScalars(Reader reader) {
        List<Scalar> scalars = new ArrayList<>();
        for (Node root : yamlParser.composeAll(reader)) {
            collectScalarsFrom(root, scalars);
        }
        return scalars;
    }

    private void collectScalarsFrom(Node node, List<Scalar> scalars) {
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                collectScalarsFrom(tuple.getKeyNode(), scalars);
                collectScalarsFrom(tuple.getValueNode(), scalars);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                collectScalarsFrom(item, scalars);
            }
        } else if (node instanceof ScalarNode scalar) {
            scalars.add(new Scalar(scalar.getStartMark().getLine() + 1, scalar.getValue()));
        }
    }

    // ------------------------------------------------- expression extraction

    /**
     * Pull each {@code <+ ... >} expression out of one scalar value.
     *
     * <p>The closing delimiter is ambiguous: {@code >} is also greater-than and
     * part of {@code =>} and {@code >=}. Rather than guess with a heuristic,
     * this asks the parser - of every candidate {@code >}, keep the longest
     * span the engine can parse. {@code <+a > 3>} resolves to {@code a > 3},
     * and {@code <+a.b> and <+c.d>} resolves to {@code a.b}, because in each
     * case the alternative span is not valid JEXL.
     *
     * <p>If nothing parses, the expression is broken anyway, so the span to the
     * last {@code >} is returned and left to fail the syntax check.
     */
    List<Extracted> extractExpressions(String scalar) {
        List<Extracted> expressions = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int open = scalar.indexOf("<+", cursor);
            if (open < 0) {
                return expressions;
            }
            int bodyStart = open + 2;
            String longestParsable = null;
            int longestEnd = -1;
            int lastCandidateEnd = -1;

            for (int close = scalar.indexOf('>', bodyStart); close >= 0;
                 close = scalar.indexOf('>', close + 1)) {
                lastCandidateEnd = close;
                if (parses(scalar.substring(bodyStart, close))) {
                    longestParsable = scalar.substring(bodyStart, close);
                    longestEnd = close;
                }
            }

            if (longestParsable != null) {
                expressions.add(new Extracted(longestParsable, true));
                cursor = longestEnd + 1;
            } else if (lastCandidateEnd >= 0) {
                expressions.add(new Extracted(scalar.substring(bodyStart, lastCandidateEnd), true));
                cursor = lastCandidateEnd + 1;
            } else {
                expressions.add(new Extracted(scalar.substring(bodyStart), false));
                return expressions;
            }
        }
    }

    // ---------------------------------------------------- SYNTAX: the engine

    /** True when the engine can compile the text either way - used for delimiter resolution. */
    private boolean parses(String source) {
        return compilesAsExpression(source, null) == null
                || compilesAsScript(source, null) == null;
    }

    /**
     * Compile as an expression. Returns the engine's error, or null on success.
     *
     * <p>The {@link JexlInfo} makes the engine report the YAML file and line in
     * its message instead of this class's own source position.
     */
    private String compilesAsExpression(String source, JexlInfo where) {
        try {
            jexlEngine.createExpression(where, source);
            return null;
        } catch (JexlException | IllegalArgumentException failure) {
            return describe(failure);
        }
    }

    /**
     * Compile as a script. Scripts accept statements - {@code var} declarations,
     * semicolons, blocks - that {@code createExpression} rejects outright, so
     * this distinguishes "not valid JEXL" from "valid, but not an expression".
     */
    private String compilesAsScript(String source, JexlInfo where) {
        try {
            // The explicit String[] picks the overload that exists in both 3.0
            // and 3.5; 3.0 has no two-argument createScript(JexlInfo, String).
            jexlEngine.createScript(where, source, (String[]) null);
            return null;
        } catch (JexlException | IllegalArgumentException failure) {
            return describe(failure);
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message.trim();
    }

    // --------------------------------------------------- POLICY: the sandbox

    private static final Pattern REFLECTION = Pattern.compile(
            "\\b(getClass|forName|newInstance|getDeclared\\w*|getMethod\\w*|getField\\w*"
            + "|getConstructor\\w*|getSuperclass|getClassLoader|ProcessBuilder)\\b");

    private static final Pattern LOCAL_DECLARATION =
            Pattern.compile("\\b(?:var|let|const)\\s+\\w+\\s*$");

    private static final String NON_ASSIGNMENT_PREFIXES = "!<>=+-*/%^$~&|";
    private static final String NON_ASSIGNMENT_SUFFIXES = "=~>";

    /** Replace the inside of quoted strings with spaces, preserving length. */
    private static String blankQuotedStrings(String source) {
        char[] characters = source.toCharArray();
        char openQuote = 0;
        for (int index = 0; index < characters.length; index++) {
            char current = source.charAt(index);
            if (openQuote != 0) {
                if (current == openQuote && (index == 0 || source.charAt(index - 1) != '\\')) {
                    openQuote = 0;
                } else {
                    characters[index] = ' ';
                }
            } else if (current == '"' || current == '\'') {
                openQuote = current;
            }
        }
        return new String(characters);
    }

    /** Drop {@code //} and slash-star comments so operators inside them are ignored. */
    private static String blankComments(String source) {
        String withoutLineComments = source.replaceAll("//[^\\n]*", " ");
        return withoutLineComments.replaceAll("(?s)/\\*.*?\\*/", " ");
    }

    private static String scannable(String source) {
        return blankComments(blankQuotedStrings(source));
    }

    static String reflectionFlag(String source) {
        Matcher matcher = REFLECTION.matcher(scannable(source));
        return matcher.find()
                ? "reflection via '" + matcher.group(1) + "' - blocked by the Harness sandbox"
                : null;
    }

    static String assignmentFlag(String source) {
        String text = scannable(source);
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '=') {
                continue;
            }
            char previous = index > 0 ? text.charAt(index - 1) : ' ';
            char following = index + 1 < text.length() ? text.charAt(index + 1) : ' ';
            if (NON_ASSIGNMENT_PREFIXES.indexOf(previous) >= 0
                    || NON_ASSIGNMENT_SUFFIXES.indexOf(following) >= 0) {
                continue;
            }
            if (LOCAL_DECLARATION.matcher(text.substring(0, index).stripTrailing() + " ").find()) {
                continue;
            }
            return "single '=' assigns a global - use '==' to compare or 'var' to declare "
                    + "(check the logic: under 3.0 this condition always passed)";
        }
        return null;
    }

    // ------------------------------------------------------------- file pass

    List<Finding> checkFile(Path file, boolean syntaxOnly) throws IOException {
        List<Finding> findings = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (Scalar scalar : collectScalars(reader)) {
                if (!scalar.text().contains("<+")) {
                    continue;
                }
                for (Extracted extracted : extractExpressions(scalar.text())) {
                    String expression = extracted.body();
                    JexlInfo where = new JexlInfo(file.toString(), scalar.line(), 1);

                    // No closing '>' at all. The engine cannot see this - the text
                    // inside usually parses fine - so it is caught structurally.
                    if (!extracted.terminated()) {
                        findings.add(new Finding(scalar.line(), expression, Kind.WRAPPER,
                                "unterminated '<+ ... >' wrapper - no closing '>'"));
                        continue;
                    }

                    // A nested '<+ ... >' never reaches JEXL as written: Harness
                    // resolves the inner expression and substitutes its value
                    // first. Feeding the literal text to the parser would fail on
                    // every engine version - including 3.0 - and that failure says
                    // nothing about 3.5. Report it rather than pretend to judge it.
                    if (expression.contains("<+")) {
                        findings.add(new Finding(scalar.line(), expression, Kind.NESTED,
                                "nested expression - Harness substitutes the inner value before "
                                + "JEXL parses, so no engine verdict is possible here. This is the "
                                + "documented nested-subscript case: quote the inner expression."));
                        continue;
                    }

                    String expressionError = compilesAsExpression(expression, where);
                    if (expressionError != null) {
                        boolean validAsScript = compilesAsScript(expression, where) == null;
                        findings.add(validAsScript
                                ? new Finding(scalar.line(), expression, Kind.SCRIPT,
                                    "valid JEXL, but a statement rather than an expression - "
                                    + "accepted by createScript, rejected by createExpression")
                                : new Finding(scalar.line(), expression, Kind.SYNTAX, expressionError));
                        continue;   // a broken parse makes policy flags meaningless
                    }
                    if (syntaxOnly) {
                        continue;
                    }
                    for (String flag : new String[] {
                            reflectionFlag(expression), assignmentFlag(expression) }) {
                        if (flag != null) {
                            findings.add(new Finding(scalar.line(), expression, Kind.POLICY, flag));
                        }
                    }
                }
            }
        }
        return findings;
    }

    // ------------------------------------------------------------- reporting

    private static void report(Path file, List<Finding> findings, int expressionCount) {
        System.out.printf("%n%s  [%d expression(s), %d finding(s)]%n",
                file, expressionCount, findings.size());
        for (Finding finding : findings) {
            // An unterminated wrapper has no closing '>' in the source; printing
            // one would misrepresent the text the user has to go and find.
            String closer = finding.kind() == Kind.WRAPPER ? "" : ">";
            System.out.printf("  %-7s line %d: <+%s%s%n",
                    finding.kind(), finding.line(), finding.expression(), closer);
            System.out.printf("         %s%n", finding.detail());
        }
    }

    /** Version of the engine actually on the classpath, recorded at build time. */
    private static String engineVersion() {
        try (InputStream stream = JexlCheck.class.getResourceAsStream("/jexl-check.properties")) {
            if (stream != null) {
                Properties properties = new Properties();
                properties.load(stream);
                return properties.getProperty("jexl.version", "unknown");
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "unknown";
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        List<String> paths = new ArrayList<>();
        boolean syntaxOnly = false;
        for (String arg : args) {
            if (arg.equals("--syntax-only")) {
                syntaxOnly = true;
            } else if (!arg.startsWith("--")) {
                paths.add(arg);
            }
        }
        if (paths.isEmpty()) {
            System.err.println(
                    "usage: jexl-check <file.yaml> [more.yaml ...] [--syntax-only]");
            System.exit(2);
        }

        JexlCheck checker = new JexlCheck();
        int syntaxFailures = 0;
        int statementOnly = 0;
        int nestedUnjudged = 0;
        int badWrappers = 0;
        int policyFlags = 0;
        boolean hadFileError = false;

        for (String path : paths) {
            Path file = Path.of(path);
            try {
                List<Finding> findings = checker.checkFile(file, syntaxOnly);
                int expressions = checker.countExpressions(file);
                report(file, findings, expressions);
                syntaxFailures += findings.stream().filter(f -> f.kind() == Kind.SYNTAX).count();
                statementOnly += findings.stream().filter(f -> f.kind() == Kind.SCRIPT).count();
                nestedUnjudged += findings.stream().filter(f -> f.kind() == Kind.NESTED).count();
                badWrappers += findings.stream().filter(f -> f.kind() == Kind.WRAPPER).count();
                policyFlags += findings.stream().filter(f -> f.kind() == Kind.POLICY).count();
            } catch (IOException | RuntimeException failure) {
                System.err.printf("%n%s%n  ERROR: %s%n", file, failure.getMessage());
                hadFileError = true;
            }
        }

        System.out.printf("%n%s%n", "-".repeat(70));
        System.out.printf("Engine: Apache Commons JEXL %s%n", engineVersion());
        System.out.printf("  SYNTAX rejected by the parser  : %d%n", syntaxFailures);
        System.out.printf("  SCRIPT statement-not-expression: %d%n", statementOnly);
        System.out.printf("  NESTED not judgeable by engine : %d%n", nestedUnjudged);
        System.out.printf("  WRAPPER malformed <+ ... >     : %d%n", badWrappers);
        System.out.printf("  POLICY flagged lexically       : %d%n", policyFlags);
        if (policyFlags > 0) {
            System.out.println("  POLICY findings are pattern matches, not engine verdicts -");
            System.out.println("  reflection and '=' parse cleanly on a stock JEXL 3.5 engine.");
        }

        if (hadFileError) {
            System.exit(2);
        }
        // SCRIPT findings are informational: whether the platform compiles <+ ... >
        // as an expression or a script is a host choice this tool cannot observe.
        System.exit(syntaxFailures + nestedUnjudged + badWrappers + policyFlags > 0 ? 1 : 0);
    }

    /** Count expressions in a file, for the per-file header. */
    private int countExpressions(Path file) throws IOException {
        int total = 0;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (Scalar scalar : collectScalars(reader)) {
                if (scalar.text().contains("<+")) {
                    total += extractExpressions(scalar.text()).size();
                }
            }
        }
        return total;
    }
}
