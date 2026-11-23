package com.quiz;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;

public class QuizGenerator {
    public record OxQuestion(String statement, String presentedDescription, boolean answer, Term original,
            Term descriptionSource) {
        public OxQuestion(String statement, String description, boolean answer, Term original) {
            this(statement, description, answer, original, description.equals(original.description()) ? original : null);
        }
        public OxQuestion(String statement, boolean answer, Term original) {
            this(statement, original.description(), answer, original);
        }
        /** 기존 호출 호환. X에서는 A의 원래 설명이 아니라 제시된 B 설명이다. */
        public String description() { return presentedDescription; }
        public String explanation() {
            String actual = original.term() + " — " + original.description();
            if (answer || descriptionSource == null) return actual;
            return actual + ". 제시된 설명 '" + presentedDescription + "'은 '" + descriptionSource.term()
                    + "'의 설명입니다 (" + pageLabel(descriptionSource) + ").";
        }
    }
    public record OxResult(List<OxQuestion> questions, List<String> skipped, int candidateCount) {
        public OxResult {
            questions = List.copyOf(questions);
            skipped = List.copyOf(skipped);
        }
        public String summary() {
            long count = questions.stream().filter(OxQuestion::answer).count();
            return "O/X 생성 결과: " + questions.size() + "문항 (O " + count + ", X "
                    + (questions.size() - count) + ")"
                    + (Math.abs(2 * count - questions.size()) > 1 ? " — 대체 설명 후보가 부족해 균형이 맞지 않음"
                            : questions.size() % 2 == 1 ? " — 홀수 문항 수로 완전한 동수 배정 불가" : "");
        }
    }
    private record Screened(List<Term> eligible, Map<Term, Integer> lines, List<String> skipped) { }
    public record MultipleChoiceResult(List<MultipleChoiceQuestion> questions, List<String> skipped,
            int candidateCount) {
        public MultipleChoiceResult {
            questions = List.copyOf(questions);
            skipped = List.copyOf(skipped);
        }
        public MultipleChoiceResult(List<MultipleChoiceQuestion> questions, List<String> skipped) {
            this(questions, skipped, questions.size());
        }
    }

    private final Random random;

    public QuizGenerator() {
        this(new Random());
    }

    public QuizGenerator(Random random) {
        this.random = random;
    }

    public List<OxQuestion> generateOx(List<Term> terms) {
        return generateOxDetailed(new TermExtractor.Result(terms, List.of())).questions();
    }

    public OxResult generateOxDetailed(TermExtractor.Result extracted) {
        Screened screened = screenOx(extracted);
        List<Term> eligible = screened.eligible();
        List<String> skipped = new ArrayList<>(screened.skipped());
        List<OxQuestion> questions = new ArrayList<>();
        OxAllocator allocator = new OxAllocator(eligible, random);
        int[] shown = allocator.allocate();
        for (int i = 0; i < eligible.size(); i++) {
            Term term = eligible.get(i);
            if (shown[i] < 0) {
                skipped.add("O/X 문항 제외: " + term.page() + "쪽 '" + term.term()
                        + "' — 설명 중복 제시를 막기 위해 다른 X 문항의 B(설명 제공용)로만 사용");
                continue;
            }
            Term source = eligible.get(shown[i]);
            questions.add(new OxQuestion(sentence(term), source.description(), shown[i] == i, term, source));
            if (shown[i] == i && !allocator.hasAlternative(i)) skipped.add("O/X X 배정 제외 (O로 출제): '"
                    + term.term() + "' — 필터와 100자 기준을 통과한 대체 설명 후보가 없음");
        }
        Collections.shuffle(questions, random);
        OxResult result = new OxResult(questions, skipped, eligible.size());
        if (Math.abs(2 * questions.stream().filter(OxQuestion::answer).count() - questions.size()) > 1)
            skipped.add("O/X 균형 제한: 대체 설명 후보가 부족해 균형이 맞지 않음 — 설명 중복 금지와 후보당 최대 1문항을 유지함");
        return new OxResult(questions, skipped, result.candidateCount());
    }

    public int oxCandidateCount(List<Term> terms) {
        return screenOx(new TermExtractor.Result(terms, List.of())).eligible().size();
    }

    /** 전체 매칭 후 균형 부분집합을 고른다. 홀수의 추가 1개는 O 우선, 불가능하면 X다. 난수 소비 없음. */
    public OxResult generateOxDetailed(TermExtractor.Result extracted, Integer count) {
        if (count != null && (count < 0 || count > GenerationOptions.MAX_COUNT)) throw new IllegalArgumentException("O/X 개수 범위 오류");
        OxResult full = generateOxDetailed(extracted);
        if (count == null) return full;
        int availableO = (int) full.questions().stream().filter(OxQuestion::answer).count();
        int availableX = full.questions().size() - availableO;
        int target = Math.min(count, full.questions().size());
        int pairs = Math.min(target / 2, Math.min(availableO, availableX));
        int o = pairs, x = pairs;
        if (2 * pairs < target) {
            if (availableO > o) o++;
            else if (availableX > x) x++;
        }
        List<OxQuestion> kept = new ArrayList<>();
        for (OxQuestion q : full.questions()) {
            if (q.answer() && o > 0) { kept.add(q); o--; }
            else if (!q.answer() && x > 0) { kept.add(q); x--; }
        }
        List<String> skipped = new ArrayList<>(full.skipped());
        for (OxQuestion q : full.questions()) if (!kept.contains(q))
            skipped.add("O/X 개수/균형 제한으로 제외: '" + q.original().term() + "' — 설명 중복 금지와 정답 균형 유지");
        skipped.add("O/X 요청 " + count + "개, 생성 " + kept.size() + "개, 사유: "
                + (kept.size() < count ? "후보 또는 균형 가능한 문항 부족" : "요청 개수 충족"));
        return new OxResult(kept, skipped, full.candidateCount());
    }

    private static String sentence(Term term) {
        return "다음 설명은 '" + term.term() + "'에 대한 설명이다.";
    }

    private static String pageLabel(Term term) { return term.page() > 0 ? term.page() + "페이지" : "페이지 없음"; }

    private static Screened screenOx(TermExtractor.Result extracted) {
        Screened screened = screen(extracted);
        List<String> skipped = new ArrayList<>(screened.skipped());
        List<Term> shortEnough = new ArrayList<>();
        for (Term term : screened.eligible()) {
            if (term.description().codePointCount(0, term.description().length()) <= 100) shortEnough.add(term);
            else skipped.add("O/X 제외: " + term.page() + "쪽 '" + term.term() + "' — 설명이 100자를 초과함");
        }
        List<Term> unique = new ArrayList<>();
        for (Term term : shortEnough) {
            String key = SavedQuestion.optionKey(term.description());
            if (shortEnough.stream().filter(t -> SavedQuestion.optionKey(t.description()).equals(key)).count() == 1) unique.add(term);
            else skipped.add("O/X 제외: '" + term.term() + "' — 정규화 후 중복된 제시 설명");
        }
        return new Screened(List.copyOf(unique), screened.lines(), List.copyOf(skipped));
    }

    public void runOx(List<Term> terms, Scanner input, PrintStream output) {
        List<OxQuestion> questions = generateOx(terms);
        if (questions.isEmpty()) {
            output.println("내용이 부족합니다");
            return;
        }
        int score = 0;
        for (int i = 0; i < questions.size(); i++) {
            OxQuestion question = questions.get(i);
            output.printf("문제 %d/%d: %s%n", i + 1, questions.size(), question.statement());
            output.println("설명: " + question.presentedDescription());
            String answer;
            while (true) {
                output.print("답 (O/X): ");
                if (!input.hasNextLine()) {
                    output.println("입력이 종료되어 퀴즈를 중단합니다.");
                    return;
                }
                answer = input.nextLine().strip();
                if (answer.equalsIgnoreCase("O") || answer.equalsIgnoreCase("X")) break;
                output.println("O 또는 X를 입력하세요.");
            }
            boolean correct = answer.equalsIgnoreCase("O") == question.answer();
            if (correct) score++;
            output.println(correct ? "정답입니다." : "오답입니다.");
            output.println("정답: " + (question.answer() ? "O" : "X"));
            if (question.answer()) output.printf("해설: %s - %s (%s)%n", question.original().term(),
                    question.original().description(), question.original().page() > 0 ? "페이지 " + question.original().page() : "페이지 없음");
            else output.println("해설: " + question.explanation());
        }
        output.printf("점수: %d/%d%n", score, questions.size());
    }

    public MultipleChoiceResult generateMultipleChoice(List<Term> terms) {
        return generateMultipleChoice(new TermExtractor.Result(terms, List.of()));
    }

    public MultipleChoiceResult generateMultipleChoice(TermExtractor.Result extracted) {
        return generateMultipleChoice(extracted, 30, GenerationOptions.Direction.FORWARD);
    }

    public MultipleChoiceResult generateMultipleChoice(List<Term> terms, int count, GenerationOptions.Direction direction) {
        return generateMultipleChoice(new TermExtractor.Result(terms, List.of()), count, direction);
    }

    public static final String REVERSE_PROMPT = "다음 설명에 해당하는 용어는?";

    /** 포함 검사는 NFKC/소문자/공백 정규화와 공백·문장부호 제거 비교를 함께 적용한다. */
    public static String exposureKey(String text) {
        return SavedQuestion.optionKey(text).replaceAll("(?U)[\\s\\p{P}]+", "");
    }

    public static boolean exposes(String text, String term) {
        return SavedQuestion.optionKey(text).contains(SavedQuestion.optionKey(term))
                || exposureKey(text).contains(exposureKey(term));
    }

    public static boolean reverse(MultipleChoiceQuestion q) { return q.prompt().startsWith(REVERSE_PROMPT); }

    public MultipleChoiceResult generateMultipleChoice(TermExtractor.Result extracted, int count, GenerationOptions.Direction direction) {
        if (count < 0 || count > GenerationOptions.MAX_COUNT || direction == null)
            throw new IllegalArgumentException("객관식 생성 옵션 오류");
        Screened screened = screen(extracted);
        List<Term> eligible = screened.eligible();
        Map<Term, Integer> eligibleLines = screened.lines();
        List<String> skipped = new ArrayList<>(screened.skipped());
        skipped.add("'옳지 않은 것은?'은 한 개념의 참인 특징 3개와 거짓인 특징 1개를 "
                + "PDF 내용으로 검증할 수 없어 생성하지 않았습니다.");
        if (direction != GenerationOptions.Direction.FORWARD) {
            long exposed = eligible.stream().filter(t -> exposes(REVERSE_PROMPT + "\n\n설명: " + t.description(), t.term())).count();
            skipped.add("역방향 정답 노출로 제외된 앵커 " + exposed + "개");
        }
        List<MultipleChoiceQuestion> questions = new ArrayList<>();
        if (eligible.size() < 4) {
            skipped.add("서로 다른 용어와 설명이 4쌍 미만이라 객관식 문제를 만들 수 없습니다.");
            return new MultipleChoiceResult(questions, skipped, eligible.size());
        }
        List<Term> shuffled = new ArrayList<>(eligible);
        Collections.shuffle(shuffled, random);
        List<Term> reverseAnchors = new ArrayList<>();
        if (direction != GenerationOptions.Direction.FORWARD) {
            List<Term> selected = new ArrayList<>();
            List<Term> reversible = new ArrayList<>();
            for (Term a : shuffled) {
                String prompt = REVERSE_PROMPT + "\n\n설명: " + a.description();
                boolean canReverse = !exposes(prompt, a.term()) && eligible.stream()
                        .filter(b -> isDistractor(a, b) && !exposes(prompt, b.term())).count() >= 3;
                boolean canForward = eligible.stream().filter(b -> isDistractor(a, b)).count() >= 3;
                if (!canReverse) skipped.add("역방향 제외: '" + a.term() + "' — 용어 노출 또는 안전한 오답 3개 부족");
                if (canReverse) reversible.add(a);
                if (direction == GenerationOptions.Direction.REVERSE ? canReverse : canForward)
                    selected.add(a);
            }
            int size = Math.min(count, selected.size());
            int desiredReverse = direction == GenerationOptions.Direction.REVERSE ? size : (size + 1) / 2;
            reverseAnchors.addAll(reversible.subList(0, Math.min(desiredReverse, reversible.size())));
            List<Term> chosen = new ArrayList<>(reverseAnchors);
            for (Term a : selected) if (chosen.size() < size && !chosen.contains(a)) chosen.add(a);
            shuffled = selected.stream().filter(chosen::contains).toList();
        }
        List<Integer> positions = new ArrayList<>();
        for (Term anchor : shuffled) {
            if (questions.size() >= count) break;
            String reversePrompt = REVERSE_PROMPT + "\n\n설명: " + anchor.description();
            List<Term> reverseOthers = direction == GenerationOptions.Direction.FORWARD ? List.of() : eligible.stream()
                    .filter(other -> isDistractor(anchor, other) && !exposes(reversePrompt, other.term())).toList();
            boolean reversePossible = !exposes(reversePrompt, anchor.term()) && reverseOthers.size() >= 3;
            if (direction != GenerationOptions.Direction.FORWARD && !reversePossible)
                skipped.add("역방향 제외: '" + anchor.term() + "' — 정답/오답 용어 노출 또는 안전한 오답 용어 3개 부족"
                        + (direction == GenerationOptions.Direction.MIXED ? " (정방향으로 전환)" : ""));
            if (direction == GenerationOptions.Direction.REVERSE && !reversePossible) continue;
            boolean reverse = reverseAnchors.contains(anchor);
            List<Term> others = new ArrayList<>(eligible.stream().filter(other ->
                    isDistractor(anchor, other)).toList());
            if (reverse) others = new ArrayList<>(reverseOthers);
            if (others.size() < 3) {
                skipped.add("'" + anchor.term()
                        + "': 문자열 또는 용어가 겹치지 않는 다른 설명이 3개 미만이라 건너뜁니다.");
                continue;
            }
            Collections.shuffle(others, random);
            others.sort(Comparator.comparingInt(other ->
                    reverse ? Math.abs(other.term().length() - anchor.term().length()) : descriptionDistance(anchor, other)));
            List<Term> choices = new ArrayList<>();
            choices.add(anchor);
            choices.addAll(others.subList(0, 3));
            Collections.shuffle(choices, random);
            // 성공적으로 생성한 문항 4개마다 각 정답 위치를 한 번씩 무작위 배정한다.
            if (questions.size() % 4 == 0) {
                positions = new ArrayList<>(List.of(0, 1, 2, 3));
                Collections.shuffle(positions, random);
            }
            Collections.swap(choices, choices.indexOf(anchor), positions.get(questions.size() % 4));
            List<String> options = choices.stream().map(reverse ? Term::term : Term::description).toList();
            StringBuilder explanation = new StringBuilder(reverse
                    ? "정답은 '" + anchor.term() + "'입니다. 제시된 설명은 " + anchor.term() + "의 설명입니다. (" + pageLabel(anchor) + ")"
                    : "PDF에서 '" + anchor.term() + "'에 직접 연결된 설명은 '" + anchor.description() + "'입니다.");
            String[] labels = {"①", "②", "③", "④"};
            for (int option = 0; option < choices.size(); option++) {
                Term source = choices.get(option);
                if (source != anchor) {
                    explanation.append(" ").append(labels[option]).append(": '")
                            .append(source.term()).append(reverse ? "'는 (" + source.description() + ")에 연결된 용어입니다." : "'의 설명입니다.");
                }
            }
            questions.add(new MultipleChoiceQuestion(MultipleChoiceQuestion.Type.CORRECT,
                    reverse ? reversePrompt : "PDF에서 '" + anchor.term() + "'에 직접 연결된 설명으로 옳은 것은?",
                    options, choices.indexOf(anchor), explanation.toString(),
                    anchor, eligibleLines.get(anchor)));
        }
        return new MultipleChoiceResult(questions, skipped, eligible.size());
    }

    static boolean isDistractor(Term anchor, Term other) {
        return other != anchor && !other.term().contains(anchor.term()) && !anchor.term().contains(other.term())
                && !other.description().contains(anchor.term()) && !anchor.description().contains(other.term())
                && !overlappingDescription(anchor.description(), other.description());
    }

    static int descriptionDistance(Term anchor, Term other) {
        return Math.abs(other.description().length() - anchor.description().length());
    }

    /** 추출 진단용 읽기 전용 조회. 기존 screen의 검증 조건/난수 상태는 변경하지 않는다. */
    public List<Term> screenedTerms(TermExtractor.Result extracted) { return screen(extracted).eligible(); }

    private static Screened screen(TermExtractor.Result extracted) {
        List<Term> original = extracted.terms();
        Map<Term, TermExtractor.Evidence> sources = new IdentityHashMap<>();
        for (TermExtractor.Evidence evidence : extracted.evidence()) {
            sources.put(evidence.term(), evidence);
        }
        List<Term> eligible = new ArrayList<>();
        Map<Term, Integer> eligibleLines = new IdentityHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (Term raw : original) {
            TermExtractor.Evidence source = sources.get(raw);
            String where = raw.page() + "쪽"
                    + (source == null || source.termLine() == null ? ""
                            : " " + source.termLine().lineNumber() + "행");
            Term candidate = raw;
            String reason = null;
            if (source != null && !source.interruptions().isEmpty()) {
                Term restored = restoreBeforeInterruption(raw, source);
                if (restored == null) {
                    reason = "빈칸 뒤의 다른 문장이 설명에 합쳐졌으며, 앞부분만으로는 완결된 설명을 확인할 수 없음";
                } else {
                    candidate = restored;
                    skipped.add("복원: " + where + " '" + raw.term()
                            + "' — 빈칸 앞의 원본 설명 줄만 사용하고 뒤의 줄은 제외");
                }
            }
            if (reason == null) reason = exclusionReason(candidate);
            if (reason == null && (original.stream().filter(other ->
                    SavedQuestion.optionKey(other.term()).equals(SavedQuestion.optionKey(raw.term()))).count() > 1
                    || original.stream().filter(other ->
                    other != raw && overlappingDescription(other.description(), raw.description())).count() > 0)) {
                reason = "중복되거나 매우 유사한 용어·설명이 있어 정답 관계가 유일하지 않음";
            }
            if (reason == null) {
                eligible.add(candidate);
                if (source != null && source.termLine() != null) {
                    eligibleLines.put(candidate, source.termLine().lineNumber());
                }
            } else {
                skipped.add("제외/검토 필요: " + where + " '" + raw.term() + "' — " + reason);
            }
        }
        return new Screened(List.copyOf(eligible), eligibleLines, List.copyOf(skipped));
    }

    private static Term restoreBeforeInterruption(Term raw, TermExtractor.Evidence source) {
        int boundary = source.interruptions().get(0).lineNumber();
        List<TermExtractor.SourceLine> before = source.descriptionLines().stream()
                .filter(line -> line.lineNumber() < boundary).toList();
        if (before.size() != 1 || !before.get(0).text().strip().startsWith(":")) return null;
        String description = before.get(0).text().strip().substring(1).strip();
        // 문장 끝이 확인되는 한 줄만 복원한다. 미완성 구절은 검토 대상으로 남긴다.
        if (!description.matches(".*(됨|것|과정|이다|한다)$")) return null;
        Term restored = new Term(raw.term(), description, raw.page());
        return existingExclusionReason(restored) == null ? restored : null;
    }

    private static final String BRACKET_REASON = "용어 또는 설명의 (), [], {} 짝이 맞지 않음";

    private static String exclusionReason(Term term) {
        String existing = existingExclusionReason(term);
        List<String> reasons = new ArrayList<>();
        if (existing != null) reasons.add(existing);
        if (hasUnbalancedBrackets(term.term()) || hasUnbalancedBrackets(term.description())) reasons.add(BRACKET_REASON);
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }

    /** 전각 호환 문자를 검사 중에만 정규화한다. 용어/설명을 서로 합산하지 않는다. */
    private static boolean hasUnbalancedBrackets(String text) {
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '(' || c == '[' || c == '{') stack.push(c);
            else if (c == ')' || c == ']' || c == '}') {
                char opening = c == ')' ? '(' : c == ']' ? '[' : '{';
                if (stack.isEmpty() || stack.pop() != opening) return true;
            }
        }
        return !stack.isEmpty();
    }

    private static String existingExclusionReason(Term term) {
        String name = term.term().strip();
        String description = term.description().strip();
        List<String> reasons = new ArrayList<>();
        if (name.isEmpty() || description.isEmpty()) reasons.add("용어 또는 설명이 비어 있음");
        if (name.length() > 24) reasons.add("용어가 문장형으로 길어 개념명인지 불명확함");
        if (description.length() < 8) reasons.add("설명이 짧아 관계를 확인하기 어려움");
        if (description.length() > 180) reasons.add("설명에 여러 항목이 합쳐졌을 가능성이 큼");
        if (name.matches(".*[_&].*")) reasons.add("용어가 빈칸·인용 표기를 포함함");
        if (description.contains("_")) reasons.add("설명에 빈칸이 남아 있음");
        if (name.matches(".*(됨|있음|없음|아님|나타남|의 연구)$"))
            reasons.add("용어가 개념명보다 문장·연구 표제에 가까움");
        if (description.matches("(?s).*(?:^|\\s)[•▪●■◦○·-]\\s+.*"))
            reasons.add("설명에 다음 목록 항목이 합쳐짐");
        if (description.matches("(?s).*[，,]\\s{2,}.*"))
            reasons.add("설명 절 사이의 공백이 비정상적으로 합쳐져 관계 확인 필요");
        if (description.matches("(?s).*관찰하고\\s+강화\\s*받는\\s*것.*"))
            reasons.add("강화를 받는 주체가 문장만으로 불명확해 원본 검토 필요");
        if (description.matches("(?is).*(?:https?://|www\\.|[\\w.-]+\\.(?:com|net|org|kr)\\b|[{}<>]|[a-z_][a-z_0-9]*\\().*"))
            reasons.add("설명에 URL 또는 코드형 문자열이 포함됨");
        if (description.matches("(?s).*\\b[0-9]+[.)]\\s*[^0-9].*"))
            reasons.add("설명에 다른 항목 번호가 들어 있음");
        if (description.matches("(?s).*(?<!에) 의해 .*"))
            reasons.add("설명 일부가 끊겨 문맥 연결이 불명확함");
        if (description.matches("(?is).*(?:e\\.g\\.|ex\\.|\\bbut\\b).*"))
            reasons.add("설명에 예시 또는 대비 문장이 합쳐짐");
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }

    private static boolean overlappingDescription(String left, String right) {
        String a = SavedQuestion.optionKey(left).replaceAll("[\\s\\p{P}]+", "");
        String b = SavedQuestion.optionKey(right).replaceAll("[\\s\\p{P}]+", "");
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    public void runMultipleChoice(List<Term> terms, Scanner input, PrintStream output) {
        runMultipleChoice(new TermExtractor.Result(terms, List.of()), input, output);
    }

    public void runMultipleChoice(TermExtractor.Result extracted, Scanner input, PrintStream output) {
        MultipleChoiceResult result = generateMultipleChoice(extracted);
        for (String reason : result.skipped()) {
            output.println(reason.startsWith("복원:") || reason.startsWith("제외/검토 필요:")
                    ? reason : "건너뜀: " + reason);
        }
        if (result.questions().isEmpty()) return;
        String[] labels = {"①", "②", "③", "④"};
        int score = 0;
        for (int i = 0; i < result.questions().size(); i++) {
            MultipleChoiceQuestion question = result.questions().get(i);
            output.printf("문제 %d/%d: %s%n", i + 1, result.questions().size(), question.prompt());
            for (int option = 0; option < 4; option++) {
                output.println(labels[option] + " " + question.options().get(option));
            }
            int answer;
            while (true) {
                output.print("답 (1~4): ");
                if (!input.hasNextLine()) {
                    output.println("입력이 종료되어 퀴즈를 중단합니다.");
                    return;
                }
                String entered = input.nextLine().strip();
                if (entered.matches("[1-4]")) {
                    answer = Integer.parseInt(entered) - 1;
                    break;
                }
                output.println("1~4 사이의 번호를 입력하세요.");
            }
            boolean correct = answer == question.correctIndex();
            if (correct) score++;
            output.println(correct ? "정답입니다." : "오답입니다.");
            output.println("정답: " + labels[question.correctIndex()]);
            output.println("해설: " + question.explanation());
        }
        output.printf("점수: %d/%d%n", score, result.questions().size());
    }
    public void runSavedQuestions(List<SavedQuestion> questions, Scanner input, PrintStream output) {
        String source = questions.isEmpty() ? "unknown.pdf" : questions.get(0).sourcePdf();
        playSavedQuestions(new SavedQuizSet(UUID.randomUUID().toString(), source, questions),
                questions, input, output, Clock.systemUTC(), null);
    }

    /** 보기 순서를 바꾸지 않고 답안을 기록한다. 입력 종료 시 답한 문항만 저장 대상으로 돌려준다. */
    public QuizResult playSavedQuestions(SavedQuizSet set, List<SavedQuestion> selected,
            Scanner input, PrintStream output, Clock clock, String reviewOfResultId) {
        if (selected.stream().anyMatch(q -> !set.questions().contains(q)))
            throw new IllegalArgumentException("선택한 문제가 원래 문제 세트에 없습니다.");
        List<AnswerRecord> answers = new ArrayList<>();
        Instant startedAt = clock.instant();
        String[] labels = {"①", "②", "③", "④"};
        if (selected.isEmpty()) output.println("저장된 문제가 없습니다.");
        for (int i = 0; i < selected.size(); i++) {
            SavedQuestion question = selected.get(i);
            output.printf("문제 %d/%d: %s%n", i + 1, selected.size(), question.question());
            if (question.type() == SavedQuestion.Type.MULTIPLE_CHOICE) {
                for (int option = 0; option < 4; option++)
                    output.println(labels[option] + " " + question.options().get(option));
            }
            output.printf("출처: %s%s%s%n", question.sourcePdf(),
                    question.page() == null ? "" : " (페이지 " + question.page(),
                    question.page() == null ? "" : question.lineNumber() == null
                            ? ")" : ", 줄 " + question.lineNumber() + ")");
            AnswerRecord answer = null;
            while (answer == null) {
                output.print(question.type() == SavedQuestion.Type.OX ? "답 (O/X): " : "답 (1~4): ");
                if (!input.hasNextLine()) {
                    output.println("입력이 종료되어 퀴즈를 중단합니다.");
                    break;
                }
                try {
                    answer = AnswerRecord.grade(question, input.nextLine(), clock.instant());
                } catch (IllegalArgumentException e) {
                    output.println(e.getMessage());
                }
            }
            if (answer == null) break;
            answers.add(answer);
            output.println(answer.correct() ? "정답입니다." : "오답입니다.");
            output.println("정답: " + (question.type() == SavedQuestion.Type.OX
                    ? question.options().get(question.correctIndex()) : labels[question.correctIndex()]));
            output.println("해설: " + question.explanation());
        }
        QuizResult result = new QuizResult(UUID.randomUUID().toString(), set.id(), set.sourcePdf(),
                reviewOfResultId, selected.size(), startedAt, answers);
        printSummary(set, selected.size(), result, output);
        return result;
    }

    public void printSummary(SavedQuizSet set, int total, QuizResult result, PrintStream output) {
        result.validateAgainst(set);
        output.printf("점수: %d/%d%n", result.correctCount(), total);
        output.printf("전체: %d문제, 정답 %d, 오답 %d, 미응답 %d, 정답률 %.1f%%%n",
                total, result.correctCount(), result.wrongCount(), total - result.answers().size(),
                percent(result.correctCount(), result.answers().size()));
        for (SavedQuestion.Type type : SavedQuestion.Type.values()) {
            long attempted = result.answers().stream().filter(a -> set.questions().stream()
                    .anyMatch(q -> q.id().equals(a.questionId()) && q.type() == type)).count();
            long correct = result.answers().stream().filter(AnswerRecord::correct).filter(a ->
                    set.questions().stream().anyMatch(q -> q.id().equals(a.questionId())
                            && q.type() == type)).count();
            output.printf("%s 정답률: %.1f%% (%d/%d)%n",
                    type == SavedQuestion.Type.OX ? "O/X" : "4지선다",
                    percent(correct, attempted), correct, attempted);
        }
    }

    private static double percent(long correct, long total) {
        return total == 0 ? 0 : correct * 100.0 / total;
    }
}
