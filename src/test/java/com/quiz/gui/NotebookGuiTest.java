package com.quiz.gui;

import com.quiz.*;
import static com.quiz.NotebookFixtures.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class NotebookGuiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root() { return temporary.getRoot().toPath(); }
    private static final class Dialogs implements NotebookDialogs {
        final Deque<Boolean> answers = new ArrayDeque<>(); int prompts, errors;
        public boolean continueWithNext(JFrame parent, int remaining) { prompts++; return !answers.isEmpty() && answers.removeFirst(); }
        public void saveError(JFrame parent, Throwable cause) { errors++; }
    }
    private QuizWorkflow workflow(AtomicBoolean fail) throws Exception {
        QuizRepository quizzes = new QuizRepository(root().resolve("quizzes"));
        QuizResultRepository results = new QuizResultRepository(root().resolve("results")) {
            @Override public Path save(QuizResult result, SavedQuizSet set) throws java.io.IOException {
                if (fail.get()) throw new java.io.IOException("주입한 저장 실패");
                return super.save(result, set);
            }
        };
        return new QuizWorkflow(quizzes, results, new ManualTermRepository(root().resolve("manual")),
                Clock.fixed(TIME.plusSeconds(100), ZoneOffset.UTC), QuizGenerator::new);
    }
    private void seed(int count) throws Exception {
        QuizRepository quizzes = new QuizRepository(root().resolve("quizzes"));
        QuizResultRepository results = new QuizResultRepository(root().resolve("results"));
        for (int i = 0; i < count; i++) {
            SavedQuestion q = question("q" + (char)('a' + i), null);
            SavedQuizSet set = set(i == 1 ? "B" : "A" + i, TIME.plusSeconds(i), q);
            quizzes.save(set); results.save(result("seed" + i, set, q, false, TIME.plusSeconds(i)), set);
        }
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object target, String name) {
        try { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return (T) f.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void invoke(QuizMakerFrame frame, String method) {
        try { var m = QuizMakerFrame.class.getDeclaredMethod(method); m.setAccessible(true); m.invoke(frame); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void await(QuizMakerFrame frame) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        AtomicBoolean done = new AtomicBoolean();
        while (!done.get() && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> done.set(field(frame, "notebookWorker") == null));
            if (!done.get()) Thread.sleep(20);
        }
        assertTrue("SwingWorker 완료", done.get());
    }
    private QuizMakerFrame open(QuizWorkflow workflow, Dialogs dialogs) throws Exception {
        AtomicReference<QuizMakerFrame> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            QuizMakerFrame frame = new QuizMakerFrame(workflow, QuizTransferDialogs.swing(), dialogs); ref.set(frame); frame.setVisible(true);
            JButton button = field(frame, "notebookButton"); button.doClick();
            WrongAnswerNotebookPanel panel = field(frame, "notebookPanel");
            assertTrue(panel.status.getText().contains("읽는 중")); assertFalse(panel.start.isEnabled());
        });
        await(ref.get()); return ref.get();
    }
    private static void selectAllAndStart(QuizMakerFrame frame) {
        WrongAnswerNotebookPanel panel = field(frame, "notebookPanel");
        panel.table.setRowSelectionInterval(0, panel.model.getRowCount() - 1); assertTrue(panel.start.isEnabled()); panel.start.doClick();
    }
    private static void complete(QuizMakerFrame frame) {
        QuizSession session = field(frame, "session");
        session.submit(String.valueOf(session.current().correctIndex() + 1)); session.next(); invoke(frame, "finishQuiz");
    }

    @Test public void loadingFilteringSelectionAndStartUseFilteredRepresentative() throws Exception {
        seed(2); var workflow = workflow(new AtomicBoolean()); var dialogs = new Dialogs();
        QuizMakerFrame frame = open(workflow, dialogs);
        String selectedSetId = workflow.notebook().groups().stream().filter(g -> g.matches("과목 B", null)).findFirst().orElseThrow().representative(null).set().id();
        try {
            SwingUtilities.invokeAndWait(() -> {
                WrongAnswerNotebookPanel panel = field(frame, "notebookPanel");
                assertEquals(2, panel.model.getRowCount()); assertFalse(panel.start.isEnabled());
                for (int i = 0; i < panel.subject.getItemCount(); i++) if ("과목 B".equals(panel.subject.getItemAt(i).value())) panel.subject.setSelectedIndex(i);
                assertEquals(1, panel.model.getRowCount());
                String setId = selectedSetId;
                for (int i = 0; i < panel.set.getItemCount(); i++) if (setId.equals(panel.set.getItemAt(i).value())) panel.set.setSelectedIndex(i);
                selectAllAndStart(frame);
                QuizSession session = field(frame, "session"); assertEquals(setId, session.set().id()); assertEquals(1, session.total());
                assertEquals(0, ((NotebookQueue) field(frame, "notebookQueue")).remaining());
            });
        } finally { SwingUtilities.invokeAndWait(frame::dispose); }
    }

    @Test public void emptyStateAndBackDuringLoadDoNotEnableStartOrReopenNotebook() throws Exception {
        QuizMakerFrame frame = open(workflow(new AtomicBoolean()), new Dialogs());
        try {
            SwingUtilities.invokeAndWait(() -> {
                WrongAnswerNotebookPanel panel = field(frame, "notebookPanel"); assertEquals("아직 오답이 없습니다", panel.status.getText());
                assertFalse(panel.start.isEnabled()); invoke(frame, "openNotebook"); invoke(frame, "showNotebookHome");
            });
            await(frame);
            SwingUtilities.invokeAndWait(() -> { JPanel root = field(frame, "root"); assertTrue(root.getComponent(0).isVisible()); });
        } finally { SwingUtilities.invokeAndWait(frame::dispose); }
    }

    @Test public void naturalCompletionCancelEarlyStopAndHomeDiscardRemainingQueue() throws Exception {
        seed(3); var workflow = workflow(new AtomicBoolean()); Dialogs dialogs = new Dialogs();
        QuizMakerFrame frame = open(workflow, dialogs);
        try {
            SwingUtilities.invokeAndWait(() -> {
                selectAllAndStart(frame); assertEquals(2, ((NotebookQueue) field(frame, "notebookQueue")).remaining());
                complete(frame); // 취소/여기서 종료의 기본값 false.
                assertEquals(1, dialogs.prompts); assertEquals(0, ((NotebookQueue) field(frame, "notebookQueue")).remaining());
                assertFalse((Boolean) field(frame, "notebookSession"));
            });
            await(frame);
            SwingUtilities.invokeAndWait(() -> {
                // 저장 후 백그라운드 재집계에서 새 첫 정답이 즉시 반영된다.
                WrongAnswerNotebookPanel panel = field(frame, "notebookPanel");
                assertTrue(java.util.stream.IntStream.range(0, panel.model.getRowCount()).anyMatch(r -> Integer.valueOf(2).equals(panel.model.getValueAt(r, 5))));
                selectAllAndStart(frame); JButton end = field(frame, "endButton"); end.doClick();
                assertEquals(0, ((NotebookQueue) field(frame, "notebookQueue")).remaining()); assertEquals(1, dialogs.prompts);
            });
            await(frame);
            SwingUtilities.invokeAndWait(() -> {
                selectAllAndStart(frame); assertEquals(2, ((NotebookQueue) field(frame, "notebookQueue")).remaining());
                invoke(frame, "showNotebookHome"); assertEquals(0, ((NotebookQueue) field(frame, "notebookQueue")).remaining());
                invoke(frame, "openNotebook");
            });
            await(frame);
            SwingUtilities.invokeAndWait(() -> {
                WrongAnswerNotebookPanel panel = field(frame, "notebookPanel");
                panel.table.setRowSelectionInterval(0, 0); panel.start.doClick();
                assertEquals(0, ((NotebookQueue) field(frame, "notebookQueue")).remaining());
            });
        } finally { SwingUtilities.invokeAndWait(frame::dispose); }
    }

    @Test public void nextSetSaveFailureStopsCurrentSessionAndKeepsAlreadySavedResults() throws Exception {
        seed(3); AtomicBoolean fail = new AtomicBoolean(); var workflow = workflow(fail); Dialogs dialogs = new Dialogs(); dialogs.answers.add(true);
        QuizMakerFrame frame = open(workflow, dialogs);
        try {
            AtomicReference<String> firstSet = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                selectAllAndStart(frame); firstSet.set(((QuizSession) field(frame, "session")).set().id()); complete(frame);
                assertNotEquals(firstSet.get(), ((QuizSession) field(frame, "session")).set().id()); assertEquals(1, dialogs.prompts);
            });
            await(frame); var before = snapshot(root().resolve("results"));
            fail.set(true);
            SwingUtilities.invokeAndWait(() -> {
                QuizSession second = field(frame, "session"); complete(frame);
                assertSame(second, field(frame, "session")); assertEquals(1, dialogs.errors); assertEquals(1, dialogs.prompts);
                assertEquals(1, ((NotebookQueue) field(frame, "notebookQueue")).remaining());
                JButton end = field(frame, "endButton"); assertEquals("결과 저장 다시 시도", end.getText());
            });
            assertEquals(before, snapshot(root().resolve("results")));
        } finally { SwingUtilities.invokeAndWait(frame::dispose); }
    }

    @Test public void queueUsesExactlyTheSameFilteredAndNewestInstancesAsRepresentative() {
        SavedQuestion a = question("a", null), b = linked("b", a);
        var old = set("old", TIME, a); var newer = set("newer", TIME.plusSeconds(1), b);
        var group = aggregate(List.of(old, newer), result("wrong", old, a, false, TIME)).groups().getFirst();
        NotebookQueue queue = new NotebookQueue(); queue.start(List.of(group, group), null);
        assertEquals(1, queue.remaining()); assertEquals(newer, queue.next().set());
        queue.start(List.of(group), old.id()); var selected = queue.next(); assertEquals(old, selected.set()); assertEquals(List.of(a), selected.questions());
        queue.start(List.of(group), null); queue.start(List.of(), null); assertEquals(0, queue.remaining());
    }
    @Test public void masteryControlsHideAndShowRowsAndDisplayExcludedResults() throws Exception {
        seed(1); var workflow = workflow(new AtomicBoolean());
        var group = workflow.notebook().groups().getFirst(); var instance = group.representative(null);
        QuizResultRepository results = new QuizResultRepository(root().resolve("results"));
        for (int i = 1; i <= 3; i++) results.save(result("master" + i, instance.set(), instance.question(), true, TIME.plusSeconds(i)), instance.set());
        Files.writeString(root().resolve("results/broken.json"), "bad");
        QuizMakerFrame frame = open(workflow, new Dialogs());
        try {
            SwingUtilities.invokeAndWait(() -> {
                WrongAnswerNotebookPanel panel = field(frame, "notebookPanel");
                assertEquals(0, panel.model.getRowCount()); assertTrue(panel.warnings.getText().contains("제외된 결과 1개"));
                panel.includeMastered.doClick(); assertEquals(1, panel.model.getRowCount());
                panel.threshold.setValue(4); panel.includeMastered.doClick(); assertEquals(1, panel.model.getRowCount());
                panel.threshold.setValue(3); assertEquals(0, panel.model.getRowCount()); assertFalse(panel.start.isEnabled());
            });
        } finally { SwingUtilities.invokeAndWait(frame::dispose); }
    }
}
