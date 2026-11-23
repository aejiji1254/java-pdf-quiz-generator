package com.quiz;

import static com.quiz.NotebookFixtures.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class NotebookRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root() { return temporary.getRoot().toPath(); }
    private QuizRepository quizzes() { return new QuizRepository(root().resolve("quizzes")); }
    private QuizResultRepository results() { return new QuizResultRepository(root().resolve("results")); }

    @Test public void mixedV1V2AndImportedSetsAggregateWithoutChangingAnyListsBytesOrTimes() throws Exception {
        LegacyFixtures.copy("quiz-v1.json", root().resolve("quizzes").resolve(LegacyFixtures.SET_ID + ".json"));
        LegacyFixtures.copy("result-v1.json", root().resolve("results").resolve(LegacyFixtures.RESULT_ID + ".json"));
        SavedQuestion q = question("local", null); SavedQuizSet local = set("local", TIME, q);
        quizzes().save(local); results().save(result("local", local, q, false, TIME), local);
        QuizRepository sender = new QuizRepository(root().resolve("sender"));
        SavedQuizSet incoming = set("incoming", TIME, question("incoming", null)); Path source = sender.save(incoming);
        Path exported = new QuizTransfer(sender, Clock.fixed(TIME, ZoneOffset.UTC)).export(source, root().resolve("shared.json"));
        QuizTransfer transfer = new QuizTransfer(quizzes(), Clock.fixed(TIME.plusSeconds(10), ZoneOffset.UTC));
        SavedQuizSet imported = transfer.commit(transfer.inspect(exported), false).set();
        results().save(result("imported", imported, imported.questions().getFirst(), false, TIME), imported);
        var beforeQuizzes = snapshot(root().resolve("quizzes")); var beforeResults = snapshot(root().resolve("results"));
        var note = new WrongAnswerNotebook().load(quizzes(), results());
        assertEquals(4, note.groups().size()); assertEquals(0, note.excludedResults());
        assertEquals(beforeQuizzes, snapshot(root().resolve("quizzes"))); assertEquals(beforeResults, snapshot(root().resolve("results")));
        assertTrue(note.groups().stream().anyMatch(g -> g.representative(null).set().id().equals(imported.id())));
        SavedQuizSet legacy = note.groups().stream().flatMap(g -> g.instances().stream()).map(WrongAnswerNotebook.Instance::set)
                .filter(s -> s.id().equals(LegacyFixtures.SET_ID)).findFirst().orElseThrow();
        assertEquals("원본 강의.pdf", legacy.title()); assertEquals("", legacy.subject());
        assertTrue(imported.sourcePdf().endsWith("notes.pdf"));
    }

    @Test public void skipsDamagedWrongGradeMissingSetFilenameUtf8OversizeAndUnknownFields() throws Exception {
        SavedQuestion q = question("good", null); SavedQuizSet set = set("good", TIME, q); quizzes().save(set);
        Path good = results().save(result("good", set, q, false, TIME), set);
        String json = Files.readString(good);
        Files.writeString(root().resolve("results/broken.json"), "[");
        for (String label : List.of("grade", "missing", "extra", "version", "omitted")) {
            String newId = id("bad-" + label); String content = json.replace(id("result-good"), newId);
            content = switch (label) {
                case "grade" -> content.replace("\"correct\":false", "\"correct\":true");
                case "missing" -> content.replace(set.id(), id("absent-set"));
                case "extra" -> content.replace("\"version\":1", "\"extra\":1,\"version\":1");
                case "version" -> content.replace("\"version\":1", "\"version\":99");
                default -> content.replace("\"sourcePdf\":", "\"other\":");
            };
            Files.writeString(root().resolve("results").resolve(newId + ".json"), content);
        }
        Files.write(root().resolve("results/bad-utf8.json"), new byte[] {(byte)0xc3, (byte)0x28});
        Files.write(root().resolve("results/large.json"), new byte[5 * 1024 * 1024 + 1]);
        Files.writeString(root().resolve("results/wrong-filename.json"), json);
        Files.writeString(root().resolve("results/deep.json"), "[".repeat(10000));
        Files.writeString(root().resolve("results/ignored.tmp"), "not JSON");
        Files.createDirectory(root().resolve("results/folder.json"));
        Files.writeString(root().resolve("quizzes/damaged.json"), "bad");
        var before = snapshot(root());
        var note = new WrongAnswerNotebook().load(quizzes(), results());
        assertEquals(1, note.groups().size()); assertEquals(10, note.excludedResults());
        assertEquals(5, note.reasons().size()); assertEquals(1, note.unreadableSets());
        assertEquals(before, snapshot(root()));
        assertEquals(result("good", set, q, false, TIME), results().load(good.toString(), set));
    }

    @Test public void twoHundredResultsReadEachSetOnceAndReportActualDurationWithoutTimeAssertion() throws Exception {
        SavedQuestion q = question("many", null); SavedQuizSet set = set("many", TIME, q); quizzes().save(set);
        for (int i = 0; i < 200; i++) results().save(result("many" + i, set, q, i % 2 == 0, TIME.plusSeconds(i)), set);
        Map<String, Integer> reads = new LinkedHashMap<>();
        QuizRepository counting = new QuizRepository(root().resolve("quizzes")) {
            @Override public SavedQuizSet load(String filename) throws java.io.IOException {
                SavedQuizSet loaded = super.load(filename); reads.merge(loaded.id(), 1, Integer::sum); return loaded;
            }
        };
        var before = snapshot(root()); long started = System.nanoTime();
        var note = new WrongAnswerNotebook().load(counting, results());
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        System.out.println("NOTEBOOK_200_RESULTS elapsedMs=" + elapsed + " setReads=" + reads);
        assertEquals(Map.of(set.id(), 1), reads);
        assertEquals(1, note.groups().size()); assertEquals(200, note.groups().getFirst().attempts());
        assertEquals(100, note.groups().getFirst().wrongCount()); assertEquals(before, snapshot(root()));
        // 필터와 정복 기준 변경은 캐시만 사용한다.
        note.visible(null, null, true, 1, WrongAnswerNotebook.Order.MOST_WRONG);
        note.visible(set.subject(), set.id(), false, 10, WrongAnswerNotebook.Order.RECENT_WRONG);
        assertEquals(Map.of(set.id(), 1), reads);
    }

    @Test public void absentFoldersStayAbsentAndSavingANewSessionAppearsInNextAggregation() throws Exception {
        assertTrue(new WrongAnswerNotebook().load(quizzes(), results()).groups().isEmpty());
        assertFalse(Files.exists(root().resolve("quizzes"))); assertFalse(Files.exists(root().resolve("results")));
        SavedQuestion q = question("flow", null); SavedQuizSet set = set("flow", TIME, q); quizzes().save(set);
        results().save(result("first", set, q, false, TIME.minusSeconds(1)), set);
        var workflow = workflow(root()); var old = new WrongAnswerNotebook().load(quizzes(), results());
        var before = snapshot(root().resolve("results"));
        var group = old.groups().getFirst(); var instance = group.representative(null);
        var session = workflow.startNotebook(instance.set(), List.of(instance.question())); session.submit("1");
        workflow.saveResult(session);
        var updated = workflow.notebook(); assertEquals(2, updated.groups().getFirst().attempts());
        assertEquals(1, updated.groups().getFirst().streak());
        var after = snapshot(root().resolve("results"));
        for (var entry : before.entrySet()) if (entry.getValue().kind().equals("file")) assertEquals(entry.getValue(), after.get(entry.getKey()));
        try { workflow.startNotebook(set, List.of()); fail("빈 선택 거부"); } catch (IllegalArgumentException expected) { }
    }
}
