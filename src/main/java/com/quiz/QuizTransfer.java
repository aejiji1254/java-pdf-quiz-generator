package com.quiz;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** 퀴즈 세트만 전송한다. 결과 저장소를 읽거나 쓰지 않는다. */
public final class QuizTransfer {
    private final QuizRepository repository;
    private final Clock clock;

    public QuizTransfer(QuizRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }

    public record ImportPlan(SavedQuizSet incoming, List<SavedQuizSet> matchingSets,
            Set<String> overlappingQuestionIds, boolean setIdExists, boolean identical, int unreadableCount) {
        public ImportPlan { matchingSets = List.copyOf(matchingSets); overlappingQuestionIds = Set.copyOf(overlappingQuestionIds); }
        public boolean hasConflict() { return setIdExists || !overlappingQuestionIds.isEmpty(); }
        public String warning() {
            return unreadableCount == 0 ? "" : "읽을 수 없는 파일 " + unreadableCount + "개는 충돌 검사에서 제외되었습니다.";
        }
        public String message() {
            String reason = setIdExists ? identical ? "이미 가져온 세트와 동일합니다." : "ID는 같지만 내용이 다릅니다."
                    : "문항 ID가 다른 저장 세트와 겹칩니다.";
            return reason + "\n" + String.join(", ", matchingSets.stream().map(SavedQuizSet::title).toList())
                    + (warning().isEmpty() ? "" : "\n" + warning());
        }
    }
    public record Imported(SavedQuizSet set, Path path, int unreadableCount) {
        public String warning() {
            return unreadableCount == 0 ? "" : "읽을 수 없는 파일 " + unreadableCount + "개는 충돌 검사에서 제외되었습니다.";
        }
    }
    public static final class ConflictException extends IOException {
        private final ImportPlan plan;
        ConflictException(ImportPlan plan) { super(plan.message()); this.plan = plan; }
        public ImportPlan plan() { return plan; }
    }

    public Path export(Path source, Path target) throws IOException {
        return repository.exportFile(portable(repository.load(source.toString())), target);
    }

    public ImportPlan inspect(Path external) throws IOException { return inspect(repository.loadExternal(external)); }

    private ImportPlan inspect(SavedQuizSet incoming) throws IOException {
        QuizRepository.Listing listing = repository.list();
        Set<String> ids = incoming.questions().stream().map(SavedQuestion::id).collect(Collectors.toSet());
        Set<String> overlapping = new HashSet<>();
        List<SavedQuizSet> matching = new ArrayList<>();
        boolean identical = false;
        for (QuizRepository.Entry entry : listing.entries()) {
            SavedQuizSet local = entry.set();
            boolean questionOverlap = false;
            for (SavedQuestion q : local.questions()) if (ids.contains(q.id())) {
                overlapping.add(q.id()); questionOverlap = true;
            }
            if (local.id().equals(incoming.id())) identical = sameContent(local, incoming);
            if (questionOverlap || local.id().equals(incoming.id())) matching.add(local);
        }
        return new ImportPlan(incoming, matching, overlapping, repository.containsSetId(incoming.id()),
                identical, listing.unreadableCount());
    }

    public Imported commit(ImportPlan proposed, boolean asCopy) throws IOException {
        ImportPlan checked = inspect(proposed.incoming()); // 확인 대화상자가 열린 동안 생긴 충돌도 다시 검사한다.
        if (checked.hasConflict() && !asCopy) throw new ConflictException(checked);
        SavedQuizSet incoming = checked.incoming();
        SavedQuizSet saved;
        if (asCopy) saved = copyForImport(checked);
        else saved = incoming.forSave(incoming.title(), incoming.subject(), clock.instant());
        Path path = repository.save(saved);
        return new Imported(saved, path, checked.unreadableCount());
    }

    /** 충돌 복사본은 수신 파일의 기존 derivedFromSetId를 로컬 충돌 세트 ID로 대체한다. */
    private SavedQuizSet copyForImport(ImportPlan plan) {
        SavedQuizSet incoming = plan.incoming();
        String parent = plan.setIdExists() ? incoming.id()
                : plan.matchingSets().isEmpty() ? null : plan.matchingSets().getFirst().id();
        List<SavedQuestion> questions = incoming.questions().stream().map(q -> new SavedQuestion(
                UUID.randomUUID().toString(), q.type(), q.question(), q.options(), q.correctIndex(), q.explanation(),
                q.sourcePdf(), q.page(), q.lineNumber(), q.origin(),
                plan.overlappingQuestionIds().contains(q.id()) ? q.id() : null)).toList();
        return new SavedQuizSet(UUID.randomUUID().toString(), incoming.sourcePdf(), questions,
                QuizMetadata.withSuffix(incoming.title(), " (가져옴)"), incoming.subject(), clock.instant(), parent);
    }

    public static SavedQuizSet portable(SavedQuizSet set) {
        String source = QuizMetadata.portableSource(set.sourcePdf());
        List<SavedQuestion> questions = set.questions().stream().map(q -> new SavedQuestion(q.id(), q.type(),
                q.question(), q.options(), q.correctIndex(), q.explanation(), source, q.page(), q.lineNumber(),
                q.origin(), q.derivedFromQuestionId())).toList();
        return new SavedQuizSet(set.id(), source, questions, set.title(), set.subject(), set.createdAt(), set.derivedFromSetId());
    }

    public static boolean sameContent(SavedQuizSet first, SavedQuizSet second) {
        SavedQuizSet a = portable(first), b = portable(second);
        return a.forSave(a.title(), a.subject(), Instant.EPOCH).equals(b.forSave(b.title(), b.subject(), Instant.EPOCH));
    }
}
