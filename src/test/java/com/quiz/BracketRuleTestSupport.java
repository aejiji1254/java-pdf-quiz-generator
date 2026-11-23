package com.quiz;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.Assert.*;

/** 제품 옵션 없이 테스트 JVM에서만 새 검사 한 줄을 제거해 컴파일한다. */
final class BracketRuleTestSupport implements AutoCloseable {
    static final String REASON = "용어 또는 설명의 (), [], {} 짝이 맞지 않음";
    static final String GUARD = "        if (hasUnbalancedBrackets(term.term()) || hasUnbalancedBrackets(term.description())) reasons.add(BRACKET_REASON);";
    private final URLClassLoader loader;
    private final Class<?> disabled;
    BracketRuleTestSupport(Path temporary) throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/quiz/QuizGenerator.java"));
        assertEquals("검사 한 줄만 테스트 사본에서 제거", 2, source.split(java.util.regex.Pattern.quote(GUARD), -1).length);
        Path file = temporary.resolve("src/com/quiz/QuizGenerator.java"), classes = temporary.resolve("classes");
        Files.createDirectories(file.getParent()); Files.createDirectories(classes);
        Files.writeString(file, source.replace(GUARD, ""));
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull("JDK compiler 필요", compiler);
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-classpath",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), "-d", classes.toString(), file.toString()));
        loader = new URLClassLoader(new URL[]{classes.toUri().toURL(), QuizGenerator.class.getProtectionDomain().getCodeSource().getLocation()}, QuizGenerator.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("com.quiz.QuizGenerator") || name.startsWith("com.quiz.QuizGenerator$")
                        || name.equals("com.quiz.OxAllocator") || name.startsWith("com.quiz.OxAllocator$")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name); if (c == null) c = findClass(name);
                        if (resolve) resolveClass(c); return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        disabled = loader.loadClass("com.quiz.QuizGenerator");
    }
    @SuppressWarnings("unchecked") List<Term> withoutRule(TermExtractor.Result input) throws Exception {
        Object generator = disabled.getConstructor(Random.class).newInstance(new Random(7));
        return (List<Term>) disabled.getMethod("screenedTerms", TermExtractor.Result.class).invoke(generator, input);
    }
    String beforeReason(Term term) throws Exception { return reason(disabled, term); }
    static String afterReason(Term term) throws Exception { return reason(QuizGenerator.class, term); }
    private static String reason(Class<?> type, Term term) throws Exception {
        var method = type.getDeclaredMethod("exclusionReason", Term.class); method.setAccessible(true);
        return (String) method.invoke(null, term);
    }
    List<Term> withRule(TermExtractor.Result input) { return new QuizGenerator(new Random(7)).screenedTerms(input); }
    record Replay(List<Map<String,Object>> questions, String mc, String ox, List<String> tail) { }
    @SuppressWarnings("unchecked") Replay replay(TermExtractor.Result input, boolean enabled) throws Exception {
        Class<?> type = enabled ? QuizGenerator.class : disabled;
        Random random = new Random(7); Object generator = type.getConstructor(Random.class).newInstance(random);
        Object mc = type.getMethod("generateMultipleChoice", TermExtractor.Result.class, int.class, GenerationOptions.Direction.class)
                .invoke(generator, input, 30, GenerationOptions.Direction.MIXED);
        Object ox = type.getMethod("generateOxDetailed", TermExtractor.Result.class).invoke(generator, input);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (var question : (List<MultipleChoiceQuestion>) mc.getClass().getMethod("questions").invoke(mc)) {
            var row = new LinkedHashMap<String,Object>(); row.put("type", "MULTIPLE_CHOICE"); row.put("question", question.prompt());
            row.put("options", question.options()); row.put("correctIndex", question.correctIndex()); row.put("page", question.original().page());
            row.put("lineNumber", question.sourceLine()); rows.add(row);
        }
        for (Object q : (List<?>) ox.getClass().getMethod("questions").invoke(ox)) {
            Term original = (Term) q.getClass().getMethod("original").invoke(q);
            var row = new LinkedHashMap<String,Object>(); row.put("type", "OX");
            row.put("question", q.getClass().getMethod("statement").invoke(q) + "\n\n설명: " + q.getClass().getMethod("presentedDescription").invoke(q));
            row.put("options", List.of("O", "X")); row.put("correctIndex", (boolean) q.getClass().getMethod("answer").invoke(q) ? 0 : 1);
            row.put("page", original.page()); row.put("lineNumber", input.evidence().stream().filter(e -> e.term().term().equals(original.term()) && e.term().page()==original.page())
                    .findFirst().map(e -> e.termLine().lineNumber()).orElse(null)); rows.add(row);
        }
        return new Replay(rows, mc.toString(), ox.toString(), List.of(Long.toString(random.nextLong()), Long.toString(random.nextLong()), Long.toString(random.nextLong())));
    }
    void assertMonotonic(TermExtractor.Result input) throws Exception {
        List<Term> before = withoutRule(input), after = withRule(input);
        assertTrue("새 규칙으로 후보가 늘면 안 됨", before.containsAll(after));
        for (Term term : before) if (!after.contains(term)) {
            assertNull(beforeReason(term)); assertEquals(REASON, afterReason(term));
        }
        for (Term term : after) assertFalse(REASON.equals(afterReason(term)));
    }
    @Override public void close() throws Exception { loader.close(); }
}
