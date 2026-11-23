package com.quiz.gui;

import com.quiz.*;
import java.awt.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GenerationOptionsGuiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private QuizWorkflow workflow() {
        return new QuizWorkflow(new QuizRepository(temporary.getRoot().toPath().resolve("quizzes")),
                new QuizResultRepository(temporary.getRoot().toPath().resolve("results")), Clock.systemUTC(), () -> new QuizGenerator(new Random(7)));
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object target, String name) {
        try { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return (T) f.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void set(Object target, String name, Object value) {
        try { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void invoke(Object target, String name) {
        try { var m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Test public void controlsDefaultMixedAllOxAndZeroManualPreviewDiagnosticsOnEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuizMakerFrame frame = new QuizMakerFrame(workflow());
            try {
                GenerationOptionsPanel pdf = field(frame, "pdfOptions"), manual = field(frame, "manualOptions");
                assertEquals(GenerationOptions.defaults(), pdf.options()); assertEquals(pdf.options(), manual.options());
                manual.allOx.doClick(); assertTrue(manual.oxCount.isEnabled());
                manual.oxCount.setValue(0); manual.mcCount.setValue(0);
                set(frame, "manualSet", new ManualTermSet(UUID.randomUUID().toString(), "",
                        GenerationOptionsTest.terms(4).stream().map(t -> new Term(t.term(), t.description(), 0)).toList()));
                invoke(frame, "generateManual");
                QuizWorkflow.Preview preview = field(frame, "preview"); assertTrue(preview.set().questions().isEmpty());
                JTextArea report = field(frame, "generationDetails");
                assertTrue(report.getText().contains("요청 0개, 생성 0개"));
                manual.oxCount.setValue(3); manual.mcCount.setValue(5);
                invoke(frame, "generateManual");
                preview = field(frame, "preview"); assertFalse(preview.set().questions().isEmpty());
                assertTrue(report.getText().contains("객관식 요청 5개, 생성 4개"));
                assertTrue(report.getText().contains("정방향 2개, 역방향 2개"));
                assertEquals(GenerationOptions.defaults(), pdf.options());
            } finally { frame.dispose(); }
        });
    }

    @Test public void pdfWorkerUsesOptionsAndShowsZeroCountDiagnostics() throws Exception {
        QuizMakerFrame[] ref = new QuizMakerFrame[1];
        java.util.concurrent.atomic.AtomicReference<SwingWorker<?, ?>> worker = new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            QuizMakerFrame frame = new QuizMakerFrame(workflow()); ref[0] = frame; frame.setVisible(true);
            set(frame, "selectedPdf", Path.of("sample.pdf")); set(frame, "selectedPageCount", 23);
            JSpinner lastPage = field(frame, "lastPage"); lastPage.setModel(new SpinnerNumberModel(23, 1, 23, 1));
            GenerationOptionsPanel options = field(frame, "pdfOptions");
            options.allOx.doClick(); options.oxCount.setValue(0); options.mcCount.setValue(0);
            invoke(frame, "generate");
            worker.set(field(frame, "generationWorker"));
        });
        try {
            assertNotNull(worker.get());
            worker.get().get(20, java.util.concurrent.TimeUnit.SECONDS);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            boolean[] done = {false};
            while (!done[0] && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> done[0] = field(ref[0], "generationWorker") == null);
                if (!done[0]) Thread.sleep(30);
            }
            SwingUtilities.invokeAndWait(() -> {
                assertTrue("PDF worker 완료", done[0]);
                QuizWorkflow.Preview preview = field(ref[0], "preview"); assertNotNull(preview);
                assertTrue(preview.set().questions().isEmpty());
                JTextArea report = field(ref[0], "generationDetails");
                assertTrue(report.getText().contains("요청 0개, 생성 0개"));
                assertTrue(report.getText().contains("정방향 0개, 역방향 0개"));
            });
        } finally { SwingUtilities.invokeAndWait(() -> ref[0].dispose()); }
    }

    @Test public void longMultilineMcPromptStaysWholeScrollableInPlayAndEditor() throws Exception {
        String description = "가".repeat(89) + "\n" + "나".repeat(90); // 설명 180자
        String prompt = QuizGenerator.REVERSE_PROMPT + "\n\n설명: " + description;
        SavedQuestion q = new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.MULTIPLE_CHOICE,
                prompt, List.of("알파", "베타", "감마", "델타"), 0, "정답 해설", "source.pdf", 1, 2);
        SwingUtilities.invokeAndWait(() -> {
            QuizMakerFrame frame = new QuizMakerFrame(workflow());
            try {
                frame.setVisible(true);
                set(frame, "currentSet", new SavedQuizSet(UUID.randomUUID().toString(), "source.pdf", List.of(q)));
                invoke(frame, "beginQuiz"); frame.validate();
                JTextArea text = field(frame, "questionText"); JScrollPane ox = field(frame, "oxDescriptionScroll");
                assertEquals(prompt, text.getText()); assertFalse(ox.isVisible()); assertTrue(text.getLineWrap());
                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, text);
                assertNotNull(scroll); assertTrue(scroll.getViewport().getHeight() > 0);
                text.setCaretPosition(text.getDocument().getLength());
                assertEquals(prompt.length(), text.getCaretPosition());
                assertLastCharacterAccessible(text);
                QuestionEditorPanel editor = new QuestionEditorPanel(q);
                JDialog dialog = new JDialog(frame); dialog.setContentPane(editor); dialog.pack(); dialog.setVisible(true);
                try {
                    assertEquals(prompt, editor.question.getText()); assertTrue(editor.question.getLineWrap());
                    assertNotNull(SwingUtilities.getAncestorOfClass(JScrollPane.class, editor.question));
                    editor.question.setCaretPosition(prompt.length());
                    assertLastCharacterAccessible(editor.question);
                    assertEquals(q, editor.editedQuestion());
                } finally { dialog.dispose(); }
            } finally { frame.dispose(); }
        });
    }

    private static void assertLastCharacterAccessible(JTextArea area) {
        try {
            Rectangle last = area.modelToView2D(area.getDocument().getLength()).getBounds();
            area.scrollRectToVisible(last);
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, area);
            assertTrue("스크롤하여 마지막 글자까지 볼 수 있다", scroll.getViewport().getViewRect().intersects(last));
        } catch (javax.swing.text.BadLocationException e) { throw new AssertionError(e); }
    }
}
