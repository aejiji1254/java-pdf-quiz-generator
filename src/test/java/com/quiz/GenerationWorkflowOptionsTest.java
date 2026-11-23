package com.quiz;

import com.quiz.GenerationOptions.Direction;
import com.quiz.gui.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GenerationWorkflowOptionsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private QuizWorkflow workflow() {
        Path root = temporary.getRoot().toPath();
        return new QuizWorkflow(new QuizRepository(root.resolve("quizzes")), new QuizResultRepository(root.resolve("results")),
                Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC), () -> new QuizGenerator(new Random(7)));
    }
    private Path pdf() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("options.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            var font = PdfTestSupport.koreanFont(document);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(font, 10); stream.newLineAtOffset(45, 760);
                for (Term term : GenerationOptionsTest.terms(8)) {
                    stream.showText("- " + term.term()); stream.newLineAtOffset(0, -18);
                    stream.showText(": " + term.description()); stream.newLineAtOffset(0, -18);
                }
                stream.endText();
            }
            document.save(path.toFile());
        }
        return path;
    }
    @Test public void pdfAndManualReportsIncludeRequestedActualReasonsAndDirections() throws Exception {
        QuizWorkflow workflow = workflow(); Path pdf = pdf();
        ManualTermSet manual = new ManualTermSet(UUID.randomUUID().toString(), pdf.toString(),
                GenerationOptionsTest.terms(8).stream().map(t -> new Term(t.term(), t.description(), 0)).toList());
        for (GenerationOptions options : List.of(new GenerationOptions(3, 5, Direction.MIXED),
                new GenerationOptions(500, 500, Direction.REVERSE), new GenerationOptions(0, 0, Direction.FORWARD))) {
            for (var preview : List.of(workflow.generate(pdf, 1, 1, options), workflow.generate(pdf, options),
                    workflow.generateManual(manual, options))) {
                String text = preview.diagnostics();
                int ox = (int) preview.set().questions().stream().filter(q -> q.type() == SavedQuestion.Type.OX).count();
                int mc = preview.set().questions().size() - ox;
                assertTrue(text.contains("O/X 요청 " + options.oxCount() + "개, 생성 " + ox + "개, 사유:"));
                assertTrue(text.contains("객관식 요청 " + options.multipleChoiceCount() + "개, 생성 " + mc + "개, 사유:"));
                assertTrue(text.contains("정방향 ")); assertTrue(text.contains("역방향 "));
                if (options.multipleChoiceCount() == 500) assertTrue(text.contains("후보 부족"));
                if (options.multipleChoiceCount() == 0) assertTrue(preview.set().questions().isEmpty());
            }
        }
        for (var preview : List.of(workflow.generate(pdf, GenerationOptions.defaults()), workflow.generateManual(manual, GenerationOptions.defaults())))
            assertTrue(preview.diagnostics().contains("O/X 요청 가능한 전부"));
    }
    @Test public void reverseQuestionsUseUnchangedV2StorageAndResultsThroughWrongReview() throws Exception {
        QuizWorkflow workflow = workflow(); Path pdf = pdf();
        var preview = workflow.generate(pdf, new GenerationOptions(0, 5, Direction.REVERSE));
        assertEquals(5, preview.set().questions().size());
        for (SavedQuestion q : preview.set().questions()) {
            assertEquals(SavedQuestion.Type.MULTIPLE_CHOICE, q.type()); assertEquals(Integer.valueOf(1), q.page());
            assertNotNull(q.lineNumber()); assertTrue(q.question().startsWith(QuizGenerator.REVERSE_PROMPT));
        }
        SavedQuizSet saved = workflow.saveReviewed(preview, Set.of());
        Path file = temporary.getRoot().toPath().resolve("quizzes").resolve(saved.id() + ".json");
        assertEquals(saved, workflow.loadQuiz(file));
        QuizSession session = workflow.start(saved);
        session.submit(String.valueOf((session.current().correctIndex() + 1) % 4 + 1));
        Path result = workflow.saveResult(session);
        var review = workflow.loadReview(file, result);
        assertEquals(1, review.wrong().size());
        QuizSession retry = workflow.startReview(review);
        assertTrue(retry.submit(String.valueOf(retry.current().correctIndex() + 1)).correct());
        assertEquals(saved.questions().getFirst().question(), retry.current().question());
    }

    @SuppressWarnings("unchecked")
    @Test public void newGuiForward30OverloadsMatchFrozenPdfAndManualPathsIncludingRandomTail() throws Exception {
        Map<String, Object> baseline;
        try (var stream = getClass().getResourceAsStream("/baseline/mc-seed7-forward30-before.json")) {
            assertNotNull(stream);
            baseline = (Map<String, Object>) QuizJson.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var documents = (List<Map<String, Object>>) baseline.get("documents");
        var samples = OxReviewFixture.samples();
        GenerationOptions options = new GenerationOptions(null, 30, Direction.FORWARD);
        int cases = 0;
        for (int i = 0; i < documents.size(); i++) {
            var doc = documents.get(i); var sample = samples.get(i);
            assertEquals(doc.get("sha256"), McBaselineCapture.sha(sample.pdf()));
            for (var expected : (List<Map<String, Object>>) doc.get("cases")) {
                String path = (String) expected.get("path");
                if (path.startsWith("MAIN")) continue; // 실제 콘솔 세 경로는 변경 없는 McBaselineSnapshotTest가 검증한다.
                Random random = new Random(7);
                QuizWorkflow workflow = new QuizWorkflow(new QuizRepository(temporary.getRoot().toPath().resolve("quizzes")),
                        new QuizResultRepository(temporary.getRoot().toPath().resolve("results")), Clock.systemUTC(), () -> new QuizGenerator(random));
                QuizWorkflow.Preview preview;
                if (path.equals("MANUAL")) {
                    Map<String, Term> unique = new LinkedHashMap<>();
                    for (Term term : sample.extracted().terms()) unique.putIfAbsent(term.term().strip().toLowerCase(Locale.ROOT),
                            new Term(term.term(), term.description(), 0));
                    preview = workflow.generateManual(new ManualTermSet(UUID.randomUUID().toString(),
                            sample.pdf().toAbsolutePath().normalize().toString(), new ArrayList<>(unique.values())), options);
                } else preview = path.equals("PDF_WHOLE") ? workflow.generate(sample.pdf(), options)
                        : workflow.generate(sample.pdf(), sample.first(), sample.last(), options);
                assertEquals(sample.key() + " / 새 GUI " + path, expected,
                        McBaselineCapture.snapshot(path, (String) expected.get("callOrder"), preview.set(), random));
                cases++;
            }
        }
        assertEquals(13, cases);
    }
}
