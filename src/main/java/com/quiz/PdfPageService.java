package com.quiz;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 보기 전용 세션 상태. 파일 쓰기와 저장소 접근을 하지 않는다. */
public final class PdfPageService {
    public static final int TEXT_LIMIT = 20_000, CACHE_LIMIT = 20, BINDING_LIMIT = 200;
    public interface Access {
        boolean safeAutomaticFile(Path path) throws IOException;
        int pageCount(Path path) throws IOException;
        String read(Path path, int page) throws IOException;
    }
    private static final class LocalAccess implements Access {
        private final PdfReader reader = new PdfReader();
        public boolean safeAutomaticFile(Path path) throws IOException {
            // 자동 접근은 네트워크 매핑 가능성이 없는 시스템 볼륨으로 보수적으로 제한한다.
            String systemDrive = System.getenv().getOrDefault("SystemDrive", "C:");
            if (!path.toString().regionMatches(true, 0, systemDrive + "\\", 0, 3)
                    && !path.toString().regionMatches(true, 0, systemDrive + "/", 0, 3)) return false;
            Path part = path.getRoot();
            if (part == null) return false;
            BasicFileAttributes attrs = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) return false;
            for (Path segment : path) {
                part = part.resolve(segment);
                attrs = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) return false;
            }
            return attrs.isRegularFile();
        }
        public int pageCount(Path path) throws IOException { return reader.pageCount(path); }
        public String read(Path path, int page) throws IOException { return reader.readPageForDisplay(path, page); }
    }
    private record Key(Path path, int page) { }
    private record Binding(String setId, String source) { }
    public record Resolved(Path path, boolean chosen) { }
    public record Selection(int page, int line, String explanation) { }
    public record PageText(String text, boolean truncated, List<Integer> starts) {
        public PageText { starts = List.copyOf(starts); }
        public static PageText fromRaw(String raw) {
            String text = String.join("\n", raw.split("\\R", -1));
            boolean truncated = text.length() > TEXT_LIMIT;
            if (truncated) {
                int end = TEXT_LIMIT;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                text = text.substring(0, end);
            }
            List<Integer> starts = new ArrayList<>(); starts.add(0);
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
            return new PageText(text, truncated, starts);
        }
        public int lineAt(int offset) {
            if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("선택 범위가 올바르지 않습니다.");
            int found = Collections.binarySearch(starts, offset);
            return found >= 0 ? found + 1 : -found - 1;
        }
        public String line(int number) {
            if (number < 1 || number > starts.size()) throw new IllegalArgumentException("표시 범위 밖의 줄입니다.");
            int start = starts.get(number - 1), end = number < starts.size() ? starts.get(number) - 1 : text.length();
            return text.substring(start, end);
        }
    }
    private final Access access;
    private final Map<Binding, Path> bindings = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Binding, Path> e) { return size() > BINDING_LIMIT; }
    };
    private final Map<Key, PageText> cache = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, PageText> e) { return size() > CACHE_LIMIT; }
    };
    public PdfPageService() { this(new LocalAccess()); }
    public PdfPageService(Access access) { this.access = Objects.requireNonNull(access); }

    /** 신뢰하지 않는 JSON 문자열을 Path 생성이나 파일시스템 호출보다 먼저 검사한다. */
    public static boolean automaticSyntax(String source) {
        if (source == null || !source.matches("(?is)^[a-z]:[\\\\/][^\\\\/].*\\.pdf$")) return false;
        if (source.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL || Character.getType(c) == Character.FORMAT)) return false;
        String rest = source.substring(3);
        if (rest.matches(".*[<>:\"|?*].*")) return false;
        for (String segment : rest.split("[\\\\/]", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) return false;
        return true;
    }
    public Resolved resolve(String setId, String source) {
        synchronized (bindings) {
            Path chosen = bindings.get(new Binding(setId, source));
            if (chosen != null) return new Resolved(chosen, true);
        }
        if (!automaticSyntax(source)) return null;
        try {
            Path path = Path.of(source);
            if (access.safeAutomaticFile(path)) return new Resolved(path, false);
        } catch (IOException | InvalidPathException | SecurityException ignored) { }
        return null;
    }
    public PageText load(Path path, int page) throws IOException {
        if (page < 1) throw new IOException("PDF 페이지 범위를 벗어났습니다.");
        Key key = new Key(path.toAbsolutePath().normalize(), page);
        synchronized (cache) { PageText hit = cache.get(key); if (hit != null) return hit; }
        PageText value = PageText.fromRaw(access.read(key.path(), page));
        synchronized (cache) { cache.put(key, value); }
        return value;
    }
    public PageText choose(String setId, String source, Path path, int page) throws IOException {
        PageText text = validateChoice(path, page);
        remember(setId, source, path);
        return text;
    }
    public PageText validateChoice(Path path, int page) throws IOException {
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) throw new IOException("PDF 파일을 선택하세요.");
        if (page < 1 || access.pageCount(path) < page) throw new IOException("선택한 PDF에 해당 페이지가 없습니다.");
        return load(path, page);
    }
    public void remember(String setId, String source, Path path) {
        synchronized (bindings) {
            Binding key = new Binding(setId, source);
            bindings.remove(key); // 등록/재등록 시각 순서다. 조회만으로 보존 기간을 늘리지 않는다.
            bindings.put(key, path.toAbsolutePath().normalize());
        }
    }
    /** GUI 수정본 저장 성공 시에만 호출한다. JSON의 부모 ID만으로 신뢰를 추론하지 않는다. */
    public boolean inheritChosenConnection(SavedQuizSet original, SavedQuizSet revision) {
        if (original.id().equals(revision.id()) || !original.sourcePdf().equals(revision.sourcePdf())
                || !original.id().equals(revision.derivedFromSetId())) return false;
        synchronized (bindings) {
            Path chosen = bindings.get(new Binding(original.id(), original.sourcePdf()));
            Binding target = new Binding(revision.id(), revision.sourcePdf());
            if (chosen == null || bindings.containsKey(target)) return false;
            remember(revision.id(), revision.sourcePdf(), chosen);
            return true;
        }
    }
    public static Selection prepare(PageText pageText, int page, int start, int end) {
        String text = pageText.text();
        if (page < 1 || start < 0 || end > text.length() || start >= end || splitsPair(text, start) || splitsPair(text, end))
            throw new IllegalArgumentException("선택 범위가 올바르지 않습니다.");
        String selected = text.substring(start, end);
        if (selected.codePointCount(0, selected.length()) > 500) throw new IllegalArgumentException("선택 텍스트는 500자까지 사용할 수 있습니다.");
        selected = selected.replaceAll("(?U)\\s+", " ").strip();
        if (selected.isBlank()) throw new IllegalArgumentException("원문 텍스트를 선택하세요.");
        return new Selection(page, pageText.lineAt(start), "원문 " + page + "쪽: " + selected);
    }
    private static boolean splitsPair(String s, int offset) {
        return offset > 0 && offset < s.length() && Character.isHighSurrogate(s.charAt(offset - 1)) && Character.isLowSurrogate(s.charAt(offset));
    }
}
