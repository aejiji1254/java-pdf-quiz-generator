package com.quiz;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class QuizTransferTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    static final Instant AT = Instant.parse("2026-11-22T12:00:00Z");
    private Path root() { return temporary.getRoot().toPath(); }
    private QuizRepository repo(String name) { return new QuizRepository(root().resolve(name)); }
    private QuizTransfer transfer(QuizRepository repository) { return new QuizTransfer(repository, Clock.fixed(AT, ZoneOffset.UTC)); }
    private Path output(String name) throws IOException { Path dir = root().resolve("exports"); Files.createDirectories(dir); return dir.resolve(name); }
    static SavedQuizSet sample(String source) {
        return new SavedQuizSet(UUID.randomUUID().toString(), source, List.of(
                new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.MULTIPLE_CHOICE, "질문", List.of("가", "나", "정답", "라"),
                        2, "해설", source, 7, 9, SavedQuestion.Origin.GENERATED, UUID.randomUUID().toString()),
                new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX, "OX 질문", List.of("O", "X"),
                        0, "직접 해설", source, null, null, SavedQuestion.Origin.MANUAL, null)),
                "퀴즈 이름", "과목", AT.minusSeconds(100), UUID.randomUUID().toString());
    }
    private static Map<String, String> snapshot(Path directory) throws IOException {
        Map<String, String> files = new TreeMap<>();
        if (Files.exists(directory)) try (var paths = Files.walk(directory)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) files.put(directory.relativize(p).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
        }
        return files;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> json(Path file) throws IOException {
        return (Map<String, Object>) QuizJson.parse(Files.readString(file));
    }
    private static void sourcesHaveNoDirectories(SavedQuizSet set) {
        for (String source : java.util.stream.Stream.concat(java.util.stream.Stream.of(set.sourcePdf()),
                set.questions().stream().map(SavedQuestion::sourcePdf)).toList()) {
            assertFalse(source.contains("\\")); assertFalse(source.contains("/")); assertFalse(source.contains("C:"));
        }
    }

    @Test public void exportAndImportPreserveAllQuizFieldsAndScrubBothSourcesIdempotently() throws Exception {
        int i = 0;
        for (String source : List.of("C:\\Users\\Alice\\자료.pdf", "/home/alice/자료.pdf", "C:자료.pdf",
                "수동 입력 (원문 대조 필요: C:\\Users\\Alice\\자료.pdf)", "수동 입력 (원문 대조 필요: /home/alice/자료.pdf)",
                "수동 입력 (PDF 미지정)")) {
            QuizRepository library = repo("library" + i), receiver = repo("receiver" + i);
            SavedQuizSet original = sample(source);
            Path stored = library.save(original);
            byte[] before = Files.readAllBytes(stored);
            Path exported = transfer(library).export(stored, output("friendly" + i + ".json"));
            assertEquals(2, json(exported).get("version"));
            SavedQuizSet portable = library.loadExternal(exported);
            sourcesHaveNoDirectories(portable);
            assertEquals(QuizTransfer.portable(original), portable);
            if (source.startsWith("수동 입력 (원문")) assertEquals("수동 입력 (원문 대조 필요: 자료.pdf)", portable.sourcePdf());
            QuizTransfer service = transfer(receiver);
            var plan = service.inspect(exported);
            assertFalse(plan.hasConflict());
            var imported = service.commit(plan, false);
            assertEquals(AT, imported.set().createdAt());
            assertEquals(portable.forSave(portable.title(), portable.subject(), AT), imported.set());
            assertEquals(imported.set(), receiver.load(imported.path().toString()));
            assertFalse(receiver.containsSetId(original.derivedFromSetId())); // 외부 참조가 없어도 성공한다.
            Path reexported = service.export(imported.path(), output("again" + i + ".json"));
            assertEquals(imported.set(), receiver.loadExternal(reexported));
            assertTrue(QuizTransfer.sameContent(original, receiver.loadExternal(reexported)));
            assertArrayEquals(before, Files.readAllBytes(stored));
            i++;
        }
    }

    @Test public void v1ExportIsV2AndNeverChangesOriginalOrExistingResultBytes() throws Exception {
        QuizRepository library = repo("legacy");
        Path original = LegacyFixtures.copy("quiz-v1.json", library.directory().resolve(LegacyFixtures.SET_ID + ".json"));
        Path result = LegacyFixtures.copy("result-v1.json", root().resolve("results").resolve(LegacyFixtures.RESULT_ID + ".json"));
        byte[] originalBytes = Files.readAllBytes(original), resultBytes = Files.readAllBytes(result);
        Path out = transfer(library).export(original, output("shared-v1.json"));
        assertEquals(2, json(out).get("version"));
        SavedQuizSet model = library.loadExternal(out);
        assertEquals(SavedQuestion.Origin.UNKNOWN, model.questions().getFirst().origin());
        assertEquals(SavedQuestion.Origin.MANUAL, model.questions().get(1).origin());
        assertArrayEquals(originalBytes, Files.readAllBytes(original));
        assertArrayEquals(resultBytes, Files.readAllBytes(result));
        new QuizResultRepository(result.getParent()).load(result.toString(), library.load(original.toString()));
    }

    @Test public void sameIdSameContentSameIdDifferentContentAndQuestionOnlyCollisionsCancelOrCopy() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            QuizRepository library = repo("collision" + scenario);
            SavedQuizSet local = sample("C:\\Users\\Alice\\자료.pdf");
            Path localPath = library.save(local);
            SavedQuizSet incoming = QuizTransfer.portable(local);
            if (scenario == 1) {
                SavedQuestion q = incoming.questions().getFirst();
                incoming = incoming.withQuestions(List.of(q.edited(q.question(), List.of("나", "가", "정답", "라"), q.correctIndex(), q.explanation()), incoming.questions().getLast()));
            } else if (scenario == 2) {
                SavedQuestion q = incoming.questions().getLast();
                SavedQuestion unique = new SavedQuestion(UUID.randomUUID().toString(), q.type(), q.question(), q.options(), q.correctIndex(),
                        q.explanation(), q.sourcePdf(), q.page(), q.lineNumber(), q.origin(), UUID.randomUUID().toString());
                incoming = new SavedQuizSet(UUID.randomUUID().toString(), incoming.sourcePdf(), List.of(incoming.questions().getFirst(), unique),
                        "😀".repeat(60), incoming.subject(), incoming.createdAt(), incoming.derivedFromSetId());
            }
            Path file = library.exportFile(incoming, output("conflict" + scenario + ".json"));
            QuizTransfer service = transfer(library);
            var plan = service.inspect(file);
            assertTrue(plan.hasConflict());
            assertEquals(scenario != 2, plan.setIdExists());
            assertEquals(scenario == 0, plan.identical());
            assertTrue(plan.message().contains(scenario == 0 ? "이미 가져온 세트와 동일합니다" : scenario == 1 ? "내용이 다릅니다" : "문항 ID"));
            Map<String, String> before = snapshot(library.directory());
            try { service.commit(plan, false); fail("충돌 미승인 저장 금지"); }
            catch (QuizTransfer.ConflictException expected) { assertTrue(expected.plan().hasConflict()); }
            assertEquals(before, snapshot(library.directory()));
            var imported = service.commit(plan, true);
            SavedQuizSet copy = imported.set();
            assertNotEquals(incoming.id(), copy.id());
            assertNotEquals(local.id(), copy.id());
            assertTrue(Collections.disjoint(incoming.questions().stream().map(SavedQuestion::id).toList(),
                    copy.questions().stream().map(SavedQuestion::id).toList()));
            assertEquals(local.id(), copy.derivedFromSetId()); // 수신 파일의 이전 원본 링크를 의도적으로 덮어쓴다.
            assertNotEquals(incoming.derivedFromSetId(), copy.derivedFromSetId());
            assertTrue(copy.title().endsWith(" (가져옴)"));
            assertTrue(copy.title().codePointCount(0, copy.title().length()) <= 60);
            for (int q = 0; q < incoming.questions().size(); q++) {
                SavedQuestion from = incoming.questions().get(q), to = copy.questions().get(q);
                assertNotEquals(from.id(), to.id());
                assertEquals(from.origin(), to.origin());
                assertEquals(scenario == 2 && q == 1 ? null : from.id(), to.derivedFromQuestionId());
                assertEquals(from.options(), to.options()); assertEquals(from.correctIndex(), to.correctIndex());
            }
            assertEquals(AT, copy.createdAt());
            assertEquals(copy, library.load(imported.path().toString()));
            assertEquals(local, library.load(localPath.toString()));
        }
    }

    @Test public void sameContentExcludesOnlyClockAndNormalizesBothSources() {
        SavedQuizSet original = sample("C:\\Alice\\자료.pdf");
        SavedQuizSet portable = QuizTransfer.portable(original).forSave(original.title(), original.subject(), AT);
        assertTrue(QuizTransfer.sameContent(original, portable));
        assertEquals(portable, QuizTransfer.portable(portable));
        assertFalse(QuizTransfer.sameContent(original, portable.forSave("다른 제목", portable.subject(), AT)));
        assertFalse(QuizTransfer.sameContent(original, portable.forSave(portable.title(), "다른 과목", AT)));
        assertFalse(QuizTransfer.sameContent(original, portable.withQuestions(portable.questions().reversed())));
        SavedQuestion q = portable.questions().getFirst();
        SavedQuestion changed = new SavedQuestion(q.id(), q.type(), q.question(), q.options(), q.correctIndex(), q.explanation(),
                q.sourcePdf(), q.page(), q.lineNumber(), SavedQuestion.Origin.UNKNOWN, q.derivedFromQuestionId());
        assertFalse(QuizTransfer.sameContent(original, portable.withQuestions(List.of(changed, portable.questions().getLast()))));
        SavedQuizSet parentChanged = new SavedQuizSet(portable.id(), portable.sourcePdf(), portable.questions(), portable.title(), portable.subject(), AT, UUID.randomUUID().toString());
        assertFalse(QuizTransfer.sameContent(original, parentChanged));
    }

    @Test public void unreadableFilesAreReportedInConflictAndNonConflictSuccessWithoutBlocking() throws Exception {
        QuizRepository library = repo("library");
        Files.createDirectories(library.directory());
        Files.writeString(library.directory().resolve("bad.json"), "broken");
        QuizRepository sender = repo("sender");
        SavedQuizSet set = sample("資料.pdf");
        Path source = sender.save(set), file = transfer(sender).export(source, output("warnings.json"));
        QuizTransfer service = transfer(library);
        var plan = service.inspect(file);
        assertFalse(plan.hasConflict()); assertEquals(1, plan.unreadableCount());
        var imported = service.commit(plan, false);
        assertTrue(imported.warning().contains("읽을 수 없는 파일 1개는 충돌 검사에서 제외되었습니다"));
        var again = service.inspect(file);
        assertTrue(again.identical()); assertTrue(again.message().contains("충돌 검사에서 제외"));
    }

    @Test public void externalValidationDoesNotRelaxInternalLoadAndInvalidInputNeverWritesAnything() throws Exception {
        QuizRepository sender = repo("sender"), receiver = repo("receiver");
        SavedQuizSet set = sample("자료.pdf");
        Path valid = sender.exportFile(set, output("not-a-uuid.json"));
        assertEquals(set, receiver.loadExternal(valid));
        try { receiver.load(valid.toString()); fail("외부 경로를 load로 읽으면 안 됩니다."); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("폴더")); }
        Files.createDirectories(receiver.directory());
        Path wrongName = receiver.directory().resolve("wrong-name.json");
        Files.copy(valid, wrongName);
        try { receiver.load(wrongName.toString()); fail("파일명과 ID 일치 검사 유지"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("ID")); }
        Files.delete(wrongName);
        Map<String, String> before = snapshot(receiver.directory());
        List<String> broken = new ArrayList<>(List.of("{broken", "[".repeat(30_000) + "]".repeat(30_000),
                "{\"a\":".repeat(30_000) + "0" + "}".repeat(30_000)));
        for (String mutation : List.of("version", "missing", "extra", "origin", "title", "subject")) {
            Map<String, Object> json = json(valid);
            if (mutation.equals("version")) json.put("version", 999);
            if (mutation.equals("missing")) json.remove("subject");
            if (mutation.equals("extra")) json.put("extra", true);
            if (mutation.equals("title")) json.put("title", "제목\n");
            if (mutation.equals("subject")) json.put("subject", "과목\u200B");
            if (mutation.equals("origin")) {
                @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) ((List<?>) json.get("questions")).getFirst();
                first.put("origin", "INVALID");
            }
            broken.add(QuizJson.write(json));
        }
        int i = 0;
        for (String content : broken) {
            Path bad = output("invalid" + i++ + ".json"); Files.writeString(bad, content);
            reject(receiver, bad); assertEquals(before, snapshot(receiver.directory()));
        }
        Path badUtf = output("invalid-utf8.json"); Files.write(badUtf, new byte[] {(byte) 0xC3, 0x28}); reject(receiver, badUtf);
        Path tooBig = output("too-big.json"); Files.writeString(tooBig, " ".repeat(5 * 1024 * 1024 + 1)); reject(receiver, tooBig);
        Path wrongExt = output("invalid.txt"); Files.copy(valid, wrongExt); reject(receiver, wrongExt);
        Path directory = output("directory.json"); Files.createDirectory(directory); reject(receiver, directory);
        Path results = LegacyFixtures.copy("result-v1.json", output("not-a-quiz.json")); reject(receiver, results);
        assertEquals(before, snapshot(receiver.directory()));
        // 내부 load에도 같은 깊이 제한이 적용되는지 확인한다.
        Path internal = receiver.directory().resolve("deep.json"); Files.writeString(internal, broken.get(1));
        try { receiver.load(internal.toString()); fail("깊은 내부 JSON 거부"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("깊")); }
        Files.delete(internal);
        assertEquals(before, snapshot(receiver.directory()));
    }

    private void reject(QuizRepository receiver, Path file) throws Exception {
        try { transfer(receiver).inspect(file); fail("외부 파일 거부: " + file); }
        catch (IOException expected) { assertFalse(expected.getMessage().isBlank()); }
    }

    @Test public void exportNeverOverwritesAndUsesSafeFileNames() throws Exception {
        QuizRepository library = repo("library");
        SavedQuizSet set = sample("자료.pdf"); Path source = library.save(set);
        Path target = output("already.json"); Files.writeString(target, "keep");
        try { transfer(library).export(source, target); fail("내보내기 덮어쓰기 거부"); }
        catch (FileAlreadyExistsException expected) { assertEquals("keep", Files.readString(target)); }
        assertEquals(set, library.load(source.toString()));
        for (String title : List.of("CON", "LPT1.txt", "<bad>:name/\\*?\"|", "...", "😀".repeat(60))) {
            String name = QuizMetadata.exportFileName(title);
            assertTrue(name.endsWith(".json"));
            assertFalse(name.matches(".*[<>:\"/\\\\|?*].*"));
            assertFalse(name.equalsIgnoreCase("CON.json"));
        }
    }

    @Test public void importedQuizCanBeListedPlayedSavedAndReviewedWithoutAReferencedParent() throws Exception {
        QuizRepository sender = repo("sender"), receiver = repo("receiver");
        SavedQuizSet set = sample("C:\\private\\lecture.pdf");
        Path exported = transfer(sender).export(sender.save(set), output("play.json"));
        var service = transfer(receiver);
        var imported = service.commit(service.inspect(exported), false);
        assertEquals(imported.set(), receiver.list().entries().getFirst().set());
        assertFalse(receiver.containsSetId(imported.set().derivedFromSetId()));
        var workflow = new com.quiz.gui.QuizWorkflow(receiver, new QuizResultRepository(root().resolve("results")),
                Clock.fixed(AT, ZoneOffset.UTC), QuizGenerator::new);
        var session = workflow.start(receiver.load(imported.path().toString()));
        assertFalse(session.submit("1").correct()); assertTrue(session.submit("3").correct()); session.next();
        assertTrue(session.submit("O").correct()); session.next();
        var review = workflow.loadReview(imported.path(), workflow.saveResult(session));
        assertEquals(List.of(imported.set().questions().getFirst()), review.wrong());
        var retry = workflow.startReview(review); assertTrue(retry.submit("3").correct()); retry.next();
        assertTrue(workflow.loadReview(imported.path(), workflow.saveResult(retry)).wrong().isEmpty());
    }

    @Test public void externalV1ImportsAsV2WithImportTimeAndDoesNotModifyItsBytes() throws Exception {
        QuizRepository receiver = repo("receiver");
        Path original = LegacyFixtures.copy("quiz-v1.json", output("legacy-friendly.json"));
        Files.setLastModifiedTime(original, java.nio.file.attribute.FileTime.from(AT.minusSeconds(10000)));
        byte[] before = Files.readAllBytes(original);
        var service = transfer(receiver);
        var imported = service.commit(service.inspect(original), false);
        assertEquals(2, json(imported.path()).get("version"));
        assertEquals(AT, imported.set().createdAt());
        assertEquals("원본 강의.pdf", imported.set().title());
        assertEquals(SavedQuestion.Origin.UNKNOWN, imported.set().questions().getFirst().origin());
        assertEquals(SavedQuestion.Origin.MANUAL, imported.set().questions().get(1).origin());
        assertArrayEquals(before, Files.readAllBytes(original));
        assertEquals(imported.set(), receiver.load(imported.path().toString()));
    }

    @Test public void commitRechecksConflictsThatAppearedAfterInspection() throws Exception {
        QuizRepository library = repo("library");
        SavedQuizSet set = sample("자료.pdf");
        Path external = library.exportFile(set, output("late-conflict.json"));
        var service = transfer(library);
        var plan = service.inspect(external);
        assertFalse(plan.hasConflict());
        library.save(set);
        Map<String, String> before = snapshot(library.directory());
        try { service.commit(plan, false); fail("새 충돌은 재확인해야 합니다."); }
        catch (QuizTransfer.ConflictException expected) { assertTrue(expected.plan().identical()); }
        assertEquals(before, snapshot(library.directory()));
    }
}
