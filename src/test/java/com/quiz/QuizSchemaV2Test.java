package com.quiz;

import com.quiz.gui.QuizWorkflow;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class QuizSchemaV2Test {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Instant NOW = Instant.parse("2026-11-22T01:02:03Z");
    private Path folder() { return temporary.getRoot().toPath().resolve("quizzes"); }
    private QuizRepository repository() { return new QuizRepository(folder()); }
    private QuizWorkflow workflow(Clock clock) {
        return new QuizWorkflow(repository(), new QuizResultRepository(folder().resolveSibling("results")), clock, QuizGenerator::new);
    }
    private Path legacy() throws IOException {
        return LegacyFixtures.copy("quiz-v1.json", folder().resolve(LegacyFixtures.SET_ID + ".json"));
    }
    private SavedQuizSet set() {
        String id = UUID.randomUUID().toString();
        SavedQuestion q = new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX,
                "질문", List.of("O", "X"), 0, "해설", "자료.pdf", null, null,
                SavedQuestion.Origin.MANUAL, LegacyFixtures.RESULT_ID);
        return new SavedQuizSet(id, "자료.pdf", List.of(q), "같은 이름", "과목", NOW, LegacyFixtures.SET_ID);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> json(String text) { return (Map<String, Object>) QuizJson.parse(text); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> questions(Map<String, Object> root) {
        return (List<Map<String, Object>>) (List<?>) root.get("questions");
    }
    private static void invalid(Runnable action) {
        try { action.run(); fail("규칙 위반 거부"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isBlank()); }
    }
    private void rejected(Path path, Map<String, Object> value) throws IOException {
        Files.writeString(path, QuizJson.write(value));
        try { repository().load(path.toString()); fail("잘못된 JSON 거부"); }
        catch (IOException expected) { assertFalse(expected.getMessage().isBlank()); }
    }

    @Test public void readsActualV1WithMtimeAndConservativeOriginWithoutChangingBytes() throws Exception {
        Path file = legacy();
        Files.setLastModifiedTime(file, FileTime.from(NOW));
        byte[] before = Files.readAllBytes(file);
        SavedQuizSet loaded = repository().load(file.toString());
        assertEquals("원본 강의.pdf", loaded.title());
        assertEquals("", loaded.subject());
        assertEquals(NOW, loaded.createdAt());
        assertNull(loaded.derivedFromSetId());
        assertEquals(SavedQuestion.Origin.UNKNOWN, loaded.questions().get(0).origin());
        assertEquals(SavedQuestion.Origin.MANUAL, loaded.questions().get(1).origin());
        assertTrue(loaded.questions().stream().allMatch(q -> q.derivedFromQuestionId() == null));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(NOW, Files.getLastModifiedTime(file).toInstant());
    }

    @Test public void fallbackUsesOnlyStringsAndCountsCodePointsWithoutThrowing() {
        assertEquals("a.pdf", QuizMetadata.fileName("C:\\folder/a.pdf"));
        assertEquals("수동 입력 - a", QuizMetadata.defaultTitle("수동 입력 (원문 대조 필요: C:\\folder\\a.pdf)"));
        assertEquals("수동 입력", QuizMetadata.defaultTitle("수동 입력 (PDF 미지정)"));
        assertEquals("a", QuizMetadata.defaultTitle("/folder/a.pdf"));
        assertEquals("수동 입력 강의", QuizMetadata.defaultTitle("수동 입력 강의.pdf"));
        assertEquals("수동 입력 강의.pdf", QuizMetadata.legacyTitle("수동 입력 강의.pdf"));
        assertEquals("(제목 없음)", QuizMetadata.defaultTitle(null));
        for (String input : List.of("/", "\n\u200B", "😀".repeat(80) + ".pdf", "C:\\bad:middle/")) {
            String title = QuizMetadata.defaultTitle(input);
            assertTrue(title.codePointCount(0, title.length()) <= 60);
            assertFalse(title.isBlank());
            assertEquals(title, QuizMetadata.title(title));
        }
        assertEquals("😀".repeat(60), QuizMetadata.defaultTitle("😀".repeat(80) + ".pdf"));
    }

    @Test public void legacyInvalidFallbackIsSanitizedButV2InvalidTitlesAreRejected() throws Exception {
        Path file = legacy();
        String fixture = Files.readString(file);
        for (String source : List.of("/", "😀".repeat(80) + ".pdf", "\n\u200B", "C:\\folder\\a.pdf")) {
            Map<String, Object> root = json(fixture);
            root.put("sourcePdf", source);
            questions(root).forEach(q -> q.put("sourcePdf", source));
            // 공백뿐인 sourcePdf는 원래 규칙상 잘못되므로 비공백 서식 문자를 포함한다.
            Files.writeString(file, QuizJson.write(root));
            SavedQuizSet loaded = repository().load(file.toString());
            assertTrue(loaded.title().codePointCount(0, loaded.title().length()) <= 60);
            assertFalse(loaded.title().isBlank());
        }
        SavedQuizSet v2 = set();
        Path v2file = repository().save(v2);
        String valid = Files.readString(v2file);
        for (String title : List.of("", " ", "가".repeat(61), "이름\n", "이름\u200B", "이름\u202E")) {
            var root = json(valid); root.put("title", title); rejected(v2file, root);
        }
    }

    @Test public void metadataValidatesBoundariesTrimAndEveryCcCfIncludingSubject() {
        assertEquals("가", QuizMetadata.title(" 가 "));
        assertEquals("😀".repeat(60), QuizMetadata.title("😀".repeat(60)));
        assertEquals("", QuizMetadata.subject("  "));
        assertEquals("가".repeat(30), QuizMetadata.subject("가".repeat(30)));
        invalid(() -> QuizMetadata.title(" "));
        invalid(() -> QuizMetadata.title("가".repeat(61)));
        invalid(() -> QuizMetadata.subject("가".repeat(31)));
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (Character.getType(codePoint) == Character.CONTROL || Character.getType(codePoint) == Character.FORMAT) {
                String value = "가" + new String(Character.toChars(codePoint));
                invalid(() -> QuizMetadata.title(value));
                invalid(() -> QuizMetadata.subject(value));
            }
        }
    }

    @Test public void v2RoundTripPreservesAllFieldsAndEditingPreservesLineage() throws Exception {
        SavedQuizSet set = set();
        Path path = repository().save(set);
        assertEquals(set, repository().load(path.toString()));
        assertEquals(2, json(Files.readString(path)).get("version"));
        SavedQuestion original = set.questions().getFirst();
        SavedQuestion edited = original.edited("변경", original.options(), 1, "접두어 없음");
        assertEquals(original.origin(), edited.origin());
        assertEquals(original.derivedFromQuestionId(), edited.derivedFromQuestionId());
        assertEquals(original.id(), edited.id());
    }

    @Test public void exactV2FieldSetsUnknownVersionsInvalidOriginsInstantsAndUuidsAreRejected() throws Exception {
        Path path = repository().save(set());
        String valid = Files.readString(path);
        for (String key : json(valid).keySet()) {
            var root = json(valid); root.remove(key); rejected(path, root);
        }
        for (String key : questions(json(valid)).getFirst().keySet()) {
            var root = json(valid); questions(root).getFirst().remove(key); rejected(path, root);
        }
        var extraRoot = json(valid); extraRoot.put("extra", true); rejected(path, extraRoot);
        var extraQuestion = json(valid); questions(extraQuestion).getFirst().put("extra", true); rejected(path, extraQuestion);
        for (Object version : List.of(0, 3, "2")) {
            var root = json(valid); root.put("version", version); rejected(path, root);
        }
        Files.writeString(path, valid.replace("\"version\":2", "\"version\":2.0"));
        try { repository().load(path.toString()); fail("정수가 아닌 version 거부"); }
        catch (IOException expected) { assertFalse(expected.getMessage().isBlank()); }
        for (String origin : List.of("madeUp", "manual", "")) {
            var root = json(valid); questions(root).getFirst().put("origin", origin); rejected(path, root);
        }
        for (String key : List.of("createdAt", "derivedFromSetId")) {
            var root = json(valid); root.put(key, "invalid"); rejected(path, root);
        }
        var root = json(valid); questions(root).getFirst().put("derivedFromQuestionId", "invalid"); rejected(path, root);
    }

    @Test public void listingHandlesMixedFilesAndCapsReasonsWithoutSwing() throws Exception {
        Path legacy = legacy();
        Files.setLastModifiedTime(legacy, FileTime.from(NOW.minusSeconds(100)));
        SavedQuizSet set = set();
        Path good = repository().save(set);
        Files.copy(good, folder().resolve("wrong-id.json"));
        var badDate = json(Files.readString(good));
        badDate.put("createdAt", "invalid");
        Files.writeString(folder().resolve("bad-date.json"), QuizJson.write(badDate));
        for (int i = 0; i < 7; i++) Files.writeString(folder().resolve("broken" + i + ".json"), "{broken");
        Files.write(folder().resolve("invalid-utf8.json"), new byte[] {(byte) 0xC3, 0x28});
        Files.writeString(folder().resolve("too-big.json"), " ".repeat(5 * 1024 * 1024 + 1));
        Files.writeString(folder().resolve("ignore.txt"), "broken");
        Files.createDirectories(folder().resolve("directory.json"));
        Files.copy(good, folder().resolve("directory.json").resolve(good.getFileName()));
        QuizRepository.Listing listing = repository().list();
        assertEquals(2, listing.entries().size());
        assertEquals(set.id(), listing.entries().getFirst().set().id());
        assertEquals(LegacyFixtures.SET_ID, listing.entries().getLast().set().id());
        assertEquals(11, listing.unreadableCount());
        assertEquals(5, listing.reasons().size());
        assertTrue(listing.reasons().stream().allMatch(s -> s.contains(".json:")));
    }

    @Test public void emptyOrMissingFolderIsEmptyAndFolderFailureIsNotSilenced() throws Exception {
        assertTrue(repository().list().entries().isEmpty());
        Files.createDirectories(folder());
        assertEquals(0, repository().list().unreadableCount());
        Path file = temporary.newFile("file").toPath();
        try { new QuizRepository(file).list(); fail("폴더 자체 오류는 전달"); }
        catch (IOException expected) { assertFalse(expected.getMessage().isBlank()); }
    }

    @Test public void generationManualAdditionRevisionAndSecondRevisionRecordImmediateOrigins() throws Exception {
        QuizWorkflow w = workflow(Clock.fixed(NOW, ZoneOffset.UTC));
        var pdfPreview = w.generate(Path.of("sample.pdf"));
        assertFalse(pdfPreview.set().questions().isEmpty());
        assertTrue(pdfPreview.set().questions().stream().allMatch(q -> q.origin() == SavedQuestion.Origin.GENERATED
                && q.derivedFromQuestionId() == null));
        List<Term> terms = List.of(new Term("캐시", "반복되는 자료를 빠르게 가져오는 임시 저장소", 1),
                new Term("버퍼", "입출력 속도 차이를 완화하는 보관 공간", 2));
        QuizGenerator generator = new QuizGenerator(new Random(7));
        SavedQuizSet generated = SavedQuizSet.fromGenerated("자료.pdf", new TermExtractor.Result(terms, List.of()),
                generator.generateOx(terms), List.of());
        assertTrue(generated.questions().stream().allMatch(q -> q.origin() == SavedQuestion.Origin.GENERATED && q.derivedFromQuestionId() == null));
        var manual = w.generateManual(ManualTermSet.empty("").add("캐시", terms.getFirst().description()));
        assertFalse(manual.set().questions().isEmpty());
        assertTrue(manual.set().questions().stream().allMatch(q -> q.origin() == SavedQuestion.Origin.GENERATED));
        var p = new QuizWorkflow.Preview(generated, List.of());
        p = w.addQuestion(p, SavedQuestion.Type.OX, "직접", List.of("O", "X"), 0, "해설");
        assertEquals(SavedQuestion.Origin.MANUAL, p.set().questions().getLast().origin());
        assertNull(p.set().questions().getLast().derivedFromQuestionId());
        SavedQuizSet original = w.saveReviewed(p, Set.of(), "원본", "과목");
        var next = w.addQuestion(new QuizWorkflow.Preview(original, List.of()), SavedQuestion.Type.OX,
                "수정본에서 추가", List.of("O", "X"), 0, "해설");
        SavedQuizSet revision = w.saveRevision(original, next, Set.of(), false).set();
        assertEquals(original.id(), revision.derivedFromSetId());
        for (int i = 0; i < original.questions().size(); i++) {
            assertEquals(original.questions().get(i).id(), revision.questions().get(i).derivedFromQuestionId());
            assertEquals(original.questions().get(i).origin(), revision.questions().get(i).origin());
            assertNotEquals(original.questions().get(i).id(), revision.questions().get(i).id());
        }
        assertNull(revision.questions().getLast().derivedFromQuestionId());
        assertEquals(SavedQuestion.Origin.MANUAL, revision.questions().getLast().origin());
        SavedQuizSet second = w.saveRevision(revision, new QuizWorkflow.Preview(revision, List.of()), Set.of(), true).set();
        assertEquals(revision.id(), second.derivedFromSetId());
        for (int i = 0; i < revision.questions().size(); i++)
            assertEquals(revision.questions().get(i).id(), second.questions().get(i).derivedFromQuestionId());
        assertEquals(second, repository().load(second.id() + ".json"));
    }

    @Test public void creationTimeIsAssignedAtSaveAndReturnedModelMatchesDisk() throws Exception {
        final Instant[] current = {NOW.minusSeconds(500)};
        Clock mutable = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return current[0]; }
        };
        QuizWorkflow w = workflow(mutable);
        var preview = w.generateManual(ManualTermSet.empty("").add("캐시", "반복 자료를 보관하는 공간"));
        current[0] = NOW;
        SavedQuizSet saved = w.saveReviewed(preview, Set.of(), "저장 이름", "과목");
        assertEquals(NOW, saved.createdAt());
        assertEquals(saved, repository().load(saved.id() + ".json"));
        current[0] = NOW.plusSeconds(200);
        SavedQuizSet next = w.saveRevision(saved, new QuizWorkflow.Preview(saved, List.of()), Set.of(), true).set();
        assertEquals(current[0], next.createdAt());
        assertEquals(next, repository().load(next.id() + ".json"));
    }

    @Test public void legacyResultStillLoadsAndConsoleReviewsWithoutRewritingLegacyFiles() throws Exception {
        Path quiz = legacy();
        Path result = LegacyFixtures.copy("result-v1.json", folder().resolveSibling("results").resolve(LegacyFixtures.RESULT_ID + ".json"));
        byte[] quizBefore = Files.readAllBytes(quiz), resultBefore = Files.readAllBytes(result);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Main.runInteractive(new Scanner("review\n" + quiz + "\n" + result + "\nO\nO\n"),
                new PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8), repository());
        assertTrue(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("점수: 2/2"));
        assertArrayEquals(quizBefore, Files.readAllBytes(quiz));
        assertArrayEquals(resultBefore, Files.readAllBytes(result));
    }

    @Test public void consoleSaveAppearsInGuiCatalogAndGuiSaveCanBeLoadedInConsole() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Main.runInteractive(new Scanner(Path.of("sample.pdf").toAbsolutePath() + "\n5\n0\n"),
                new PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8), repository(),
                new QuizResultRepository(folder().resolveSibling("results")), Clock.fixed(NOW, ZoneOffset.UTC));
        assertTrue(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("문제 세트 저장:"));
        QuizWorkflow w = workflow(Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));
        SavedQuizSet console = w.listQuizzes().entries().getFirst().set();
        assertEquals("sample", console.title());
        assertEquals("", console.subject());
        assertEquals(NOW, console.createdAt());
        var preview = w.generateManual(ManualTermSet.empty("").add("캐시", "반복 데이터를 보관하는 임시 저장소"));
        SavedQuizSet gui = w.saveReviewed(preview, Set.of(), "GUI에서 저장", "테스트 과목");
        String answers = gui.questions().stream().map(q -> q.options().get(q.correctIndex()) + "\n")
                .collect(java.util.stream.Collectors.joining());
        bytes.reset();
        Main.runInteractive(new Scanner("load\n" + gui.id() + ".json\n" + answers),
                new PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8), repository());
        String displayed = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(displayed.contains("문제 세트 불러옴:"));
        assertTrue(displayed.contains("점수: " + gui.questions().size() + "/" + gui.questions().size()));
        assertEquals(gui, repository().load(gui.id() + ".json"));
    }

    @Test public void v1StillRejectsMissingAndExtraRootAndQuestionFields() throws Exception {
        Path path = legacy();
        String valid = Files.readString(path);
        var root = json(valid); root.remove("sourcePdf"); rejected(path, root);
        root = json(valid); root.put("title", "v1에는 없는 필드"); rejected(path, root);
        root = json(valid); questions(root).getFirst().remove("page"); rejected(path, root);
        root = json(valid); questions(root).getFirst().put("origin", "GENERATED"); rejected(path, root);
    }

    @Test public void sameTitlesAreAllowedAndCreationTimeOrdersSeparateIds() throws Exception {
        SavedQuizSet first = set();
        repository().save(first);
        SavedQuizSet second = new SavedQuizSet(UUID.randomUUID().toString(), first.sourcePdf(), first.questions(),
                first.title(), first.subject(), NOW.plusSeconds(1), null);
        repository().save(second);
        var entries = repository().list().entries();
        assertEquals(2, entries.size());
        assertEquals(second.id(), entries.getFirst().set().id());
        assertEquals(first.id(), entries.getLast().set().id());
        assertEquals(entries.getFirst().set().title(), entries.getLast().set().title());
    }
}
