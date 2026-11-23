package com.quiz;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class StructuredEvaluationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void evaluatesOutsideFolderWithOnlyFileNameHashAndAllTermsAndNeverChangesInput() throws Exception {
        Path input = temporary.newFolder("unseen").toPath();
        Files.copy(Path.of("samples/c_behavior.pdf.pdf"), input.resolve("lecture.pdf"));
        Files.writeString(input.resolve("damaged.pdf"), "broken"); Files.writeString(input.resolve("ignore.txt"), "ignore");
        var before = NotebookFixtures.snapshot(input);
        String report = StructuredExtractionEvaluation.evaluate(input);
        assertFalse(report.contains(input.toString())); assertFalse(report.contains(input.toAbsolutePath().toString()));
        assertTrue(report.contains("lecture.pdf [876e97b15a1d]")); assertTrue(report.contains("데이터(data 또는 자료)"));
        assertTrue(report.contains("첫 페이지:제목행")); assertTrue(report.contains("실패 1개"));
        assertEquals(before, NotebookFixtures.snapshot(input));
    }
    @Test public void noPropertyDoesNothingAndDocsRequireExplicitFlag() throws Exception {
        Properties old = (Properties) System.getProperties().clone(); PrintStream previous = System.out;
        Path output = temporary.getRoot().toPath().resolve("output.md");
        var docsBefore = NotebookFixtures.snapshot(Path.of("docs"));
        try (var bytes = new ByteArrayOutputStream(); var capture = new PrintStream(bytes)) {
            System.clearProperty("quiz.eval.dir"); System.clearProperty("quiz.writeDocs"); System.setOut(capture);
            new StructuredExtractionEvaluation().evaluateIfRequested(); assertEquals("", bytes.toString());
            StructuredExtractionEvaluation.writeIfRequested("report", output); assertFalse(Files.exists(output));
            System.setProperty("quiz.writeDocs", "true"); StructuredExtractionEvaluation.writeIfRequested("report", output);
            assertEquals("report", Files.readString(output));
        } finally { System.setOut(previous); System.setProperties(old); }
        assertEquals(docsBefore, NotebookFixtures.snapshot(Path.of("docs")));
    }
    @Test public void rejectsDataSubfoldersAndUnreadableLocationsWithoutLeakingPaths() throws Exception {
        Path data = temporary.newFolder("data").toPath(), nested = Files.createDirectory(data.resolve("nested"));
        for (Path p : List.of(data, nested)) try {
            StructuredExtractionEvaluation.checkedDirectory(p, data); fail("data 내부 평가 금지");
        } catch (IOException expected) { assertFalse(expected.getMessage().contains(data.toString())); }
        Path outside = temporary.newFolder("outside").toPath(); assertEquals(outside.toRealPath(), StructuredExtractionEvaluation.checkedDirectory(outside, data));
        try { StructuredExtractionEvaluation.checkedDirectory(outside.resolve("absent"), data); fail("없는 폴더 거부"); }
        catch (IOException expected) { assertFalse(expected.getMessage().contains(outside.toString())); }
    }
}
