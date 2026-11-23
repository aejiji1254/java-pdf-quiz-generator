package com.quiz;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MainTest {
    @Test
    public void interactiveShowsLimitMessageThenPromptsAgain() throws Exception {
        Path empty = temporary.newFile("empty.pdf").toPath();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(empty.toFile());
        }
        Path valid = createPdf("valid-after-limit.pdf", "- First", ": One", "- Second", ": Two");
        String output = interactive(empty + "\n" + valid + "\n0\n");
        assertTrue(output.contains("추출된 글자가 부족합니다"));
        assertEquals(2, output.split("PDF 경로를 입력하세요", -1).length - 1);
        assertTrue(output.contains("용어 2개를 찾았습니다"));
    }
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void printsTermsWarningsAndPerPdfSummaryAndContinuesAfterFailure() throws Exception {
        Path valid = temporary.newFile("valid.pdf").toPath();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PdfTestSupport.koreanFont(document), 12);
                content.newLineAtOffset(50, 700);
                content.showText("가".repeat(100));
                content.newLineAtOffset(0, -20);
                for (String line : new String[] {"- Learning", ": A change in behavior", "123"}) {
                    content.showText(line);
                    content.newLineAtOffset(0, -20);
                }
                content.endText();
            }
            document.save(valid.toFile());
        }
        Path missing = temporary.getRoot().toPath().resolve("missing.pdf");
        Path invalid = temporary.newFile("invalid.pdf").toPath();
        Files.writeString(invalid, "not a PDF");
        String[] output = capture(missing.toString(), invalid.toString(), valid.toString());
        assertTrue(output[0].contains("[페이지 1] Learning: A change in behavior"));
        assertTrue(output[0].contains("숫자만 있는 줄 | 원문: 123"));
        assertTrue(output[0].contains("요약: " + valid + " | Term 수: 1"));
        assertTrue(output[0].contains("요약: " + missing + " | 추출 실패"));
        assertTrue(output[0].contains("요약: " + invalid + " | 추출 실패"));
        assertTrue(output[1].contains("PDF 파일이 없습니다"));
        assertTrue(output[1].contains("유효한 PDF인지"));
    }

    @Test
    public void promptsForPdfWhenNoPathsGivenAndExitsAtEndOfInput() throws Exception {
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream(new byte[0]));
            String[] output = capture();
            assertTrue(output[0].contains("PDF 경로를 입력하세요(파일을 끌어다 놓아도 됩니다)"));
            assertTrue(output[1].isEmpty());
        } finally {
            System.setIn(original);
        }
    }

    @Test
    public void interactiveRetriesInvalidFilesAndInsufficientTerms() throws Exception {
        Path few = createPdf("few.pdf", "- Only", ": Definition");
        Path valid = createPdf("with spaces.PDF", "- First", ": One", "- Second", ": Two");
        Path invalid = temporary.newFile("broken.pdf").toPath();
        Files.writeString(invalid, "invalid PDF");
        String output = interactive("wrong.txt\n" + temporary.getRoot() + "/missing.pdf\n"
                + invalid + "\n" + few + "\n  \"" + valid + "\"  \nabc\n9\n2\n0\n");
        assertTrue(output.contains(".pdf 파일 경로를 입력하세요"));
        assertTrue(output.contains("파일이 없습니다"));
        assertTrue(output.contains("PDF를 열 수 없습니다"));
        assertTrue(output.contains("용어 1개를 찾았습니다"));
        assertTrue(output.contains("내용이 부족합니다"));
        assertTrue(output.contains("용어 2개를 찾았습니다"));
        assertTrue(output.contains("숫자로 입력하세요"));
        assertTrue(output.contains("0~7 사이"));
        assertTrue(output.contains("[페이지 1] First: One"));
    }

    @Test
    public void interactiveRunsQuizAndOpensAnotherPdf() throws Exception {
        Path first = createPdf("first.pdf", "- First", ": One clear description",
                "- Second", ": Two distinct descriptions");
        Path next = createPdf("next.pdf", "- Third", ": Three clear descriptions",
                "- Fourth", ": Four distinct descriptions");
        String output = interactive("'" + first + "'\n1\nX\n3\n"
                + next + "\n2\n0\n");
        assertTrue(output.contains("문제 1/1:"));
        assertTrue(output.contains("해설:"));
        assertTrue(output.contains("점수: 1/1"));
        assertTrue(output.contains("[페이지 1] Third: Three clear descriptions"));
        assertEquals(2, output.split("용어 2개를 찾았습니다", -1).length - 1);
    }

    @Test
    public void oxArgumentStillRunsQuiz() throws Exception {
        Path pdf = createPdf("quiz.pdf", "- First", ": One clear description",
                "- Second", ": Two distinct descriptions");
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream("O\nX\nO\nX\n".getBytes(StandardCharsets.UTF_8)));
            String[] output = capture("--ox", pdf.toString());
            assertTrue(output[0].contains("점수:"));
            assertFalse(output[0].contains("[Term 목록]"));
        } finally {
            System.setIn(original);
        }
    }

    @Test
    public void interactiveOffersMultipleChoiceWithoutChangingOxMenu() throws Exception {
        Path pdf = createPdf("multiple.pdf", "- First", ": First clear description",
                "- Second", ": Second clear description", "- Third", ": Third clear description",
                "- Fourth", ": Fourth clear description");
        String output = interactive(pdf + "\n4\n0\n");
        assertTrue(output.contains("1.O/X 퀴즈"));
        assertTrue(output.contains("4.객관식 퀴즈"));
        assertTrue(output.contains("옳은 것은?"));
        assertTrue(output.contains("건너뜀:"));
        assertTrue(output.contains("퀴즈를 중단합니다"));
    }

    @Test
    public void savesThenFreshRunLoadsAndAnswersWithoutPdf() throws Exception {
        Path pdf = createPdf("stored.pdf", "- First", ": First clear description",
                "- Second", ": Second clear description", "- Third", ": Third clear description",
                "- Fourth", ": Fourth clear description");
        Path folder = temporary.getRoot().toPath().resolve("quizzes");
        QuizRepository firstRepository = new QuizRepository(folder);
        ByteArrayOutputStream firstBytes = new ByteArrayOutputStream();
        Main.runInteractive(new Scanner(pdf + "\n5\n0\n"),
                new PrintStream(firstBytes, true, StandardCharsets.UTF_8), firstRepository);
        assertTrue(firstBytes.toString(StandardCharsets.UTF_8).contains("문제 세트 저장:"));
        Path saved;
        try (var files = Files.list(folder)) {
            saved = files.findFirst().orElseThrow();
        }
        QuizRepository nextRepository = new QuizRepository(folder);
        SavedQuizSet restored = nextRepository.load(saved.getFileName().toString());
        assertTrue(restored.questions().stream().anyMatch(q -> q.type() == SavedQuestion.Type.OX));
        assertTrue(restored.questions().stream().anyMatch(q -> q.type() == SavedQuestion.Type.MULTIPLE_CHOICE));
        StringBuilder answers = new StringBuilder();
        for (SavedQuestion question : restored.questions()) {
            answers.append(question.type() == SavedQuestion.Type.OX
                    ? question.options().get(question.correctIndex()) : question.correctIndex() + 1).append('\n');
        }
        ByteArrayOutputStream secondBytes = new ByteArrayOutputStream();
        Main.runInteractive(new Scanner("load\n" + saved.getFileName() + "\n" + answers + "0\n"),
                new PrintStream(secondBytes, true, StandardCharsets.UTF_8), nextRepository);
        String output = secondBytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("문제 세트 불러옴:"));
        assertTrue(output.contains(restored.questions().get(0).question()));
        assertTrue(output.contains("점수: " + restored.questions().size() + "/" + restored.questions().size()));
    }

    @Test
    public void corruptSavedFileDoesNotCloseConsole() throws Exception {
        Path folder = temporary.getRoot().toPath().resolve("quizzes");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("bad.json"), "{broken");
        Path pdf = createPdf("after-bad.pdf", "- First", ": One", "- Second", ": Two");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Main.runInteractive(new Scanner("load\nbad.json\n" + pdf + "\n0\n"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), new QuizRepository(folder));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("문제 세트를 불러올 수 없습니다"));
        assertTrue(output.contains("용어 2개를 찾았습니다"));
    }

    @Test
    public void reloadsStudyResultAndReviewsOnlyWrongQuestion() throws Exception {
        Path quizFolder = temporary.getRoot().toPath().resolve("quizzes");
        QuizRepository quizzes = new QuizRepository(quizFolder);
        String source = "강의 PDF.pdf";
        SavedQuestion ox = new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX,
                "캐시 설명?", List.of("O", "X"), 0, "캐시 해설", source, 1, 2);
        SavedQuestion choice = new SavedQuestion(UUID.randomUUID().toString(),
                SavedQuestion.Type.MULTIPLE_CHOICE, "역할은?",
                List.of("가", "나", "다", "라"), 2, "역할 해설", source, 2, 3);
        SavedQuizSet set = new SavedQuizSet(UUID.randomUUID().toString(), source, List.of(ox, choice));
        Path quizFile = quizzes.save(set);
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        Main.runInteractive(new Scanner("load\n" + quizFile.getFileName() + "\nX\n3\n"),
                new PrintStream(firstOutput, true, StandardCharsets.UTF_8), quizzes);
        assertTrue(firstOutput.toString(StandardCharsets.UTF_8).contains("정답 1, 오답 1"));
        Path resultFolder = quizFolder.resolveSibling("results");
        Path firstFile;
        try (var files = Files.list(resultFolder)) {
            firstFile = files.findFirst().orElseThrow();
        }
        String before = Files.readString(firstFile);
        QuizResultRepository results = new QuizResultRepository(resultFolder);
        QuizResult first = results.load(firstFile.toString(), set);
        assertEquals(List.of(ox), first.wrongQuestions(set));
        ByteArrayOutputStream reviewOutput = new ByteArrayOutputStream();
        Main.runInteractive(new Scanner("review\n" + quizFile.getFileName() + "\n"
                + firstFile.getFileName() + "\nO\n"),
                new PrintStream(reviewOutput, true, StandardCharsets.UTF_8),
                new QuizRepository(quizFolder));
        String displayed = reviewOutput.toString(StandardCharsets.UTF_8);
        assertTrue(displayed.contains("학습 결과 불러옴"));
        assertTrue(displayed.contains("오답 복습: 1개"));
        assertTrue(displayed.contains("점수: 1/1"));
        assertFalse(displayed.contains("문제 2/2"));
        assertEquals(before, Files.readString(firstFile));
        try (var files = Files.list(resultFolder)) {
            assertEquals(2, files.count());
        }
        try (var files = Files.list(resultFolder)) {
            Path secondFile = files.filter(path -> !path.equals(firstFile)).findFirst().orElseThrow();
            QuizResult review = results.load(secondFile.toString(), set);
            assertEquals(first.id(), review.reviewOfResultId());
            assertEquals(ox.id(), review.answers().get(0).questionId());
            assertEquals(1, review.correctCount());
        }
    }

    @Test
    public void damagedResultShowsErrorAndConsoleContinues() throws Exception {
        Path quizFolder = temporary.getRoot().toPath().resolve("quizzes");
        QuizRepository quizzes = new QuizRepository(quizFolder);
        SavedQuizSet set = new SavedQuizSet(UUID.randomUUID().toString(), "강의.pdf", List.of());
        Path quizFile = quizzes.save(set);
        Path resultFolder = quizFolder.resolveSibling("results");
        Files.createDirectories(resultFolder);
        Files.writeString(resultFolder.resolve("bad.json"), "{broken");
        Path pdf = createPdf("after-result-error.pdf", "- First", ": One", "- Second", ": Two");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Main.runInteractive(new Scanner("review\n" + quizFile.getFileName()
                + "\nbad.json\n" + pdf + "\n0\n"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), quizzes);
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("학습 결과를 불러올 수 없습니다"));
        assertTrue(output.contains("용어 2개를 찾았습니다"));
    }

    private String interactive(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Main.runInteractive(new Scanner(text), new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new QuizRepository(temporary.getRoot().toPath().resolve("quizzes")));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private Path createPdf(String name, String... lines) throws Exception {
        Path pdf = temporary.newFile(name).toPath();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PdfTestSupport.koreanFont(document), 12);
                content.newLineAtOffset(50, 700);
                content.showText("가".repeat(100));
                content.newLineAtOffset(0, -20);
                for (String line : lines) {
                    content.showText(line);
                    content.newLineAtOffset(0, -20);
                }
                content.endText();
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private static String[] capture(String... args) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(stdout);
            System.setErr(stderr);
            Main.main(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new String[] {out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8)};
    }
}
