package com.quiz;

import com.quiz.gui.QuizWorkflow;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

/** 기준 캡처·재생만 담당한다. 제품 코드와 기존 기준 리소스는 수정하지 않는다. */
public final class McBaselineCapture {
    static final Path BASELINE = Path.of("src/test/resources/baseline/mc-seed7-forward30-before.json");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--main-child")) {
            new QuizGenerator(new Random(7)); // 대상 클래스를 로드해 계측 적용 여부를 즉시 검사한다.
            if (McBaselineAgent.failure != null || McBaselineAgent.changedInstructions != 2)
                throw new IOException("Main 난수 계측 실패: 기본 생성자 명령 2개를 바꿔야 합니다.", McBaselineAgent.failure);
            Files.writeString(Path.of(args[1]), QuizJson.write(mainCases()), StandardOpenOption.CREATE_NEW);
            return;
        }
        if (args.length != 1 || !args[0].equals("--capture")) throw new IOException("캡처 실행은 --capture를 명시해야 합니다.");
        capture();
    }

    static void capture() throws Exception {
        if (Files.exists(BASELINE)) throw new IOException("고정 MC baseline은 덮어쓸 수 없습니다: " + BASELINE);
        Map<String, Object> value = current();
        Files.createDirectories(BASELINE.getParent());
        Files.writeString(BASELINE, QuizJson.write(value), StandardOpenOption.CREATE_NEW);
        System.out.println("Captured MC baseline: " + BASELINE);
    }

    static Map<String, Object> current() throws Exception {
        List<Object> documents = new ArrayList<>();
        List<Map<String, Object>> main = runMainChild();
        List<OxReviewFixture.Sample> samples = OxReviewFixture.samples();
        if (main.size() != samples.size()) throw new IOException("Main 캡처의 PDF 범위 수가 일치하지 않습니다.");
        Path scratch = scratch();
        for (int i = 0; i < samples.size(); i++) {
            OxReviewFixture.Sample sample = samples.get(i);
            if (sample.error() != null) throw new IOException(sample.key() + ": " + sample.error());
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("key", sample.key()); doc.put("pdf", sample.pdf().toString().replace('\\', '/'));
            doc.put("firstPage", sample.first()); doc.put("lastPage", sample.last()); doc.put("sha256", sha(sample.pdf()));
            List<Object> cases = new ArrayList<>();
            cases.add(workflowCase(sample, scratch, "PDF_RANGE"));
            if (sample.first() == 1 && new PdfReader().pageCount(sample.pdf()) <= 50) cases.add(workflowCase(sample, scratch, "PDF_WHOLE"));
            cases.add(workflowCase(sample, scratch, "MANUAL"));
            if (!sample.key().equals(main.get(i).get("key"))) throw new IOException("Main 캡처 범위 순서가 다릅니다.");
            cases.addAll((List<?>) main.get(i).get("cases"));
            doc.put("cases", cases); documents.add(doc);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("captureFormat", 1); result.put("seed", 7); result.put("forwardDefaultCount", 30);
        result.put("notes", List.of("UUID와 저장 시각은 비교 대상에서 제외한다. sourcePdf의 프로젝트 절대 경로 접두사만 <PROJECT>로 치환한다.",
                "PDF는 MC_EXTRACTED -> OX_DETAILED, 수동은 OX_DETAILED -> MC_LIST 순서다.",
                "Main은 별도 테스트 JVM의 기본 Random 생성자만 seed 7로 계측한다. 실제 Main 메뉴 1/4/5와 전체 읽기가 가능한 PDF의 runInteractive 메뉴 5를 실행한다.",
                "55페이지 PDF는 기존 readRange 1~50/51~55와 Main의 실제 runMenu 메서드로 검사한다. 콘솔 전체 PDF 읽기 제한을 변경하지 않는다.",
                "후속 난수 3개는 생성 완료 후 관찰하며 Main에서는 저장 직전 검사한다. 제품 알고리즘이나 제품 소스 사본을 테스트에 넣지 않는다."));
        result.put("documents", documents);
        return result;
    }

    private static Map<String, Object> workflowCase(OxReviewFixture.Sample sample, Path scratch, String kind) throws Exception {
        Random random = new Random(7);
        QuizWorkflow workflow = new QuizWorkflow(new QuizRepository(scratch.resolve(UUID.randomUUID().toString())),
                new QuizResultRepository(scratch.resolve(UUID.randomUUID().toString())), CLOCK, () -> new QuizGenerator(random));
        SavedQuizSet set;
        String order;
        if (kind.equals("MANUAL")) {
            Map<String, Term> unique = new LinkedHashMap<>();
            for (Term term : sample.extracted().terms()) unique.putIfAbsent(term.term().strip().toLowerCase(Locale.ROOT),
                    new Term(term.term(), term.description(), 0));
            ManualTermSet manual = new ManualTermSet(UUID.randomUUID().toString(), sample.pdf().toAbsolutePath().normalize().toString(), new ArrayList<>(unique.values()));
            set = workflow.generateManual(manual).set();
            order = "OX_DETAILED -> MC_LIST (수동 입력 규칙에 맞게 중복 용어를 첫 항목으로 통합, page=0)";
        } else {
            set = (kind.equals("PDF_WHOLE") ? workflow.generate(sample.pdf()) : workflow.generate(sample.pdf(), sample.first(), sample.last())).set();
            order = "MC_EXTRACTED -> OX_DETAILED";
        }
        return snapshot(kind, order, set, random);
    }

    private static List<Object> mainCases() throws Exception {
        List<Object> result = new ArrayList<>();
        Path scratch = scratch();
        int index = 0;
        for (var sample : OxReviewFixture.samples()) {
            if (sample.error() != null) throw new IOException(sample.error());
            List<Object> cases = new ArrayList<>();
            for (int menu : List.of(1, 4, 5)) cases.add(mainCase(sample, scratch.resolve("menu-" + index++), menu, false));
            if (sample.first() == 1 && new PdfReader().pageCount(sample.pdf()) <= 50)
                cases.add(mainCase(sample, scratch.resolve("interactive-" + index++), 5, true));
            Map<String, Object> doc = new LinkedHashMap<>(); doc.put("key", sample.key()); doc.put("cases", cases); result.add(doc);
        }
        return result;
    }

    private static Map<String, Object> mainCase(OxReviewFixture.Sample sample, Path directory, int menu, boolean interactive) throws Exception {
        McBaselineRandom.reset();
        List<Map<String, Object>> captured = new ArrayList<>();
        String kind = interactive ? "MAIN_INTERACTIVE_5" : "MAIN_MENU_" + menu;
        String order = menu == 1 ? "OX_LIST" : menu == 4 ? "MC_EXTRACTED" : "OX_LIST -> MC_EXTRACTED";
        QuizRepository repository = new QuizRepository(directory.resolve("quizzes")) {
            @Override public Path save(SavedQuizSet set) throws IOException {
                captured.add(snapshot(kind, order, set, McBaselineRandom.latest()));
                return super.save(set);
            }
        };
        QuizResultRepository results = new QuizResultRepository(directory.resolve("results"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            if (interactive) Main.runInteractive(new Scanner(sample.pdf().toAbsolutePath() + "\n5\n0\n"), out, repository, results, CLOCK);
            else {
                var method = Main.class.getDeclaredMethod("runMenu", TermExtractor.Result.class, String.class,
                        QuizRepository.class, QuizResultRepository.class, Scanner.class, PrintStream.class, Clock.class);
                method.setAccessible(true);
                try { method.invoke(null, sample.extracted(), sample.pdf().toAbsolutePath().normalize().toString(),
                        repository, results, new Scanner(menu + "\n"), out, CLOCK); }
                catch (InvocationTargetException e) { throw new IOException("실제 Main 메뉴 실행 실패", e.getCause()); }
            }
        }
        if (captured.size() != 1 || !bytes.toString(StandardCharsets.UTF_8).contains("문제 세트 저장:"))
            throw new IOException(kind + " 실제 Main 저장 경로 캡처에 실패했습니다: " + bytes.toString(StandardCharsets.UTF_8));
        return captured.getFirst();
    }

    static Map<String, Object> snapshot(String kind, String order, SavedQuizSet set, Random random) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("path", kind); snapshot.put("callOrder", order); snapshot.put("sourcePdf", source(set.sourcePdf()));
        List<Object> questions = new ArrayList<>();
        for (SavedQuestion q : set.questions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", q.type().name()); row.put("question", q.question()); row.put("options", q.options());
            row.put("correctIndex", q.correctIndex()); row.put("explanation", q.explanation());
            row.put("sourcePdf", source(q.sourcePdf())); row.put("page", q.page()); row.put("lineNumber", q.lineNumber());
            row.put("origin", q.origin().name()); row.put("derivedFromQuestionId", q.derivedFromQuestionId());
            questions.add(row);
        }
        snapshot.put("questions", questions);
        snapshot.put("nextRandomLongs", List.of(Long.toString(random.nextLong()), Long.toString(random.nextLong()), Long.toString(random.nextLong())));
        return snapshot;
    }

    static String source(String source) {
        return source.replace('\\', '/').replace(Path.of("").toAbsolutePath().normalize().toString().replace('\\', '/'), "<PROJECT>");
    }
    static String sha(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private static Path scratch() throws IOException { Files.createDirectories(Path.of("target")); return Files.createTempDirectory(Path.of("target"), "mc-baseline-").toAbsolutePath(); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> runMainChild() throws Exception {
        Path directory = scratch(), jar = directory.resolve("seed-agent.jar"), output = directory.resolve("main-cases.json"), log = directory.resolve("main.log");
        Manifest manifest = new Manifest(); manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", McBaselineAgent.class.getName());
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(jar, StandardOpenOption.CREATE_NEW), manifest)) {
            for (String name : List.of("McBaselineAgent", "McBaselineAgent$1", "McBaselineRandom")) {
                String entry = "com/quiz/" + name + ".class";
                try (var input = McBaselineCapture.class.getResourceAsStream("/" + entry)) {
                    if (input == null) throw new IOException("계측 테스트 클래스 없음: " + entry);
                    archive.putNextEntry(new JarEntry(entry)); input.transferTo(archive); archive.closeEntry();
                }
            }
        }
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", binary).toString(),
                "-javaagent:" + jar, "-cp", classpath, McBaselineCapture.class.getName(), "--main-child", output.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Main 캡처 JVM 시간 초과: " + log); }
        if (process.exitValue() != 0) throw new IOException("Main 캡처 JVM 실패:\n" + Files.readString(log));
        return (List<Map<String, Object>>) (List<?>) QuizJson.parse(Files.readString(output));
    }
}
