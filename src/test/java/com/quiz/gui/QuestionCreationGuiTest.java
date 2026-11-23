package com.quiz.gui;

import com.quiz.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class QuestionCreationGuiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private QuizWorkflow workflow() {
        return new QuizWorkflow(new QuizRepository(temporary.getRoot().toPath().resolve("quizzes")),
                new QuizResultRepository(temporary.getRoot().toPath().resolve("results")),
                java.time.Clock.systemUTC(), QuizGenerator::new);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(QuizMakerFrame frame, String name) {
        try {
            var field = QuizMakerFrame.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(frame);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void show(QuizMakerFrame frame, QuizWorkflow.Preview p, SavedQuizSet original) {
        try {
            var method = QuizMakerFrame.class.getDeclaredMethod("showPreview", QuizWorkflow.Preview.class, SavedQuizSet.class);
            method.setAccessible(true);
            method.invoke(frame, p, original);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void accept(QuizMakerFrame frame, QuizWorkflow.Preview p) {
        try {
            var method = QuizMakerFrame.class.getDeclaredMethod("acceptAddedQuestion", QuizWorkflow.Preview.class);
            method.setAccessible(true);
            method.invoke(frame, p);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @Test public void zeroQuestionPdfOpensReviewAndCanAddSelectCountAndSave() throws Exception {
        QuizWorkflow w = workflow();
        Path pdf = DirectQuestionTest.plainPdf(temporary.getRoot().toPath().resolve("zero.pdf"));
        QuizWorkflow.Preview zero = w.generate(pdf);
        assertTrue(zero.set().questions().isEmpty());
        AtomicReference<QuizWorkflow.Preview> added = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            QuizMakerFrame frame = new QuizMakerFrame(w);
            try {
                show(frame, zero, null);
                JPanel root = field(frame, "root");
                assertTrue(root.getComponent(1).isVisible());
                assertFalse(root.getComponent(0).isVisible());
                DefaultListModel<SavedQuestion> model = field(frame, "reviewModel");
                assertEquals(0, model.size());
                AtomicInteger attempt = new AtomicInteger();
                AtomicInteger errors = new AtomicInteger();
                AtomicReference<QuestionEditorPanel> firstEditor = new AtomicReference<>();
                QuizMakerFrame.createQuestion(w, zero, () -> SavedQuestion.Type.MULTIPLE_CHOICE, editor -> {
                    if (attempt.getAndIncrement() == 0) {
                        firstEditor.set(editor);
                        assertEquals("", editor.question.getText());
                        assertEquals("", editor.explanation.getText());
                        assertTrue(editor.options.stream().allMatch(option -> option.getText().isEmpty()));
                        editor.question.setText("직접 질문");
                        for (int i = 0; i < 4; i++) editor.options.get(i).setText("선택 " + i);
                    } else {
                        assertSame(firstEditor.get(), editor);
                        assertEquals("직접 질문", editor.question.getText());
                        editor.explanation.setText("직접 해설");
                        editor.answer.setSelectedIndex(3);
                    }
                    return JOptionPane.OK_OPTION;
                }, error -> errors.incrementAndGet(), updated -> {
                    accept(frame, updated);
                    added.set(updated);
                });
                assertEquals(1, errors.get());
                assertEquals(1, model.size());
                JList<SavedQuestion> list = field(frame, "reviewList");
                assertEquals(0, list.getSelectedIndex());
                assertEquals(added.get().set().questions().getLast(), list.getSelectedValue());
                JLabel count = field(frame, "reviewCount");
                assertTrue(count.getText().contains("4지선다 1개"));
                assertTrue(count.getText().contains("O/X 0개"));
            } finally { frame.dispose(); }
        });
        SavedQuizSet saved = w.saveReviewed(added.get(), Set.of());
        assertEquals(saved, w.loadQuiz(temporary.getRoot().toPath().resolve("quizzes").resolve(saved.id() + ".json")));
    }

    @Test public void cancelAtEitherDialogOrAfterValidationErrorLeavesBothReviewModesUntouched() throws Exception {
        QuizWorkflow w = workflow();
        QuizWorkflow.Preview p = w.addQuestion(DirectQuestionTest.empty("source.pdf"), SavedQuestion.Type.OX,
                "기존 질문", List.of("O", "X"), 0, "기존 해설");
        SwingUtilities.invokeAndWait(() -> {
            QuizMakerFrame frame = new QuizMakerFrame(w);
            try {
                for (boolean revision : List.of(false, true)) {
                    show(frame, p, revision ? p.set() : null);
                    DefaultListModel<SavedQuestion> model = field(frame, "reviewModel");
                    JList<SavedQuestion> list = field(frame, "reviewList");
                    JLabel count = field(frame, "reviewCount");
                    String beforeCount = count.getText();
                    SavedQuestion beforeSelected = list.getSelectedValue();
                    for (int cancelAt = 0; cancelAt < 3; cancelAt++) {
                        final int stage = cancelAt;
                        AtomicInteger attempts = new AtomicInteger();
                        AtomicInteger errors = new AtomicInteger();
                        QuizMakerFrame.createQuestion(w, p, () -> stage == 0 ? null : SavedQuestion.Type.OX,
                                editor -> {
                                    editor.question.setText("작성 중인 질문");
                                    if (stage == 2 && attempts.getAndIncrement() == 0) return JOptionPane.OK_OPTION;
                                    assertEquals("작성 중인 질문", editor.question.getText());
                                    return JOptionPane.CANCEL_OPTION;
                                }, error -> errors.incrementAndGet(), updated -> accept(frame, updated));
                        assertSame(p, field(frame, "preview"));
                        assertEquals(1, model.size());
                        assertEquals(p.set().questions().getFirst(), model.get(0));
                        assertEquals(beforeSelected, list.getSelectedValue());
                        assertEquals(0, list.getSelectedIndex());
                        assertEquals(beforeCount, count.getText());
                        assertEquals(stage == 2 ? 1 : 0, errors.get());
                    }
                }
            } finally { frame.dispose(); }
        });
    }
}
