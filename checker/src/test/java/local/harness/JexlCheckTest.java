package local.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import local.harness.JexlCheck.Extracted;
import local.harness.JexlCheck.Finding;
import local.harness.JexlCheck.Kind;

/**
 * Tests for {@link JexlCheck}.
 *
 * <p>These deliberately exercise the real JEXL engine rather than mocking it -
 * the whole point of the tool is that verdicts come from the shipped parser, so
 * a mocked parser would test nothing worth testing. That does mean the expected
 * results in {@link EngineVerdicts} are tied to the engine version in
 * {@code pom.xml}; see the README for what changes under JEXL 3.0.
 */
class JexlCheckTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------- helpers

    private List<Finding> check(String yaml) throws IOException {
        return check(yaml, false);
    }

    private List<Finding> check(String yaml, boolean syntaxOnly) throws IOException {
        Path file = tempDir.resolve("pipeline.yaml");
        Files.writeString(file, yaml);
        return new JexlCheck().checkFile(file, syntaxOnly);
    }

    /** The expression bodies extracted from a single YAML scalar value. */
    private List<String> bodiesOf(String scalar) {
        return new JexlCheck().extractExpressions(scalar).stream().map(Extracted::body).toList();
    }

    private static Finding only(List<Finding> findings) {
        assertEquals(1, findings.size(), () -> "expected exactly one finding, got " + findings);
        return findings.get(0);
    }

    // ------------------------------------------------------------ extraction

    @Nested
    @DisplayName("Delimiter resolution: '>' is both a closer and an operator")
    class DelimiterResolution {

        @Test
        void plainExpressionIsExtracted() {
            assertEquals(List.of("pipeline.name"), bodiesOf("<+pipeline.name>"));
        }

        @Test
        void greaterThanIsNotMistakenForTheClosingDelimiter() {
            assertEquals(List.of("count > 3"), bodiesOf("<+count > 3>"));
        }

        @Test
        void greaterOrEqualIsNotMistakenForTheClosingDelimiter() {
            assertEquals(List.of("count >= 3"), bodiesOf("<+count >= 3>"));
        }

        @Test
        void fatArrowLambdaSurvivesExtraction() {
            assertEquals(List.of("items.filter(x => x.enabled)"),
                    bodiesOf("<+items.filter(x => x.enabled)>"));
        }

        @Test
        void twoExpressionsOnOneLineAreSplitCorrectly() {
            assertEquals(List.of("a.b", "c.d"), bodiesOf("<+a.b> and <+c.d>"));
        }

        @Test
        void chainedComparisonKeepsItsWholeBody() {
            assertEquals(List.of("count > 3 && name == \"x\""),
                    bodiesOf("<+count > 3 && name == \"x\">"));
        }

        @Test
        void textWithoutExpressionsYieldsNothing() {
            assertTrue(bodiesOf("just a plain string").isEmpty());
        }

        @Test
        void unterminatedWrapperIsMarkedUnterminated() {
            List<Extracted> extracted = new JexlCheck().extractExpressions("<+pipeline.name");
            assertEquals(1, extracted.size());
            assertFalse(extracted.get(0).terminated());
            assertEquals("pipeline.name", extracted.get(0).body());
        }

        @Test
        void closedWrapperIsMarkedTerminated() {
            List<Extracted> extracted = new JexlCheck().extractExpressions("<+pipeline.name>");
            assertTrue(extracted.get(0).terminated());
        }
    }

    // ----------------------------------------------------------- YAML layer

    @Nested
    @DisplayName("YAML semantics come from the parser, not a text scan")
    class YamlSemantics {

        @Test
        @DisplayName("an expression inside a YAML comment is invisible")
        void commentedOutExpressionIsNotAFinding() throws IOException {
            assertTrue(check("""
                    pipeline:
                      # disabled: <+stage.shouldRun="Yes">
                      name: demo
                    """).isEmpty());
        }

        @Test
        void expressionsInsideSequencesAreFound() throws IOException {
            List<Finding> findings = check("""
                    variables:
                      - value: <+a=="x"?[""]:"q">
                    """);
            assertEquals(Kind.SYNTAX, only(findings).kind());
        }

        @Test
        void findingsReportTheSourceLine() throws IOException {
            List<Finding> findings = check("""
                    pipeline:
                      name: demo
                      broken: <+a=="x"?[""]:"q">
                    """);
            assertEquals(3, only(findings).line());
        }

        @Test
        void foldedBlockScalarsAreFoldedBeforeChecking() throws IOException {
            assertTrue(check("""
                    description: >
                      <+pipeline.name>
                    """).isEmpty());
        }

        @Test
        void multipleDocumentsAreAllChecked() throws IOException {
            List<Finding> findings = check("""
                    first: <+a=="x"?[""]:"q">
                    ---
                    second: <+b=="y"?[""]:"q">
                    """);
            assertEquals(2, findings.size());
        }

        @Test
        @DisplayName("YAML unescaping is applied before JEXL sees the text")
        void doubledSingleQuotesReachJexlAsInvalidSyntax() throws IOException {
            // YAML turns '' into a literal ', which JEXL does not accept as an
            // escape - so this is a true positive, not a quoting artefact.
            List<Finding> findings = check(
                    "value: '<+msg == ''it''''s fine''>'\n");
            assertEquals(Kind.SYNTAX, only(findings).kind());
        }
    }

    // ------------------------------------------------------- engine verdicts

    @Nested
    @DisplayName("SYNTAX findings are the engine's own verdict")
    class EngineVerdicts {

        @Test
        @DisplayName("ternary followed by '[' is rejected by JEXL 3.5")
        void ternaryBracketIsRejected() throws IOException {
            Finding finding = only(check("value: <+BUILD_ENVS==\"dev\"?[\"\"]:\"qa\">\n"));
            assertEquals(Kind.SYNTAX, finding.kind());
            // The engine reports the orphaned ':' - '?[' was consumed as
            // null-safe array access, which is the documented mechanism.
            assertTrue(finding.detail().contains("parsing error"),
                    () -> "expected a parser message, got: " + finding.detail());
        }

        @Test
        void validExpressionProducesNoFinding() throws IOException {
            assertTrue(check("value: <+pipeline.variables.region == \"us-east-1\">\n").isEmpty());
        }

        @Test
        void quotedNestedSubscriptIsAccepted() throws IOException {
            assertTrue(check("value: <+pipeline.variables[\"region\"]>\n").isEmpty());
        }

        @Test
        @DisplayName("'var x = y' is valid JEXL but a statement, not an expression")
        void varDeclarationIsReportedAsScriptNotSyntax() throws IOException {
            assertEquals(Kind.SCRIPT, only(check("value: <+var e = env.identifier>\n")).kind());
        }

        @Test
        @DisplayName("nested '<+ >' cannot be judged by any engine")
        void nestedExpressionIsNotGivenAVerdict() throws IOException {
            Finding finding = only(check("value: <+pipeline.variables[<+stage.variables.t>]>\n"));
            assertEquals(Kind.NESTED, finding.kind());
        }

        @Test
        void unterminatedWrapperIsReportedStructurally() throws IOException {
            // The body parses fine, so only the missing '>' makes this a finding.
            assertEquals(Kind.WRAPPER, only(check("value: \"<+pipeline.name\"\n")).kind());
        }
    }

    // --------------------------------------------------------- policy flags

    @Nested
    @DisplayName("POLICY flags are lexical, so their negatives matter most")
    class PolicyFlags {

        @Test
        void reflectionIsFlagged() {
            assertNotNull(JexlCheck.reflectionFlag("''.getClass().forName(\"java.lang.Runtime\")"));
        }

        @Test
        void ordinaryMethodCallsAreNotFlaggedAsReflection() {
            assertNull(JexlCheck.reflectionFlag("pipeline.variables.get(\"region\")"));
        }

        @Test
        void singleEqualsIsFlaggedAsAssignment() {
            assertNotNull(JexlCheck.assignmentFlag("stage.variables.shouldRun=\"Yes\""));
        }

        @Test
        void equalityComparisonIsNotFlagged() {
            assertNull(JexlCheck.assignmentFlag("stage.variables.shouldRun == \"Yes\""));
        }

        @Test
        void relationalOperatorsAreNotFlagged() {
            assertNull(JexlCheck.assignmentFlag("count >= 3 && other <= 5 && x != 1"));
        }

        @Test
        void localDeclarationIsNotFlagged() {
            assertNull(JexlCheck.assignmentFlag("var ENVIRONMENT = env.identifier"));
        }

        @Test
        void fatArrowIsNotFlaggedAsAssignment() {
            assertNull(JexlCheck.assignmentFlag("items.filter(x => x.enabled)"));
        }

        @Test
        @DisplayName("an '=' inside a string literal is not an assignment")
        void equalsInsideStringIsNotFlagged() {
            assertNull(JexlCheck.assignmentFlag("msg == \"key=value\""));
        }

        @Test
        @DisplayName("an '=' inside a JEXL comment is not an assignment")
        void equalsInsideLineCommentIsNotFlagged() {
            assertNull(JexlCheck.assignmentFlag("a == b // note: x = y"));
        }

        @Test
        void equalsInsideBlockCommentIsNotFlagged() {
            assertNull(JexlCheck.assignmentFlag("a == b /* x = y */"));
        }

        @Test
        void policyFindingsAreSuppressedBySyntaxOnly() throws IOException {
            String yaml = "value: <+stage.variables.shouldRun=\"Yes\">\n";
            assertEquals(Kind.POLICY, only(check(yaml)).kind());
            assertTrue(check(yaml, true).isEmpty());
        }
    }
}
