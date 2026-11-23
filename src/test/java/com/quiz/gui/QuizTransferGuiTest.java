package com.quiz.gui;

import com.quiz.*;
import java.awt.Component;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class QuizTransferGuiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Instant AT = Instant.parse("2026-11-22T12:00:00Z");
    private Path root() { return temporary.getRoot().toPath(); }
    private QuizWorkflow workflow(QuizRepository repository) {
        return new QuizWorkflow(repository, new QuizResultRepository(root().resolve("results")), Clock.fixed(AT, ZoneOffset.UTC), QuizGenerator::new);
    }
    private SavedQuizSet sample() {
        String source = "C:\\Users\\Alice\\자료.pdf";
        SavedQuestion q = new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX, "질문", List.of("O", "X"), 0, "해설", source, 1, 3,
                SavedQuestion.Origin.GENERATED, null);
        return new SavedQuizSet(UUID.randomUUID().toString(), source, List.of(q));
    }
    private static final class Dialogs implements QuizTransferDialogs {
        Path importPath, exportPath;
        boolean copy;
        volatile int infos, conflicts;
        volatile QuizTransfer.ImportPlan lastPlan;
        String lastInfo, defaultName;
        final AtomicReference<Throwable> error = new AtomicReference<>();
        public Path exportTarget(Component owner, String name) { assertTrue(SwingUtilities.isEventDispatchThread()); defaultName = name; return exportPath; }
        public Path importSource(Component owner) { assertTrue(SwingUtilities.isEventDispatchThread()); return importPath; }
        public boolean copyConflict(Component owner, QuizTransfer.ImportPlan plan) {
            assertTrue(SwingUtilities.isEventDispatchThread()); lastPlan = plan; conflicts++; return copy;
        }
        public void info(Component owner, String message) { assertTrue(SwingUtilities.isEventDispatchThread()); lastInfo = message; infos++; }
        public void error(Component owner, String message, Throwable error) { this.error.set(error); }
    }
    @SuppressWarnings("unchecked") private static <T> T field(QuizMakerFrame frame, String name) {
        try { var f = QuizMakerFrame.class.getDeclaredField(name); f.setAccessible(true); return (T) f.get(frame); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void load(QuizMakerFrame frame) {
        try { var m = QuizMakerFrame.class.getDeclaredMethod("loadQuiz"); m.setAccessible(true); m.invoke(frame); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            AtomicBoolean done = new AtomicBoolean(); SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(10);
        }
        fail("전송 worker 대기 시간 초과");
    }
    private static Map<String, String> snapshot(Path directory) throws IOException {
        Map<String, String> map = new TreeMap<>();
        try (var files = Files.list(directory)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) map.put(p.getFileName().toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
        }
        return map;
    }

    @Test public void exportButtonThenConflictCancelPreservesLibraryAndCopySelectsNewSet() throws Exception {
        Path directory = root().resolve("quizzes");
        QuizRepository repository = new QuizRepository(directory);
        SavedQuizSet original = sample(); Path originalPath = repository.save(original);
        Files.writeString(directory.resolve("bad.json"), "broken");
        byte[] before = Files.readAllBytes(originalPath);
        Dialogs dialogs = new Dialogs(); dialogs.exportPath = root().resolve("friendly.json"); dialogs.importPath = dialogs.exportPath;
        AtomicReference<QuizMakerFrame> ref = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> { var frame = new QuizMakerFrame(workflow(repository), dialogs); frame.addNotify(); ref.set(frame); load(frame); });
            await(() -> ((QuizLibraryPanel) field(ref.get(), "library")).table.getRowCount() == 1);
            SwingUtilities.invokeAndWait(() -> {
                QuizLibraryPanel panel = field(ref.get(), "library");
                assertFalse(panel.export.isEnabled()); panel.table.setRowSelectionInterval(0, 0); assertTrue(panel.export.isEnabled()); panel.export.doClick();
            });
            await(() -> dialogs.infos == 1);
            assertNull(dialogs.error.get());
            assertEquals(QuizMetadata.exportFileName(original.title()), dialogs.defaultName);
            assertEquals("자료.pdf", repository.loadExternal(dialogs.exportPath).sourcePdf());
            Map<String, String> snapshot = snapshot(directory);
            AtomicReference<String> count = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                QuizLibraryPanel panel = field(ref.get(), "library"); count.set(panel.status.getText()); panel.importFile.doClick();
            });
            await(() -> dialogs.conflicts == 1);
            assertTrue(dialogs.lastPlan.identical());
            assertTrue(dialogs.lastPlan.message().contains("읽을 수 없는 파일 1개는 충돌 검사에서 제외되었습니다"));
            assertEquals(snapshot, snapshot(directory));
            SwingUtilities.invokeAndWait(() -> {
                QuizLibraryPanel panel = field(ref.get(), "library");
                assertEquals(0, panel.table.getSelectedRow()); assertEquals(count.get(), panel.status.getText());
                dialogs.copy = true; panel.importFile.doClick();
            });
            await(() -> {
                QuizLibraryPanel panel = field(ref.get(), "library");
                return panel.table.getRowCount() == 2 && panel.table.getSelectedRow() >= 0;
            });
            SwingUtilities.invokeAndWait(() -> {
                QuizLibraryPanel panel = field(ref.get(), "library");
                assertTrue(String.valueOf(panel.table.getValueAt(panel.table.getSelectedRow(), 0)).endsWith(" (가져옴)"));
                assertTrue(dialogs.lastInfo.contains("충돌 검사에서 제외되었습니다"));
            });
            assertArrayEquals(before, Files.readAllBytes(originalPath)); assertNull(dialogs.error.get());
            assertFalse(Files.exists(root().resolve("results")));
        } finally { if (ref.get() != null) SwingUtilities.invokeAndWait(() -> ref.get().dispose()); }
    }

    @Test public void noConflictImportRefreshesSelectsAndChooserCancelDoesNotChangeAnything() throws Exception {
        QuizRepository repository = new QuizRepository(root().resolve("quizzes"));
        Files.createDirectories(root().resolve("quizzes")); Files.writeString(root().resolve("quizzes/bad.json"), "broken");
        QuizRepository sender = new QuizRepository(root().resolve("sender"));
        SavedQuizSet source = sample(); Path sourcePath = sender.save(source);
        Path external = new QuizTransfer(sender, Clock.systemUTC()).export(sourcePath, root().resolve("received.json"));
        Dialogs dialogs = new Dialogs();
        AtomicReference<QuizMakerFrame> ref = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> { var frame = new QuizMakerFrame(workflow(repository), dialogs); frame.addNotify(); ref.set(frame); load(frame); });
            await(() -> ((QuizLibraryPanel) field(ref.get(), "library")).importFile.isEnabled());
            Map<String, String> before = snapshot(root().resolve("quizzes"));
            SwingUtilities.invokeAndWait(() -> ((QuizLibraryPanel) field(ref.get(), "library")).importFile.doClick());
            assertEquals(before, snapshot(root().resolve("quizzes"))); assertEquals(0, dialogs.infos);
            SwingUtilities.invokeAndWait(() -> { dialogs.importPath = external; ((QuizLibraryPanel) field(ref.get(), "library")).importFile.doClick(); });
            await(() -> { QuizLibraryPanel p = field(ref.get(), "library"); return p.table.getRowCount() == 1 && p.table.getSelectedRow() == 0; });
            assertNull(dialogs.error.get()); assertEquals(0, dialogs.conflicts);
            assertTrue(dialogs.lastInfo.contains("읽을 수 없는 파일 1개는 충돌 검사에서 제외되었습니다"));
            SavedQuizSet imported = repository.list().entries().getFirst().set();
            assertEquals(source.id(), imported.id()); assertEquals(AT, imported.createdAt());
            Map<String, String> after = snapshot(root().resolve("quizzes"));
            SwingUtilities.invokeAndWait(() -> ((QuizLibraryPanel) field(ref.get(), "library")).export.doClick()); // 저장 위치 선택 취소
            assertEquals(after, snapshot(root().resolve("quizzes")));
            SwingUtilities.invokeAndWait(() -> assertEquals(0, ((QuizLibraryPanel) field(ref.get(), "library")).table.getSelectedRow()));
        } finally { if (ref.get() != null) SwingUtilities.invokeAndWait(() -> ref.get().dispose()); }
    }
}
