package com.quiz.gui;

import com.quiz.PdfReader;
import com.quiz.QuizGenerator;
import com.quiz.QuizRepository;
import com.quiz.QuizResult;
import com.quiz.QuizResultRepository;
import com.quiz.SavedQuestion;
import com.quiz.SavedQuizSet;
import com.quiz.TermExtractor;
import com.quiz.Term;
import com.quiz.ManualTermSet;
import com.quiz.ManualTermRepository;
import com.quiz.QuizMetadata;
import com.quiz.GenerationOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Locale;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Swing에 독립적인 PDF 생성, 저장, 불러오기와 풀이 조정 계층. */
public final class QuizWorkflow {
    public record Preview(SavedQuizSet set, List<String> skipped, String diagnostics) {
        public Preview {
            skipped = List.copyOf(skipped);
        }
        public Preview(SavedQuizSet set, List<String> skipped) {
            this(set, skipped, "");
        }
    }

    public record Review(SavedQuizSet set, QuizResult previous, List<SavedQuestion> wrong) {
        public Review {
            wrong = List.copyOf(wrong);
        }
    }

    public record Summary(int total, int answered, long correct, long wrong,
            int unanswered, double accuracy, double completion) { }

    public record SavedRevision(SavedQuizSet set, Path path) { }

    public Summary summary(QuizResult result) {
        int answered = result.answers().size();
        int total = result.totalQuestions();
        long correct = result.correctCount();
        return new Summary(total, answered, correct, result.wrongCount(), total - answered,
                answered == 0 ? 0 : correct * 100.0 / answered,
                total == 0 ? 0 : answered * 100.0 / total);
    }

    private final QuizRepository quizzes;
    private final QuizResultRepository results;
    private final ManualTermRepository manualTerms;
    private final Clock clock;
    private final Supplier<QuizGenerator> generators;

    public QuizWorkflow() {
        this(new QuizRepository(), new QuizResultRepository(), Clock.systemUTC(), QuizGenerator::new);
    }

    public QuizWorkflow(QuizRepository quizzes, QuizResultRepository results,
            Clock clock, Supplier<QuizGenerator> generators) {
        this(quizzes, results, new ManualTermRepository(), clock, generators);
    }

    public QuizWorkflow(QuizRepository quizzes, QuizResultRepository results,
            ManualTermRepository manualTerms, Clock clock, Supplier<QuizGenerator> generators) {
        this.quizzes = quizzes;
        this.results = results;
        this.manualTerms = manualTerms;
        this.clock = clock;
        this.generators = generators;
    }

    public ManualTermSet newManualSet(Path pdf) {
        return ManualTermSet.empty(pdf == null ? "" : pdf.toAbsolutePath().normalize().toString());
    }

    public Path saveManual(ManualTermSet set) throws IOException { return manualTerms.save(set); }

    public ManualTermSet loadManual(Path file) throws IOException { return manualTerms.load(file); }

    public Preview generateManual(ManualTermSet manual) {
        return generateManual(manual, null);
    }

    public Preview generateManual(ManualTermSet manual, GenerationOptions options) {
        QuizGenerator generator = generators.get();
        TermExtractor.Result extracted = new TermExtractor.Result(manual.terms(), List.of());
        QuizGenerator.OxResult ox = options == null ? generator.generateOxDetailed(extracted)
                : generator.generateOxDetailed(extracted, options.oxCount());
        QuizGenerator.MultipleChoiceResult multiple = options == null ? generator.generateMultipleChoice(manual.terms())
                : generator.generateMultipleChoice(manual.terms(), options.multipleChoiceCount(), options.direction());
        SavedQuizSet set = SavedQuizSet.fromGenerated(manual.sourceLabel(),
                new TermExtractor.Result(manual.terms(), List.of()), ox.questions(), multiple.questions());
        StringBuilder report = new StringBuilder("수동 입력 ").append(manual.terms().size())
                .append("개 — PDF 원문 일치 여부는 사용자가 검토해야 합니다.\nO/X ")
                .append(ox.questions().size()).append("개, 4지선다 ").append(multiple.questions().size()).append("개");
        if (ox.candidateCount() == 0)
            report.append("\nO/X에는 검증 가능한 용어·설명이 필요합니다.");
        if (multiple.candidateCount() < 4)
            report.append("\n4지선다에는 정답 외에 검증 가능한 서로 다른 설명 후보 3개가 필요합니다.");
        report.append("\n").append(ox.summary());
        ox.skipped().forEach(reason -> report.append("\nO/X 출제 제외: ").append(reason));
        multiple.skipped().forEach(reason -> report.append("\n출제 제외: ").append(reason));
        List<String> skipped = new java.util.ArrayList<>(ox.skipped());
        skipped.addAll(multiple.skipped());
        if (options != null) report.append("\n").append(optionReport(options, ox, multiple));
        return new Preview(set, skipped, report.toString());
    }

    public enum CloseChoice { SAVE, DISCARD, CANCEL }

    /** 취소는 세션을 유지하고, 저장 실패는 호출자가 창을 유지하도록 오류를 전달한다. */
    public boolean closeSession(QuizSession session, CloseChoice choice) throws IOException {
        if (session == null) return true;
        return switch (choice) {
            case SAVE -> {
                saveResult(session);
                yield true;
            }
            case DISCARD -> true;
            case CANCEL -> false;
        };
    }

    public Path validatePdfSelection(Path selected) throws IOException {
        if (selected == null) return null;
        if (!selected.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
            throw new IOException("PDF(.pdf) 파일을 선택하세요.");
        if (!Files.isRegularFile(selected) || !Files.isReadable(selected))
            throw new IOException("선택한 PDF 파일이 없거나 읽을 수 없습니다: " + selected);
        return selected.toAbsolutePath().normalize();
    }

    public Preview generate(Path pdf) throws IOException {
        return generateFromPages(pdf, new PdfReader().read(pdf), 1);
    }

    public Preview generate(Path pdf, int startPage, int endPage) throws IOException {
        return generateFromPages(pdf, new PdfReader().readRange(pdf, startPage, endPage), startPage);
    }

    public Preview generate(Path pdf, GenerationOptions options) throws IOException {
        return generateFromPages(pdf, new PdfReader().read(pdf), 1, options);
    }

    public Preview generate(Path pdf, int startPage, int endPage, GenerationOptions options) throws IOException {
        return generateFromPages(pdf, new PdfReader().readRange(pdf, startPage, endPage), startPage, options);
    }

    public Preview generate(Path pdf, int startPage, int endPage, GenerationOptions options, TermExtractor.Mode mode) throws IOException {
        return generateFromPages(pdf, new PdfReader().readRange(pdf, startPage, endPage), startPage, options, mode);
    }

    public Preview generate(Path pdf, GenerationOptions options, TermExtractor.Mode mode) throws IOException {
        return generateFromPages(pdf, new PdfReader().read(pdf), 1, options, mode);
    }

    private Preview generateFromPages(Path pdf, List<String> pages, int firstPage) {
        return generateFromPages(pdf, pages, firstPage, null);
    }

    private Preview generateFromPages(Path pdf, List<String> pages, int firstPage, GenerationOptions options) {
        return generateFromPages(pdf, pages, firstPage, options, TermExtractor.Mode.LEGACY);
    }

    private Preview generateFromPages(Path pdf, List<String> pages, int firstPage, GenerationOptions options, TermExtractor.Mode mode) {
        TermExtractor.Extraction extraction = new TermExtractor().extractDetailed(pages, firstPage, mode);
        TermExtractor.Result extracted = extraction.result();
        QuizGenerator generator = generators.get();
        QuizGenerator.MultipleChoiceResult multiple = options == null ? generator.generateMultipleChoice(extracted)
                : generator.generateMultipleChoice(extracted, options.multipleChoiceCount(), options.direction());
        QuizGenerator.OxResult ox = options == null ? generator.generateOxDetailed(extracted)
                : generator.generateOxDetailed(extracted, options.oxCount());
        List<QuizGenerator.OxQuestion> oxQuestions = ox.questions();
        SavedQuizSet set = SavedQuizSet.fromGenerated(pdf.toAbsolutePath().normalize().toString(),
                extracted, oxQuestions, multiple.questions());
        long characters = pages.stream().mapToLong(page -> page.codePoints()
                .filter(c -> !Character.isWhitespace(c)).count()).sum();
        long lines = pages.stream().mapToLong(page -> page.lines().filter(line -> !line.isBlank()).count()).sum();
        StringBuilder report = new StringBuilder("PDF 읽기 성공: ").append(pages.size())
                .append("페이지, 전체 텍스트 ").append(characters).append("자, 텍스트 줄 ")
                .append(lines).append("개");
        report.append("\n처리한 원본 PDF 페이지 범위: ").append(firstPage)
                .append("~").append(firstPage + pages.size() - 1);
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i);
            report.append("\n").append(firstPage + i).append("페이지: 텍스트 ")
                    .append(page.codePoints().filter(c -> !Character.isWhitespace(c)).count())
                    .append("자, 줄 ").append(page.lines().filter(line -> !line.isBlank()).count()).append("개");
        }
        report.append("\n용어 후보: ").append(extracted.candidateCount())
                .append("개, 유효한 용어·설명 쌍: ").append(extracted.terms().size())
                .append("개, 제외 줄: ").append(extracted.warnings().size())
                .append("개\nO/X 적합 용어 후보: ").append(ox.candidateCount())
                .append("개, 최종 채택: ").append(oxQuestions.size())
                .append("개\n4지선다 적합 용어 후보: ").append(multiple.candidateCount())
                .append("개, 최종 채택: ").append(multiple.questions().size())
                .append("개\n최종 출제: O/X ").append(oxQuestions.size())
                .append("개, 4지선다 ").append(multiple.questions().size()).append("개");
        if (set.questions().isEmpty()) {
            report.append("\n문제 생성 실패: ");
            if (extracted.terms().isEmpty()) report.append(
                    "PDF는 읽었지만 문제 생성에 적합한 용어·설명 관계를 찾지 못했습니다. "
                    + "'용어: 설명' 또는 '용어 다음 줄 : 설명' 형식을 확인하세요.");
            else report.append("추출된 쌍의 설명이 불완전하거나 중복되었고, "
                    + "O/X에는 검증 가능한 쌍, 4지선다에는 서로 다른 오답 3개가 필요합니다. "
                    + "아래 출제 제외 사유를 확인하세요.");
        } else report.append("\n문제 생성 성공: ").append(set.questions().size()).append("문항");
        if (ox.candidateCount() == 0)
            report.append("\nO/X 제외 사유: 검증 가능한 용어·설명이 없습니다.");
        if (multiple.candidateCount() < 4)
            report.append("\n4지선다 제외 사유: 서로 다른 유효 설명 후보가 4개 미만입니다. "
                    + "정답 외에 검증 가능한 오답 3개가 필요합니다.");
        else if (multiple.questions().isEmpty())
            report.append("\n4지선다 제외 사유: 후보 간 용어·설명의 중첩으로 "
                    + "검증 가능한 오답 3개를 고를 수 없습니다.");
        extracted.warnings().forEach(w -> report.append("\n제외 ")
                .append(w.page()).append("페이지: ").append(w.reason()).append(" — ").append(w.line()));
        report.append("\n").append(ox.summary());
        ox.skipped().forEach(reason -> report.append("\nO/X 출제 제외: ").append(reason));
        multiple.skipped().forEach(reason -> report.append("\n출제 제외: ").append(reason));
        List<String> skipped = new java.util.ArrayList<>(ox.skipped());
        skipped.addAll(multiple.skipped());
        if (options != null) report.append("\n").append(optionReport(options, ox, multiple));
        if (mode == TermExtractor.Mode.STRUCTURED) report.append("\n").append(structuredReport(extraction));
        return new Preview(set, skipped, report.toString());
    }

    private static String structuredReport(TermExtractor.Extraction extraction) {
        QuizGenerator inspector = new QuizGenerator(new java.util.Random(0));
        java.util.List<Term> accepted = inspector.screenedTerms(extraction.result());
        java.util.Map<String, Long> titles = extraction.result().terms().stream().collect(java.util.stream.Collectors.groupingBy(
                t -> SavedQuestion.optionKey(t.term()), java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
        long rescued = extraction.result().evidence().stream().filter(e -> !e.repetitions().isEmpty() && accepted.contains(e.term())).count();
        long duplicated = extraction.result().evidence().stream().filter(e -> titles.get(SavedQuestion.optionKey(e.term().term())) > 1
                && !accepted.contains(e.term()) && !inspector.screenedTerms(new TermExtractor.Result(List.of(e.term()), List.of(), List.of(e))).isEmpty()).count();
        StringBuilder out = new StringBuilder("추출 방식: STRUCTURED\n구조 규칙으로 추출된 용어 ").append(extraction.stats().addedTerms()).append("개")
                .append("\n동일 쌍으로 통합된 반복 ").append(extraction.stats().mergedOccurrences()).append("개, screen 구제 ").append(rescued)
                .append("개, 여전히 중복 제목으로 제외 ").append(duplicated).append("개")
                .append("\n기존 추출 경고는 원래 처리 이력이며 아래 구조 출처로 연결된 줄을 포함할 수 있습니다.");
        for (var e : extraction.result().evidence()) if (e.sourceKind() == TermExtractor.SourceKind.TITLE_BULLET || !e.repetitions().isEmpty()) {
            out.append("\n").append(e.sourceKind() == TermExtractor.SourceKind.TITLE_BULLET ? "제목+불릿" : "기존 규칙+반복 출처")
                    .append(": ").append(e.term().term()).append(" / ").append(e.termLine().page()).append("페이지 ").append(e.termLine().lineNumber()).append("행")
                    .append(" / 설명 행 ").append(e.descriptionLines().stream().map(TermExtractor.SourceLine::lineNumber).toList());
            for (var repeat : e.repetitions()) out.append(" / 반복 ").append(repeat.title().page()).append("페이지 ").append(repeat.title().lineNumber())
                    .append("행(설명 ").append(repeat.descriptionLines().stream().map(TermExtractor.SourceLine::lineNumber).toList()).append(")");
        }
        return out.toString();
    }

    private static String optionReport(GenerationOptions options, QuizGenerator.OxResult ox,
            QuizGenerator.MultipleChoiceResult multiple) {
        long reverse = multiple.questions().stream().filter(QuizGenerator::reverse).count();
        return "O/X 요청 " + (options.oxCount() == null ? "가능한 전부" : options.oxCount() + "개")
                + ", 생성 " + ox.questions().size() + "개, 사유: "
                + (options.oxCount() == null ? "기존 전체 매칭 결과 유지" : ox.questions().size() < options.oxCount()
                        ? "후보 또는 균형 가능한 문항 부족" : "요청 개수 충족")
                + "\n객관식 요청 " + options.multipleChoiceCount() + "개, 생성 " + multiple.questions().size() + "개, 사유: "
                + (multiple.questions().size() < options.multipleChoiceCount() ? "필터/방향 조건을 만족하는 후보 부족" : "요청 개수 충족")
                + " (정방향 " + (multiple.questions().size() - reverse) + "개, 역방향 " + reverse + "개)";
    }

    public Preview editQuestion(Preview preview, String id, String question, List<String> options,
            int correctIndex, String explanation) {
        SavedQuestion original = preview.set().questions().stream().filter(q -> q.id().equals(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("검토 목록에 없는 문제 ID입니다."));
        SavedQuestion edited = original.edited(question, options, correctIndex, explanation);
        SavedQuizSet updated = preview.set().withQuestions(
                preview.set().questions().stream().map(q -> q.id().equals(id) ? edited : q).toList());
        return new Preview(updated, preview.skipped(), preview.diagnostics());
    }

    public Preview addQuestion(Preview preview, SavedQuestion.Type type, String question,
            List<String> options, int correctIndex, String explanation) {
        return addQuestion(preview, type, question, options, correctIndex, explanation, null, null);
    }

    public Preview addQuestion(Preview preview, SavedQuestion.Type type, String question,
            List<String> options, int correctIndex, String explanation, Integer page, Integer lineNumber) {
        if (explanation == null || explanation.isBlank())
            throw new IllegalArgumentException("해설을 입력하세요.");
        String text = explanation.strip();
        if (text.equals("[직접 작성]")) throw new IllegalArgumentException("해설 내용을 입력하세요.");
        if (!text.startsWith("[직접 작성] ")) text = "[직접 작성] " + text;
        SavedQuestion added = new SavedQuestion(UUID.randomUUID().toString(), type, question,
                options, correctIndex, text, preview.set().sourcePdf(), page, lineNumber, SavedQuestion.Origin.MANUAL, null);
        List<SavedQuestion> questions = new ArrayList<>(preview.set().questions());
        questions.add(added);
        return new Preview(preview.set().withQuestions(questions),
                preview.skipped(), preview.diagnostics());
    }

    public SavedQuizSet saveReviewed(Preview preview, Set<String> excludedIds) throws IOException {
        return saveReviewed(preview, excludedIds, preview.set().title(), preview.set().subject());
    }

    public SavedQuizSet saveReviewed(Preview preview, Set<String> excludedIds, String title, String subject) throws IOException {
        SavedQuizSet reviewed = preview.set().withQuestions(reviewedQuestions(preview, excludedIds))
                .forSave(title, subject, clock.instant());
        quizzes.save(reviewed);
        return reviewed;
    }

    private List<SavedQuestion> reviewedQuestions(Preview preview, Set<String> excludedIds) {
        if (excludedIds.stream().anyMatch(id -> preview.set().questions().stream()
                .noneMatch(question -> question.id().equals(id)))) {
            throw new IllegalArgumentException("검토 목록에 없는 문제 ID가 있습니다.");
        }
        List<SavedQuestion> kept = preview.set().questions().stream()
                .filter(question -> !excludedIds.contains(question.id())).toList();
        if (kept.isEmpty()) throw new IllegalArgumentException("저장할 문항이 없습니다. 최소 1개 문항을 남겨 주세요.");
        return kept;
    }

    private List<SavedQuestion> revisionQuestions(SavedQuizSet original, Preview preview,
            Set<String> excludedIds) {
        List<SavedQuestion> questions = preview.set().questions();
        int originalCount = original.questions().size();
        if (!original.id().equals(preview.set().id())
                || !original.sourcePdf().equals(preview.set().sourcePdf())
                || questions.size() < originalCount
                || !original.questions().stream().map(SavedQuestion::id).toList()
                        .equals(questions.stream().limit(originalCount).map(SavedQuestion::id).toList())) {
            throw new IllegalArgumentException("원본 세트와 수정 중인 검토 목록이 일치하지 않습니다.");
        }
        Set<String> originalIds = original.questions().stream().map(SavedQuestion::id)
                .collect(java.util.stream.Collectors.toSet());
        for (int i = 0; i < originalCount; i++) {
            SavedQuestion a = original.questions().get(i), b = questions.get(i);
            if (!a.sourcePdf().equals(b.sourcePdf()) || !java.util.Objects.equals(a.page(), b.page())
                    || !java.util.Objects.equals(a.lineNumber(), b.lineNumber()) || a.origin() != b.origin()
                    || !java.util.Objects.equals(a.derivedFromQuestionId(), b.derivedFromQuestionId()))
                throw new IllegalArgumentException("원본 문항의 출처·페이지·줄·생성 구분·연결 정보는 변경할 수 없습니다.");
        }
        for (SavedQuestion question : questions.subList(originalCount, questions.size())) {
            if (originalIds.contains(question.id()) || !original.sourcePdf().equals(question.sourcePdf())
                    || question.origin() != SavedQuestion.Origin.MANUAL || question.derivedFromQuestionId() != null
                    || question.lineNumber() != null && question.page() == null) {
                throw new IllegalArgumentException("추가 문항은 새 ID·세트 출처·직접 작성 구분을 사용하고 연결 정보는 없어야 합니다.");
            }
        }
        // SavedQuestion/SavedQuizSet 생성자가 필수 값·보기·정답·출처 일치·중복 ID를 검증한다.
        return reviewedQuestions(preview, excludedIds);
    }

    public boolean hasReviewChanges(SavedQuizSet original, Preview preview, Set<String> excludedIds) {
        return !original.questions().equals(revisionQuestions(original, preview, excludedIds));
    }

    public SavedRevision saveRevision(SavedQuizSet original, Preview preview, Set<String> excludedIds,
            boolean confirmedUnchangedCopy) throws IOException {
        return saveRevision(original, preview, excludedIds, confirmedUnchangedCopy,
                QuizMetadata.fallback(original.title() + " (수정본)"), original.subject());
    }

    /** 연결 정보는 바로 직전 원본 하나만 가리키며, 이번에 추가한 문항은 문항 연결을 갖지 않는다. */
    public SavedRevision saveRevision(SavedQuizSet original, Preview preview, Set<String> excludedIds,
            boolean confirmedUnchangedCopy, String title, String subject) throws IOException {
        List<SavedQuestion> kept = revisionQuestions(original, preview, excludedIds);
        if (original.questions().equals(kept) && !confirmedUnchangedCopy) {
            throw new IllegalArgumentException("변경 사항이 없습니다. 같은 내용의 복제본 저장을 확인해 주세요.");
        }
        List<SavedQuestion> copied = kept.stream().map(question -> new SavedQuestion(
                UUID.randomUUID().toString(), question.type(), question.question(), question.options(),
                question.correctIndex(), question.explanation(), question.sourcePdf(), question.page(),
                question.lineNumber(), question.origin(), original.questions().stream()
                        .anyMatch(q -> q.id().equals(question.id())) ? question.id() : null)).toList();
        SavedQuizSet revision = new SavedQuizSet(UUID.randomUUID().toString(), original.sourcePdf(), copied,
                title, subject, clock.instant(), original.id());
        Path path = quizzes.save(revision);
        return new SavedRevision(revision, path);
    }

    public SavedQuizSet loadQuiz(Path file) throws IOException {
        return quizzes.load(file.toString());
    }

    public QuizRepository.Listing listQuizzes() throws IOException { return quizzes.list(); }

    public Path exportQuiz(Path source, Path target) throws IOException { return new com.quiz.QuizTransfer(quizzes, clock).export(source, target); }
    public com.quiz.QuizTransfer.ImportPlan inspectImport(Path file) throws IOException { return new com.quiz.QuizTransfer(quizzes, clock).inspect(file); }
    public com.quiz.QuizTransfer.Imported importQuiz(com.quiz.QuizTransfer.ImportPlan plan, boolean asCopy) throws IOException {
        return new com.quiz.QuizTransfer(quizzes, clock).commit(plan, asCopy);
    }

    public Review loadReview(Path quizFile, Path resultFile) throws IOException {
        SavedQuizSet set = loadQuiz(quizFile);
        QuizResult previous = results.load(resultFile.toString(), set);
        return new Review(set, previous, previous.wrongQuestionsRecentFirst(set));
    }

    public QuizSession start(SavedQuizSet set) {
        return new QuizSession(set, set.questions(), null, clock);
    }

    public QuizSession startReview(Review review) {
        return new QuizSession(review.set(), review.wrong(), review.previous().id(), clock);
    }

    public Path saveResult(QuizSession session) throws IOException {
        return results.save(session.finish(), session.set());
    }

    public com.quiz.WrongAnswerNotebook.Snapshot notebook() throws IOException {
        return new com.quiz.WrongAnswerNotebook().load(quizzes, results);
    }

    public QuizSession startNotebook(SavedQuizSet set, List<SavedQuestion> questions) {
        if (questions.isEmpty()) throw new IllegalArgumentException("선택한 문제가 없습니다.");
        return new QuizSession(set, questions, null, clock);
    }
}
