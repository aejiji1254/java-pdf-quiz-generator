package com.quiz.gui;

import com.quiz.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class QuizLibraryGuiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Instant NOW = Instant.parse("2026-11-22T01:02:03Z");

    private static void invoke(QuizMakerFrame frame, String method) {
        try {
            var m = QuizMakerFrame.class.getDeclaredMethod(method); m.setAccessible(true); m.invoke(frame);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") private static <T> T field(QuizMakerFrame frame, String name) {
        try {
            var f = QuizMakerFrame.class.getDeclaredField(name); f.setAccessible(true); return (T) f.get(frame);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void awaitEdt(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            AtomicBoolean result = new AtomicBoolean();
            SwingUtilities.invokeAndWait(() -> result.set(done.getAsBoolean()));
            if (result.get()) return;
            Thread.sleep(10);
        }
        fail("SwingWorker 완료 대기 시간 초과");
    }

    @Test public void asynchronousLoadingDisplaysListThenSelectionOpensReview() throws Exception {
        Path folder = temporary.getRoot().toPath().resolve("quizzes");
        CountDownLatch scanStarted = new CountDownLatch(1), allowScan = new CountDownLatch(1);
        QuizRepository repository = new QuizRepository(folder) {
            @Override public Listing list() throws java.io.IOException {
                assertFalse(SwingUtilities.isEventDispatchThread());
                scanStarted.countDown();
                try {
                    if (!allowScan.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("목록 테스트 대기 시간 초과");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                return super.list();
            }
        };
        Path legacy = LegacyFixtures.copy("quiz-v1.json", folder.resolve(LegacyFixtures.SET_ID + ".json"));
        Files.setLastModifiedTime(legacy, FileTime.from(NOW.minusSeconds(100)));
        QuizWorkflow w = new QuizWorkflow(repository, new QuizResultRepository(folder.resolveSibling("results")),
                Clock.fixed(NOW, ZoneOffset.UTC), QuizGenerator::new);
        var generated = w.generateManual(ManualTermSet.empty("").add("캐시", "반복 데이터를 보관하는 공간"));
        SavedQuizSet saved = w.saveReviewed(generated, java.util.Set.of(), "새 이름", "과목");
        Files.writeString(folder.resolve("bad.json"), "{broken");
        AtomicReference<QuizMakerFrame> reference = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                QuizMakerFrame frame = new QuizMakerFrame(w); frame.addNotify(); reference.set(frame);
                invoke(frame, "loadQuiz");
                QuizLibraryPanel library = field(frame, "library");
                assertTrue(library.isVisible());
                assertTrue(library.status.getText().contains("읽는 중"));
                assertEquals(0, library.table.getRowCount());
                assertFalse(library.open.isEnabled());
            });
            assertTrue(scanStarted.await(5, TimeUnit.SECONDS));
            allowScan.countDown();
            awaitEdt(() -> ((QuizLibraryPanel) field(reference.get(), "library")).table.getRowCount() == 2);
            SwingUtilities.invokeAndWait(() -> {
                QuizLibraryPanel library = field(reference.get(), "library");
                assertEquals("새 이름", library.table.getValueAt(0, 0));
                assertEquals("과목", library.table.getValueAt(0, 1));
                assertTrue(library.status.getText().contains("읽을 수 없는 파일 1개"));
                assertFalse(library.open.isEnabled());
                library.table.setRowSelectionInterval(0, 0);
                assertTrue(library.open.isEnabled());
                library.open.doClick();
            });
            awaitEdt(() -> field(reference.get(), "preview") != null);
            SwingUtilities.invokeAndWait(() -> {
                QuizMakerFrame frame = reference.get();
                QuizWorkflow.Preview preview = field(frame, "preview");
                assertEquals(saved, preview.set());
                assertEquals(saved, field(frame, "revisionOriginal"));
                JPanel root = field(frame, "root");
                assertTrue(root.getComponent(1).isVisible());
            });
        } finally {
            allowScan.countDown();
            if (reference.get() != null) SwingUtilities.invokeAndWait(() -> reference.get().dispose());
        }
    }

    @Test public void metadataFormSuggestsSubjectsValidatesAndCancelReturnsNoSaveValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuizMetadataPanel panel = new QuizMetadataPanel("기본 제목", "기존 과목", List.of("기존 과목", "다른 과목"));
            assertTrue(panel.subject.isEditable());
            assertEquals(2, panel.subject.getItemCount());
            assertEquals("기존 과목", panel.values().subject());
            AtomicInteger attempts = new AtomicInteger(), errors = new AtomicInteger();
            QuizMetadataPanel.Values cancelled = panel.request(editor -> {
                if (attempts.getAndIncrement() == 0) { editor.title.setText(" "); return JOptionPane.OK_OPTION; }
                assertEquals(" ", editor.title.getText());
                return JOptionPane.CANCEL_OPTION;
            }, error -> errors.incrementAndGet());
            assertNull(cancelled);
            assertEquals(1, errors.get());
            for (String bad : List.of("줄바꿈\n", "제로폭\u200B", "방향\u202E")) {
                panel.title.setText(bad);
                assertEquals(bad, panel.title.getText());
                try { panel.values(); fail("이름 제어문자 거부"); }
                catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isBlank()); }
                panel.title.setText("이름");
                panel.subject.getEditor().setItem(bad);
                assertEquals(bad, panel.subject.getEditor().getItem());
                try { panel.values(); fail("과목 제어문자 거부"); }
                catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isBlank()); }
                panel.subject.getEditor().setItem("");
            }
            panel.title.setText(" 새 제목 "); panel.subject.getEditor().setItem(" 새 과목 ");
            assertEquals(new QuizMetadataPanel.Values("새 제목", "새 과목"), panel.values());
        });
    }
}
