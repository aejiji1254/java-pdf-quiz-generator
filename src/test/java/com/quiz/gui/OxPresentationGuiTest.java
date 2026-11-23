package com.quiz.gui;

import com.quiz.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class OxPresentationGuiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @SuppressWarnings("unchecked") private static <T> T field(QuizMakerFrame frame, String name) {
        try { var f = QuizMakerFrame.class.getDeclaredField(name); f.setAccessible(true); return (T) f.get(frame); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void open(QuizMakerFrame frame, SavedQuizSet set) {
        try {
            var f = QuizMakerFrame.class.getDeclaredField("currentSet"); f.setAccessible(true); f.set(frame, set);
            var m = QuizMakerFrame.class.getDeclaredMethod("beginQuiz"); m.setAccessible(true); m.invoke(frame);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @Test public void oldAndNewSavedOxKeepSplitDisplayGradingAndStoredBytes() throws Exception {
        QuizRepository repository = new QuizRepository(temporary.getRoot().toPath().resolve("quizzes"));
        QuizWorkflow workflow = new QuizWorkflow(repository, new QuizResultRepository(temporary.getRoot().toPath().resolve("results")), Clock.systemUTC(), QuizGenerator::new);
        String source = "legacy.pdf";
        SavedQuestion legacy = new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX,
                "원본 자료에서 다음 설명은 '알파'에 직접 연결된 설명이 아니다.\n\n설명: 분리된 자료를 보관하는 공간",
                List.of("O", "X"), 1, "알파 — 분리된 자료를 보관하는 공간", source, 2, 3);
        SavedQuizSet old = new SavedQuizSet(UUID.randomUUID().toString(), source, List.of(legacy));
        var extracted = new TermExtractor().extract(List.of("- 알파\n: 분리된 자료를 보관하는 공간\n- 베타\n: 다른 고유 동작을 수행하는 과정"));
        var ox = new QuizGenerator(new Random(7)).generateOxDetailed(extracted);
        SavedQuizSet current = SavedQuizSet.fromGenerated("current.pdf", extracted, ox.questions(), List.of());
        assertEquals(1, current.questions().size());
        for (SavedQuizSet set : List.of(old, current)) {
            Path file = repository.save(set);
            byte[] before = Files.readAllBytes(file);
            SavedQuizSet loaded = repository.load(file.toString());
            assertEquals(set, loaded);
            SwingUtilities.invokeAndWait(() -> {
                QuizMakerFrame frame = new QuizMakerFrame(workflow);
                try {
                    open(frame, loaded);
                    SavedQuestion q = loaded.questions().getFirst();
                    String[] parts = q.question().split("\n\n", 2);
                    JTextArea text = field(frame, "questionText"), description = field(frame, "oxDescription");
                    JScrollPane scroll = field(frame, "oxDescriptionScroll");
                    assertEquals(parts[0], text.getText()); assertEquals(parts[1], description.getText()); assertTrue(scroll.isVisible());
                    QuizSession session = field(frame, "session");
                    assertTrue(session.submit("X").correct());
                    assertEquals(List.of("O", "X"), q.options());
                } finally { frame.dispose(); }
            });
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(set, repository.load(file.toString()));
        }
    }
}
